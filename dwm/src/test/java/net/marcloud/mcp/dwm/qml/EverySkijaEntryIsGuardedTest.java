package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Every place dwm lets Skija issue raw GL runs under {@link GlStateGuard}.
 *
 * <p>{@link GlStateGuard}'s own class doc enumerates what it repairs — ARRAY_BUFFER and
 * ELEMENT_ARRAY_BUFFER bindings, enabled vertex-attribute arrays, the FBO, the shader program — and
 * two of those crashed a live client in Apple's vertex submit path before they were restored. The
 * bracket covered the frame and nothing else, while {@code open()} (DirectContext.makeGL plus the
 * scene load, re-entered by MC's {@code initGui} on every resize) and {@code closeQuietly()}
 * (view.dispose plus the backend's context.close) issue the same GL from the same Skija. A guard
 * with holes in exactly the paths that run once, at the moments the client is most likely to be in
 * an unusual GL state, is the shape the two crashes had.
 *
 * <p>Source-level because the property is about CALL SITES, not about a value: nothing at runtime
 * says "that GL call happened outside the bracket" without reading driver state afterwards, and the
 * state in question (a live context, MC's {@code GlStateManager} shadow) needs the game running.
 */
public class EverySkijaEntryIsGuardedTest {

    private static final Path SURFACE =
            Paths.get("src/main/java/net/marcloud/mcp/dwm/qml/QmlUiSurface.java");

    /**
     * The three entry points, asserted one by one so a removal names the path that lost its bracket.
     */
    @Test
    public void openFrameAndCloseAllBracketTheGuard() throws Exception {
        String source = SourceScan.read(SURFACE);
        String[] entries = {"open", "frame", "closeQuietly"};
        for (String entry : entries) {
            String body = SourceScan.bodyOf(source, entry);
            assertTrue(entry + "() must enter GlStateGuard: it is one of the places dwm lets Skija "
                    + "issue raw GL, and the state Skija leaves behind is state GlStateManager still "
                    + "believes it owns. Body was: " + body, body.contains("GlStateGuard.enter()"));
            assertTrue(entry + "() must leave the guard -- unconditionally, i.e. from a finally, or "
                    + "a fault on that path leaves the game rendering through Skia's state. Body "
                    + "was: " + body, body.contains("GlStateGuard.leave()"));
        }
    }

    /**
     * The failure path inside the bracket must not re-enter it.
     */
    @Test
    public void openReleasesWithoutReenteringTheGuard() throws Exception {
        String body = SourceScan.bodyOf(SourceScan.read(SURFACE), "open");
        assertFalse("open()'s failure path must release through releaseNatives(), not through "
                + "closeQuietly(): GlStateGuard is not reentrant -- its capture lives in static "
                + "fields, so a nested pair would make the outer leave() restore the INNER capture, "
                + "i.e. the state Skija left behind instead of MC's. Body was: " + body,
                body.contains("closeQuietly()"));
        assertTrue("and releaseNatives() is what it must call instead: the release has to happen "
                + "while the guard is held, not after it. Body was: " + body,
                body.contains("releaseNatives()"));
    }
}
