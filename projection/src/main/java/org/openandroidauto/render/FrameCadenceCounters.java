package org.openandroidauto.render;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Content-free aggregate producer counters. No per-frame logs, callbacks,
 * timers or device APIs. Handles bind producers to one epoch, including across
 * a peer codec restart. Snapshot fields are independent atomic observations. */
public final class FrameCadenceCounters {
    private static final AtomicLong EPOCHS = new AtomicLong();
    private static final Session DISABLED = new Session(0, 0, false);
    private static final AtomicReference<Session> CURRENT = new AtomicReference<>(DISABLED);
    private FrameCadenceCounters() {}

    public static Session beginSession() {
        Session session = new Session(EPOCHS.incrementAndGet(), System.nanoTime(), true);
        CURRENT.set(session);
        return session;
    }
    public static Session currentSession() { return CURRENT.get(); }
    /** Preview must explicitly keep this handle, even while a car is connected. */
    public static Session disabledSession() { return DISABLED; }
    public static void endSession(Session session) { if (session != null) session.closed.set(true); }

    public static void dump(PrintWriter writer) {
        writer.println("OPEN_AUTO_FRAME_CADENCE_V1");
        for (Map.Entry<String, Long> entry : CURRENT.get().snapshot().entrySet())
            writer.println(entry.getKey() + "=" + entry.getValue());
    }

    public static final class Session {
        private final long epoch;
        private final long startedNanos;
        private final boolean enabled;
        private final AtomicBoolean closed;
        private final AtomicLong mapFrames = new AtomicLong();
        private final AtomicLong mapFullyFrames = new AtomicLong();
        private final AtomicLong encoderStreams = new AtomicLong();
        private final AtomicLong codecConfigBuffers = new AtomicLong();
        private final AtomicLong encodedBuffers = new AtomicLong();
        private final AtomicLong distinctPts = new AtomicLong();
        private final AtomicLong encodedBytes = new AtomicLong();
        private final AtomicLong callbackUnits = new AtomicLong();
        private final AtomicLong callbackBytes = new AtomicLong();
        private final AtomicLong ptsRegressions = new AtomicLong();
        private final AtomicLong locationFixes = new AtomicLong();
        private final AtomicLong locationAgeTotalNanos = new AtomicLong();
        private final AtomicLong locationAgeMaxNanos = new AtomicLong();
        private Session(long epoch, long startedNanos, boolean enabled) {
            this.epoch = epoch; this.startedNanos = startedNanos; this.enabled = enabled;
            closed = new AtomicBoolean(!enabled);
        }
        private boolean active() { return enabled && !closed.get(); }
        public void mapFrameRendered(boolean fullyRendered) {
            if (!active()) return;
            mapFrames.incrementAndGet();
            if (fullyRendered) mapFullyFrames.incrementAndGet();
        }
        /** Age at navigator receipt only; excludes rendering/encoding/display delay.
         * No coordinates, per-fix timestamps or provider identity are retained. */
        public void locationAccepted(long ageNanos) {
            if (!active() || ageNanos < 0 || ageNanos > 120_000_000_000L) return;
            locationFixes.incrementAndGet();
            locationAgeTotalNanos.addAndGet(ageNanos);
            locationAgeMaxNanos.accumulateAndGet(ageNanos, Math::max);
        }
        /** Called after an actual MediaCodec.start succeeds. The stream-local
         * PTS history prevents a legitimate restart at PTS0 becoming migration. */
        public EncoderStream encoderStarted() {
            if (active()) encoderStreams.incrementAndGet();
            return new EncoderStream(this);
        }
        public Map<String, Long> snapshot() {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("frame_epoch", epoch);
            values.put("frame_session_started_monotonic_ns", startedNanos);
            values.put("frame_sample_monotonic_ns", System.nanoTime());
            values.put("frame_session_closed", closed.get() ? 1L : 0L);
            values.put("map_rendered_frames", mapFrames.get());
            values.put("map_fully_rendered_frames", mapFullyFrames.get());
            values.put("encoder_streams_started", encoderStreams.get());
            values.put("codec_config_output_buffers", codecConfigBuffers.get());
            values.put("encoded_output_buffers", encodedBuffers.get());
            values.put("encoded_distinct_pts", distinctPts.get());
            values.put("encoded_bytes", encodedBytes.get());
            values.put("frame_callbacks_completed", callbackUnits.get());
            values.put("frame_callback_bytes", callbackBytes.get());
            values.put("encoded_pts_regressions", ptsRegressions.get());
            values.put("navigation_location_fixes", locationFixes.get());
            values.put("navigation_location_age_total_ns", locationAgeTotalNanos.get());
            values.put("navigation_location_age_max_ns", locationAgeMaxNanos.get());
            return Collections.unmodifiableMap(values);
        }
    }

    public static final class EncoderStream {
        private final Session session;
        private final AtomicLong lastPtsUs = new AtomicLong(-1);
        private EncoderStream(Session session) { this.session = session; }
        public void configurationOutput() {
            if (session.active()) session.codecConfigBuffers.incrementAndGet();
        }
        /** Non-config output only. Distinct PTS counts can include encoder idle
         * repeats: they do not prove new UI content, wire delivery or display. */
        public void encodedOutput(long presentationTimeUs, int bytes) {
            if (!session.active() || presentationTimeUs < 0 || bytes <= 0) return;
            session.encodedBuffers.incrementAndGet();
            session.encodedBytes.addAndGet(bytes);
            long previous = lastPtsUs.getAndSet(presentationTimeUs);
            if (previous != presentationTimeUs) session.distinctPts.incrementAndGet();
            if (previous >= 0 && presentationTimeUs < previous) session.ptsRegressions.incrementAndGet();
        }
        /** Producer callback returned successfully; transport queue/receiver
         * acknowledgement and actual HU display remain different milestones. */
        public void frameCallbackCompleted(int bytes) {
            if (!session.active() || bytes <= 0) return;
            session.callbackUnits.incrementAndGet();
            session.callbackBytes.addAndGet(bytes);
        }
    }
}
