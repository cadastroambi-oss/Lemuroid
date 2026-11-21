package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
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
) : PhoneControllerServer.Listener {
    data class UiState(
        val enabled: Boolean = false,
        val url: String? = null,
        val qrVisible: Boolean = false,
        val connectedPlayers: List<Int> = emptyList(),
        val message: String? = null,
    )

    private class PhoneState(val client: PhoneControllerServer.Client) {
        var port: Int? = null
        val pressedKeys = mutableSetOf<Int>()
        val pressTimes = mutableMapOf<Int, Long>()
        var dpadX = 0f
        var dpadY = 0f
        var dpadTime = 0L
    }

    private val server =
        PhoneControllerServer(
            pageProvider = { loadPage() },
            listener = this,
        )

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
                    gamePadPorts = it
                    reassignPorts()
                }
        }
    }

    fun stop() {
        PhoneControllerFiles.clearGamePort(appContext)
        server.stop()
        started = false
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

    // Server callbacks arrive on background threads: everything is moved to the scope (main thread),
    // which also keeps the events in order.

    override fun onClientConnected(client: PhoneControllerServer.Client) {
        scope.launch {
            phones.add(PhoneState(client))
            reassignPorts()
            val player = phones.firstOrNull { it.client == client }?.port?.plus(1)
            if (player != null) {
                showMessage("Celular conectado: Jogador $player")
                hideQrCode()
            } else {
                showMessage("Celular conectado, mas não há vaga de jogador livre")
            }
        }
    }

    override fun onClientDisconnected(client: PhoneControllerServer.Client) {
        scope.launch {
            val phone = phones.firstOrNull { it.client == client } ?: return@launch
            releaseAll(phone)
            phones.remove(phone)
            phone.port?.let { showMessage("Celular do Jogador ${it + 1} desconectou") }
            reassignPorts()
        }
    }

    override fun onClientMessage(
        client: PhoneControllerServer.Client,
        message: String,
    ) {
        if (message == "h") return // heartbeat
        scope.launch {
            val phone = phones.firstOrNull { it.client == client } ?: return@launch
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
                if (keyCode !in ALLOWED_KEYS) return
                val pressed = parts.getOrNull(2) == "1"
                if (pressed) {
                    phone.pressedKeys.add(keyCode)
                    phone.pressTimes[keyCode] = SystemClock.uptimeMillis()
                    phone.port?.let { sendKey(it, keyCode, true) }
                } else {
                    phone.pressedKeys.remove(keyCode)
                    // A quick tap may press and release between two frames: the game would never
                    // see it. Keep every press for a minimum time.
                    val elapsed = SystemClock.uptimeMillis() - (phone.pressTimes[keyCode] ?: 0L)
                    if (elapsed >= MIN_PRESS_MS) {
                        phone.port?.let { sendKey(it, keyCode, false) }
                    } else {
                        scope.launch {
                            delay(MIN_PRESS_MS - elapsed)
                            if (keyCode !in phone.pressedKeys) phone.port?.let { sendKey(it, keyCode, false) }
                        }
                    }
                }
            }
            "d" -> {
                phone.dpadX = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
                phone.dpadY = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
                val centered = phone.dpadX == 0f && phone.dpadY == 0f
                val elapsed = SystemClock.uptimeMillis() - phone.dpadTime
                if (!centered) {
                    phone.dpadTime = SystemClock.uptimeMillis()
                    phone.port?.let { sendDpad(it, phone.dpadX, phone.dpadY) }
                } else if (elapsed >= MIN_PRESS_MS) {
                    phone.port?.let { sendDpad(it, 0f, 0f) }
                } else {
                    scope.launch {
                        delay(MIN_PRESS_MS - elapsed)
                        if (phone.dpadX == 0f && phone.dpadY == 0f) phone.port?.let { sendDpad(it, 0f, 0f) }
                    }
                }
            }
            "a" -> {
                // Shortcuts are only allowed to the first phone/player to avoid accidents.
                if (phone != phones.firstOrNull()) return
                when (parts.getOrNull(1)) {
                    "save" -> {
                        sideEffects.saveQuickSave()
                        showMessage("Estado salvo")
                    }
                    "load" -> {
                        sideEffects.loadQuickSave()
                        showMessage("Estado carregado")
                    }
                    "ff" -> sideEffects.toggleFastForward()
                    "menu" -> sideEffects.showMenu(tilt, inputs)
                }
            }
        }
    }

    private fun reassignPorts() {
        val freePorts = (0 until MAX_PORTS).filter { it !in gamePadPorts }
        phones.forEachIndexed { index, phone ->
            val newPort = freePorts.getOrNull(index)
            if (newPort != phone.port) {
                releaseAll(phone)
                phone.port = newPort
                newPort?.let { port ->
                    phone.pressedKeys.forEach { sendKey(port, it, true) }
                    sendDpad(port, phone.dpadX, phone.dpadY)
                }
            }
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

    private fun releaseAll(phone: PhoneState) {
        val port = phone.port ?: return
        phone.pressedKeys.forEach { sendKey(port, it, false) }
        sendDpad(port, 0f, 0f)
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
        private const val MIN_PRESS_MS = 70L

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
