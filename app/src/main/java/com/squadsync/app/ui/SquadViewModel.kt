package com.squadsync.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squadsync.app.media.MediaCommander
import com.squadsync.app.model.AppPrefs
import com.squadsync.app.model.Actions
import com.squadsync.app.model.Peer
import com.squadsync.app.model.RemoteApp
import com.squadsync.app.model.Wire
import com.squadsync.app.ui.EventLog
import com.squadsync.app.net.MasterClient
import com.squadsync.app.net.NsdController
import com.squadsync.app.net.Protocol
import com.squadsync.app.net.RelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for the UI.
 *
 * - Holds a map of `Peer` keyed by `host:port`.
 * - Spawns / tears down a `MasterClient` per peer as connections come and go.
 */
class SquadViewModel(app: Application) : AndroidViewModel(app) {

    private val nsd = NsdController(app)
    private val audio = MediaCommander(app)

    // Optional remote relay. Set via [setRelayUrl] when the user fills in the
    // "转发服务器 URL" field. When null the app behaves exactly as before
    // (LAN-only via mDNS).
    private var relay: RelayClient? = null

    /** Public state for the UI to show a "已连接服务器" badge. */
    private val _relayConnected = MutableStateFlow(false)
    val relayConnected: StateFlow<Boolean> = _relayConnected

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers

    private val clients = mutableMapOf<String, MasterClient>()

    val roomCode: String get() = AppPrefs.roomCode
    val deviceName: String get() = AppPrefs.deviceName

    init {
        viewModelScope.launch {
            nsd.peers.collect { discovered ->
                val current = _peers.value
                val merged = current.toMutableMap()
                // Local IP set, refreshed occasionally so we recognise
                // ourselves even if our address changes (DHCP / VPN).
                val myIps = localIps()
                for ((name, dp) in discovered) {
                    val key = "${dp.host}:${dp.port}"
                    if (dp.host in myIps) {
                        // mDNS on Android routinely reports our own service
                        // back to us. Skip it so we don't open a loopback
                        // WebSocket to ourselves.
                        continue
                    }
                    val prev = merged[key]
                    merged[key] = (prev ?: Peer(
                        id = key, host = dp.host, port = dp.port, name = dp.name
                    )).copy(name = dp.name, lastSeenMs = System.currentTimeMillis())
                }
                // Drop stale peers older than 30 s (NSD doesn't always emit "lost").
                val cutoff = System.currentTimeMillis() - 30_000
                for ((k, p) in merged.toMap()) {
                    if (p.lastSeenMs < cutoff && !clients.containsKey(k)) {
                        merged.remove(k)
                    }
                }
                _peers.value = merged
                syncClients(merged)
            }
        }
    }

    fun setRoomCode(code: String) { AppPrefs.roomCode = code }
    fun setDeviceName(name: String) { AppPrefs.deviceName = name }

    private fun localIps(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses?.asSequence() ?: emptySequence() }
                ?.forEach { out.add(it.hostAddress?.removePrefix("%") ?: return@forEach) }
        } catch (_: Throwable) { /* best effort */ }
        return out
    }

    fun startDiscovery() = nsd.startDiscovery()
    fun stopDiscovery() = nsd.stopDiscovery()

    /**
     * Apply a new relay URL. Empty string disables the relay and tears
     * down the existing connection. Any non-empty value (with or without
     * `ws://` prefix) triggers a reconnect.
     */
    fun setRelayUrl(url: String) {
        AppPrefs.relayUrl = url.trim()
        relay?.stop()
        relay = null
        _relayConnected.value = false
        if (url.isBlank()) return
        val client = RelayClient(url.trim()).also { it.start() }
        relay = client
        viewModelScope.launch {
            client.connected.collect { ok ->
                _relayConnected.value = ok
                if (ok) EventLog.info("relay: ${url.trim()}")
                else EventLog.info("relay: disconnected")
            }
        }
        // Bridge the inbound text stream into the EventLog too, so the user
        // can see relay traffic right next to LAN traffic.
        viewModelScope.launch {
            client.events.collect { line ->
                EventLog.send(line.removePrefix("→").trim())
            }
        }
    }

    /** Force a reconnect of the relay (e.g. after server URL edit). */
    fun reconnectRelay() = setRelayUrl(AppPrefs.relayUrl)

    /** True if the user has configured a non-empty relay URL. */
    fun hasRelay(): Boolean = AppPrefs.relayUrl.isNotBlank()

    private fun syncClients(snapshot: Map<String, Peer>) {
        // Spawn clients for new peers. Re-create if the resolved host:port
        // changed (DHCP renewed) or the existing client isn't actually
        // connected (the old socket may be dead after a network blip).
        for ((key, peer) in snapshot) {
            val existing = clients[key]
            val stale = existing != null && (
                existing.host != peer.host || existing.port != peer.port
            )
            val dead = existing != null && !existing.connected.value
            if (existing == null || stale || dead) {
                existing?.stop()
                clients.remove(key)
                val client = MasterClient(peer.host, peer.port).also { it.start() }
                clients[key] = client
                EventLog.info("→ ${peer.host}:${peer.port} 开始建立连接")
                viewModelScope.launch {
                    client.connected.collect { online ->
                        val p = _peers.value[key] ?: return@collect
                        if (p.online != online) {
                            _peers.value = _peers.value.toMutableMap().apply {
                                put(key, p.copy(online = online))
                            }
                            EventLog.info(
                                if (online) "✓ ${peer.name} 已连接"
                                else "✗ ${peer.name} 断开"
                            )
                        }
                    }
                }
                viewModelScope.launch {
                    client.events.collect { line ->
                        val clean = line.removePrefix("→").trim()
                        EventLog.send(clean)
                    }
                }
                viewModelScope.launch {
                    client.state.collect { st ->
                        if (st == null) return@collect
                        val p = _peers.value[key] ?: return@collect
                        _peers.value = _peers.value.toMutableMap().apply {
                            put(key, p.copy(
                                volume = st.volume,
                                maxVolume = st.maxVolume,
                                playing = st.playing,
                                brightness = st.brightness,
                                online = true
                            ))
                        }
                    }
                }
                viewModelScope.launch {
                    client.apps.collect { apps ->
                        val p = _peers.value[key] ?: return@collect
                        _peers.value = _peers.value.toMutableMap().apply {
                            put(key, p.copy(apps = apps))
                        }
                    }
                }
            }
        }
        // Remove clients for vanished peers.
        val keep = clients.keys.intersect(snapshot.keys)
        for (k in clients.keys.toList()) if (k !in keep) {
            clients.remove(k)?.stop()
        }
    }

    // -------- Command fan-out --------

    fun broadcastVolume(value: Int) = broadcast(Actions.VOLUME, value)
    fun broadcastVolumeUp() = broadcast(Actions.VOLUME_UP, null)
    fun broadcastVolumeDown() = broadcast(Actions.VOLUME_DOWN, null)

    /**
     * Sets every slave's media volume to [percentPercent] (0..100). Master also
     * applies the change locally so the slider always reflects reality.
     */
    fun broadcastVolumePercent(percentPercent: Int) {
        val clamped = percentPercent.coerceIn(0, 100)
        clients.values.forEach { it.sendCmd(Actions.VOLUME_PERCENT, clamped) }
        // Apply locally as well.
        audio.setVolumePercent(clamped)
    }

    fun broadcastBrightnessPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        var sent = 0
        clients.values.forEach { c ->
            val peer = _peers.value[c.key]
            if (peer?.selected != false) {
                c.sendCmd(Actions.BRIGHTNESS_PERCENT, clamped)
                sent++
            }
        }
        audio.setBrightnessPercent(clamped)
        if (sent == 0) EventLog.send("亮度 → $clamped% (no online peer)")
    }

    fun sendBrightnessPercentTo(peerKey: String, percent: Int) {
        clients[peerKey]?.sendCmd(Actions.BRIGHTNESS_PERCENT, percent.coerceIn(0, 100))
    }

    fun requestAppsFor(peerKey: String) {
        clients[peerKey]?.sendCmd(Actions.LIST_APPS)
    }

    fun launchAppOn(peerKey: String, packageName: String) {
        val c = clients[peerKey] ?: return
        c.sendCmd(Actions.LAUNCH_APP, target = packageName)
        relay?.send(Protocol.encode(Wire.Cmd(action = Actions.LAUNCH_APP, target = packageName)))
    }

    /**
     * Sets a single slave's media volume to [percentPercent] (0..100).
     */
    fun sendVolumePercentTo(peerKey: String, percentPercent: Int) {
        val clamped = percentPercent.coerceIn(0, 100)
        clients[peerKey]?.sendCmd(Actions.VOLUME_PERCENT, clamped)
    }

    fun setLocalVolumePercent(percentPercent: Int) {
        audio.setVolumePercent(percentPercent.coerceIn(0, 100))
        EventLog.send("本机音量 → ${percentPercent.coerceIn(0, 100)}%")
    }

    /** Local screen brightness (0..100). Returns 0 if WRITE_SETTINGS not granted. */
    fun localBrightnessPercent(): Int = audio.brightnessPercent()

    fun hasBrightnessPermission(): Boolean = audio.hasBrightnessWritePermission()

    /** Returns current master local volume as percent 0..100. */
    fun localVolumePercent(): Int = audio.volumePercent()

    /** Returns current master local max volume index (max slider). */
    fun localMaxVolumeIndex(): Int = audio.maxVolume()
    fun broadcastPlay() = broadcast(Actions.PLAY, null)
    fun broadcastPause() = broadcast(Actions.PAUSE, null)
    fun broadcastToggle() = broadcast(Actions.TOGGLE, null)
    fun broadcastNext() = broadcast(Actions.NEXT, null)
    fun broadcastPrev() = broadcast(Actions.PREVIOUS, null)
    fun broadcastMute() = broadcast(Actions.MUTE, null)
    fun broadcastUnmute() = broadcast(Actions.UNMUTE, null)

    fun sendTo(peerKey: String, action: String, value: Int? = null) {
        val c = clients[peerKey] ?: return
        c.sendCmd(action, value)
        // Mirror over the relay too. The remote peer (across the relay)
        // will receive the same Wire.Cmd JSON and dispatch it.
        relay?.send(Protocol.encode(Wire.Cmd(action = action, value = value)))
    }

    private fun broadcast(action: String, value: Int?) {
        // Routing policy:
        //   relay connected   ->  send ONLY via relay (peers reachable across
        //                        the Internet are typically behind NAT; the relay
        //                        is the only path that reaches them. Don't double
        //                        send to LAN peers or they'll execute twice.)
        //   relay not yet     ->  fall back to LAN (mDNS) peers. Same selection
        //                        rules: only peers the user has ticked.
        //   both              ->  impossible (we only set `_relayConnected` to
        //                        true after a successful onOpen).
        if (_relayConnected.value && relay != null) {
            relay?.send(Protocol.encode(Wire.Cmd(action = action, value = value)))
            return
        }
        // Fallback: LAN / direct peers.
        var sent = 0
        clients.values.forEach { c ->
            val peer = _peers.value[c.key]
            if (peer?.selected != false) {
                c.sendCmd(action, value)
                sent++
            }
        }
        if (sent == 0) {
            EventLog.send("$action ${value ?: ""} (no online peer)")
        }
    }

    fun setPeerSelected(peerKey: String, selected: Boolean) {
        val p = _peers.value[peerKey] ?: return
        _peers.value = _peers.value.toMutableMap().apply { put(peerKey, p.copy(selected = selected)) }
    }

    fun selectAll(selected: Boolean) {
        if (_peers.value.isEmpty()) return
        _peers.value = _peers.value.mapValues { (_, p) -> p.copy(selected = selected) }
    }

    override fun onCleared() {
        super.onCleared()
        clients.values.forEach { it.stop() }
        nsd.stopDiscovery()
    }
}