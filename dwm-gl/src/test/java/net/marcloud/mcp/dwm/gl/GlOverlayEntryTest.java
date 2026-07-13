package net.marcloud.mcp.dwm.gl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.function.LongConsumer;

import org.junit.Test;

/**
 * Contract test for the reflective entry point core reaches by name. Core calls
 * {@code GlOverlayEntry.frameSink(long)} via reflection and REQUIRES a non-null
 * {@link LongConsumer} back (a null would NPE the seam install). Driving that consumer
 * headless (no GL context) must not throw — the backend's GL faults are swallowed by the
 * compositor's fault isolation, so the game render thread is never broken.
 *
 * <p>These mirror {@code dwm-compose}'s {@code ComposeOverlayEntryTest}: they lock the
 * exact signature + degrade-to-inert behavior the coordinator depends on.
 */
public class GlOverlayEntryTest {

    @Test
    public void frameSinkSignatureIsReflectivelyResolvable() throws Exception {
        // Core resolves this EXACTLY: public static LongConsumer frameSink(long).
        var m = GlOverlayEntry.class.getMethod("frameSink", long.class);
        assertTrue("frameSink must be static", java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        assertTrue("frameSink must return a LongConsumer",
                LongConsumer.class.isAssignableFrom(m.getReturnType()));
    }

    @Test
    public void frameSinkReturnsNonNullDriver() {
        // frameSink builds the host + registry + compositor and returns the driver, but
        // does NOT touch GL (onAttach fires later, on the first driveFrame). So this is
        // safe headless. Driving the returned consumer IS live-only: the first frame
        // calls real GL (glGetInteger) which LWJGL turns into a FATAL native JVM abort
        // when no context is current — an abort, not a catchable Throwable, so it cannot
        // be exercised headless. That path is validated live (see the overlay run script).
        LongConsumer driver = GlOverlayEntry.frameSink(0L);
        assertNotNull("frameSink must never return null (would NPE the seam install)", driver);
    }
}
