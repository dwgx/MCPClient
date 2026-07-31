package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.layout.Column;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Holds down that a menu panel sizes itself from its children, within the first frame.
 *
 * <p>MenuPanel used to make every caller declare {@code itemCount} / {@code separatorCount} by
 * hand, on the belief that a binding to the Column's height evaluates before layout has run and
 * collapses the panel to its title block. Measured, that is not what happens: the renderer settles
 * layout up to its pass cap per frame and flushes the dirty queue between passes, and
 * {@code Column.layout()} publishes its height into that loop, so the binding resolves in the same
 * frame. Removing the declared counts removed a per-panel tax and, more importantly, a trap where
 * adding a row without bumping the number silently produced a short panel.
 *
 * <p>Short is not merely cosmetic, which is why the hit box is asserted too: hit testing rejects a
 * point that falls outside any ancestor, so a panel one row too short renders that row perfectly
 * and swallows every click on it — the same class of silent failure {@link CompositorLiveIT} exists
 * for.
 */
public class PanelSizingLiveIT {

    private static final String SCENE = "dwm/Main.qml";

    /** 6 rows + 2 separators + title block + top and bottom padding. */
    private static final float EXPECTED_HEIGHT = 6 * 40 + 2 * 9 + 44 + 2 * 4;

    @Test
    public void panelHeightFollowsItsChildrenOnTheFirstFrame() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open; " + surface.lastError(),
                surface.open(Display.getWidth(), Display.getHeight()));
            surface.setFramebufferId(0);
            // Exactly one frame: the claim under test is about the FIRST frame, so rendering more
            // would hide a panel that needed a second one to settle.
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

            Item panel = panelOf(surface);
            Column body = columnIn(panel);
            assertNotNull("the panel must contain the item Column", body);

            assertEquals("the Column must have stacked its children", 8, body.children.size());
            assertTrue("the Column must publish a non-zero height, which is what the panel reads",
                body.height.peekFloat() > 0.0F);
            assertEquals("the panel must size itself from its children within one frame; a "
                + "collapsed panel here means the binding did not settle",
                EXPECTED_HEIGHT, panel.height.peekFloat(), 0.5F);

            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    /**
     * The last row must fall inside the panel's box.
     *
     * <p>The consequence assertion: a panel that is short by even one row leaves that row
     * rendered but unclickable, and no screenshot shows it.
     */
    @Test
    public void theLastRowFallsInsideThePanelSoItStaysClickable() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        try {
            QmlUiSurface surface = new QmlUiSurface(SCENE);
            assertTrue("scene must open; " + surface.lastError(),
                surface.open(Display.getWidth(), Display.getHeight()));
            surface.setFramebufferId(0);
            surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

            Item panel = panelOf(surface);
            Column body = columnIn(panel);

            Item last = body.children.get(body.children.size() - 1);
            // Row centre in the panel's own coordinates: the Column's offset plus the row's.
            float centreY = body.y.peekFloat() + last.y.peekFloat()
                + last.height.peekFloat() / 2.0F;

            assertTrue("the last row's centre (" + centreY + ") must fall within the panel's "
                + "height (" + panel.height.peekFloat() + ") or every click on it is rejected "
                + "at the panel before reaching the row",
                centreY < panel.height.peekFloat());
            surface.close();
        } finally {
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    private static Item panelOf(QmlUiSurface surface) throws Exception {
        QmlView view = surface.view();
        Item panel = view.findByObjectName("menuPanel");
        assertNotNull("Main.qml must name its panel so this test can find it", panel);
        return panel;
    }

    private static Column columnIn(Item node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Column) {
            return (Column) node;
        }
        for (Item child : node.children) {
            Column hit = columnIn(child);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(854, 480));
            Display.create();
            Display.update();
            return true;
        } catch (Throwable t) {
            System.out.println("[IT] no display (" + t + ") — skipping");
            return false;
        }
    }

    private static void destroyDisplay() {
        try {
            Display.destroy();
        } catch (Throwable ignored) {
            // Teardown of an already-dead display is not actionable.
        }
    }
}
