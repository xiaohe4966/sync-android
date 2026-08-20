package com.squadsync.app.net

import com.squadsync.app.model.AppPrefs
import com.squadsync.app.model.RemoteApp
import com.squadsync.app.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client (master side) using OkHttp.
 *
 * - Holds one OkHttp client instance (shared, threadsafe).
 * - Auto-reconnects with exponential backoff (500 ms → 8 s).
 * - Outgoing commands are pushed through a small mailbox; the WebSocket is
 *   recreated on each reconnect, so the loop body just sends queued items
 *   once the connection is open.
 */
class MasterClient(
    val host: String,
    val port: Int
) {
    /** Stable key matching the corresponding `Peer.id` (host:port). */
    val key: String get() = "$host:$port"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private val _state = MutableStateFlow<Wire.State?>(null)
    val state: StateFlow<Wire.State?> = _state

    private val _apps = MutableSharedFlow<List<RemoteApp>>(extraBufferCapacity = 8)
    val apps: SharedFlow<List<RemoteApp>> = _apps

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events

    private val cmdMailbox = Channel<Triple<String, Int?, String?>>(capacity = 128)
    private val shouldRun = AtomicBoolean(false)

    fun start() {
        if (loopJob != null) return
        shouldRun.set(true)
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        shouldRun.set(false)
        loopJob?.cancel()
        loopJob = null
    }

    fun sendCmd(action: String, value: Int? = null) {
        sendCmd(action, value, null)
    }

    fun sendCmd(action: String, value: Int? = null, target: String? = null) {
        val item = Triple(action, value, target)
        android.util.Log.i("MasterClient", "sendCmd $action $value target=$target to $host:$port")
        if (cmdMailbox.trySend(item).isSuccess) {
            _events.tryEmit("→ $action ${value ?: ""} ${target ?: ""}")
        } else {
            android.util.Log.w("MasterClient", "mailbox full, dropping $action")
        }
    }

    private suspend fun runLoop() {
        var backoff = 500L
        while (shouldRun.get()) {
            val wsRef = Holder()
            try {
                val req = Request.Builder()
                    .url("ws://$host:$port/ws")
                    .build()

                val opened = Channel<Boolean>(capacity = 1)
                val closed = Channel<Throwable?>(capacity = 1)

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        wsRef.value = webSocket
                        _connected.value = true
                        _events.tryEmit("Connected $host:$port")
                        // Handshake.
                        try {
                            webSocket.send(Protocol.encode(
                                Wire.Hello(
                                    roomCode = AppPrefs.roomCode,
                                    deviceName = AppPrefs.deviceName,
                                    role = "master"
                                )
                            ))
                        } catch (t: Throwable) {
                            _events.tryEmit("handshake send failed: ${t.message}")
                        }
                        opened.trySend(true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = Protocol.decode(text) ?: return
                        when (msg) {
                            is Wire.State -> _state.value = msg
                            is Wire.Ack -> _events.tryEmit(
                                "ack: ${msg.action} ok=${msg.ok} ${msg.message ?: ""}"
                            )
                            is Wire.AppsList -> _apps.tryEmit(
                                msg.apps.map { RemoteApp(it.packageName, it.label, it.isSystem) }
                            )
                            is Wire.Pong -> { /* heartbeat */ }
                            is Wire.ErrorMsg -> _events.tryEmit("err: ${msg.message}")
                            else -> { /* ignore */ }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        _events.tryEmit("WS failure: ${t.message}")
                        closed.trySend(t)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        _events.tryEmit("WS closed: $code $reason")
                        closed.trySend(null)
                    }
                }

                client.newWebSocket(req, listener)

                // Wait for open.
                opened.receive()

                // Send-pump: drain queued cmds until the socket closes.
                val senderJob = scope.launch {
                    while (true) {
                        val (action, value, target) = cmdMailbox.receive()
                        val sock = wsRef.value ?: break
                        try {
                            sock.send(Protocol.encode(Wire.Cmd(action = action, value = value, target = target)))
                        } catch (t: Throwable) {
                            break
                        }
                    }
                }

                // Wait for close.
                closed.receive()
                senderJob.cancel()

            } catch (t: Throwable) {
                _events.tryEmit("Disconnected ($host:$port): ${t.message}")
            } finally {
                wsRef.value?.close(1000, "bye")
                _connected.value = false
                _state.value = null
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
        }
    }

    private class Holder { var value: WebSocket? = null }
}