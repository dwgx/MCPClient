package net.marcloud.mcp.dwm.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Non-vacuous tests for {@link FrameClock} — the wall-clock frame-delta computer that
 * replaced the hardcoded {@code 1f/60f} in the three MD3 UiEntry frame sinks. Feeds
 * synthetic {@link System#nanoTime()} values (pure arithmetic, no GL / no game) and
 * asserts the clamp + first-frame + monotonicity behavior. Each of these would FAIL
 * against the old constant-delta code (there was no delta computer at all).
 */
public class FrameClockTest {

    private static final long MS = 1_000_000L; // nanos per millisecond

    @Test
    public void firstFrameReturnsNominalNotZero() {
        FrameClock clock = new FrameClock();
        // No prior timestamp exists, so the first sample must not report 0 seconds (which
        // would freeze a just-armed animation) — it returns the nominal 1/60 tick.
        assertEquals(FrameClock.FIRST_DT, clock.tickSeconds(123_456_789L), 1e-7f);
    }

    @Test
    public void steadySixtyHzYieldsAboutSixteenMillis() {
        FrameClock clock = new FrameClock();
        long t = 0L;
        clock.tickSeconds(t);            // prime
        t += 16 * MS + 666_667L;         // ~16.667 ms == 1/60 s
        float dt = clock.tickSeconds(t);
        assertEquals(1f / 60f, dt, 1e-4f);
    }

    @Test
    public void oneHundredFortyFourHzIsSmallerDeltaThanSixty() {
        // The whole point of the real delta: at 144 Hz the per-frame advance is ~6.94 ms,
        // markedly less than the 16.67 ms the old hardcoded 1/60 always fed. Proves the
        // clock actually tracks the rate rather than a constant.
        FrameClock clock = new FrameClock();
        long t = 0L;
        clock.tickSeconds(t);
        t += 6_944_444L; // 1/144 s
        float dt = clock.tickSeconds(t);
        assertEquals(1f / 144f, dt, 1e-4f);
        assertTrue("144Hz delta must be below the old 1/60 constant", dt < 1f / 60f);
    }

    @Test
    public void longStallIsClampedToMax() {
        FrameClock clock = new FrameClock();
        clock.tickSeconds(0L);
        // A 2-second stall (GC / alt-tab / breakpoint) must NOT advance animations by 2s —
        // it is clamped to MAX_DT so a ripple cannot jump straight to its end.
        float dt = clock.tickSeconds(2_000L * MS);
        assertEquals(FrameClock.MAX_DT, dt, 1e-7f);
    }

    @Test
    public void nonMonotonicClockFlooredToZero() {
        FrameClock clock = new FrameClock();
        clock.tickSeconds(1_000L * MS);
        // A backwards reading (non-monotonic clock / wrap) must floor to 0, never a
        // negative dt that would run animations in reverse.
        float dt = clock.tickSeconds(500L * MS);
        assertEquals(0f, dt, 1e-7f);
        assertTrue(dt >= FrameClock.MIN_DT);
    }

    @Test
    public void deltaIsRelativeToPreviousSampleNotStart() {
        // Consecutive 10 ms frames each report ~10 ms — the clock advances its baseline
        // every tick (a cumulative-from-start bug would report 10, 20, 30...).
        FrameClock clock = new FrameClock();
        clock.tickSeconds(0L);
        float d1 = clock.tickSeconds(10 * MS);
        float d2 = clock.tickSeconds(20 * MS);
        float d3 = clock.tickSeconds(30 * MS);
        assertEquals(0.010f, d1, 1e-5f);
        assertEquals(0.010f, d2, 1e-5f);
        assertEquals(0.010f, d3, 1e-5f);
    }

    @Test
    public void deltaAlwaysWithinClampBounds() {
        FrameClock clock = new FrameClock();
        clock.tickSeconds(0L);
        long[] steps = {5 * MS, 500 * MS, -100 * MS, 33 * MS, 0L, 999 * MS};
        long now = 0L;
        for (long step : steps) {
            now += step;
            float dt = clock.tickSeconds(now);
            assertTrue("dt below MIN_DT: " + dt, dt >= FrameClock.MIN_DT);
            assertTrue("dt above MAX_DT: " + dt, dt <= FrameClock.MAX_DT);
        }
    }
}
