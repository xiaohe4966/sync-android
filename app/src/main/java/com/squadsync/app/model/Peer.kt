package com.squadsync.app.model

/** In-memory representation of a discovered / connected peer. */
data class Peer(
    val id: String,                // host:port
    val host: String,
    val port: Int,
    var name: String,
    var role: String = "slave",
    var online: Boolean = false,
    var volume: Int = 0,
    var maxVolume: Int = 15,
    var playing: Boolean = false,
    var brightness: Int = 0,
    var apps: List<RemoteApp> = emptyList(),
    // True when the master user has ticked this peer in the multi-select UI.
    // Broadcast commands skip unselected peers.
    var selected: Boolean = true,
    var lastSeenMs: Long = System.currentTimeMillis()
)

data class RemoteApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean = false
)