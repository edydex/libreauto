package org.openandroidauto.render;

import org.junit.Test;
import org.openandroidauto.navigation.AdaptiveMapFramePolicy;
import static org.junit.Assert.*;

public final class AdaptiveMapFramePolicyTest {
    private AdaptiveMapFramePolicy adaptive() {
        return new AdaptiveMapFramePolicy(AdaptiveMapFramePolicy.Mode.ADAPTIVE30, 30);
    }
    @Test public void idleAndHiddenDoNotRequestAnotherWakeup() {
        AdaptiveMapFramePolicy p = adaptive();
        assertEquals(5, p.evaluate(0).maximumFps);
        p.setVisible(true, 100);
        assertEquals(30, p.evaluate(100).maximumFps);
        assertEquals(1100, p.evaluate(100).nextReevaluateAtMillis);
        assertEquals(5, p.evaluate(1100).maximumFps);
        assertEquals(0, p.evaluate(1100).nextReevaluateAtMillis);
    }
    @Test public void realTouchAndCameraFlingStayResponsiveThenDecayOnce() {
        AdaptiveMapFramePolicy p = adaptive(); p.setVisible(true, 0);
        p.setInteracting(true, 2000);
        assertEquals(30, p.evaluate(9000).maximumFps);
        assertEquals(0, p.evaluate(9000).nextReevaluateAtMillis);
        p.setCameraMoving(true, 9000); p.setInteracting(false, 9100);
        assertEquals(0, p.evaluate(9200).nextReevaluateAtMillis);
        p.setCameraMoving(false, 9300);
        assertEquals(10300, p.evaluate(9400).nextReevaluateAtMillis);
        assertEquals(5, p.evaluate(10300).maximumFps);
    }
    @Test public void movingLocationHasBoundedLeaseAndStationaryDoesNotBoost() {
        AdaptiveMapFramePolicy p = adaptive(); p.setVisible(true, 0);
        p.onLocation(false, 2000);
        assertEquals(5, p.evaluate(2000).maximumFps);
        p.onLocation(true, 3000);
        assertEquals(5500, p.evaluate(3100).nextReevaluateAtMillis);
        assertEquals(30, p.evaluate(5400).maximumFps);
        assertEquals(5, p.evaluate(5500).maximumFps);
        p.onLocation(true, 6000); p.onLocation(false, 6100);
        assertEquals(5, p.evaluate(6100).maximumFps);
    }
    @Test public void hidingClearsTouchCameraAndMovementAndIgnoresHiddenEvents() {
        AdaptiveMapFramePolicy p = adaptive(); p.setVisible(true, 0);
        p.setInteracting(true, 2000); p.setCameraMoving(true, 2000); p.onLocation(true, 2000);
        p.setVisible(false, 2100); p.noteActivity(2200); p.onLocation(true, 2200);
        assertEquals(5, p.evaluate(2200).maximumFps);
        assertEquals(0, p.evaluate(2200).nextReevaluateAtMillis);
        p.setVisible(true, 3000);
        assertEquals(5, p.evaluate(4000).maximumFps);
    }
    @Test public void fixedControlRemains15WithoutDecayTimersOrInteractionChanges() {
        AdaptiveMapFramePolicy p = new AdaptiveMapFramePolicy(AdaptiveMapFramePolicy.Mode.FIXED15, 30);
        p.setVisible(true, 0); p.setInteracting(true, 500); p.onLocation(true, 600);
        assertEquals(15, p.evaluate(700).maximumFps);
        assertEquals(0, p.evaluate(700).nextReevaluateAtMillis);
    }
    @Test public void explicitCeilingAndBuildModeAreStrict() {
        assertEquals(AdaptiveMapFramePolicy.Mode.FIXED15, AdaptiveMapFramePolicy.modeFromBuildValue("fixed15"));
        assertEquals(AdaptiveMapFramePolicy.Mode.ADAPTIVE30, AdaptiveMapFramePolicy.modeFromBuildValue("adaptive30"));
        try { AdaptiveMapFramePolicy.modeFromBuildValue("adaptive"); fail(); } catch (IllegalArgumentException expected) {}
        AdaptiveMapFramePolicy p = new AdaptiveMapFramePolicy(AdaptiveMapFramePolicy.Mode.ADAPTIVE30, 20);
        p.setVisible(true, 0); assertEquals(20, p.evaluate(0).maximumFps);
        try { p.evaluate(-1); fail(); } catch (IllegalArgumentException expected) {}
    }
}
