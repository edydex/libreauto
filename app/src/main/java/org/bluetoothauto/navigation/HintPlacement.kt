// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Guidance

/** Stable preference values; an absent or unrecognized value preserves the original behavior. */
enum class HintPlacement(val inTitle: Boolean, val inArtwork: Boolean) {
    TITLE_ONLY(true, false),
    ARTWORK_ONLY(false, true),
    BOTH(true, true);

    fun titleGuidance(guidance: Guidance?): Guidance? = guidance.takeIf { inTitle }
    fun navigationArtwork(guidance: Guidance?): Boolean = inArtwork && guidance != null
    fun artworkLocation(mapEnabled: Boolean): Boolean = inArtwork && mapEnabled
    fun needsLocation(mapEnabled: Boolean, guidance: Guidance?, ownTrip: Boolean): Boolean =
        ownTrip || (artworkLocation(mapEnabled) && guidance != null)

    companion object {
        fun fromPreference(value: String?): HintPlacement = entries.firstOrNull { it.name == value } ?: BOTH
    }
}
