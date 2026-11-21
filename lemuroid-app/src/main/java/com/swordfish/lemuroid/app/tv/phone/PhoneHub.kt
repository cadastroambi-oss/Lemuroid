package com.swordfish.lemuroid.app.tv.phone

import android.app.Instrumentation
import android.content.Context
import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.phonecontroller.PhoneControllerFiles
import com.swordfish.lemuroid.app.shared.phonecontroller.PhoneControllerServer
import com.swordfish.lemuroid.app.shared.phonecontroller.PhoneControllerServer.HttpResponse
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Phone hub running in the main (menu) process. A phone that scans its QR code can:
 *  - send ROM files to the TV (saved in the app's internal ROM folder, then the library is rescanned);
 *  - navigate the TV menus (arrows / OK / back);
 *  - jump automatically to the game controller as soon as a game is opened on the TV.
 */
object PhoneHub : PhoneControllerServer.Listener {
    data class State(
        val url: String? = null,
        val connectedPhones: Int = 0,
        val lastMessage: String? = null,
    )

    private lateinit var appContext: Context
    private var server: PhoneControllerServer? = null
    private val state = MutableStateFlow(State())
    private val keyExecutor = Executors.newSingleThreadExecutor()
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private val instrumentation by lazy { Instrumentation() }

    @Volatile
    private var currentGamePort = 0
    private var connectedPhones = 0

    fun getState(): StateFlow<State> = state

    @Synchronized
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        if (server != null) {
            refreshUrl()
            return
        }

        val newServer =
            PhoneControllerServer(
                pageProvider = { appContext.assets.open(JOYSTICK_ASSET).use { it.readBytes() } },
                listener = this,
                httpHandler = ::handleHttp,
            )

        if (!newServer.start()) {
            Timber.e("Unable to start phone hub server")
            return
        }

        server = newServer
        PhoneControllerFiles.writeHubPort(appContext, newServer.port)
        refreshUrl()

        poller.scheduleWithFixedDelay({ pollGame() }, 1, 1, TimeUnit.SECONDS)
    }

    fun refreshUrl() {
        val port = server?.port ?: return
        val address = PhoneControllerServer.findLocalAddress()
        state.value = state.value.copy(url = address?.let { "http://$it:$port/" })
    }

    private fun gameUrlSuffix(gamePort: Int): String = "$gamePort"

    private fun pollGame() {
        val port = PhoneControllerFiles.readGamePort(appContext)?.takeIf { isPortOpen(it) } ?: 0
        if (port != currentGamePort) {
            currentGamePort = port
            server?.broadcast("g ${gameUrlSuffix(port)}")
        }
    }

    private fun isPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            true
        }.getOrDefault(false)
    }

    private fun setMessage(message: String) {
        state.value = state.value.copy(lastMessage = message)
    }

    override fun onClientConnected(client: PhoneControllerServer.Client) {
        synchronized(this) {
            connectedPhones++
            state.value = state.value.copy(connectedPhones = connectedPhones)
        }
        client.send("m")
        client.send("g $currentGamePort")
    }

    override fun onClientDisconnected(client: PhoneControllerServer.Client) {
        stopRepeat(client)
        synchronized(this) {
            connectedPhones = (connectedPhones - 1).coerceAtLeast(0)
            state.value = state.value.copy(connectedPhones = connectedPhones)
        }
    }

    override fun onClientMessage(
        client: PhoneControllerServer.Client,
        message: String,
    ) {
        // The phone is only a game controller: in the TV menus it just waits for a game to start.
    }

    private val repeatJobs = mutableMapOf<Int, ScheduledFuture<*>>()

    @Synchronized
    private fun startRepeat(
        client: PhoneControllerServer.Client,
        keyCode: Int?,
    ) {
        repeatJobs.remove(client.id)?.cancel(false)
        if (keyCode == null) return
        injectKey(keyCode)
        repeatJobs[client.id] =
            poller.scheduleWithFixedDelay({ injectKey(keyCode) }, 450, 150, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stopRepeat(client: PhoneControllerServer.Client) {
        repeatJobs.remove(client.id)?.cancel(false)
    }

    private fun injectKey(keyCode: Int) {
        keyExecutor.execute {
            // Instrumentation can inject keys into windows that belong to this app.
            runCatching { instrumentation.sendKeyDownUpSync(keyCode) }
                .onFailure { Timber.w(it, "Unable to inject key") }
        }
    }

    // ---------------- HTTP routes (games upload) ----------------

    private fun romsDirectory(): File = DirectoriesManager(appContext).getInternalRomsDirectory()

    private fun handleHttp(
        method: String,
        path: String,
        query: Map<String, String>,
        body: InputStream,
        contentLength: Long,
    ): HttpResponse? {
        return when {
            method == "GET" && path == "/jogos" ->
                HttpResponse(
                    200,
                    "text/html; charset=utf-8",
                    appContext.assets.open(UPLOAD_ASSET).use { it.readBytes() },
                )
            method == "GET" && path == "/files" -> listFiles()
            method == "POST" && path == "/upload" -> upload(query["name"], body, contentLength)
            method == "POST" && path == "/delete" -> delete(query["name"])
            method == "POST" && path == "/rescan" -> {
                LibraryIndexScheduler.scheduleLibrarySync(appContext)
                HttpResponse.json("{\"ok\":true}")
            }
            else -> null
        }
    }

    private fun listFiles(): HttpResponse {
        val files =
            romsDirectory().walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .toList()
        val array = JSONArray()
        files.forEach {
            array.put(JSONObject().put("name", it.relativeTo(romsDirectory()).path).put("size", it.length()))
        }
        val result =
            JSONObject()
                .put("files", array)
                .put("free", romsDirectory().usableSpace)
        return HttpResponse.json(result.toString())
    }

    private fun safeName(name: String?): String? {
        val clean = name?.let { File(it).name }?.trim() ?: return null
        if (clean.isEmpty() || clean.startsWith(".")) return null
        return clean
    }

    private fun upload(
        name: String?,
        body: InputStream,
        contentLength: Long,
    ): HttpResponse {
        val fileName = safeName(name) ?: return HttpResponse.error(400, "Nome de arquivo inválido")
        if (contentLength <= 0) return HttpResponse.error(400, "Arquivo vazio")
        if (contentLength > romsDirectory().usableSpace) return HttpResponse.error(507, "Sem espaço na TV")

        val target = File(romsDirectory(), fileName)
        val temp = File(romsDirectory(), ".$fileName.part")
        return try {
            temp.outputStream().use { output -> body.copyTo(output, 256 * 1024) }
            if (temp.length() != contentLength) {
                temp.delete()
                return HttpResponse.error(400, "Envio incompleto")
            }
            target.delete()
            temp.renameTo(target)
            setMessage("Recebido: $fileName")
            LibraryIndexScheduler.scheduleLibrarySync(appContext)
            HttpResponse.json("{\"ok\":true}")
        } catch (e: Exception) {
            temp.delete()
            Timber.e(e, "Upload failed")
            HttpResponse.error(500, "Falha ao salvar: ${e.message}")
        }
    }

    private fun delete(name: String?): HttpResponse {
        val fileName = safeName(name) ?: return HttpResponse.error(400, "Nome de arquivo inválido")
        val target = File(romsDirectory(), fileName)
        if (target.exists()) target.delete()
        LibraryIndexScheduler.scheduleLibrarySync(appContext)
        return HttpResponse.json("{\"ok\":true}")
    }

    private const val JOYSTICK_ASSET = "phone_controller.html"
    private const val UPLOAD_ASSET = "phone_hub.html"
}
