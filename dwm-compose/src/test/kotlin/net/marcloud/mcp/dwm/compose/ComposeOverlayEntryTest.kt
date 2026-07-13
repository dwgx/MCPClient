package net.marcloud.mcp.dwm.compose

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.function.LongConsumer

/**
 * Verifies the reflective wiring CONTRACT between core's RenderOverlayCoordinator and
 * this module — the part provable headlessly (no live game / GL). core reaches
 * [ComposeOverlayEntry.frameSink] by reflection and expects a JDK [LongConsumer] back;
 * this asserts the exact shape core reflects against (static method, name, param type,
 * return type). If someone renames/reshapes it, core's reflection would silently
 * degrade to "no overlay" — this test catches that at build time.
 *
 * <p>Does NOT drive a frame (that needs a live GL context — the live gate). It only
 * proves the entry point exists with the signature core binds to, and returns a
 * non-null, callable consumer.
 */
class ComposeOverlayEntryTest {

    @Test
    fun entryPointMatchesCoreReflectionContract() {
        // Mirror EXACTLY what core's RenderOverlayCoordinator does:
        //   Class.forName("net.marcloud.mcp.dwm.compose.ComposeOverlayEntry")
        //        .getMethod("frameSink", long.class).invoke(null, windowHandle)
        val entry = Class.forName("net.marcloud.mcp.dwm.compose.ComposeOverlayEntry")
        val method = entry.getMethod("frameSink", java.lang.Long.TYPE)
        val result = method.invoke(null, 0L)
        assertNotNull("frameSink must return a driver", result)
        assertNotNull("must be a JDK LongConsumer (the core-owned neutral type)",
            result as? LongConsumer)
    }
}
