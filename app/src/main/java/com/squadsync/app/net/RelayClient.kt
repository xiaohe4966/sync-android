package com.squadsync.app.net

import android.util.Log
import com.squadsync.app.model.AppPrefs
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
 * Connection to a remote WebSocket relay (e.g. the Go server in
 * `squadsync-relay/`). When the user configures a relay URL the app dials
 * `/v1/rooms/<room>/ws?device=<name>` and uses this client to talk to
 * peers that are NOT on the local Wi-Fi.
 *
 * Failure mode: the relay is best-effort. If it goes away, the LAN-only
 * NSD path keeps working — the two transports are independent and clients
 * deduplicate by sender name + cmd.
 */
class RelayClient(
    private val baseUrl: String   // e.g. "wss://relay.example.com" or "ws://10.0.0.5:7879"
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events

    /** Outbound frames (Wire JSON). The loop drains this and sends. */
    private val outbox = Channel<String>(capacity = 256)
    private val shouldRun = AtomicBoolean(false)

    fun start() {
        if (loopJob != null) return
        shouldRun.set(true)
        android.util.Log.i("RelayClient", "start url=$baseUrl room=${AppPrefs.roomCode} device=${AppPrefs.deviceName}")
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        shouldRun.set(false)
        loopJob?.cancel()
        loopJob = null
    }

    /** Enqueue a Wire JSON frame for broadcast over the relay. */
    fun send(json: String) {
        if (outbox.trySend(json).isSuccess) {
            _events.tryEmit("→ relay $json")
        }
    }

    private suspend fun runLoop() {
        val room = AppPrefs.roomCode.ifEmpty { "default" }
        val device = AppPrefs.deviceName
        val wsUrl = buildWsUrl(baseUrl, room, device)

        var backoff = 500L
        while (shouldRun.get()) {
            val holder = Holder()
            try {
                val req = Request.Builder().url(wsUrl).build()
                val opened = Channel<Boolean>(capacity = 1)
                val closed = Channel<Throwable?>(capacity = 1)

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        holder.value = webSocket
                        _connected.value = true
                        backoff = 500L
                        _events.tryEmit("relay connected → $wsUrl")
                        opened.trySend(true)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Hand the raw JSON to the consumer; the consumer
                        // decodes Wire and dispatches to local peer state.
                        _events.tryEmit("← $text")
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        _events.tryEmit("relay failure: ${t.message}")
                        closed.trySend(t)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        _events.tryEmit("relay closed: $code $reason")
                        closed.trySend(null)
                    }
                }
                android.util.Log.i("RelayClient", "opening $wsUrl")
                client.newWebSocket(req, listener)
                opened.receive()

                // Send pump: drain outbox while connected.
                val sender = scope.launch {
                    for (msg in outbox) {
                        val s = holder.value ?: break
                        try {
                            s.send(msg)
                        } catch (_: Throwable) { break }
                    }
                }
                closed.receive()
                sender.cancel()
            } catch (t: Throwable) {
                _events.tryEmit("relay error: ${t.message}")
            } finally {
                holder.value?.close(1000, "bye")
                _connected.value = false
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    private class Holder { var value: WebSocket? = null }

    private fun buildWsUrl(base: String, room: String, device: String): String {
        val trimmed = base.trim().trimEnd('/')
        android.util.Log.i("RelayClient", "buildWsUrl: input='$trimmed' len=${trimmed.length}")
        // Preserve the original scheme (wss vs ws) so secure relays stay secure.
        val (scheme, host) = when {
            trimmed.startsWith("wss://") -> "wss" to trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "ws" to trimmed.removePrefix("ws://")
            trimmed.startsWith("https://") -> "wss" to trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws" to trimmed.removePrefix("http://")
            else -> "ws" to trimmed
        }
        android.util.Log.i("RelayClient", "buildWsUrl: scheme=$scheme host='$host'")
        val encodedRoom = java.net.URLEncoder.encode(room, "UTF-8")
        val encodedDevice = java.net.URLEncoder.encode(device, "UTF-8")
        return "$scheme://$host/v1/rooms/$encodedRoom/ws?device=$encodedDevice"
    }

    companion object { private const val TAG = "RelayClient" }
}