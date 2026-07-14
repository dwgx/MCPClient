package net.marcloud.mcp.dwm.gl;

/**
 * Wall-clock frame-delta computer for the MD3 overlay entries. Each {@code *UiEntry}
 * previously handed {@link net.marcloud.mcp.dwm.compositor.UiComposer#driveFrame} a
 * hardcoded {@code 1f/60f}, so every animation (ripple grow, state-layer fade) ran at a
 * fixed 60 Hz cadence regardless of the real render rate — visibly too slow at 144 Hz,
 * too fast on a stutter. This measures the true elapsed seconds between frames from
 * {@link System#nanoTime()} deltas so timelines advance in real time.
 *
 * <p><b>Clamp.</b> The delta is clamped to {@code [MIN_DT, MAX_DT]}. A long stall (GC,
 * alt-tab, breakpoint, first frame after a load screen) would otherwise produce a huge dt
 * that jumps a ripple to its end in one frame; capping at {@value #MAX_DT}s bounds the
 * per-frame advance. A negative raw delta (a non-monotonic clock reading, or wrap) is
 * floored to 0. The very first sample has no prior timestamp, so it returns a nominal
 * {@code 1/60} rather than 0 (keeps a just-armed animation moving on frame one).
 *
 * <p>Pure arithmetic — no GL, no game types — so it is fully unit-testable headless by
 * feeding synthetic nanoTime values to {@link #tickSeconds(long)}.
 */
public final class FrameClock {

    /** Floor for the per-frame delta (seconds). A backwards clock reading floors here. */
    public static final float MIN_DT = 0f;

    /** Ceiling for the per-frame delta (seconds): bounds the jump after a long stall. */
    public static final float MAX_DT = 0.1f;

    /** Nominal delta returned for the first (unprimed) frame — one 60 Hz tick. */
    public static final float FIRST_DT = 1f / 60f;

    private long lastNanos;
    private boolean primed;

    /**
     * Compute the clamped elapsed seconds since the previous sample.
     *
     * @param nowNanos the current {@link System#nanoTime()} reading (or a test value)
     * @return elapsed seconds in {@code [MIN_DT, MAX_DT]}; {@link #FIRST_DT} on the first call
     */
    public float tickSeconds(long nowNanos) {
        if (!primed) {
            primed = true;
            lastNanos = nowNanos;
            return FIRST_DT;
        }
        long deltaNanos = nowNanos - lastNanos;
        lastNanos = nowNanos;
        if (deltaNanos < 0L) {
            deltaNanos = 0L; // non-monotonic reading / wrap: treat as no time passed
        }
        float dt = deltaNanos / 1_000_000_000f;
        if (dt < MIN_DT) {
            dt = MIN_DT;
        } else if (dt > MAX_DT) {
            dt = MAX_DT;
        }
        return dt;
    }

    /** Live convenience: sample the real clock. Live-only; tests use {@link #tickSeconds}. */
    public float tick() {
        return tickSeconds(System.nanoTime());
    }
}
