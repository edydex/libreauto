// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

/** Connection observations, never a selected mode, decide whether stereo navigation work runs. */
class OfficialProjectionWorkPolicy {
    enum class Change { NONE, SUSPEND_NAVIGATION, RESUME_NAVIGATION }
    var connected: Boolean = false
        private set
    val navigationAllowed: Boolean get() = !connected
    fun observeConnection(actualConnected: Boolean): Change {
        if (connected == actualConnected) return Change.NONE
        connected = actualConnected
        return if (actualConnected) Change.SUSPEND_NAVIGATION else Change.RESUME_NAVIGATION
    }
}
