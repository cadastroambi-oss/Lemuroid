package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.phonecontroller.PhoneControllerFiles
import com.swordfish.lemuroid.app.shared.phonecontroller.PhoneControllerServer
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_DPAD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lets phones on the same Wi-Fi act as gamepads. The TV shows a QR code with the address of a small
 * web page served by [PhoneControllerServer]; every phone that opens it becomes the next free player
 * after the physical gamepads.
 */
class GameViewModelPhoneController(
    private val appContext: Context,
    private val inputDeviceManager: InputDeviceManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val tilt: GameViewModelTilt,
    private val inputs: GameViewModelInput,
    private val sideEffects: GameViewModelSideEffects,
    private val scope: CoroutineScope,
    private val saveSlot: suspend (Int) -> Unit,
    private val loadSlot: suspend (Int) -> Unit,
) : PhoneControllerServer.Listener {
    data class UiState(
        val enabled: Boolean = false,
        val url: String? = null,
        val qrVisible: Boolean = false,
        val connectedPlayers: List<Int> = emptyList(),
        val message: String? = null,
    )

    /**
     * Delivers the states of one button (or the D-pad) to the game in order, keeping each state for
     * at least [MIN_STATE_MS]. The core reads inputs once per frame: without this, a quick tap or a
     * quick "release and press again" (sliding A -> B -> A) could happen between two frames and be lost.
     */
    private inner class InputChannel<T>(
        private val idle: T,
        private val apply: (port: Int, value: T) -> Unit,
    ) {
        var port: Int? = null
        var desired: T = idle
            private set
        private var current: T = idle
        private var lastChange = 0L
        private val queue = ArrayDeque<T>()
        private var job: ScheduledFuture<*>? = null

        fun push(value: T) {
            desired = value
            val last = queue.lastOrNull() ?: current
            if (last == value) return
            queue.addLast(value)
            // Avoid unbounded lag when buttons are mashed: keep only the most recent changes.
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            pump()
        }

        private fun pump() {
            while (queue.isNotEmpty()) {
                val next = queue.first()
                if (next == current) {
                    queue.removeFirst()
                    continue
                }
                val elapsed = SystemClock.uptimeMillis() - lastChange
                if (elapsed < MIN_STATE_MS) {
                    if (job == null) {
                        job =
                            runCatching {
                                timer.schedule(
                                    Runnable {
                                    synchronized(lock) {
                                        job = null
                                        pump()
                                    }
                                },
                                    MIN_STATE_MS - elapsed,
                                    TimeUnit.MILLISECONDS,
                                )
                            }.getOrNull()
                    }
                    return
                }
                queue.removeFirst()
                current = next
                lastChange = SystemClock.uptimeMillis()
                port?.let { apply(it, next) }
            }
        }

        /** Moves to another player port: release on the old one, restore the held state on the new one. */
        fun movePort(newPort: Int?) {
            job?.cancel(false)
            job = null
            queue.clear()
            val oldPort = port
            if (oldPort != null && current != idle) apply(oldPort, idle)
            port = newPort
            current = idle
            lastChange = 0L
            if (desired != idle) push(desired)
        }

        fun releaseAll() {
            desired = idle
            movePort(port)
        }
    }

    private inner class PhoneState(val client: PhoneControllerServer.Client) {
        var port: Int? = null
        val keys =
            ALLOWED_KEYS.associateWith { keyCode ->
                InputChannel(false) { port, pressed -> sendKey(port, keyCode, pressed) }
            }
        val dpad = InputChannel(0f to 0f) { port, value -> sendDpad(port, value.first, value.second) }

        fun channels() = keys.values + dpad

        fun assignPort(newPort: Int?) {
            port = newPort
            channels().forEach { it.movePort(newPort) }
        }

        fun releaseAll() {
            channels().forEach { it.releaseAll() }
        }
    }

    private val server =
        PhoneControllerServer(
            pageProvider = { loadPage() },
            listener = this,
        )

    // Inputs are handled directly on the network threads (no hop through the busy main thread):
    // the lock keeps the phones' state consistent.
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var wifiLock: WifiManager.WifiLock? = null

    private val state = MutableStateFlow(UiState())
    private val phones = mutableListOf<PhoneState>()
    private var gamePadPorts = emptySet<Int>()
    private var qrJob: Job? = null
    private var messageJob: Job? = null
    private var started = false

    fun getUiState(): StateFlow<UiState> = state

    fun start() {
        if (started) return
        started = true

        if (!server.start()) {
            Timber.e("Unable to start phone controller server")
            return
        }

        acquireWifiLock()
        PhoneControllerFiles.writeGamePort(appContext, server.port)
        state.value = state.value.copy(enabled = true, url = computeUrl())
        showQrCode(INITIAL_QR_DURATION_MS)

        scope.launch {
            // Ports used by real gamepads (same order Lemuroid uses). The TV remote is often
            // detected as a "keyboard" and would steal player 1, so it is ignored here.
            inputDeviceManager.getEnabledInputsObservable()
                .map { devices ->
                    devices.mapIndexedNotNull { index, device -> index.takeIf { isRealGamePad(device) } }.toSet()
                }
                .distinctUntilChanged()
                .collect {
                    synchronized(lock) {
                        gamePadPorts = it
                        reassignPorts()
                    }
                }
        }
    }

    fun stop() {
        PhoneControllerFiles.clearGamePort(appContext)
        server.stop()
        timer.shutdownNow()
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        started = false
    }

    /** Keeps the TV Wi-Fi out of power saving while playing: it adds tens of ms to every button press. */
    private fun acquireWifiLock() {
        runCatching {
            val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
            wifiLock =
                wifiManager.createWifiLock(mode, "lemuroid:phone-controller").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { Timber.w(it, "Unable to acquire Wi-Fi lock") }
    }

    fun showQrCode(durationMs: Long = MENU_QR_DURATION_MS) {
        if (!state.value.enabled) return
        qrJob?.cancel()
        state.value = state.value.copy(url = computeUrl(), qrVisible = true)
        qrJob =
            scope.launch {
                delay(durationMs)
                state.value = state.value.copy(qrVisible = false)
            }
    }

    fun hideQrCode() {
        qrJob?.cancel()
        state.value = state.value.copy(qrVisible = false)
    }

    private fun showMessage(message: String) {
        messageJob?.cancel()
        state.value = state.value.copy(message = message)
        messageJob =
            scope.launch {
                delay(MESSAGE_DURATION_MS)
                state.value = state.value.copy(message = null)
            }
    }

    private fun computeUrl(): String? {
        val address = PhoneControllerServer.findLocalAddress() ?: return null
        val hub = PhoneControllerFiles.readHubPort(appContext)?.let { "?hub=$it" } ?: ""
        return "http://$address:${server.port}/$hub"
    }

    private fun loadPage(): ByteArray {
        return appContext.assets.open(PAGE_ASSET).use { it.readBytes() }
    }

    // Server callbacks arrive on network threads.

    override fun onClientConnected(client: PhoneControllerServer.Client) {
        val player =
            synchronized(lock) {
                phones.add(PhoneState(client))
                reassignPorts()
                phones.firstOrNull { it.client == client }?.port?.plus(1)
            }
        scope.launch {
            if (player != null) {
                showMessage("Celular conectado: Jogador $player")
                hideQrCode()
            } else {
                showMessage("Celular conectado, mas não há vaga de jogador livre")
            }
        }
    }

    override fun onClientDisconnected(client: PhoneControllerServer.Client) {
        val port =
            synchronized(lock) {
                val phone = phones.firstOrNull { it.client == client } ?: return
                phone.releaseAll()
                phones.remove(phone)
                reassignPorts()
                phone.port
            }
        port?.let { scope.launch { showMessage("Celular do Jogador ${it + 1} desconectou") } }
    }

    override fun onClientMessage(
        client: PhoneControllerServer.Client,
        message: String,
    ) {
        if (message == "h") return // heartbeat
        if (message.startsWith("t ")) {
            // Ping: echo it back right away so the phone can show the delay.
            client.send(message)
            return
        }
        synchronized(lock) {
            val phone = phones.firstOrNull { it.client == client } ?: return
            handleMessage(phone, message)
        }
    }

    private fun handleMessage(
        phone: PhoneState,
        message: String,
    ) {
        val parts = message.split(" ")
        when (parts.getOrNull(0)) {
            "k" -> {
                val keyCode = parts.getOrNull(1)?.toIntOrNull() ?: return
                val channel = phone.keys[keyCode] ?: return
                channel.push(parts.getOrNull(2) == "1")
            }
            "d" -> {
                val x = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
                val y = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
                phone.dpad.push(x to y)
            }
            "a" -> {
                // Shortcuts are only allowed to the first phone/player to avoid accidents.
                if (phone != phones.firstOrNull()) return
                when (parts.getOrNull(1)) {
                    // Saved in slot 1 of the game menu: it stays there even after closing the game.
                    "save" ->
                        scope.launch {
                            saveSlot(PHONE_SAVE_SLOT)
                            showMessage("Jogo salvo no espaço 1")
                        }
                    "load" ->
                        scope.launch {
                            loadSlot(PHONE_SAVE_SLOT)
                            showMessage("Voltou para o espaço 1")
                        }
                    "ff" -> scope.launch { sideEffects.toggleFastForward() }
                    "menu" -> scope.launch { sideEffects.showMenu(tilt, inputs) }
                }
            }
        }
    }

    private fun reassignPorts() {
        val freePorts = (0 until MAX_PORTS).filter { it !in gamePadPorts }
        phones.forEachIndexed { index, phone ->
            val newPort = freePorts.getOrNull(index)
            if (newPort != phone.port) phone.assignPort(newPort)
            val isMain = index == 0
            phone.client.send("p ${(phone.port ?: -1) + 1} ${if (isMain) 1 else 0}")
        }
        state.value = state.value.copy(connectedPlayers = phones.mapNotNull { it.port?.plus(1) })
    }

    private fun isRealGamePad(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun sendKey(
        port: Int,
        keyCode: Int,
        pressed: Boolean,
    ) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        retroGameView.retroGameView?.sendKeyEvent(action, keyCode, port)
    }

    private fun sendDpad(
        port: Int,
        x: Float,
        y: Float,
    ) {
        retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_DPAD, x, y, port)
    }

    companion object {
        private const val PAGE_ASSET = "phone_controller.html"
        private const val MAX_PORTS = 4
        private const val INITIAL_QR_DURATION_MS = 30_000L
        private const val MENU_QR_DURATION_MS = 90_000L
        private const val MESSAGE_DURATION_MS = 3_000L
        private const val MIN_STATE_MS = 50L
        private const val MAX_QUEUE = 8
        private const val PHONE_SAVE_SLOT = 0

        private val ALLOWED_KEYS =
            setOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_START,
            )
    }
}
