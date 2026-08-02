package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The guard must disable the alpha test for Skia, and must do it after the attribute push.
 *
 * <p>Headless companion to {@link GlStateGuardLiveIT}, which asserts the same rule against real
 * pixels but needs a display and therefore self-skips -- including on CI. This runs on every build,
 * because the defect it guards is both easy to reintroduce (deleting one line looks harmless) and
 * expensive to diagnose: MC draws its GUI with {@code GL_ALPHA_TEST} at {@code GL_GREATER} 0.1, so
 * a foreign renderer inherits a cutoff of 25/255 and every Skia fragment at or below that alpha is
 * discarded by the GPU while the state fields all report health.
 *
 * <p>That is not a corner case for this module. FIFTEEN of {@code Fluent.qml}'s colour tokens have
 * alpha <= 25 -- {@code divider} (21, used in seven files), {@code panelStroke} (18),
 * {@code cardFill} (13), {@code controlFill} (15) and {@code subtleHover} (15) among them -- so with
 * the alpha test armed the entire set of subtle Fluent layers is invisible while every metric
 * remains correct. (Counted again 2026-08-02: earlier revisions of this file and of the handoffs
 * said "ten", which matched neither the 15 token NAMES nor the 9 distinct VALUES below the line.)
 *
 * <p>Source-level assertions, because the ORDER is the part that is easy to get wrong and cannot be
 * observed from outside: the disable has to sit after {@code glPushAttrib} so that
 * {@code glPopAttrib} in {@code leave()} undoes it. Placed before the push, MC would get the
 * disabled state back and its own GUI would lose its cutout transparency.
 */
public class GlStateGuardDisablesAlphaTestTest {

    private static String guardSource() {
        try {
            return new String(Files.readAllBytes(Paths.get(
                "src/main/java/net/marcloud/mcp/dwm/qml/GlStateGuard.java")),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("cannot read GlStateGuard.java", e);
        }
    }

    /** enter()'s body, so an assertion cannot accidentally match one of this file's comments. */
    private static String enterBody() {
        String src = guardSource();
        int enterAt = src.indexOf("static void enter()");
        int leaveAt = src.indexOf("static void leave()");
        assertTrue("both methods must exist", enterAt > 0 && leaveAt > enterAt);
        return src.substring(enterAt, leaveAt);
    }

    @Test
    public void enterDisablesTheAlphaTestSoSkiaIsNotSubjectToMinecraftsCutoff() {
        String src = guardSource();
        assertTrue("GlStateGuard.enter() must disable GL_ALPHA_TEST. MC leaves it enabled at "
                + "GREATER 0.1 for its GUI, and Skia inherits it -- which discards every fragment "
                + "at alpha <= 25, i.e. FIFTEEN of Fluent's tokens including every divider, panel "
                + "stroke and the hover backplate. Skia expresses translucency through blending and "
                + "never through the fixed-function alpha test, so this removes a constraint it "
                + "cannot see.",
            Pattern.compile("glDisable\\s*\\(\\s*GL11\\.GL_ALPHA_TEST\\s*\\)").matcher(src).find());
    }

    @Test
    public void theDisableComesAfterThePushSoPopRestoresIt() {
        // Sliced to enter()'s body first, and matched on the CALL rather than the bare name: this
        // file mentions glPushAttrib in seven comments, the earliest of them 140 lines above the
        // call, so searching the whole source found a comment and compared against that. The first
        // version of this test passed with the disable moved before the push for exactly that
        // reason -- it was asserting nothing.
        String src = enterBody();
        int push = src.indexOf("GL11.glPushAttrib(");
        int disable = src.indexOf("GL11.glDisable(GL11.GL_ALPHA_TEST)");
        assertTrue("the glPushAttrib CALL must exist in enter()", push > 0);
        assertTrue("the alpha-test disable must exist in enter()", disable > 0);
        assertTrue("the disable must come AFTER glPushAttrib, so glPopAttrib in leave() undoes it. "
                + "Before the push, the disabled state would be what MC gets back, and MC's own GUI "
                + "depends on the alpha test for cutout transparency -- foliage and glass would stop "
                + "punching through.",
            disable > push);
    }

    /**
     * The disable must be in {@code enter()}, not in {@code leave()}.
     *
     * <p>A plausible-looking wrong fix is to disable it on the way out, which would leave Skia
     * subject to the cutoff for the whole frame and hand MC a state it did not ask for. Checked by
     * slicing the source at the two method boundaries rather than by trusting position alone.
     */
    @Test
    public void theDisableLivesInEnterNotLeave() {
        assertTrue("the alpha-test disable belongs in enter(), so it covers the span Skia draws in",
            enterBody().contains("glDisable(GL11.GL_ALPHA_TEST)"));
    }
}
