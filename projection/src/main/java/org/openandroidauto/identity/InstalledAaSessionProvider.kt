// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.identity

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.openandroidauto.tls.AaTlsServer

/** Ordinary app-UID provider; no shell, persistent credentials, or identity fallback.
 * The only material/factory references are local to this one joined session. */
internal object InstalledAaSessionProvider {
    private val busy = AtomicBoolean()
    suspend fun withIdentity(context: Context, stop: ProjectionStopSignal,
        requestStop: () -> Unit, cleanupVerified: () -> Boolean,
        onStatus: (IdentityStatus) -> Unit,
        body: suspend (() -> SSLEngine) -> Unit) = withContext(NonCancellable + Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) { onStatus(IdentityStatus.BUSY); requestStop(); return@withContext }
        var material: InstalledAaIdentityMaterial.Material? = null
        try {
            onStatus(IdentityStatus.PREPARING)
            val result = InstalledAaIdentityReader.prepare(context, stop)
            val prepared = result.prepared
            if (prepared == null) { onStatus(result.status); requestStop(); return@withContext }
            material = prepared.material
            onStatus(IdentityStatus.READY)
            SessionIdentityLease.run(stop, requestStop, cleanupVerified,
                onRetained = { onStatus(IdentityStatus.CLEANUP_UNVERIFIED) }) {
                body {
                    if (stop.isStopped) throw CancellationException("Session stopped")
                    if (!InstalledAaIdentityReader.stillCurrent(context, prepared)) {
                        onStatus(IdentityStatus.PACKAGE_CHANGED); requestStop()
                        throw CancellationException("Installed package changed")
                    }
                    try {
                        prepared.material.createEngine { store, password -> AaTlsServer(store, password).createEngine() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        onStatus(IdentityStatus.KEYSTORE_FAILED); requestStop()
                        throw CancellationException("Compatibility initialization failed")
                    }
                }
            }
        } finally {
            // Lease cannot return while an actual TLS user or unproven cleanup remains.
            material?.close()
            busy.set(false)
        }
    }
}
