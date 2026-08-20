package com.squadsync.app.ui

import android.content.Context
import com.squadsync.app.model.AppPrefs
import com.squadsync.app.net.SlaveService

/**
 * Brings up both halves of SquadSync as soon as the app is on screen:
 *
 *  - Slave half: a [SlaveService] runs in the foreground, opening the
 *    WebSocket on port 7878 and registering our mDNS service so other
 *    devices can discover us.
 *  - Master half: the [SquadViewModel] starts its own NSD discovery
 *    and connects to every peer it finds.
 *
 * After this runs the user can simply slide the volume/brightness on any
 * phone and the change is mirrored on every other phone in the room.
 */
object AutoStartService {

    fun bringUp(context: Context, vm: SquadViewModel) {
        val ctx = context.applicationContext
        // 1. Make sure the user-supplied room + device name are persisted
        //    before any other device tries to discover us.
        AppPrefs.roomCode
        AppPrefs.deviceName
        // 2. Start the WS server + mDNS registration (runs in foreground).
        SlaveService.start(ctx, port = 7878)
        // 3. Start discovering peers. MasterClient objects are created
        //    inside the VM as peers show up in nsd.peers.
        vm.startDiscovery()
        // 4. If the user previously configured a relay server, dial it.
        //    Failure is silent: the LAN path keeps working.
        if (AppPrefs.relayUrl.isNotBlank()) {
            vm.setRelayUrl(AppPrefs.relayUrl)
        }
    }

    fun tearDown(context: Context, vm: SquadViewModel) {
        val ctx = context.applicationContext
        SlaveService.stop(ctx)
        vm.stopDiscovery()
        // Best-effort: ask the VM to drop any relay connection too.
        vm.setRelayUrl("")
    }
}