// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.content.Context
import org.openandroidauto.navigation.DestinationStore

/** The existing host keys remain readable by its built-in map, including after an upgrade. */
class SharedDestinationsBackend(context: Context) : DestinationStore.Backend {
    private val preferences = context.applicationContext.getSharedPreferences("own-map-destinations", Context.MODE_PRIVATE)
    override fun read(): String = SharedDestinationsCodec.toProjection(
        preferences.getString("saved", "[]") ?: "[]",
        preferences.getString("recent", "[]") ?: "[]",
        preferences.getString("projection-details-v1", null)
    )
    override fun write(value: String) {
        val converted = SharedDestinationsCodec.toHost(value)
        check(preferences.edit().putString("saved", converted.saved).putString("recent", converted.recent)
            .putString("projection-details-v1", converted.details).commit()) { "Could not save places; free some storage and try again" }
    }
}
