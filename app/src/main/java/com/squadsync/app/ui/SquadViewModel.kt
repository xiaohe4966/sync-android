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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth for the UI.
 *
 * - Holds a map of `Peer` keyed by `host:port` (LAN) or `relay:<name>` (relay).
 * - Spawns / tears down a `MasterClient` per LAN peer as connections come and go.
 * - Relay peers are managed entirely through the relay inbound stream.
 */
class SquadViewModel(app: Application) : AndroidViewModel(app) {

    private val nsd = NsdController(app)
    private val audio = MediaCommander(app)

    // Optional remote relay.
    private var relay: RelayClient? = null
    private var relayStatePumpJob: Job? = null

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
                val myIps = localIps()
                for ((name, dp) in discovered) {
                    val key = "${dp.host}:${dp.port}"
                    if (dp.host in myIps) continue
                    val prev = merged[key]
                    merged[key] = (prev ?: Peer(
                        id = key, host = dp.host, port = dp.port, name = dp.name
                    )).copy(name = dp.name, lastSeenMs = System.currentTimeMillis())
                }
                // Drop stale peers older than 30 s.
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
     * Apply a new relay URL. Empty string disables the relay.
     */
    fun setRelayUrl(url: String) {
        AppPrefs.relayUrl = url.trim()
        relay?.stop()
        relay = null
        relayStatePumpJob?.cancel()
        relayStatePumpJob = null
        _relayConnected.value = false
        markRelayPeersOffline()
        if (url.isBlank()) return
        val client = RelayClient(url.trim()).also { it.start() }
        relay = client
        viewModelScope.launch {
            client.connected.collect { ok ->
                _relayConnected.value = ok
                if (ok) {
                    EventLog.info("relay: ${url.trim()}")
                    sendRelayHello()
                    startRelayStatePump()
                } else {
                    EventLog.info("relay: disconnected")
                    relayStatePumpJob?.cancel()
                    relayStatePumpJob = null
                    markRelayPeersOffline()
                }
            }
        }
        // Inbound stream: parse real messages, log the rest.
        viewModelScope.launch {
            client.events.collect { line ->
                if (line.startsWith("← ")) {
                    handleRelayInbound(line.removePrefix("← ").trim())
                } else {
                    EventLog.send(line.removePrefix("→").trim())
                }
            }
        }
    }

    fun reconnectRelay() = setRelayUrl(AppPrefs.relayUrl)
    fun hasRelay(): Boolean = AppPrefs.relayUrl.isNotBlank()

    // -------- Relay inbound handling --------

    private fun handleRelayInbound(text: String) {
        val msg = Protocol.decode(text) ?: return
        when (msg) {
            is Wire.Hello -> handleRelayHello(msg)
            is Wire.State -> handleRelayState(msg)
            is Wire.Cmd -> handleRelayCmd(msg)
            is Wire.Ack -> EventLog.send("relay ack: ${msg.action} ok=${msg.ok}")
            is Wire.AppsList -> { /* apps list needs a sender context; ignored for now */ }
            is Wire.Ping -> relay?.send(Protocol.encode(Wire.Pong()))
            is Wire.Pong -> { /* heartbeat */ }
            is Wire.ErrorMsg -> EventLog.send("relay err: ${msg.message}")
        }
    }

    private fun handleRelayHello(hello: Wire.Hello) {
        if (hello.deviceName == AppPrefs.deviceName) return
        if (hello.roomCode != AppPrefs.roomCode) return
        val key = RELAY_PEER_PREFIX + hello.deviceName
        val peers = _peers.value.toMutableMap()
        val existing = peers[key]
        peers[key] = (existing ?: Peer(
            id = key, host = "relay", port = 0, name = hello.deviceName
        )).copy(
            name = hello.deviceName,
            role = hello.role,
            online = true,
            lastSeenMs = System.currentTimeMillis()
        )
        _peers.value = peers
        EventLog.info("relay peer joined: ${hello.deviceName}")
    }

    private fun handleRelayState(state: Wire.State) {
        if (state.deviceName == AppPrefs.deviceName) return
        val key = RELAY_PEER_PREFIX + state.deviceName
        val peers = _peers.value.toMutableMap()
        val existing = peers[key]
        if (existing == null) {
            peers[key] = Peer(
                id = key, host = "relay", port = 0, name = state.deviceName,
                online = true, volume = state.volume, maxVolume = state.maxVolume,
                brightness = state.brightness, lastSeenMs = System.currentTimeMillis()
            )
        } else {
            peers[key] = existing.copy(
                volume = state.volume,
                maxVolume = state.maxVolume,
                brightness = state.brightness,
                online = true,
                lastSeenMs = System.currentTimeMillis()
            )
        }
        _peers.value = peers
    }

    private fun handleRelayCmd(cmd: Wire.Cmd) {
        if (cmd.sender == AppPrefs.deviceName) return
        EventLog.send("relay cmd: ${cmd.action} ${cmd.value ?: ""}")
        when (cmd.action) {
            Actions.VOLUME -> audio.setVolume(cmd.value ?: 0)
            Actions.VOLUME_PERCENT -> audio.setVolumePercent(cmd.value ?: 0)
            Actions.VOLUME_UP -> audio.adjust(1)
            Actions.VOLUME_DOWN -> audio.adjust(-1)
            Actions.BRIGHTNESS_PERCENT -> {
                if (audio.hasBrightnessWritePermission()) audio.setBrightnessPercent(cmd.value ?: 0)
            }
            Actions.LAUNCH_APP -> cmd.target?.let { audio.launchApp(it) }
            Actions.LIST_APPS -> { /* master-only request; ignore on slave */ }
            Actions.PLAY -> audio.play()
            Actions.PAUSE -> audio.pause()
            Actions.TOGGLE -> audio.toggle()
            Actions.NEXT -> audio.next()
            Actions.PREVIOUS -> audio.previous()
            Actions.MUTE -> audio.mute(true)
            Actions.UNMUTE -> audio.mute(false)
        }
    }

    private fun markRelayPeersOffline() {
        val peers = _peers.value.toMutableMap()
        var changed = false
        for ((k, p) in peers) {
            if (k.startsWith(RELAY_PEER_PREFIX) && p.online) {
                peers[k] = p.copy(online = false)
                changed = true
            }
        }
        if (changed) _peers.value = peers
    }

    // -------- Relay outbound helpers --------

    private fun sendRelayHello() {
        relay?.send(Protocol.encode(Wire.Hello(
            roomCode = AppPrefs.roomCode,
            deviceName = AppPrefs.deviceName,
            role = "master",
            maxVolume = audio.maxVolume()
        )))
    }

    private fun startRelayStatePump() {
        relayStatePumpJob?.cancel()
        relayStatePumpJob = viewModelScope.launch {
            while (isActive) {
                relay?.send(Protocol.encode(Wire.State(
                    deviceName = AppPrefs.deviceName,
                    playing = false,
                    volume = audio.volumePercent(),
                    maxVolume = 100,
                    brightness = audio.brightnessPercent()
                )))
                delay(2000)
            }
        }
    }

    private fun relayCmd(action: String, value: Int? = null, target: String? = null) {
        relay?.send(Protocol.encode(Wire.Cmd(
            action = action,
            value = value,
            target = target,
            sender = AppPrefs.deviceName
        )))
    }

    // -------- LAN client sync --------

    private fun syncClients(snapshot: Map<String, Peer>) {
        for ((key, peer) in snapshot) {
            if (key.startsWith(RELAY_PEER_PREFIX)) continue
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
                        EventLog.send(line.removePrefix("→").trim())
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
        val keep = clients.keys.intersect(snapshot.keys)
        for (k in clients.keys.toList()) if (k !in keep) {
            clients.remove(k)?.stop()
        }
    }

    // -------- Command fan-out --------

    fun broadcastVolume(value: Int) = broadcast(Actions.VOLUME, value)
    fun broadcastVolumeUp() = broadcast(Actions.VOLUME_UP, null)
    fun broadcastVolumeDown() = broadcast(Actions.VOLUME_DOWN, null)

    fun broadcastVolumePercent(percentPercent: Int) {
        val clamped = percentPercent.coerceIn(0, 100)
        if (_relayConnected.value && relay != null) {
            relayCmd(Actions.VOLUME_PERCENT, clamped)
        } else {
            clients.values.forEach { it.sendCmd(Actions.VOLUME_PERCENT, clamped) }
        }
        audio.setVolumePercent(clamped)
    }

    fun broadcastBrightnessPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        var sent = 0
        if (_relayConnected.value && relay != null) {
            relayCmd(Actions.BRIGHTNESS_PERCENT, clamped)
            sent = 1
        } else {
            clients.values.forEach { c ->
                val peer = _peers.value[c.key]
                if (peer?.selected != false) {
                    c.sendCmd(Actions.BRIGHTNESS_PERCENT, clamped)
                    sent++
                }
            }
        }
        audio.setBrightnessPercent(clamped)
        if (sent == 0) EventLog.send("亮度 → $clamped% (no online peer)")
    }

    fun sendBrightnessPercentTo(peerKey: String, percent: Int) {
        if (peerKey.startsWith(RELAY_PEER_PREFIX)) {
            relayCmd(Actions.BRIGHTNESS_PERCENT, percent.coerceIn(0, 100))
        } else {
            clients[peerKey]?.sendCmd(Actions.BRIGHTNESS_PERCENT, percent.coerceIn(0, 100))
        }
    }

    fun requestAppsFor(peerKey: String) {
        if (peerKey.startsWith(RELAY_PEER_PREFIX)) {
            relayCmd(Actions.LIST_APPS)
        } else {
            clients[peerKey]?.sendCmd(Actions.LIST_APPS)
        }
    }

    fun launchAppOn(peerKey: String, packageName: String) {
        if (peerKey.startsWith(RELAY_PEER_PREFIX)) {
            relayCmd(Actions.LAUNCH_APP, target = packageName)
        } else {
            clients[peerKey]?.sendCmd(Actions.LAUNCH_APP, target = packageName)
            relayCmd(Actions.LAUNCH_APP, target = packageName)
        }
    }

    fun sendVolumePercentTo(peerKey: String, percentPercent: Int) {
        val clamped = percentPercent.coerceIn(0, 100)
        if (peerKey.startsWith(RELAY_PEER_PREFIX)) {
            relayCmd(Actions.VOLUME_PERCENT, clamped)
        } else {
            clients[peerKey]?.sendCmd(Actions.VOLUME_PERCENT, clamped)
        }
    }

    fun setLocalVolumePercent(percentPercent: Int) {
        audio.setVolumePercent(percentPercent.coerceIn(0, 100))
        EventLog.send("本机音量 → ${percentPercent.coerceIn(0, 100)}%")
    }

    fun localBrightnessPercent(): Int = audio.brightnessPercent()
    fun hasBrightnessPermission(): Boolean = audio.hasBrightnessWritePermission()
    fun localVolumePercent(): Int = audio.volumePercent()
    fun localMaxVolumeIndex(): Int = audio.maxVolume()

    fun broadcastPlay() = broadcast(Actions.PLAY, null)
    fun broadcastPause() = broadcast(Actions.PAUSE, null)
    fun broadcastToggle() = broadcast(Actions.TOGGLE, null)
    fun broadcastNext() = broadcast(Actions.NEXT, null)
    fun broadcastPrev() = broadcast(Actions.PREVIOUS, null)
    fun broadcastMute() = broadcast(Actions.MUTE, null)
    fun broadcastUnmute() = broadcast(Actions.UNMUTE, null)

    fun sendTo(peerKey: String, action: String, value: Int? = null) {
        if (peerKey.startsWith(RELAY_PEER_PREFIX)) {
            relayCmd(action, value)
        } else {
            clients[peerKey]?.sendCmd(action, value)
            relayCmd(action, value)
        }
    }

    private fun broadcast(action: String, value: Int?) {
        if (_relayConnected.value && relay != null) {
            relayCmd(action, value)
            return
        }
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
        relay?.stop()
        relayStatePumpJob?.cancel()
        nsd.stopDiscovery()
    }

    companion object {
        private const val RELAY_PEER_PREFIX = "relay:"
    }
}
