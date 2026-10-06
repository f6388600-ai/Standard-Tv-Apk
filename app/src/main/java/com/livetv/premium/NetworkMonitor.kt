package com.livetv.premium

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object NetworkMonitor {
    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        true
    }
}

/** Live "is the device online" state. Updates automatically when the network changes. */
@Composable
fun rememberIsOnline(): MutableState<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(NetworkMonitor.isOnline(context)) }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networks = HashSet<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(networks) { networks.add(network) }
                state.value = true
            }

            override fun onLost(network: Network) {
                val any = synchronized(networks) {
                    networks.remove(network)
                    networks.isNotEmpty()
                }
                state.value = any
            }
        }
        var registered = false
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm?.registerNetworkCallback(request, callback)
            registered = cm != null
        } catch (_: Throwable) {
        }
        onDispose {
            if (registered) {
                try {
                    cm?.unregisterNetworkCallback(callback)
                } catch (_: Throwable) {
                }
            }
        }
    }
    return state
}
