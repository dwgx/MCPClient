package net.marcloud.mcp.dwm.gl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.util.function.LongConsumer;

import org.junit.Test;

/**
 * Contract test for the MD3-UI reflective entry point core reaches by name.
 * {@code GlUiEntry.frameSink(long)} drives the DWM MD3 component tree through
 * {@code UiComposer} + {@code GlRenderBackend}; core requires a non-null
 * {@link LongConsumer} back (a null would NPE the seam install). Driving it is
 * LIVE-ONLY (real GL context), so this locks the signature + non-null + arm-time
 * degrade contract only.
 */
public class GlUiEntryTest {

    @Test
    public void frameSinkSignatureIsReflectivelyResolvable() throws Exception {
        var m = GlUiEntry.class.getMethod("frameSink", long.class);
        assertTrue("frameSink must be static", Modifier.isStatic(m.getModifiers()));
        assertTrue("frameSink must return a LongConsumer",
                LongConsumer.class.isAssignableFrom(m.getReturnType()));
    }

    @Test
    public void frameSinkReturnsNonNullDriver() {
        // Arms UiComposer + registry + GL backend + MD3 root; touches no GL (attach fires
        // on the first driveFrame). Must never return null.
        LongConsumer driver = GlUiEntry.frameSink(0L);
        assertNotNull("frameSink must never return null (would NPE the seam install)", driver);
    }
}
