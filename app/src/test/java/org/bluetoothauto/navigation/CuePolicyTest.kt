// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation
import org.junit.Assert.*
import org.junit.Test
class CuePolicyTest {
    @Test fun distanceRefreshDoesNotRepeatInstruction(){val p=CuePolicy();assertTrue(p.accept("Turn right onto James Street"));assertFalse(p.accept("Turn right onto James Street"));assertFalse(p.accept(" TURN RIGHT ONTO JAMES STREET "));assertTrue(p.accept("Turn left onto 2nd Avenue"))}
    @Test fun sessionAndRouteEndAllowNextTrip(){val p=CuePolicy();assertTrue(p.accept("Turn left"));assertFalse(p.accept(null));assertTrue(p.accept("Turn left"));p.reset();assertTrue(p.accept("Turn left"))}
}
