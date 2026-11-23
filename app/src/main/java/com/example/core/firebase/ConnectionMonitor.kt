package com.example.core.firebase

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

enum class NetworkAvailability {
    ONLINE,
    OFFLINE
}

enum class SyncConnectionState(val label: String, val detail: String) {
    ONLINE_SYNCED("Cloud Synced", "Connected to Cloud Firestore & local Room cache"),
    SYNCING_PENDING("Syncing Changes", "Uploading queued local writes to Cloud Firestore"),
    LOCAL_CACHE_ONLY("Cache Active", "Reading from local persistence while cloud updates"),
    OFFLINE_QUEUED("Offline Mode", "Device offline — Room & Firestore cache handling operations")
}

class ConnectionMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _hasPendingWrites = MutableStateFlow(false)
    val hasPendingWrites: StateFlow<Boolean> = _hasPendingWrites.asStateFlow()

    private val _isFromCache = MutableStateFlow(true)
    val isFromCache: StateFlow<Boolean> = _isFromCache.asStateFlow()

    val networkAvailability: Flow<NetworkAvailability> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(NetworkAvailability.OFFLINE)
            awaitClose {}
            return@callbackFlow
        }

        fun checkCurrent(): NetworkAvailability {
            val network = cm.activeNetwork ?: return NetworkAvailability.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return NetworkAvailability.OFFLINE
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            return if (hasInternet) NetworkAvailability.ONLINE else NetworkAvailability.OFFLINE
        }

        trySend(checkCurrent())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkAvailability.ONLINE)
            }

            override fun onLost(network: Network) {
                trySend(checkCurrent())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                trySend(if (hasInternet) NetworkAvailability.ONLINE else NetworkAvailability.OFFLINE)
            }
        }

        cm.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    val syncConnectionState: Flow<SyncConnectionState> = combine(
        networkAvailability,
        _hasPendingWrites,
        _isFromCache
    ) { network, pending, fromCache ->
        when {
            network == NetworkAvailability.OFFLINE -> SyncConnectionState.OFFLINE_QUEUED
            pending -> SyncConnectionState.SYNCING_PENDING
            fromCache -> SyncConnectionState.LOCAL_CACHE_ONLY
            else -> SyncConnectionState.ONLINE_SYNCED
        }
    }.distinctUntilChanged()

    fun reportSnapshotMetadata(isFromCache: Boolean, hasPendingWrites: Boolean) {
        _isFromCache.value = isFromCache
        _hasPendingWrites.value = hasPendingWrites
    }
}
