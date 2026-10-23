package org.openandroidauto.wireless

import org.openandroidauto.transport.Transport

/** A retained, exact car-network request. No process-wide network routing is permitted. */
interface CarNetworkJoiner {
    /** Copy any needed credentials before returning; the reducer owns the borrowed originals. */
    fun request(token: Long, credentials: LegacyWppSession.Credentials, callback: Callback): Request

    interface Callback {
        fun available(lease: Lease)
        fun unavailable()
        fun lost()
    }

    interface Request : AutoCloseable { override fun close() }

    interface Lease : AutoCloseable {
        val token: Long
        /** This transport must create/bind its socket only through this lease's Network. */
        fun createTransport(ipv4: ByteArray, port: Int): Transport
        override fun close()
    }
}
