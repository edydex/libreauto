// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.junit.Assert.*
import org.junit.Test

class CarDisplayPolicyTest {
    @Test fun oldInstallAndUnknownPreferencesStayInBluetoothMode() {
        listOf(null, "", "projection", "OFFICIAL", "LIBREAUTO ").forEach {
            assertEquals(CarDisplayMode.OFFICIAL_AA, CarDisplayMode.fromStored(it))
        }
    }
    @Test fun explicitModeNamesRoundTripWithoutChangingOtherPreferences() {
        CarDisplayMode.entries.forEach { assertEquals(it, CarDisplayMode.fromStored(it.name)) }
    }
    @Test fun disabledAndroidAutoIsNotClaimedAsOurOwnChange() {
        (2..4).forEach { assertFalse(OfficialAutoStatePolicy.shouldDisable(it, false)) }
        assertFalse(OfficialAutoStatePolicy.shouldDisable(0, false))
    }
    @Test fun enabledDefaultAndExplicitStatesCanBePaused() {
        assertTrue(OfficialAutoStatePolicy.shouldDisable(0, true))
        assertTrue(OfficialAutoStatePolicy.shouldDisable(1, true))
    }
    @Test fun restoreReturnsTheExactOriginalSettingIncludingDefault() {
        assertEquals(0, OfficialAutoStatePolicy.restoreTarget(3, 0, 3))
        assertEquals(1, OfficialAutoStatePolicy.restoreTarget(3, 1, 3))
    }
    @Test fun laterUserAndSystemChangesArePreserved() {
        listOf(0, 1, 2, 4).forEach { assertNull(OfficialAutoStatePolicy.restoreTarget(it, 1, 3)) }
    }
    @Test fun failedMutationAlreadyAtOriginalNeedsNoWrite() {
        assertNull(OfficialAutoStatePolicy.restoreTarget(0, 0, 3))
        assertNull(OfficialAutoStatePolicy.restoreTarget(1, 1, 3))
    }
    @Test fun malformedJournalNeverProducesAPackageStateWrite() {
        listOf(-1, Int.MIN_VALUE, 5, Int.MAX_VALUE).forEach { invalid ->
            assertNull(OfficialAutoStatePolicy.restoreTarget(3, invalid, 3))
            assertNull(OfficialAutoStatePolicy.restoreTarget(invalid, 1, invalid))
            assertFalse(OfficialAutoStatePolicy.shouldDisable(invalid, true))
        }
    }
    @Test fun identicalOriginalAndAppliedStateDoesNotRewritePackage() {
        (0..4).forEach { assertNull(OfficialAutoStatePolicy.restoreTarget(it, it, it)) }
    }
    @Test fun failedCompareDoesNotOwnAUsersIndependentlyDisabledState() {
        assertFalse(OfficialAutoStatePolicy.ownsConfirmedPause(false, 3))
    }
    @Test fun successfulPauseRetainsItsRestoreJournalOnlyWhileItsAppliedValueRemains() {
        assertTrue(OfficialAutoStatePolicy.ownsConfirmedPause(true, 3))
        listOf(-1, 0, 1, 2, 4, 5).forEach {
            assertFalse(OfficialAutoStatePolicy.ownsConfirmedPause(true, it))
            assertFalse(OfficialAutoStatePolicy.ownsConfirmedPause(false, it))
        }
    }
}
