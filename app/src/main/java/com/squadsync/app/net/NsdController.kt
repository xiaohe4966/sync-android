package com.squadsync.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.squadsync.app.model.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Registers our service via NSD (mDNS) and discovers peers.
 *
 * Service type follows the convention `_<protocol>._<transport>.local.` -
 * we use `_squadsync._tcp.local.` for our WS-over-TCP service.
 */
class NsdController(private val appCtx: Context) {

    private val nsd: NsdManager =
        appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifi: WifiManager? =
        appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null

    private var registeredName: String? = null
    private var isDiscovering = false

    private val _peers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val peers: StateFlow<Map<String, DiscoveredPeer>> = _peers

    data class DiscoveredPeer(
        val name: String,
        val host: String,
        val port: Int,
        val roomCode: String
    )

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registeredName = serviceInfo.serviceName
            Log.i(TAG, "Registered as ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Registration failed: $errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "Unregistered ${serviceInfo.serviceName}")
        }
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Unregistration failed: $errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            isDiscovering = true
            Log.i(TAG, "Discovery started for $regType")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            isDiscovering = false
            Log.i(TAG, "Discovery stopped")
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery start failed: $errorCode")
            nsd.stopServiceDiscovery(this)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery stop failed: $errorCode")
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains(SERVICE_TYPE_PREFIX)) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed: $errorCode for ${serviceInfo.serviceName}")
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        applyResolved(serviceInfo)
                    }
                })
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            val key = service.serviceName
            val cur = _peers.value.toMutableMap()
            cur.remove(key)
            _peers.value = cur
            Log.i(TAG, "Service lost: ${service.serviceName}")
        }
    }

    private fun applyResolved(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host?.hostAddress ?: return
        val port = serviceInfo.port
        val name = serviceInfo.serviceName
        // txt record carries "roomCode"
        val txt = serviceInfo.attributes
        val room = txt["roomCode"]?.let { String(it) } ?: ""
        // Filter by room code so we only show the right room's devices.
        if (room != AppPrefs.roomCode) {
            Log.d(TAG, "Ignoring $name (room=$room != ours ${AppPrefs.roomCode})")
            return
        }
        val cur = _peers.value.toMutableMap()
        cur[name] = DiscoveredPeer(name, host, port, room)
        _peers.value = cur
        Log.i(TAG, "Resolved $name at $host:$port room=$room")
    }

    @SuppressLint("InlinedApi")
    fun register(port: Int) {
        acquireMulticast()
        val info = NsdServiceInfo().apply {
            serviceName = "SquadSync-${AppPrefs.deviceName}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("roomCode", AppPrefs.roomCode)
            setAttribute("deviceName", AppPrefs.deviceName)
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregister() {
        try { nsd.unregisterService(registrationListener) } catch (_: Throwable) {}
        releaseMulticast()
    }

    fun startDiscovery() {
        acquireMulticast()
        if (!isDiscovering) {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    fun stopDiscovery() {
        try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Throwable) {}
        releaseMulticast()
    }

    private fun acquireMulticast() {
        if (multicastLock == null) {
            multicastLock = wifi?.createMulticastLock("squadsync_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticast() {
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdController"
        private const val SERVICE_TYPE = "_squadsync._tcp."
        private const val SERVICE_TYPE_PREFIX = "_squadsync"
    }
}