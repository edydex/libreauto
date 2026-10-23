// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.junit.Assert.*
import org.junit.Test

class CarProfilesTest {
    private val a = "12:34:56:78:90:AB"
    private val b = "22:34:56:78:90:AB"
    private val unknown = "32:34:56:78:90:AB"
    private val known = setOf(a, b)

    @Test fun newCarsPreferOfficialProjectionAcrossSupportedAndroidVersions() {
        for (sdk in listOf(26, 30, 31, 32, 33, 36))
            assertEquals(CarDisplayMode.OFFICIAL_AA, CarProfileDefaults.preferredProjection(CarDisplayMode.OFFICIAL_AA, sdk))
    }
    @Test fun newCarKeepsTheUsersLibreAutoProjectorPreference() {
        for (sdk in listOf(32, 33, 36))
            assertEquals(CarDisplayMode.LIBREAUTO, CarProfileDefaults.preferredProjection(CarDisplayMode.LIBREAUTO, sdk))
    }
    @Test fun aTemporaryBluetoothChoiceDoesNotAssumeAnotherCarLacksProjection() {
        assertEquals(CarDisplayMode.OFFICIAL_AA, CarProfileDefaults.preferredProjection(CarDisplayMode.BLUETOOTH, 36))
    }
    @Test fun olderPhoneCannotDefaultANewCarToUnsupportedLibreAutoProjection() {
        assertEquals(CarDisplayMode.OFFICIAL_AA, CarProfileDefaults.preferredProjection(CarDisplayMode.LIBREAUTO, 26))
    }
    @Test fun existingExplicitBluetoothProfileIsNeverRewrittenByNewDefaults() {
        val old = CarProfile(a, "Older stereo", CarDisplayMode.BLUETOOTH)
        assertEquals(listOf(old), CarProfilesCodec.decode(CarProfilesCodec.encode(listOf(old))))
    }
    @Test fun eventCancellationIsDurableAndOneShot() {
        val event = CarProfileEvent()
        assertFalse(event.isCancelled)
        assertTrue(event.cancel()); assertTrue(event.isCancelled)
        assertFalse(event.cancel()); assertTrue(event.isCancelled)
    }
    @Test fun aLatePreparationCallbackCannotStartAfterBroadcastTimeout() {
        val event = CarProfileEvent().apply { ticket = 7 }
        var starts = 0
        val callback: (Boolean) -> Unit = { ready -> if (ready && event.canContinue(7)) starts++ }
        event.cancel()
        callback(true); callback(true)
        assertEquals(0, starts)
    }
    @Test fun aNewCarOrManualSelectionInvalidatesEarlierCallbacks() {
        val event = CarProfileEvent().apply { ticket = 7 }
        assertTrue(event.canContinue(7))
        assertFalse(event.canContinue(8))
        assertFalse(CarProfileEvent().canContinue(-1))
    }

    @Test fun emptyPreferencesStayEmpty() { assertTrue(CarProfilesCodec.decode(null).isEmpty()); assertTrue(CarProfilesCodec.decode("").isEmpty()) }
    @Test fun allModesAndCarNamesRoundTrip() {
        val cars = CarDisplayMode.entries.mapIndexed { index, mode -> CarProfile("0$index:34:56:78:90:AB", "Car $index", mode) }
        assertEquals(cars, CarProfilesCodec.decode(CarProfilesCodec.encode(cars)))
    }
    @Test fun addressesAreCanonicalButMustBeConcrete() {
        assertEquals(a, CarProfilesCodec.address(a.lowercase()))
        for (value in listOf("", "12:34", "*", "12:34:56:78:90:ZZ", "my Wi-Fi")) assertNull(CarProfilesCodec.address(value))
    }
    @Test fun repeatedAddressIsRefused() { rejects { CarProfilesCodec.encode(listOf(CarProfile(a, "One", CarDisplayMode.BLUETOOTH), CarProfile(a, "Two", CarDisplayMode.OFFICIAL_AA))) } }
    @Test fun invalidNameIsRefused() {
        for (name in listOf("", " ", "bad\nname", "x".repeat(81))) rejects { CarProfilesCodec.encode(listOf(CarProfile(a, name, CarDisplayMode.BLUETOOTH))) }
    }
    @Test fun futureAndUnknownModesAreRefused() {
        rejects { CarProfilesCodec.decode("""{"version":2,"cars":[]}""") }
        rejects { CarProfilesCodec.decode("""{"version":1,"cars":[{"address":"$a","name":"Car","mode":"WIFI"}]}""") }
    }
    @Test fun profileAndInputBoundsAreEnforced() {
        rejects { CarProfilesCodec.decode(" ".repeat(32_769)) }
        rejects { CarProfilesCodec.encode(List(17) { CarProfile("%02X:34:56:78:90:AB".format(it), "Car", CarDisplayMode.BLUETOOTH) }) }
    }
    @Test fun unknownDeviceLeavesStateUntouched() {
        val p = CarProfilesPolicy(); val before = p.save()
        assertEquals(CarProfilesPolicy.Action.IGNORE, p.connect(unknown, known).action)
        assertEquals(before, p.save()); assertFalse(p.canApply(unknown))
    }
    @Test fun firstExactConnectionAppliesOnce() {
        val p = CarProfilesPolicy()
        assertEquals(CarProfilesPolicy.Action.APPLY, p.connect(a, known).action)
        assertTrue(p.canApply(a))
        assertEquals(CarProfilesPolicy.Action.IGNORE, p.connect(a, known).action)
    }
    @Test fun manualOverrideSurvivesDuplicateBroadcast() {
        val p = CarProfilesPolicy(); p.connect(a, known); p.manualSelection()
        assertFalse(p.canApply(a)); assertEquals(CarProfilesPolicy.Action.IGNORE, p.connect(a, known).action)
        assertFalse(p.canApply(a))
    }
    @Test fun actualReconnectResetsManualOverride() {
        val p = CarProfilesPolicy(); p.connect(a, known); p.manualSelection(); p.disconnect(a)
        assertEquals(CarProfilesPolicy.Action.APPLY, p.connect(a, known).action); assertTrue(p.canApply(a))
    }
    @Test fun differentSavedCarsConnectedTogetherDoNotStealDisplay() {
        val p = CarProfilesPolicy(); p.connect(a, known)
        assertEquals(CarProfilesPolicy.Action.AMBIGUOUS, p.connect(b, known).action)
        assertFalse(p.canApply(a)); assertFalse(p.canApply(b))
        p.disconnect(b); assertFalse(p.canApply(a))
    }
    @Test fun unknownDisconnectCannotChangeCurrentCar() {
        val p = CarProfilesPolicy(); p.connect(a, known)
        assertEquals(CarProfilesPolicy.Action.IGNORE, p.disconnect(unknown).action); assertTrue(p.canApply(a))
    }
    @Test fun removingCurrentConnectionInvalidatesAsyncApply() {
        val p = CarProfilesPolicy(); p.connect(a, known)
        assertEquals(CarProfilesPolicy.Action.DISCONNECTED, p.disconnect(a).action); assertFalse(p.canApply(a))
    }
    @Test fun stateRestoresPausedConnectionAcrossProcessRecreation() {
        val p = CarProfilesPolicy(); p.connect(a, known); p.manualSelection()
        val restored = CarProfilesPolicy(); restored.restore(p.save(), known)
        assertEquals(CarProfilesPolicy.Action.IGNORE, restored.connect(a, known).action); assertFalse(restored.canApply(a))
        restored.disconnect(a); assertEquals(CarProfilesPolicy.Action.APPLY, restored.connect(a, known).action)
    }
    @Test fun restoreDropsRemovedProfiles() {
        val p = CarProfilesPolicy(); p.connect(a, known)
        val restored = CarProfilesPolicy(); restored.restore(p.save(), setOf(b))
        assertFalse(restored.canApply(a)); assertEquals(CarProfilesPolicy.Action.APPLY, restored.connect(b, setOf(b)).action)
    }
    @Test fun corruptOrOversizedStateFailsClosed() {
        for (raw in listOf("bad json", "x".repeat(4_097))) {
            val p = CarProfilesPolicy(); p.connect(a, known); p.restore(raw, known)
            assertFalse(p.canApply(a))
        }
    }
    @Test fun clearPreventsAnOldConnectionFromApplying() {
        val p = CarProfilesPolicy(); p.connect(a, known); p.clear(); assertFalse(p.canApply(a))
        assertEquals(CarProfilesPolicy.Action.APPLY, p.connect(b, known).action)
    }
    @Test fun stereoOnlyRequestsNoLocationOrScanningPermission() {
        for (sdk in listOf(26, 30, 31, 32, 33, 36)) {
            val permissions = CarProfilesPermissions.required(sdk, false)
            assertTrue(permissions.none { it.contains("LOCATION") || it.contains("SCAN") || it.contains("WIFI") })
        }
    }
    @Test fun modernProjectionRequiresFineLocationAndNearbyWifi() {
        val permissions = CarProfilesPermissions.required(33, true)
        assertTrue(permissions.containsAll(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.NEARBY_WIFI_DEVICES", "android.permission.BLUETOOTH_CONNECT", "android.permission.POST_NOTIFICATIONS")))
        assertFalse(permissions.any { it.contains("BACKGROUND_LOCATION") || it.contains("SCAN") })
    }
    @Test fun android12LProjectionRequiresFineLocationAndBluetooth() {
        assertEquals(setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_CONNECT"), CarProfilesPermissions.required(32, true).toSet())
    }
    @Test fun olderPhonesUseLegacyBluetoothWithoutUnsupportedProjectionPermissions() {
        assertEquals(listOf("android.permission.BLUETOOTH"), CarProfilesPermissions.required(26, true))
    }
    @Test fun requestingAConnectionDoesNotClaimOwnership() {
        val attempt = CarProfileAttempt(); attempt.request(a)
        assertNull(attempt.owned); assertEquals(a, attempt.pending); assertFalse(attempt.disconnected(b))
    }
    @Test fun rejectedForegroundStartStillProducesFailureFallback() {
        val attempt = CarProfileAttempt(); attempt.request(a)
        assertEquals(a, attempt.stopped()); assertNull(attempt.pending); assertNull(attempt.owned)
    }
    @Test fun transportFailureBeforeReadinessStillProducesFailureFallback() {
        val attempt = CarProfileAttempt(); attempt.request(a); attempt.carSelected(a, true)
        assertEquals(a, attempt.owned); assertEquals(a, attempt.stopped())
    }
    @Test fun readyConnectionStopsWithoutFalseFailureNotification() {
        val attempt = CarProfileAttempt(); attempt.request(a); attempt.carSelected(a, true); attempt.becameReady()
        assertNull(attempt.stopped())
    }
    @Test fun anotherCarOrUsbTransportCannotBeOwnedByAnOldRequest() {
        for (actual in listOf(b, null)) {
            val attempt = CarProfileAttempt(); attempt.request(a); attempt.carSelected(actual, true)
            assertNull(attempt.owned); assertFalse(attempt.disconnected(a))
        }
    }
    @Test fun disconnectStopsOnlyTheExactVerifiedAutoCar() {
        val attempt = CarProfileAttempt(); attempt.request(a); attempt.carSelected(a, true)
        assertFalse(attempt.disconnected(b)); assertEquals(a, attempt.owned)
        assertTrue(attempt.disconnected(a)); assertNull(attempt.owned)
    }
    @Test fun manualOverrideInvalidatesAutomaticAttemptOwnership() {
        val attempt = CarProfileAttempt(); attempt.request(a); attempt.clear(); attempt.carSelected(a, false)
        assertNull(attempt.owned); assertNull(attempt.stopped())
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("Invalid profile was accepted") } catch (_: IllegalArgumentException) {} }
}
