package com.github.libretube.helpers

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import com.github.libretube.LibreTubeApp
import com.github.libretube.extensions.TAG
import com.github.libretube.util.NewPipeDownloaderImpl
import kotlinx.coroutines.CompletableDeferred

/**
 * YouTube sometimes blocks anonymous requests of a whole IP address ("sign in to confirm you're not
 * a bot"), e.g. the one of a home Wi-Fi network, while mobile networks are not affected.
 *
 * As a workaround, all network traffic of the app can be sent through the mobile network. This has
 * to include the video data, since the stream URLs only work for the IP address that requested them.
 */
object MobileDataFallback {
    private val connectivityManager
        get() = LibreTubeApp.instance.getSystemService<ConnectivityManager>()!!

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var activatedAtMs = 0L

    val isActive get() = boundNetwork != null

    val isActiveForTooLong
        get() = isActive && SystemClock.elapsedRealtime() - activatedAtMs > MAX_ACTIVE_DURATION_MS

    /**
     * Whether the app currently uses the mobile network anyway, i.e. there is nothing to switch to.
     */
    private fun isUsingMobileNetwork(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    /**
     * Sends all network traffic of the app through the mobile network.
     *
     * @return whether the traffic is sent through the mobile network now
     */
    suspend fun enable(): Boolean {
        if (isActive) return true
        if (isUsingMobileNetwork()) return false

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val availableNetwork = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                availableNetwork.complete(network)
            }

            override fun onUnavailable() {
                availableNetwork.complete(null)
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) disable()
            }
        }

        try {
            connectivityManager.requestNetwork(request, callback, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG(), "failed to request the mobile network: $e")
            return false
        }

        val network = availableNetwork.await()
        if (network == null || !connectivityManager.bindProcessToNetwork(network)) {
            connectivityManager.unregisterNetworkCallback(callback)
            return false
        }

        networkCallback = callback
        boundNetwork = network
        activatedAtMs = SystemClock.elapsedRealtime()
        // connections that were opened before still use the old network
        NewPipeDownloaderImpl.closeConnections()
        Log.i(TAG(), "all traffic is sent through the mobile network now")
        return true
    }

    /**
     * Go back to the default network.
     */
    fun disable() {
        val callback = networkCallback ?: return
        networkCallback = null
        boundNetwork = null

        connectivityManager.bindProcessToNetwork(null)
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        NewPipeDownloaderImpl.closeConnections()
        Log.i(TAG(), "all traffic is sent through the default network again")
    }

    private const val NETWORK_REQUEST_TIMEOUT_MS = 8000

    /**
     * The mobile network should not be used for longer than needed, since it might be metered.
     */
    private const val MAX_ACTIVE_DURATION_MS = 20 * 60 * 1000L
}
