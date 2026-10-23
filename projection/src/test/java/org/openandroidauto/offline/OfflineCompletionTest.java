// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfflineCompletionTest {
    @Test public void aFreshUnknownZeroOverZeroRegionIsNotDownloaded() {
        assertFalse(OfflineCompletion.ready(false,0,0,0));
        assertFalse(OfflineCompletion.ready(true,0,0,0));
    }
    @Test public void aGrowingEstimateCannotTurnGreenAtAnIntermediateEquality() {
        assertFalse(OfflineCompletion.ready(false,100,100,20));
        assertFalse(OfflineCompletion.ready(false,100,110,20));
    }
    @Test public void missingTilesAndIncompleteResourcesCannotBeReady() {
        assertFalse(OfflineCompletion.ready(true,100,100,0));
        assertFalse(OfflineCompletion.ready(true,100,99,20));
        assertFalse(OfflineCompletion.ready(true,100,-1,20));
    }
    @Test public void precisePositiveResourcesWithSavedTilesAreReady() {
        assertTrue(OfflineCompletion.ready(true,100,100,20));
        assertTrue(OfflineCompletion.ready(true,100,101,20));
    }
}
