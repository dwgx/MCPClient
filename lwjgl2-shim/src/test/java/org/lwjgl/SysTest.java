package org.lwjgl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless regression tests for the {@link Sys} shim.
 *
 * <p>Sys is the LWJGL2 hi-res clock MC uses for {@code getSystemTime()}
 * ({@code Sys.getTime() * 1000 / Sys.getTimerResolution()}), plus the
 * openURL browser hook. All of it is pure logic with no GL/window
 * dependency, so it is fully testable headless.</p>
 */
public class SysTest {

    /** Resolution is the fixed nanosecond tick count MC divides by; must never drift. */
    @Test
    public void timerResolutionIsNanoseconds() {
        assertEquals(1000000000L, Sys.getTimerResolution());
    }

    /** getTime must be non-negative from class load (LWJGL2 zeroed the hi-res timer). */
    @Test
    public void getTimeIsNonNegative() {
        assertTrue("getTime must be >= 0", Sys.getTime() >= 0L);
    }

    /** getTime must be monotonic non-decreasing: a later read is never earlier. */
    @Test
    public void getTimeIsMonotonic() {
        long previous = Sys.getTime();
        for (int i = 0; i < 100000; i++) {
            long now = Sys.getTime();
            assertTrue("getTime went backwards: " + now + " < " + previous, now >= previous);
            previous = now;
        }
    }

    /**
     * getSystemTime() semantics: MC computes millis as
     * {@code getTime() * 1000 / getTimerResolution()}. With a nanosecond
     * resolution that is just nanos/1_000_000, i.e. a plausible elapsed-ms value.
     */
    @Test
    public void getTimeConvertsToSaneMillis() {
        long millis = Sys.getTime() * 1000L / Sys.getTimerResolution();
        assertTrue("derived millis must be >= 0", millis >= 0L);
        // Class was loaded moments ago; elapsed millis since load must be small.
        assertTrue("derived millis unexpectedly large: " + millis, millis < 60L * 60L * 1000L);
    }

    /** initialize() is a documented no-op that only forces static timer setup. */
    @Test
    public void initializeDoesNotThrow() {
        Sys.initialize();
        assertTrue(Sys.getTime() >= 0L);
    }

    /** openURL(null) must be a safe false, never an NPE (MC passes user-derived strings). */
    @Test
    public void openUrlNullReturnsFalse() {
        assertFalse(Sys.openURL(null));
    }

    /** Version string defers to the real LWJGL3 Version; must be present and non-blank. */
    @Test
    public void getVersionIsNonEmpty() {
        String v = Sys.getVersion();
        assertTrue("version must be non-empty", v != null && !v.trim().isEmpty());
    }
}
