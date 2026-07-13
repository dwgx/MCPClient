package net.marcloud.mcp.dwm.compositor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.dwm.backend.BackendCaps;
import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.ContentBackend;
import net.marcloud.mcp.dwm.backend.DefaultContentBackendRegistry;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;

import org.junit.Test;

/**
 * Teeth for {@link ComposeCompositor}: the render-frame driver's non-obvious behaviors
 * are (a) reconciling attach/detach against the registry's active backend, (b) issuing
 * resize ONLY when the framebuffer geometry changes, (c) swallowing every backend fault
 * so the game render thread is never broken, and (d) propagating consumed-input flags.
 * Each test fails on a naive driver (e.g. one that resizes every frame, or lets a
 * backend throwable escape).
 */
public final class ComposeCompositorTest {

    /** A mutable host whose framebuffer facts the test can change to trigger resize. */
    private static final class FakeHost implements BackendHost {
        int fbId;
        int w;
        int h;
        FakeHost(int fbId, int w, int h) { this.fbId = fbId; this.w = w; this.h = h; }
        @Override public long windowHandle() { return 1L; }
        @Override public boolean onRenderThread() { return true; }
        @Override public int currentFramebufferId() { return fbId; }
        @Override public int framebufferWidthPx() { return w; }
        @Override public int framebufferHeightPx() { return h; }
    }

    /** Recording content backend counting each lifecycle call. */
    private static final class RecordingBackend implements ContentBackend {
        final String id;
        int attaches, detaches, resizes, inputs, frames;
        int lastResizeW, lastResizeH, lastResizeFbId, lastResizeFmt;
        boolean pointer, keyboard, wantsContinuous;
        boolean throwOnRender, throwOnAttach;
        RecordingBackend(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public BackendCaps caps() { return BackendCaps.minimal(); }
        @Override public void onAttach(BackendHost host) {
            if (throwOnAttach) { throw new RuntimeException("boom attach"); }
            attaches++;
        }
        @Override public void onDetach() { detaches++; }
        @Override public void resize(int w, int h, int fbId, int fbFormat) {
            resizes++; lastResizeW = w; lastResizeH = h; lastResizeFbId = fbId; lastResizeFmt = fbFormat;
        }
        @Override public void submitInput(FrameInput in) { inputs++; }
        @Override public void renderFrame(FrameMetrics m, long nanoTime) {
            if (throwOnRender) { throw new RuntimeException("boom render"); }
            frames++;
        }
        @Override public boolean wantsContinuousFrames() { return wantsContinuous; }
        @Override public boolean consumedPointer() { return pointer; }
        @Override public boolean consumedKeyboard() { return keyboard; }
    }

    private static ComposeCompositor.FrameOutcome drive(ComposeCompositor c) {
        return c.driveFrame(FrameInput.none(), 1f, 0.016f, 0L);
    }

    @Test
    public void noActiveBackendIsIdleNoOp() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        ComposeCompositor.FrameOutcome out = drive(c);
        assertFalse("nothing rendered when overlay off", out.rendered());
        assertNull("no backend attached", c.attached());
    }

    @Test
    public void activatingAttachesAndRenders() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        reg.register(b);
        reg.activate("compose");
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);

        ComposeCompositor.FrameOutcome out = drive(c);
        assertTrue(out.rendered());
        assertEquals(1, b.attaches);
        assertEquals("first frame resizes", 1, b.resizes);
        assertEquals(1, b.inputs);
        assertEquals(1, b.frames);
        assertSame(b, c.attached());
    }

    @Test
    public void resizeOnlyWhenFramebufferChanges() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        reg.register(b);
        reg.activate("compose");
        FakeHost host = new FakeHost(0, 800, 600);
        ComposeCompositor c = new ComposeCompositor(host, reg);

        drive(c); // frame 1: initial resize
        drive(c); // frame 2: same geometry -> no resize
        drive(c); // frame 3: same geometry -> no resize
        assertEquals("resize once for stable geometry", 1, b.resizes);
        assertEquals(3, b.frames);

        host.w = 1024; // geometry changes
        drive(c);
        assertEquals("resize again after size change", 2, b.resizes);
        assertEquals(1024, b.lastResizeW);
    }

    @Test
    public void deactivatingDetaches() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        reg.register(b);
        reg.activate("compose");
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        drive(c);
        assertSame(b, c.attached());

        reg.activate(null); // overlay off
        ComposeCompositor.FrameOutcome out = drive(c);
        assertFalse(out.rendered());
        assertEquals("detached on deactivate", 1, b.detaches);
        assertNull(c.attached());
    }

    @Test
    public void hotSwapDetachesOldAttachesNew() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend a = new RecordingBackend("compose");
        RecordingBackend d = new RecordingBackend("web");
        reg.register(a);
        reg.register(d);
        reg.activate("compose");
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        drive(c);
        assertEquals(1, a.attaches);

        reg.activate("web");
        drive(c);
        assertEquals("old backend detached on swap", 1, a.detaches);
        assertEquals("new backend attached on swap", 1, d.attaches);
        assertEquals("new backend resizes on first frame", 1, d.resizes);
        assertSame(d, c.attached());
    }

    @Test
    public void renderFaultSwallowedToIdle() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        b.throwOnRender = true;
        reg.register(b);
        reg.activate("compose");
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        // Must NOT throw — a backend fault cannot break the game render thread.
        ComposeCompositor.FrameOutcome out = drive(c);
        assertFalse("faulted frame yields idle outcome", out.rendered());
        assertFalse(out.consumedPointer());
    }

    @Test
    public void attachFaultDisablesOverlay() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        b.throwOnAttach = true;
        reg.register(b);
        reg.activate("compose");
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        ComposeCompositor.FrameOutcome out = drive(c); // attach throws
        assertFalse(out.rendered());
        assertNull("failed attach leaves no attached backend", c.attached());
    }

    @Test
    public void consumedFlagsPropagate() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        b.pointer = true;
        b.keyboard = true;
        reg.register(b);
        reg.activate("compose");
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        ComposeCompositor.FrameOutcome out = drive(c);
        assertTrue("pointer consumption reported so caller swallows the click", out.consumedPointer());
        assertTrue(out.consumedKeyboard());
    }

    @Test
    public void continuousFramesGatedByBackend() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        b.wantsContinuous = true;
        ComposeCompositor c = new ComposeCompositor(new FakeHost(0, 800, 600), reg);
        assertFalse("no backend -> no continuous", c.shouldRenderContinuously());
        reg.register(b);
        reg.activate("compose");
        drive(c); // attach
        assertTrue("attached backend wants continuous frames", c.shouldRenderContinuously());
    }

    @Test
    public void unknownFramebufferUsesDefaultZero() {
        DefaultContentBackendRegistry reg = new DefaultContentBackendRegistry();
        RecordingBackend b = new RecordingBackend("compose");
        reg.register(b);
        reg.activate("compose");
        // Host reports -1 (unknown FBO) via BackendHost defaults.
        BackendHost unknownFb = new BackendHost() {
            @Override public long windowHandle() { return 1L; }
            @Override public boolean onRenderThread() { return true; }
        };
        ComposeCompositor c = new ComposeCompositor(unknownFb, reg);
        drive(c);
        assertEquals("unknown FBO resolves to default framebuffer 0", 0, b.lastResizeFbId);
    }
}
