// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.junit.Assert.*
import org.junit.Test

class OfficialProjectionWorkPolicyTest {
    @Test fun withoutAnActualConnectionThePlayerKeepsItsNormalNavigationBehavior() {
        val policy = OfficialProjectionWorkPolicy()
        assertTrue(policy.navigationAllowed)
        assertEquals(OfficialProjectionWorkPolicy.Change.NONE, policy.observeConnection(false))
        assertTrue(policy.navigationAllowed)
    }
    @Test fun anActualConnectionSuspendsWorkOnceAndDisconnectRestoresIt() {
        val policy = OfficialProjectionWorkPolicy()
        assertEquals(OfficialProjectionWorkPolicy.Change.SUSPEND_NAVIGATION, policy.observeConnection(true))
        assertFalse(policy.navigationAllowed)
        assertEquals(OfficialProjectionWorkPolicy.Change.NONE, policy.observeConnection(true))
        assertEquals(OfficialProjectionWorkPolicy.Change.RESUME_NAVIGATION, policy.observeConnection(false))
        assertTrue(policy.navigationAllowed)
        assertEquals(OfficialProjectionWorkPolicy.Change.NONE, policy.observeConnection(false))
    }
}
