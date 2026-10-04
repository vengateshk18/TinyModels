package com.example.tinymodels.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide connectivity source of truth. ViewModels and the root scaffold
 * observe [isOnline] to drive offline states, auto-retry, and the global
 * offline banner.
 */
interface NetworkMonitor {
    /** True when the device has a usable network connection. */
    val isOnline: StateFlow<Boolean>
}

/**
 * [ConnectivityManager]-backed [NetworkMonitor]. Registers a single system
 * callback for the lifetime of the process (Hilt singleton) and mirrors
 * availability into a [StateFlow] that survives configuration changes.
 *
 * Note: this reports *network availability*, not guaranteed internet access.
 * Requests made while "online" can still fail (captive portals, HF outages);
 * those flow through [NetworkErrorMapper] as typed errors instead.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context
) : NetworkMonitor {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyOnline())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
            }

            override fun onLost(network: Network) {
                // The default network was lost — re-check in case another one exists.
                _isOnline.value = currentlyOnline()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun currentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}