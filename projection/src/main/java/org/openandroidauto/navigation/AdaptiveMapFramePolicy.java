package org.openandroidauto.navigation;

/** Event-driven render ceiling. This class requests no frames, animations,
 * threads or timers. The UI may schedule one decay at Decision.nextReevaluateAtMillis. */
public final class AdaptiveMapFramePolicy {
    public enum Mode { FIXED15, ADAPTIVE30 }
    public static final long INTERACTION_HOLD_MS = 1000;
    public static final long MOVING_FIX_LEASE_MS = 2500;
    private final Mode mode;
    private final int ceiling;
    private boolean visible;
    private boolean interacting;
    private boolean cameraMoving;
    private long activityUntil;
    private long movingUntil;

    public AdaptiveMapFramePolicy(Mode mode, int maximumFps) {
        if (mode == null) throw new IllegalArgumentException("Frame mode required");
        this.mode = mode;
        ceiling = Math.max(5, Math.min(30, maximumFps));
    }

    public static Mode modeFromBuildValue(String value) {
        if ("fixed15".equals(value)) return Mode.FIXED15;
        if ("adaptive30".equals(value)) return Mode.ADAPTIVE30;
        throw new IllegalArgumentException("Unsupported map frame mode");
    }

    public synchronized void setVisible(boolean value, long nowMillis) {
        checkTime(nowMillis);
        if (visible == value) return;
        visible = value;
        if (value) noteActivity(nowMillis);
        else {
            interacting = cameraMoving = false;
            activityUntil = movingUntil = 0;
        }
    }

    public synchronized void setInteracting(boolean value, long nowMillis) {
        checkTime(nowMillis);
        if (!visible || interacting == value) return;
        interacting = value;
        if (!value) noteActivity(nowMillis);
    }

    public synchronized void setCameraMoving(boolean value, long nowMillis) {
        checkTime(nowMillis);
        if (!visible || cameraMoving == value) return;
        cameraMoving = value;
        if (!value) noteActivity(nowMillis);
    }

    public synchronized void noteActivity(long nowMillis) {
        checkTime(nowMillis);
        if (visible) activityUntil = Math.max(activityUntil, add(nowMillis, INTERACTION_HOLD_MS));
    }

    public synchronized void onLocation(boolean moving, long nowMillis) {
        checkTime(nowMillis);
        movingUntil = visible && moving ? add(nowMillis, MOVING_FIX_LEASE_MS) : 0;
    }

    public synchronized Decision evaluate(long nowMillis) {
        checkTime(nowMillis);
        if (mode == Mode.FIXED15) return new Decision(Math.min(15, ceiling), 0);
        if (!visible) return new Decision(5, 0);
        if (interacting || cameraMoving) return new Decision(ceiling, 0);
        long deadline = Math.max(activityUntil, movingUntil);
        return deadline > nowMillis ? new Decision(ceiling, deadline) : new Decision(5, 0);
    }

    public static final class Decision {
        public final int maximumFps;
        /** Absolute monotonic milliseconds, or0 if no future timer is needed. */
        public final long nextReevaluateAtMillis;
        private Decision(int fps, long next) { maximumFps = fps; nextReevaluateAtMillis = next; }
    }

    private static void checkTime(long now) {
        if (now < 0) throw new IllegalArgumentException("Monotonic time must be nonnegative");
    }
    private static long add(long now, long delta) {
        return now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
    }
}
