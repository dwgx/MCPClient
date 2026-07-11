package net.marcloud.mcp.board.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.RenderSignal;

/**
 * Regression tests for {@link HudMatrix}: the render pass draws only enabled
 * panels in order, wires to a {@link Trace} via {@link RenderSignal}, and
 * isolates a throwing panel. Fails on absent/old code — HudMatrix does not exist
 * without the HUD subsystem, and the enabled-only / trace-driven behavior is
 * exact.
 */
public class HudMatrixTest {

    private static final class CounterPanel extends Panel {
        final AtomicInteger renders = new AtomicInteger();
        final String id;

        CounterPanel(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        protected void onRender(RenderSignal signal) {
            renders.incrementAndGet();
        }
    }

    @Test
    public void renderDrawsOnlyEnabledPanels() {
        HudMatrix hud = new HudMatrix();
        CounterPanel a = (CounterPanel) hud.add(new CounterPanel("a"));
        CounterPanel b = (CounterPanel) hud.add(new CounterPanel("b"));
        a.setEnabled(true); // b stays disabled

        hud.render(new RenderSignal(200, 100, 0f));

        assertEquals(1, a.renders.get());
        assertEquals("disabled panel must be skipped", 0, b.renders.get());
    }

    @Test
    public void renderVisitsPanelsInInsertionOrder() {
        HudMatrix hud = new HudMatrix();
        final StringBuilder order = new StringBuilder();
        for (final String id : new String[] {"first", "second", "third"}) {
            Panel p = new Panel() {
                @Override
                public String id() {
                    return id;
                }

                @Override
                protected void onRender(RenderSignal signal) {
                    order.append(id).append(',');
                }
            };
            hud.add(p).setEnabled(true);
        }
        hud.render(new RenderSignal(10, 10, 0f));
        assertEquals("first,second,third,", order.toString());
    }

    @Test
    public void attachToTraceDrivesRenderOnPublish() {
        Trace trace = new Trace();
        HudMatrix hud = new HudMatrix();
        CounterPanel p = (CounterPanel) hud.add(new CounterPanel("p"));
        p.setAnchor(Panel.Anchor.TOP_LEFT);
        p.setOffset(0, 0);
        p.setEnabled(true);

        hud.attach(trace);
        assertTrue(hud.isAttached());
        assertEquals(1, trace.subscriberCount());

        trace.publish(new RenderSignal(320, 240, 0.5f));
        assertEquals(1, p.renders.get());
        // panel laid out from the published signal's screen size, no GL
        assertEquals(0, p.resolvedX());
    }

    @Test
    public void detachStopsRenderingAndUnsubscribes() {
        Trace trace = new Trace();
        HudMatrix hud = new HudMatrix();
        CounterPanel p = (CounterPanel) hud.add(new CounterPanel("p"));
        p.setEnabled(true);
        hud.attach(trace);

        hud.detach();
        assertFalse(hud.isAttached());
        assertEquals(0, trace.subscriberCount());

        trace.publish(new RenderSignal(100, 100, 0f));
        assertEquals("no render after detach", 0, p.renders.get());
    }

    @Test
    public void attachIsIdempotentPerTrace() {
        Trace trace = new Trace();
        HudMatrix hud = new HudMatrix();
        hud.attach(trace);
        hud.attach(trace);
        assertEquals("no double-subscribe", 1, trace.subscriberCount());
    }

    @Test
    public void attachToDifferentTraceMovesSubscription() {
        Trace first = new Trace();
        Trace second = new Trace();
        HudMatrix hud = new HudMatrix();
        hud.attach(first);
        hud.attach(second);
        assertEquals(0, first.subscriberCount());
        assertEquals(1, second.subscriberCount());
    }

    @Test
    public void throwingPanelDoesNotBreakThePass() {
        HudMatrix hud = new HudMatrix();
        Panel bad = new Panel() {
            @Override
            public String id() {
                return "bad";
            }

            @Override
            protected void onRender(RenderSignal signal) {
                throw new RuntimeException("boom");
            }
        };
        CounterPanel good = new CounterPanel("good");
        hud.add(bad).setEnabled(true);
        hud.add(good).setEnabled(true);

        hud.render(new RenderSignal(50, 50, 0f)); // must not throw
        assertEquals("good panel still rendered after bad one threw",
                1, good.renders.get());
    }

    @Test
    public void delegatesManagerSurfaceToMatrix() {
        HudMatrix hud = new HudMatrix();
        hud.add(new CounterPanel("only"));
        assertEquals(1, hud.size());
        assertTrue(hud.contains("only"));
        assertEquals("only", hud.byId("only").id());
        assertEquals(1, hud.all().size());
        hud.removeById("only");
        assertEquals(0, hud.size());
        assertNull(hud.byId("only"));
    }

    @Test
    public void clearDetachesAndEmpties() {
        Trace trace = new Trace();
        HudMatrix hud = new HudMatrix();
        hud.add(new CounterPanel("x")).setEnabled(true);
        hud.attach(trace);

        hud.clear();
        assertEquals(0, hud.size());
        assertFalse(hud.isAttached());
        assertEquals(0, trace.subscriberCount());
    }

    @Test
    public void nullSignalRenderIsNoOp() {
        HudMatrix hud = new HudMatrix();
        CounterPanel p = (CounterPanel) hud.add(new CounterPanel("p"));
        p.setEnabled(true);
        hud.render(null); // must not throw
        assertEquals(0, p.renders.get());
    }

    /**
     * S3 coverage: HudMatrix is usable through the shared {@link Manager}
     * interface, so code can treat it uniformly with Matrix without knowing it is
     * a HudMatrix. Pins the Manager&lt;Panel&gt; contract on the concrete class.
     */
    @Test
    public void hudMatrixIsUsableThroughManagerInterface() {
        net.marcloud.mcp.board.Manager<Panel> mgr = new HudMatrix();
        Panel p = mgr.add(new CounterPanel("via-iface"));
        assertTrue(mgr.contains("via-iface"));
        assertSame(p, mgr.byId("via-iface"));
        assertEquals(1, mgr.size());
        assertEquals(1, mgr.all().size());
        mgr.clear();
        assertEquals(0, mgr.size());
    }
}
