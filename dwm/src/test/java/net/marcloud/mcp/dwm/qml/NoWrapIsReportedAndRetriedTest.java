package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * A frame that composites nothing must say so, and a failed wrap must not be a one-shot decision.
 *
 * <p>Two defects, one shape. {@code QmlUiSurface.frame} gated the composite on
 * {@code backend.hasSurface()} and simply fell through when the answer was no, while
 * {@code McpFboSurfaceBackend} recorded a failed wrap as "attempted" and never tried again at the
 * same parameters. Together: one driver hiccup — an incomplete target, a framebuffer being recreated
 * under us — left the overlay permanently invisible at that size, and the surface reported itself
 * healthy the whole time ({@code isOpen() == true}, {@code lastError() == null}), because a skip is
 * not an exception. The README claimed "the composite is never skipped"; it can be, so now it is
 * never skipped in SILENCE and the skip is retried.
 *
 * <p><b>Why this is two kinds of assertion.</b> The retry is behavioural — the cooldown is driven
 * through the real {@code frameTarget}, because a countdown that is merely present and never
 * consulted would pass a text check. Reachability of the code paths that need GL cannot be
 * behavioural here (a unit test has no context, and the wrap itself is the thing that fails on its
 * own), so the reporting half is a source guard: the failing branch must consult the backend's
 * reason, must reach {@code lastError()}, and must NOT reach for {@code inert}. Both kinds fail on
 * the pre-fix tree.
 *
 * <p><b>The missing {@code inert} flag is the subtle half of the fix, so it is asserted
 * explicitly.</b> {@code inert} is the permanent-fault flag: {@code frame()} returns immediately
 * once it is set. Setting it for a missing wrap would stop the very retry the backend arms, i.e. a
 * transient failure would become permanent — precisely the defect, re-created by the tidy-up that
 * "makes the failure path look like the other failure paths". The honest answer for a frame that
 * cannot draw is {@code lastError()} plus {@code isOpen() == false}, not death.
 */
public class NoWrapIsReportedAndRetriedTest {

    private static final Path SURFACE =
            Paths.get("src/main/java/net/marcloud/mcp/dwm/qml/QmlUiSurface.java");
    private static final Path BACKEND =
            Paths.get("src/main/java/net/marcloud/mcp/dwm/qml/McpFboSurfaceBackend.java");

    /**
     * The frame that draws nothing has to carry the reason, and the reason has to be reachable.
     */
    @Test
    public void aFrameWithNoWrapReportsWhyItDrewNothing() throws Exception {
        String frame = SourceScan.bodyOf(SourceScan.read(SURFACE), "frame");
        assertTrue("frame() must do something other than skip when the backend has no wrap: it used "
                + "to gate the composite on hasSurface() and fall through, so the UI stayed "
                + "invisible while lastError() said null -- a skip is not an exception, and nothing "
                + "else would ever report it. Body was: " + frame, frame.contains("reportNoWrap("));

        String report = SourceScan.bodyOf(SourceScan.read(SURFACE), "reportNoWrap");
        assertTrue("the report must carry the backend's OWN reason, not a restatement of 'no "
                + "surface' -- the backend is what knows whether the wrap returned null or threw. "
                + "Body was: " + report, report.contains("backend.wrapFailure()"));
        assertTrue("and it must reach lastError(), which is what the probe, the host and "
                + "QmlGuiScreen.lastError() read. Body was: " + report, report.contains("lastError ="));
    }

    /**
     * "Open" must not be claimed by a surface that cannot put a pixel on the screen.
     */
    @Test
    public void isOpenDoesNotClaimHealthWithoutAWrap() throws Exception {
        String isOpen = SourceScan.bodyOf(SourceScan.read(SURFACE), "isOpen");
        assertTrue("isOpen() must follow the wrap: a surface with none renders nothing, and "
                + "answering true there was exactly the lie the README's 'never skipped' claim "
                + "rested on. Body was: " + isOpen, isOpen.contains("hasSurface()"));
    }

    /**
     * And the no-wrap path must stay recoverable: it may not reach for {@code inert}.
     *
     * <p>Asserted rather than merely commented, because "make the missing-wrap path look like the
     * other fault paths" is exactly the tidy-up that would reintroduce the defect: {@code frame()}
     * returns immediately once {@code inert} is set, so the retry the backend arms would never run
     * again and a transient wrap failure would become permanent.
     */
    @Test
    public void aMissingWrapDoesNotMakeTheSurfacePermanent() throws Exception {
        String body = SourceScan.bodyOf(SourceScan.read(SURFACE), "reportNoWrap");
        assertFalse("reportNoWrap() must not touch the inert flag: that flag stops frame() for good, "
                + "so the bounded retry would be unreachable and a transient failure would become "
                + "permanent -- which is the defect, not the fix. The honest answer for a frame that "
                + "cannot draw is lastError() plus isOpen() == false. Body was: " + body,
                body.contains("inert"));
    }

    /**
     * A failed wrap is retried on a cooldown: not never, and not every frame.
     *
     * <p>Driven through the real {@code frameTarget} with the backend's fields arranged so it takes
     * the unchanged-parameters path and never reaches the GL calls in {@code rebuild()} — a unit
     * test has no GL context, and the point under test is the policy, not the driver.
     *
     * <p>Reflective for the field rather than calling a new accessor: a test written against a
     * new method would not COMPILE on the tree it has to fail on, and a red test that is really a
     * build error tells the next reader nothing.
     */
    @Test
    public void aFailedWrapIsRetriedOnACooldown() throws Exception {
        McpFboSurfaceBackend backend = new McpFboSurfaceBackend();
        try {
            Field cooldown = declaredField(backend.getClass(), "framesUntilRetry");
            assertNotNull("a wrap that fails at the current size/FBO id must be retried after a "
                    + "cooldown. Without it the only retry trigger is a parameter change, which is "
                    + "a one-shot decision the target never gets to reconsider: the UI stays "
                    + "invisible at that size for the life of the process.",
                    cooldown);
            cooldown.setAccessible(true);
            assertNull("the one-shot 'attempted' flag must be gone: it is what made a failed wrap "
                    + "permanent, because the parameters it keyed on never move again.",
                    declaredField(backend.getClass(), "attempted"));

            // Arrange the unchanged-parameters path: same extent as frameTarget will be asked for,
            // no live wrap, nothing disposed. rebuild() is then unreachable this call.
            set(backend, "width", Integer.valueOf(800));
            set(backend, "height", Integer.valueOf(600));
            cooldown.setInt(backend, 3);

            for (int i = 0; i < 3; i++) {
                backend.frameTarget(800, 600, -1);
            }
            assertEquals("the cooldown must be consumed one frame at a time: retrying every frame "
                    + "would thrash a whole Skia context create/destroy while the target stays "
                    + "incomplete, which is the cost the old guard existed to prevent.",
                    0, cooldown.getInt(backend));

            // And once it expires the attempt is actually made. Asserted on the source because
            // reaching it means calling rebuild(), i.e. GL, which this test cannot do.
            String target = SourceScan.bodyOf(SourceScan.read(BACKEND), "frameTarget");
            assertTrue("the cooldown must fall through to rebuild() when it expires -- a countdown "
                    + "that only ever counts is a slower way of never trying again. Body was: "
                    + target, target.contains("framesUntilRetry--") && target.contains("rebuild();"));
        } finally {
            backend.dispose();
        }
    }

    /**
     * The failure reason must be a reason, not a phenomenon, and it must be readable.
     */
    @Test
    public void theBackendReportsWhyTheWrapGaveUp() throws Exception {
        String rebuild = SourceScan.bodyOf(SourceScan.read(BACKEND), "rebuild");
        assertTrue("rebuild() must record why it produced no surface, and record it in a field: "
                + "both outcomes -- the wrap returning null and the wrap throwing -- have to land "
                + "on one, or a throwing attempt would leave a stale reason behind. Body was: "
                + rebuild, rebuild.contains("wrapFailure = why"));

        String accessor = SourceScan.bodyOf(SourceScan.read(BACKEND), "wrapFailure");
        assertTrue("and the reason must be readable by the driver, not only logged: the surface "
                + "reports it to lastError() so a frame that drew nothing says why. Body was: "
                + accessor, accessor.contains("return wrapFailure;"));
    }

    // ---- helpers ---------------------------------------------------------------

    private static Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = declaredField(target.getClass(), field);
        assertNotNull(target.getClass().getSimpleName() + " must declare " + field, f);
        f.setAccessible(true);
        f.set(target, value);
    }
}
