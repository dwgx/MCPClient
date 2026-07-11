package net.marcloud.mcp.board.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.board.signals.RenderSignal;
import org.junit.Test;

/**
 * Regression tests for {@link Panel}: anchor-relative layout arithmetic and the
 * headless render bridge. These fail on absent/old code — there is no Panel type
 * without the HUD subsystem, and the anchor math / fault isolation are exact.
 */
public class PanelTest {

    /** A minimal panel with a fixed declared size and a render counter. */
    private static final class CounterPanel extends Panel {
        final AtomicInteger renders = new AtomicInteger();

        CounterPanel(int w, int h) {
            setSize(w, h);
        }

        @Override
        protected void onRender(RenderSignal signal) {
            renders.incrementAndGet();
        }
    }

    @Test
    public void topLeftIsJustTheOffset() {
        CounterPanel p = new CounterPanel(40, 10);
        p.setAnchor(Panel.Anchor.TOP_LEFT);
        p.setOffset(5, 7);
        p.resolve(1920, 1080);
        assertEquals(5, p.resolvedX());
        assertEquals(7, p.resolvedY());
    }

    @Test
    public void bottomRightSubtractsSizeAndOffset() {
        CounterPanel p = new CounterPanel(40, 10);
        p.setAnchor(Panel.Anchor.BOTTOM_RIGHT);
        p.setOffset(2, 3);
        p.resolve(200, 100);
        assertEquals(200 - 40 - 2, p.resolvedX());
        assertEquals(100 - 10 - 3, p.resolvedY());
    }

    @Test
    public void centerHalvesRemainingSpacePlusOffset() {
        CounterPanel p = new CounterPanel(40, 20);
        p.setAnchor(Panel.Anchor.CENTER);
        p.setOffset(0, 0);
        p.resolve(200, 100);
        assertEquals((200 - 40) / 2, p.resolvedX());
        assertEquals((100 - 20) / 2, p.resolvedY());
    }

    @Test
    public void fireRenderResolvesThenRendersWhenEnabled() {
        CounterPanel p = new CounterPanel(40, 10);
        p.setAnchor(Panel.Anchor.TOP_RIGHT);
        p.setOffset(4, 0);
        p.fireRender(new RenderSignal(300, 150, 1.0f));
        // resolved from the signal's screen size (headless, no GL)
        assertEquals(300 - 40 - 4, p.resolvedX());
        assertEquals(1, p.renders.get());
    }

    @Test
    public void nullAnchorResetsToTopLeft() {
        CounterPanel p = new CounterPanel(10, 10);
        p.setAnchor(null);
        assertEquals(Panel.Anchor.TOP_LEFT, p.anchor());
    }

    @Test
    public void negativeSizeIsClampedToZero() {
        CounterPanel p = new CounterPanel(-5, -5);
        assertEquals(0, p.width());
        assertEquals(0, p.height());
    }

    @Test
    public void throwingPanelIsFaultIsolated() {
        Panel bad = new Panel() {
            @Override
            protected void onRender(RenderSignal signal) {
                throw new RuntimeException("boom");
            }
        };
        // must not propagate — mirrors Chip/Trace fault isolation
        bad.fireRender(new RenderSignal(100, 100, 0f));
    }

    @Test
    public void panelIsAChipWithToggleState() {
        CounterPanel p = new CounterPanel(1, 1);
        assertFalse(p.isEnabled());
        assertTrue(p.toggle());
        assertTrue(p.isEnabled());
    }
}
