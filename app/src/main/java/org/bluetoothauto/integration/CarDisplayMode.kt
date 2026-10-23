// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

/** A preference, not a claim that a car is connected or that this app owns audio. */
enum class CarDisplayMode(val label: String) {
    BLUETOOTH("Bluetooth stereo"),
    LIBREAUTO("LibreAuto car display"),
    OFFICIAL_AA("Official Android Auto");

    companion object {
        // Android Auto handles capable receivers; the ordinary player remains available on
        // Bluetooth-only stereos. Loading this preference never starts either projector.
        fun fromStored(value: String?): CarDisplayMode = entries.firstOrNull { it.name == value } ?: OFFICIAL_AA
    }
}

/** Pure recovery policy: preserve pre-existing disabled states and subsequent outside changes. */
object OfficialAutoStatePolicy {
    const val DEFAULT = 0
    const val ENABLED = 1
    const val DISABLED = 2
    const val DISABLED_USER = 3
    const val DISABLED_UNTIL_USED = 4
    fun valid(state: Int) = state in DEFAULT..DISABLED_UNTIL_USED
    fun shouldDisable(state: Int, effectivelyEnabled: Boolean) = valid(state) && effectivelyEnabled
    fun restoreTarget(current: Int, original: Int, applied: Int): Int? =
        original.takeIf { valid(it) && valid(applied) && current == applied && it != current }
    fun ownsConfirmedPause(writePerformed: Boolean, current: Int): Boolean =
        writePerformed && current == DISABLED_USER
}
