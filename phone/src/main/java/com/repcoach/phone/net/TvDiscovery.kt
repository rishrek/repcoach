package com.repcoach.phone.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.repcoach.core.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetAddress

data class FoundTv(val name: String, val host: String)

// Finds TVs on the wifi. We resolve one at a time because older android versions fail
// if two resolves run at once.
class TvDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _tvs = MutableStateFlow<List<FoundTv>>(emptyList())
    val tvs: StateFlow<List<FoundTv>> = _tvs.asStateFlow()

    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        val callbacks = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _tvs.update { list -> list.filterNot { it.name == info.serviceName } }
            }
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.w(TAG, "Discovery failed: $error")
                synchronized(this@TvDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        listener = callbacks
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callbacks)
    }

    @Synchronized
    fun stop() {
        val callbacks = listener ?: return
        listener = null
        queue.clear()
        runCatching { nsd.stopServiceDiscovery(callbacks) }
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        queue.addLast(info)
        resolveNext()
    }

    @Synchronized
    @Suppress("DEPRECATION") // still works on every version we support
    private fun resolveNext() {
        if (resolving) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                hostOf(info)?.let { host ->
                    val tv = FoundTv(info.serviceName, host)
                    _tvs.update { list -> list.filterNot { it.name == tv.name } + tv }
                }
                done()
            }

            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Resolve failed for ${info.serviceName}: $error")
                done()
            }
        })
    }

    @Synchronized
    private fun done() {
        resolving = false
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // prefer ipv4 since that's what the TV shows on screen
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    private companion object {
        const val TAG = "TvDiscovery"
    }
}
