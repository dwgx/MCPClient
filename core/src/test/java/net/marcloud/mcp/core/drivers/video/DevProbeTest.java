package net.marcloud.mcp.core.drivers.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.GameAccess;
import org.junit.Test;

/**
 * Headless tests for {@link DevProbe} — the aggregation core behind the dev_probe
 * tool. Headless there is no live Minecraft singleton and no GL context, so the
 * contract under test is: every section must degrade to a well-formed "absent"
 * shape rather than throwing, and the JSON skeleton must be stable. This is the
 * part of the live-gap tool that CAN be proven without a running game; the real-
 * game assertions (a true GL version, a populated world) are the needs-env
 * phase 2 (see .ai-notes task dev-probe-live-debug).
 */
public class DevProbeTest {

    @Test
    public void captureWithNullGameYieldsWellFormedAbsentShape() {
        // The extreme degrade case: no game façade at all must not throw and must
        // still produce the stable top-level skeleton.
        Map<String, Object> probe = DevProbe.capture(null);
        assertNotNull("probe map is never null", probe);
        assertTrue("has game section", probe.containsKey("game"));
        assertTrue("has gl section", probe.containsKey("gl"));

        @SuppressWarnings("unchecked")
        Map<String, Object> game = (Map<String, Object>) probe.get("game");
        assertEquals("game not up with null façade", false, game.get("up"));
        assertEquals("not connected", false, game.get("connected"));
        assertEquals("not in world", false, game.get("inWorld"));
    }

    @Test
    public void captureHeadlessGameReportsNotUpAndNoGlContext() {
        // A real GameAccess, but headless: Minecraft.getMinecraft() has no instance
        // and there is no current GL context. Every accessor is wrapped so the
        // probe degrades instead of throwing.
        GameAccess game = new GameAccess();
        Map<String, Object> probe = DevProbe.capture(game);

        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) probe.get("game");
        // up/connected/inWorld are all safe-false headless (no crash on the way).
        assertEquals(false, g.get("connected"));
        assertEquals(false, g.get("inWorld"));

        @SuppressWarnings("unchecked")
        Map<String, Object> gl = (Map<String, Object>) probe.get("gl");
        assertEquals("no GL context headless", false, gl.get("present"));
        assertNotNull("gl section always carries a note explaining absence", gl.get("note"));
        // When absent, the detail fields are intentionally omitted (only present+note).
        assertFalse("absent gl omits version", gl.containsKey("version"));
    }

    @Test
    public void glContextProbeAbsentHeadlessNeverThrows() {
        // Direct unit on the GL probe: off the render thread / headless there is no
        // current context, so capture() must return absent(...) rather than throw.
        GlContextProbe gl = GlContextProbe.capture();
        assertFalse("headless has no GL context", gl.present());
        assertNotNull("absent snapshot still explains why", gl.note());
    }

    @Test
    public void absentFactoryProducesConsistentSnapshot() {
        GlContextProbe a = GlContextProbe.absent("test reason");
        assertFalse(a.present());
        assertEquals("test reason", a.note());
        assertFalse("absent implies not core profile", a.coreProfile());
        assertFalse("absent implies not compat profile", a.compatibilityProfile());
    }
}
