package com.repcoach.tv.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.repcoach.core.Protocol
import java.net.Inet4Address
import java.net.NetworkInterface

// lets phones find the TV on the wifi
class TvAnnouncer(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "RepCoach ${Build.MODEL}"
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
        }
        val callbacks = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Register failed: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Unregister failed: $error")
            }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callbacks)
        listener = callbacks
    }

    fun stop() {
        val callbacks = listener ?: return
        listener = null
        runCatching { nsd.unregisterService(callbacks) }
    }

    private companion object {
        const val TAG = "TvAnnouncer"
    }
}

// the TV's local ip, shown on screen so you can type it in
fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
