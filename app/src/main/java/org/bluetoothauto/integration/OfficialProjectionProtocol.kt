// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

/** AndroidX CarConnection's public provider protocol; original observer implementation.
 * See docs/official-projection-monitor.md and the bundled protocol attribution notice. */
object OfficialProjectionProtocol {
    const val PROVIDER_PACKAGE = "com.google.android.projection.gearhead"
    const val AUTHORITY = "androidx.car.app.connection"
    const val URI = "content://$AUTHORITY"
    const val STATE_COLUMN = "CarConnectionState"
    const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

    /** A broadcast is only an invalidation hint; only the trusted provider supplies state. */
    fun projected(providerPackage: String?, value: Int?): Boolean? =
        if (providerPackage != PROVIDER_PACKAGE) null else when (value) {
            0, 1 -> false
            2 -> true
            else -> null
        }
}

/** Last confirmed observation. Temporary failures cannot resume heavy mapping mid-projection. */
class OfficialProjectionObservation {
    var known = false; private set
    var active = false; private set
    private var epoch = 0L
    private var closed = false
    fun begin(): Long = ++epoch
    fun accept(token: Long, projected: Boolean?): Boolean {
        if (closed || token != epoch || projected == null) return false
        val changed = !known || active != projected
        known = true; active = projected
        return changed
    }
    fun close() { closed = true; epoch++; known = false; active = false }
}
