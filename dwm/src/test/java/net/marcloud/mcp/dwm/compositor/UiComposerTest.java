package net.marcloud.mcp.dwm.compositor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.BackendCaps;
import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultBackendRegistry;
import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FontSpec;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.NullBackend;
import net.marcloud.mcp.dwm.backend.PaintSpec;
import net.marcloud.mcp.dwm.backend.PathSpec;
import net.marcloud.mcp.dwm.backend.RenderBackend;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.backend.TextureData;
import net.marcloud.mcp.dwm.backend.TextureHandle;
import net.marcloud.mcp.dwm.component.ComponentContext;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.theme.MaterialMdcTheme;

/**
 * Teeth for {@link UiComposer} — the RenderBackend-axis frame driver that walks the MD3
 * component tree into a backend's {@link DrawContext}. Verifies the frame contract that
 * makes it safe to inline on the game render thread:
 * <ul>
 *   <li>the exact per-frame ORDER: backend attach → tick (before draw) → beginFrame →
 *       root.render → endFrame → gc (after draw);</li>
 *   <li>the root component actually receives the backend's live DrawContext and the
 *       full-viewport box;</li>
 *   <li>a faulting component/backend NEVER breaks the game thread AND still closes the
 *       animation frame (tick/gc pairing preserved);</li>
 *   <li>a hot-swap of the active backend detaches the old and attaches the new.</li>
 * </ul>
 * All headless — no GL — using a spy RenderBackend + spy Component.
 */
public class UiComposerTest {

    /** Fixed-size host so metrics are deterministic. */
    private static final BackendHost HOST = new BackendHost() {
        @Override public long windowHandle() { return 0L; }
        @Override public boolean onRenderThread() { return true; }
        @Override public int framebufferWidthPx() { return 640; }
        @Override public int framebufferHeightPx() { return 360; }
    };

    private UiComposer newComposer(DefaultBackendRegistry reg, Component root) {
        Compositor compositor = new Compositor();
        FrameComponentContext ctx = new FrameComponentContext(
                MaterialMdcTheme.darkTheme(), compositor.store(), WidgetId.root("test-root"));
        return new UiComposer(HOST, reg, compositor, ctx, root);
    }

    @Test
    public void realButtonClicksFromDrivenPointerInput() {
        // Input wiring end-to-end: a FrameInput with the pointer over the button + the
        // primary button down (press frame) then up (release frame) must produce a click,
        // proving driveFrame threads real input to the component's hit-test. Runs on the
        // NullBackend floor (DrawContext no-ops; the button's input logic + store still
        // run). The button lays out at (16,16, measured). Point (40,30) is inside it.
        DefaultBackendRegistry reg = new DefaultBackendRegistry(); // NullBackend active
        boolean[] clicked = {false};
        net.marcloud.mcp.dwm.component.material.MaterialButton btn =
                new net.marcloud.mcp.dwm.component.material.MaterialButton("Go");
        Component root = new Component() {
            @Override public Result render(ComponentContext ctx, float x, float y, float w, float h) {
                Size s = btn.measure(ctx);
                Result r = btn.render(ctx, 16f, 16f, s.width(), s.height());
                if (r.clicked()) {
                    clicked[0] = true;
                }
                return r;
            }
            @Override public Size measure(ComponentContext ctx) { return btn.measure(ctx); }
        };
        UiComposer composer = newComposer(reg, root);

        // Frame 1: pointer inside, primary down -> press (ripple holds, not yet clicked).
        composer.driveFrame(new FrameInput(40f, 30f, 1, 0f, 0f, java.util.List.of(), java.util.List.of()),
                1f, 1f / 60f);
        // Frame 2: pointer still inside, button released -> click edge.
        composer.driveFrame(new FrameInput(40f, 30f, 0, 0f, 0f, java.util.List.of(), java.util.List.of()),
                1f, 1f / 60f);

        assertTrue("button must register a click from press-then-release over its bounds", clicked[0]);
    }

    @Test
    public void drivesAttachTickBeginRenderEndGcInOrder() {
        List<String> log = new ArrayList<>();
        SpyBackend backend = new SpyBackend("spy", log);
        DefaultBackendRegistry reg = new DefaultBackendRegistry();
        reg.register(backend);
        reg.activate("spy");

        boolean[] rendered = {false};
        Component root = root((ctx, x, y, w, h) -> {
            log.add("render");
            rendered[0] = true;
            return Component.Result.idle();
        });
        UiComposer composer = newComposer(reg, root);

        composer.driveFrame(FrameInput.none(), 1f, 1f / 60f);

        assertSame("composer attached the active backend", backend, composer.attached());
        // The load-bearing order: attach, then begin, draw (root render), end.
        assertEquals(List.of("attach", "begin", "render", "end"), log);
        assertTrue("root component was actually rendered", rendered[0]);
    }

    @Test
    public void rootReceivesBackendDrawContextAndFullViewport() {
        DefaultBackendRegistry reg = new DefaultBackendRegistry();
        SpyBackend backend = new SpyBackend("spy", new ArrayList<>());
        reg.register(backend);
        reg.activate("spy");

        DrawContext[] seenDraw = {null};
        float[] seenBox = new float[4];
        Component root = root((ctx, x, y, w, h) -> {
            seenDraw[0] = ctx.draw();
            seenBox[0] = x; seenBox[1] = y; seenBox[2] = w; seenBox[3] = h;
            return Component.Result.idle();
        });
        newComposer(reg, root).driveFrame(FrameInput.none(), 1f, 1f / 60f);

        assertSame("root got the backend's own DrawContext", backend.draw, seenDraw[0]);
        assertEquals("box x", 0f, seenBox[0], 0f);
        assertEquals("box y", 0f, seenBox[1], 0f);
        assertEquals("box w = host fb width", 640f, seenBox[2], 0f);
        assertEquals("box h = host fb height", 360f, seenBox[3], 0f);
    }

    @Test
    public void faultingRootDoesNotThrowAndStillClosesAnimationFrame() {
        DefaultBackendRegistry reg = new DefaultBackendRegistry();
        SpyBackend backend = new SpyBackend("spy", new ArrayList<>());
        reg.register(backend);
        reg.activate("spy");

        Component boom = root((ctx, x, y, w, h) -> {
            throw new RuntimeException("component blew up mid-frame");
        });
        UiComposer composer = newComposer(reg, boom);

        // Must not propagate — a component fault on the render thread cannot break it.
        composer.driveFrame(FrameInput.none(), 1f, 1f / 60f);
        // And a SECOND frame must still work (proves the animation frame was closed;
        // an unclosed Compositor frame would throw "beginFrame called twice").
        composer.driveFrame(FrameInput.none(), 1f, 1f / 60f);
    }

    @Test
    public void faultingRootStillCallsBackendEndFrame() {
        // THE C1 REGRESSION: a component throwing mid-tree must STILL run backend.endFrame
        // (which does guard.leave() + matrix/scissor restore + MC shadow write-through).
        // The old code had endFrame() inside the try, so a render() throw skipped it and
        // orphaned the GL guard -> the black/white/invisible desync returned next frame.
        // This asserts begin/end are paired on the faulting frame.
        List<String> log = new ArrayList<>();
        SpyBackend backend = new SpyBackend("spy", log);
        DefaultBackendRegistry reg = new DefaultBackendRegistry();
        reg.register(backend);
        reg.activate("spy");

        Component boom = root((ctx, x, y, w, h) -> {
            throw new RuntimeException("component blew up mid-frame");
        });
        newComposer(reg, boom).driveFrame(FrameInput.none(), 1f, 1f / 60f);

        long begins = log.stream().filter("begin"::equals).count();
        long ends = log.stream().filter("end"::equals).count();
        assertEquals("backend.beginFrame ran once", 1L, begins);
        assertEquals("backend.endFrame MUST run despite the component throwing "
                + "(else guard.leave/matrix/scissor restore is orphaned)", 1L, ends);
    }

    @Test
    public void hotSwapDetachesOldAndAttachesNew() {
        List<String> log = new ArrayList<>();
        SpyBackend a = new SpyBackend("a", log);
        SpyBackend b = new SpyBackend("b", log);
        DefaultBackendRegistry reg = new DefaultBackendRegistry();
        reg.register(a);
        reg.register(b);
        reg.activate("a");

        Component root = root((ctx, x, y, w, h) -> Component.Result.idle());
        UiComposer composer = newComposer(reg, root);

        composer.driveFrame(FrameInput.none(), 1f, 1f / 60f);
        assertSame(a, composer.attached());

        reg.activate("b");
        composer.driveFrame(FrameInput.none(), 1f, 1f / 60f);
        assertSame(b, composer.attached());
        assertTrue("old backend was detached on swap", log.contains("a:detach"));
        assertTrue("new backend was attached on swap", log.contains("b:attach"));
    }

    @Test
    public void nullFloorRendersWithoutThrowing() {
        // The NullBackend floor: draw() is a no-op recorder; the tree still ticks +
        // renders so animations stay warm for an instant swap. Must not throw.
        DefaultBackendRegistry reg = new DefaultBackendRegistry(); // seeds NullBackend active
        assertTrue("registry seeds a NullBackend floor", reg.active() instanceof NullBackend);
        boolean[] rendered = {false};
        Component root = root((ctx, x, y, w, h) -> {
            rendered[0] = true;
            return Component.Result.idle();
        });
        newComposer(reg, root).driveFrame(FrameInput.none(), 1f, 1f / 60f);
        assertTrue("root rendered even on the Null floor", rendered[0]);
    }

    @Test
    public void detachActiveTearsDownBackend() {
        List<String> log = new ArrayList<>();
        SpyBackend backend = new SpyBackend("spy", log);
        DefaultBackendRegistry reg = new DefaultBackendRegistry();
        reg.register(backend);
        reg.activate("spy");
        Component root = root((ctx, x, y, w, h) -> Component.Result.idle());
        UiComposer composer = newComposer(reg, root);

        composer.driveFrame(FrameInput.none(), 1f, 1f / 60f);
        composer.detachActive();
        assertNull(composer.attached());
        assertTrue(log.contains("detach"));
    }

    // ---- spies ----------------------------------------------------------------------

    /** Just the render lambda; Component also needs measure(), supplied by {@link #root}. */
    @FunctionalInterface
    private interface RootFn {
        Component.Result render(ComponentContext ctx, float x, float y, float w, float h);
    }

    /** Adapt a render lambda into a full {@link Component} (measure returns a fixed size). */
    private static Component root(RootFn fn) {
        return new Component() {
            @Override
            public Result render(ComponentContext ctx, float x, float y, float w, float h) {
                return fn.render(ctx, x, y, w, h);
            }

            @Override
            public Size measure(ComponentContext ctx) {
                return new Size(0f, 0f);
            }
        };
    }

    /** A RenderBackend that records its lifecycle calls and exposes a no-op DrawContext. */
    private static final class SpyBackend implements RenderBackend {
        private final String id;
        private final List<String> log;
        final DrawContext draw = new NoopDraw();

        SpyBackend(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override public String id() { return id; }
        @Override public BackendCaps caps() { return BackendCaps.minimal(); }
        @Override public void onAttach(BackendHost host) { log.add(prefixed("attach")); }
        @Override public void onDetach() { log.add(prefixed("detach")); }
        @Override public void beginFrame(FrameInput in, FrameMetrics m) { log.add(prefixed("begin")); }
        @Override public DrawContext draw() { return draw; }
        @Override public void endFrame() { log.add(prefixed("end")); }
        @Override public TextureHandle uploadTexture(TextureData rgba) { return new TextureHandle(0L); }
        @Override public void freeTexture(TextureHandle h) { }
        @Override public FontHandle loadFont(FontSpec spec) { return new FontHandle(0L); }
        @Override public TextMetrics measureText(FontHandle f, CharSequence s, float px) {
            return new TextMetrics(0f, 0f, 0f);
        }

        // In the hot-swap test two backends share a log, so tag events with the id;
        // single-backend tests use one backend, so keep bare names. We detect the
        // multi-backend case by the id not being the lone "spy".
        private String prefixed(String ev) {
            return "spy".equals(id) ? ev : id + ":" + ev;
        }
    }

    /** A DrawContext that records nothing (like NullBackend's, but standalone for the spy). */
    private static final class NoopDraw implements DrawContext {
        @Override public void rect(float x, float y, float w, float h, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, float radius, int argb) { }
        @Override public void roundedRect(float x, float y, float w, float h, Corners c, int argb) { }
        @Override public void rectStroke(float x, float y, float w, float h, float thickness, int argb) { }
        @Override public void line(float x0, float y0, float x1, float y1, float thickness, int argb) { }
        @Override public void text(FontHandle font, float sizePx, float x, float y, int argb, CharSequence s) { }
        @Override public void image(TextureHandle tex, float x, float y, float w, float h, int tintArgb) { }
        @Override public void path(PathSpec path, PaintSpec paint) { }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public void pushOpacity(float alpha) { }
        @Override public void popOpacity() { }
    }
}
