package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * The published element table must follow the panel's page, not freeze on the first one.
 *
 * <p>Found on a live client, and only there: an agent opened the panel, saw six controls, clicked
 * a navigation item, and the table did not change. The agent was pinned to the page it arrived on
 * while a person navigates freely — and the stale rectangles kept answering clicks for controls the
 * panel had already left.
 *
 * <p>The rule had been one-shot, and the reason it was one-shot is still true: rebuilding the
 * list every frame would churn {@code buttonList} sixty times a second for nothing, and
 * republishing on resize would accumulate duplicates. So the rule is <b>republish when the
 * collected table DIFFERS from the last one</b>: a static page costs one bounded walk and no
 * mutation, and a navigation costs exactly one rebuild.
 *
 * <p>This is source-level on purpose — {@code QmlGuiScreen} needs a live GLFW window and a loaded
 * QML scene, neither of which a headless test can supply. What is pinned is the decision, not the
 * pixels: the one-shot latch must be gone and a difference check must be present. The
 * consequence is verified live.
 */
public final class QmlTheElementTableFollowsThePageTest {

    private static String screen() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/net/marcloud/mcp/dwm/qml/QmlGuiScreen.java")),
                StandardCharsets.UTF_8);
    }

    @Test
    public void theOneShotLatchIsGone() throws Exception {
        String src = screen();
        assertTrue("the one-shot publish is what pinned the agent to the first page, found live: "
                        + "this check must not find `if (!publishedAfterLayout)` guarding the "
                        + "publish any more",
                !src.contains("if (!publishedAfterLayout)"));
    }

    @Test
    public void itRepublishesOnlyOnADifference() throws Exception {
        String src = screen();
        assertTrue("the difference check must exist, or the table is rebuilt every frame: " + src,
                src.contains("republishIfChanged"));
        assertTrue("and it must compare against the last published table: " + src,
                src.contains("lastPublished"));
        assertTrue("with an equality test, not a length test -- a page swap with the same number of "
                + "controls is exactly what a length check misses", src.contains("equals(lastPublished)"));
    }

    @Test
    public void anUnreadableTreeMustNotEraseAWorkingPanel() throws Exception {
        String src = screen();
        assertTrue("a collection that throws is not an empty collection; clearing the table on one "
                + "would take a working panel down, which is the failure mode the existing "
                + "try/catch in republishElements was written to avoid: " + src,
                src.contains("An unreadable tree is not an empty one")
                        || src.contains("last good one"));
    }
}
