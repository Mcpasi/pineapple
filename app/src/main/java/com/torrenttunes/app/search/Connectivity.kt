package com.torrenttunes.app.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class Connectivity @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork).hasInternet()

    val changes = networkChanges(::isOnline) { send ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // API 26+ supplies capabilities after onAvailable. Querying the
                // manager inside the callback can race that notification.
                send(capabilities.hasInternet())
            }

            override fun onLost(network: Network) {
                send(false)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        val unsubscribe: () -> Unit = { manager.unregisterNetworkCallback(callback) }
        unsubscribe
    }
}

private fun NetworkCapabilities?.hasInternet(): Boolean =
    this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
