package org.openandroidauto.render;

import org.junit.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import static org.junit.Assert.*;

public final class FrameCadenceCountersTest {
    private long value(FrameCadenceCounters.Session session, String key) { return session.snapshot().get(key); }
    @Test public void fixAgesAreBoundedContentFreeAndSessionScoped() {
        FrameCadenceCounters.Session s = FrameCadenceCounters.beginSession();
        s.locationAccepted(2_000_000); s.locationAccepted(8_000_000);
        s.locationAccepted(-1); s.locationAccepted(120_000_000_001L);
        assertEquals(2, value(s, "navigation_location_fixes"));
        assertEquals(10_000_000, value(s, "navigation_location_age_total_ns"));
        assertEquals(8_000_000, value(s, "navigation_location_age_max_ns"));
        FrameCadenceCounters.Session next = FrameCadenceCounters.beginSession();
        assertEquals(0, value(next, "navigation_location_fixes"));
        FrameCadenceCounters.endSession(s); s.locationAccepted(4_000_000);
        assertEquals(2, value(s, "navigation_location_fixes"));
        FrameCadenceCounters.disabledSession().locationAccepted(100);
        FrameCadenceCounters.endSession(next);
    }
    @Test public void configDuplicatePtsAndCallbackAreSeparateMilestones() {
        FrameCadenceCounters.Session s = FrameCadenceCounters.beginSession();
        FrameCadenceCounters.EncoderStream stream = s.encoderStarted();
        stream.configurationOutput(); stream.encodedOutput(0, 100); stream.encodedOutput(0, 20);
        stream.encodedOutput(33333, 50); stream.frameCallbackCompleted(110);
        assertEquals(1, value(s, "codec_config_output_buffers"));
        assertEquals(3, value(s, "encoded_output_buffers"));
        assertEquals(2, value(s, "encoded_distinct_pts"));
        assertEquals(170, value(s, "encoded_bytes"));
        assertEquals(1, value(s, "frame_callbacks_completed"));
        FrameCadenceCounters.endSession(s);
    }
    @Test public void streamRestartDoesNotMistakeNewPtsZeroForRegression() {
        FrameCadenceCounters.Session s = FrameCadenceCounters.beginSession();
        FrameCadenceCounters.EncoderStream first = s.encoderStarted(); first.encodedOutput(100000, 1);
        s.encoderStarted().encodedOutput(0, 1);
        assertEquals(2, value(s, "encoder_streams_started"));
        assertEquals(0, value(s, "encoded_pts_regressions"));
        first.encodedOutput(90000, 1);
        assertEquals(1, value(s, "encoded_pts_regressions"));
        FrameCadenceCounters.endSession(s);
    }
    @Test public void oldHandleAndPreviewNeverIncrementNewEpoch() {
        FrameCadenceCounters.Session old = FrameCadenceCounters.beginSession();
        FrameCadenceCounters.Session current = FrameCadenceCounters.beginSession();
        old.mapFrameRendered(true); FrameCadenceCounters.disabledSession().mapFrameRendered(true);
        assertEquals(0, value(current, "map_rendered_frames"));
        current.mapFrameRendered(true);
        assertEquals(1, value(current, "map_fully_rendered_frames"));
        FrameCadenceCounters.endSession(old); FrameCadenceCounters.endSession(current);
    }
    @Test public void closureDisablesLaterProducerAndDumpContainsOnlyFixedNumbers() {
        FrameCadenceCounters.Session s = FrameCadenceCounters.beginSession();
        FrameCadenceCounters.EncoderStream stream = s.encoderStarted(); FrameCadenceCounters.endSession(s);
        stream.encodedOutput(0, 50); stream.frameCallbackCompleted(50); s.mapFrameRendered(true);
        assertEquals(0, value(s, "encoded_output_buffers"));
        StringWriter text = new StringWriter(); FrameCadenceCounters.dump(new PrintWriter(text));
        assertTrue(text.toString().startsWith("OPEN_AUTO_FRAME_CADENCE_V1\n"));
        assertTrue(text.toString().contains("frame_session_closed=1"));
        for (String line : text.toString().split("\n"))
            assertTrue(line.equals("OPEN_AUTO_FRAME_CADENCE_V1") || line.matches("[a-z_]+=[0-9]+"));
    }
    @Test public void parallelRenderCallbacksAccumulateWithoutFrameLogging() throws Exception {
        FrameCadenceCounters.Session s = FrameCadenceCounters.beginSession();
        CountDownLatch start = new CountDownLatch(1); Thread[] threads = new Thread[4];
        for (int i=0;i<threads.length;i++) {
            threads[i] = new Thread(() -> { try { start.await(); } catch (InterruptedException e) { return; }
                for(int j=0;j<1000;j++)s.mapFrameRendered(true); }); threads[i].start();
        }
        start.countDown(); for(Thread thread:threads)thread.join();
        assertEquals(4000, value(s, "map_rendered_frames"));
        assertEquals(4000, value(s, "map_fully_rendered_frames"));
        FrameCadenceCounters.endSession(s);
    }
}
