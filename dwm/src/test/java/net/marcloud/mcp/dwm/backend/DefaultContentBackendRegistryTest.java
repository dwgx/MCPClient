package net.marcloud.mcp.dwm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link DefaultContentBackendRegistry}: the content-overlay registry has
 * NO NullBackend floor (unlike {@link DefaultBackendRegistry}), so its distinctive
 * contract is that {@link ContentBackendRegistry#active()} is {@code null} until a
 * backend is activated, and {@code activate(null)} turns the overlay back off. Each
 * test fails on the wrong behavior (e.g. if the impl seeded a floor, or treated
 * {@code activate(null)} as "not found").
 */
public final class DefaultContentBackendRegistryTest {

    /** Minimal fake content backend with a chosen id; all ops no-op. */
    private static ContentBackend fake(String id) {
        return new ContentBackend() {
            @Override public String id() { return id; }
            @Override public BackendCaps caps() { return BackendCaps.minimal(); }
            @Override public void onAttach(BackendHost host) { }
            @Override public void onDetach() { }
            @Override public void resize(int w, int h, int fbId, int fbFormat) { }
            @Override public void submitInput(FrameInput in) { }
            @Override public void renderFrame(FrameMetrics m, long nanoTime) { }
            @Override public boolean wantsContinuousFrames() { return false; }
            @Override public boolean consumedPointer() { return false; }
            @Override public boolean consumedKeyboard() { return false; }
        };
    }

    @Test
    public void overlayOffByDefault() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        // No floor: the content overlay is optional and starts off.
        assertNull("no NullBackend floor — overlay off until activated", r.active());
        assertTrue("no floor id registered", r.ids().isEmpty());
    }

    @Test
    public void findAbsentReturnsNull() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        assertNull("absent backend -> null (caller renders no overlay)", r.find("compose"));
    }

    @Test
    public void registerThenFindAndActivate() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        ContentBackend compose = fake("compose");
        r.register(compose);
        assertSame(compose, r.find("compose"));
        assertTrue(r.activate("compose"));
        assertSame("active switched to compose", compose, r.active());
    }

    @Test
    public void activateNullTurnsOverlayOff() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        r.register(fake("compose"));
        r.activate("compose");
        assertEquals("compose", r.active().id());
        assertTrue("activate(null) is a valid deactivate, returns true", r.activate(null));
        assertNull("overlay turned off", r.active());
    }

    @Test
    public void activateAbsentReturnsFalseAndKeepsActive() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        r.register(fake("compose"));
        r.activate("compose");
        ContentBackend before = r.active();
        assertFalse("activating an absent backend fails", r.activate("skia-content"));
        assertSame("active unchanged on failed activate", before, r.active());
    }

    @Test
    public void idsListsRegisteredWithoutFloor() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        r.register(fake("compose"));
        r.register(fake("other"));
        assertEquals(2, r.ids().size());
        assertTrue(r.ids().contains("compose"));
        assertTrue(r.ids().contains("other"));
        assertFalse("no null floor in a content registry", r.ids().contains("null"));
    }

    @Test
    public void hotSwapActiveVisible() {
        DefaultContentBackendRegistry r = new DefaultContentBackendRegistry();
        r.register(fake("compose"));
        r.register(fake("web"));
        r.activate("compose");
        assertEquals("compose", r.active().id());
        r.activate("web");
        assertEquals("hot-swap to web visible", "web", r.active().id());
    }

    @Test(expected = IllegalArgumentException.class)
    public void registerNullRejected() {
        new DefaultContentBackendRegistry().register(null);
    }

    @Test
    public void backendHostGlFactsDefaultToUnknown() {
        // The widened BackendHost accessors are default methods, so a host that only
        // implements the two required facts still compiles and reports "unknown".
        BackendHost minimal = new BackendHost() {
            @Override public long windowHandle() { return 42L; }
            @Override public boolean onRenderThread() { return true; }
        };
        assertEquals(42L, minimal.windowHandle());
        assertTrue(minimal.onRenderThread());
        assertEquals("unknown FBO -> -1 (adapter uses default 0)", -1, minimal.currentFramebufferId());
        assertEquals(0, minimal.framebufferWidthPx());
        assertEquals(0, minimal.framebufferHeightPx());
    }
}
