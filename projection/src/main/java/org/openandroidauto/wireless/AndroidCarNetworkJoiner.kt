package org.openandroidauto.wireless

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import org.openandroidauto.transport.NetworkTcpTransport
import org.openandroidauto.transport.Transport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact, user-approved local Wi-Fi lease; no scans, saved credentials, or process binding. */
class AndroidCarNetworkJoiner internal constructor(
    private val platform: Platform,
    private val scheduler: NetworkTcpTransport.Scheduler = NetworkTcpTransport.deadlines,
    private val joinTimeoutMs: Long = 60_000
) : CarNetworkJoiner {
    constructor(context: Context) : this(AndroidPlatform(context.applicationContext))
    init { require(joinTimeoutMs in 1..90_000) }

    internal interface BoundNetwork {
        val identity: Any
        fun createTransport(ipv4: ByteArray, port: Int): NetworkTcpTransport
    }
    internal interface Events {
        fun available(value: BoundNetwork)
        fun unavailable()
        fun lost()
    }
    internal interface Platform {
        fun prerequisites(): Boolean
        fun request(spec: Spec, events: Events): AutoCloseable
    }
    internal class Spec(credentials: LegacyWppSession.Credentials) : AutoCloseable {
        val ssid = credentials.ssidUtf8Copy()
        val passphrase = credentials.passphraseAsciiCopy()
        val bssid = credentials.bssidCopy()
        override fun close() { ssid.fill(0); passphrase.fill(0); bssid.fill(0) }
        override fun toString() = "Exact car Wi-Fi request (credentials redacted)"
    }

    override fun request(token: Long, credentials: LegacyWppSession.Credentials, callback: CarNetworkJoiner.Callback): CarNetworkJoiner.Request {
        require(token > 0)
        val spec = Spec(credentials) // Copy before returning or scheduling any platform work.
        val attempt = Attempt(token, callback)
        try {
            if (!platform.prerequisites()) { attempt.unavailable(); return attempt }
            attempt.setTimer(scheduler.schedule(joinTimeoutMs) { attempt.timeout() })
            if (attempt.live()) attempt.setRegistration(platform.request(spec, attempt))
        } catch (_: Exception) { attempt.unavailable() }
        finally { spec.close() }
        return attempt
    }

    private inner class Attempt(val token: Long, val callback: CarNetworkJoiner.Callback) : CarNetworkJoiner.Request, Events {
        private val lock = Any()
        private var closed = false
        private var network: BoundNetwork? = null
        private var registration: AutoCloseable? = null
        private var timer: NetworkTcpTransport.Ticket? = null
        private var transport: NetworkTcpTransport? = null
        private var transportCreated = false
        private val lease = object : CarNetworkJoiner.Lease {
            override val token: Long get() = this@Attempt.token
            override fun createTransport(ipv4: ByteArray, port: Int): Transport = synchronized(lock) {
                if (closed || network == null) throw IOException("Car Wi-Fi lease ended")
                if (transportCreated) throw IOException("Car Wi-Fi lease already has a transport")
                val address = ipv4.copyOf()
                val value = try { checkNotNull(network).createTransport(address, port) } finally { address.fill(0) }
                transportCreated = true; transport = value; value
            }
            override fun close() = this@Attempt.close()
        }
        fun live(): Boolean = synchronized(lock) { !closed }
        fun setTimer(value: NetworkTcpTransport.Ticket) = synchronized(lock) {
            if (closed || network != null) value.cancel() else timer = value
        }
        fun setRegistration(value: AutoCloseable) = synchronized(lock) {
            if (closed) quietlyClose(value) else registration = value
        }
        override fun available(value: BoundNetwork) {
            synchronized(lock) {
                if (closed) return
                if (network != null) {
                    if (network?.identity != value.identity) finish(true)
                    return
                }
                network = value; timer?.cancel(); timer = null
                try { callback.available(lease) } catch (_: Exception) { finish(null) }
            }
        }
        override fun unavailable() = finish(false)
        override fun lost() = finish(true)
        override fun close() = finish(null)
        fun timeout() { synchronized(lock) { if (network == null) finish(false) } }
        /** A retained request and its lease share one terminal release. */
        private fun finish(lost: Boolean?) {
            synchronized(lock) {
                if (closed) return
                val wasAvailable = network != null
                closed = true; network = null
                timer?.cancel(); timer = null
                transport?.close(); transport = null
                quietlyClose(registration); registration = null
                try {
                    if (lost != null) {
                        if (wasAvailable) callback.lost() else callback.unavailable()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private class AndroidPlatform(private val context: Context) : Platform {
        private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
        override fun prerequisites(): Boolean {
            if (Build.VERSION.SDK_INT < 32) return false
            val permissions = listOf(Manifest.permission.CHANGE_NETWORK_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
            if (permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return false
            return connectivity != null && context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }
        override fun request(spec: Spec, events: Events): AutoCloseable {
            if (Build.VERSION.SDK_INT < 32) throw UnsupportedOperationException("Car-screen projection needs Android 12L or newer")
            val manager = checkNotNull(connectivity)
            val wifi = WifiNetworkSpecifier.Builder()
                .setSsid(spec.ssid.toString(Charsets.UTF_8))
                .setBssid(MacAddress.fromBytes(spec.bssid))
                .setWpa2Passphrase(spec.passphrase.toString(Charsets.US_ASCII)).build()
            // Android retains its own immutable strings for this request; no app preferences/logs keep them.
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(wifi).build()
            val closed = AtomicBoolean()
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var candidate: Network? = null
                private var delivered = false
                override fun onAvailable(network: Network) {
                    if (closed.get()) return
                    if (delivered && candidate != network) { events.lost(); return }
                    candidate = network
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (closed.get() || candidate != network) return
                    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { events.unavailable(); return }
                    delivered = true
                    // This exact request's callback establishes identity; WifiInfo may be correctly redacted.
                    events.available(object : BoundNetwork {
                        override val identity: Any = network
                        override fun createTransport(ipv4: ByteArray, port: Int) = NetworkTcpTransport(network, ipv4, port)
                    })
                }
                override fun onUnavailable() { if (!closed.get()) events.unavailable() }
                override fun onLost(network: Network) { if (!closed.get() && candidate == network) events.lost() }
            }
            try { manager.requestNetwork(request, callback) }
            catch (failure: Exception) {
                closed.set(true)
                try { manager.unregisterNetworkCallback(callback) } catch (_: Exception) { }
                throw failure
            }
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    try { manager.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
                }
            }
        }
    }
    companion object {
        private fun quietlyClose(value: AutoCloseable?) { try { value?.close() } catch (_: Exception) {} }
    }
}
