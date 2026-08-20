package com.squadsync.app.net

import android.content.Context
import android.util.Log
import com.squadsync.app.media.MediaCommander
import com.squadsync.app.model.Actions
import com.squadsync.app.model.AppPrefs
import com.squadsync.app.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal HTTP/WebSocket server that lives on the slave device.
 *
 * Uses a plain `java.net.ServerSocket` (no `com.sun.net.httpserver`, which
 * doesn't exist on Android). For each accepted TCP connection we peek at the
 * request line; if it's a `GET /ws` we run the WebSocket upgrade dance, then
 * pump frames using [WsFrames].
 */
class SlaveServer(private val appCtx: Context) {

    private val commander = MediaCommander(appCtx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newCachedThreadPool()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _masters = MutableStateFlow(0)
    val masters: StateFlow<Int> = _masters

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events

    fun start(port: Int) {
        if (_running.value) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
                soTimeout = 500
            }
        } catch (t: Throwable) {
            _events.tryEmit("bind failed: ${t.message}")
            return
        }
        serverSocket = ss
        _running.value = true
        _events.tryEmit("SlaveServer listening on 0.0.0.0:$port")
        acceptJob = scope.launch {
            try {
                while (_running.value) {
                    val sock = try { ss.accept() } catch (_: IOException) { continue }
                    executor.execute { handleClient(sock) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "accept loop crashed", t)
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        _running.value = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptJob?.cancel()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Parse the HTTP request line + headers.
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeHttp(output, 400, "Bad Request")
                socket.close()
                return
            }
            val path = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            if (path != "/ws") {
                writeHttp(output, 404, "Not Found")
                socket.close()
                return
            }
            val upgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            val secKey = headers["sec-websocket-key"]
            if (!upgrade || secKey.isNullOrBlank()) {
                writeHttp(output, 400, "Expected WebSocket upgrade")
                socket.close()
                return
            }

            // Send 101 Switching Protocols.
            val raw = output
            raw.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${WsFrames.computeAccept(secKey)}\r\n" +
                "\r\n").toByteArray(Charsets.UTF_8)
            )
            raw.flush()

            runSession(socket, input, BufferedOutputStream(raw))
        } catch (t: Throwable) {
            Log.w(TAG, "client error: ${t.message}")
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun runSession(socket: Socket, input: InputStream, out: OutputStream) {
        try {
            _masters.value = _masters.value + 1

            // First frame must be hello with room code.
            val first = WsFrames.readTextFrame(input) ?: return
            if (first !is WsFrames.Frame.Text) {
                WsFrames.writeCloseFrame(out, 1008, "bad hello")
                socket.close()
                return
            }
            val hello = Protocol.decode(first.text) as? Wire.Hello
            if (hello == null || hello.roomCode != AppPrefs.roomCode) {
                _events.tryEmit("Rejected connection (bad room code)")
                WsFrames.writeCloseFrame(out, 1008, "bad room")
                socket.close()
                return
            }
            _events.tryEmit("Master joined: ${hello.deviceName}")
            WsFrames.writeTextFrame(out, Protocol.encode(currentState()))

            val pushJob = scope.launch {
                while (true) {
                    delay(2000)
                    try { WsFrames.writeTextFrame(out, Protocol.encode(currentState())) }
                    catch (_: Throwable) { break }
                }
            }

            while (true) {
                val frame = try { WsFrames.readTextFrame(input) } catch (_: IOException) { null }
                    ?: break
                when (frame) {
                    is WsFrames.Frame.Text -> {
                        val msg = Protocol.decode(frame.text) ?: continue
                        when (msg) {
                            is Wire.Cmd -> handleCmd(msg, out)
                            is Wire.Ping -> WsFrames.writePong(out, ByteArray(0))
                            else -> { /* ignore */ }
                        }
                    }
                    is WsFrames.Frame.Ping -> WsFrames.writePong(out, frame.payload)
                    WsFrames.Frame.Other -> { /* ignore */ }
                }
            }
            pushJob.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "session ended: ${t.message}")
        } finally {
            _masters.value = (_masters.value - 1).coerceAtLeast(0)
            _events.tryEmit("Master left")
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun handleCmd(cmd: Wire.Cmd, out: OutputStream) {
        Log.i(TAG, "handleCmd ${cmd.action} ${cmd.value ?: ""} ${cmd.target ?: ""}")
        val ack: Wire.Ack = try {
            when (cmd.action) {
                Actions.VOLUME -> { commander.setVolume(cmd.value ?: 0); Wire.Ack(cmd.action, true) }
                Actions.VOLUME_PERCENT -> { commander.setVolumePercent(cmd.value ?: 0); Wire.Ack(cmd.action, true) }
                Actions.VOLUME_UP -> { commander.adjust(1); Wire.Ack(cmd.action, true) }
                Actions.VOLUME_DOWN -> { commander.adjust(-1); Wire.Ack(cmd.action, true) }
                Actions.BRIGHTNESS_PERCENT -> {
                    val ok = commander.hasBrightnessWritePermission()
                    if (ok) commander.setBrightnessPercent(cmd.value ?: 0)
                    Wire.Ack(cmd.action, ok, if (ok) null else "needs WRITE_SETTINGS")
                }
                Actions.LAUNCH_APP -> {
                    val pkg = cmd.target
                    val ok = pkg != null && commander.launchApp(pkg)
                    Wire.Ack(cmd.action, ok, if (!ok) "app not found or not launchable" else null)
                }
                Actions.LIST_APPS -> {
                    // Reply with a separate "apps" frame right after the ack.
                    val apps = commander.listLaunchableApps()
                    try {
                        WsFrames.writeTextFrame(
                            out,
                            Protocol.encode(Wire.AppsList(
                                apps.map { Wire.AppEntry(it.packageName, it.label, it.isSystem) }
                            ))
                        )
                    } catch (_: Throwable) {}
                    Wire.Ack(cmd.action, true, "${apps.size} apps")
                }
                Actions.PLAY -> { commander.play(); Wire.Ack(cmd.action, true) }
                Actions.PAUSE -> { commander.pause(); Wire.Ack(cmd.action, true) }
                Actions.TOGGLE -> { commander.toggle(); Wire.Ack(cmd.action, true) }
                Actions.NEXT -> { commander.next(); Wire.Ack(cmd.action, true) }
                Actions.PREVIOUS -> { commander.previous(); Wire.Ack(cmd.action, true) }
                Actions.MUTE -> { commander.mute(true); Wire.Ack(cmd.action, true) }
                Actions.UNMUTE -> { commander.mute(false); Wire.Ack(cmd.action, true) }
                else -> Wire.Ack(cmd.action, false, "unknown action")
            }
        } catch (t: Throwable) {
            Wire.Ack(cmd.action, false, t.message)
        }
        try { WsFrames.writeTextFrame(out, Protocol.encode(ack)) } catch (_: Throwable) {}
        try { WsFrames.writeTextFrame(out, Protocol.encode(currentState())) } catch (_: Throwable) {}
    }

    private fun currentState(): Wire.State {
        // We send volume as a 0..100 percent so the master UI can render a
        // unified slider regardless of each device's quirky stream max (15, 30, 150...).
        return Wire.State(
            deviceName = AppPrefs.deviceName,
            playing = false,
            volume = commander.volumePercent(),
            maxVolume = 100,
            brightness = commander.brightnessPercent()
        )
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun writeHttp(out: OutputStream, code: Int, reason: String) {
        val body = "$code $reason\n".toByteArray(Charsets.UTF_8)
        val head = ("HTTP/1.1 $code $reason\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        out.write(head)
        out.write(body)
        out.flush()
    }

    companion object { private const val TAG = "SlaveServer" }
}