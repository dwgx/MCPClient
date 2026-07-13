package net.marcloud.mcp.dwm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link DefaultBackendRegistry}: fail-safe floor, register/find/activate,
 * null-on-absent degradation. Each fails on wrong behavior.
 */
public final class DefaultBackendRegistryTest {

    /** Minimal fake backend with a chosen id; all ops no-op. */
    private static RenderBackend fake(String id) {
        return new RenderBackend() {
            @Override public String id() { return id; }
            @Override public BackendCaps caps() { return BackendCaps.minimal(); }
            @Override public void onAttach(BackendHost host) { }
            @Override public void onDetach() { }
            @Override public void beginFrame(FrameInput in, FrameMetrics m) { }
            @Override public DrawContext draw() { return null; }
            @Override public void endFrame() { }
            @Override public TextureHandle uploadTexture(TextureData rgba) { return new TextureHandle(0); }
            @Override public void freeTexture(TextureHandle h) { }
            @Override public FontHandle loadFont(FontSpec spec) { return new FontHandle(0); }
            @Override public TextMetrics measureText(FontHandle f, CharSequence s, float px) {
                return new TextMetrics(0, 0, 0);
            }
        };
    }

    @Test
    public void seededWithNullBackendActive() {
        DefaultBackendRegistry r = new DefaultBackendRegistry();
        // Fail-safe floor: active is never null and is the null backend before any real one.
        assertNotNull(r.active());
        assertEquals("null", r.active().id());
        assertNotNull("null backend is findable", r.find("null"));
    }

    @Test
    public void findAbsentReturnsNull() {
        DefaultBackendRegistry r = new DefaultBackendRegistry();
        assertNull("absent backend -> null (caller degrades)", r.find("imgui"));
    }

    @Test
    public void registerThenFindAndActivate() {
        DefaultBackendRegistry r = new DefaultBackendRegistry();
        RenderBackend imgui = fake("imgui");
        r.register(imgui);
        assertSame(imgui, r.find("imgui"));
        assertTrue(r.activate("imgui"));
        assertSame("active switched to imgui", imgui, r.active());
    }

    @Test
    public void activateAbsentReturnsFalseAndKeepsActive() {
        DefaultBackendRegistry r = new DefaultBackendRegistry();
        RenderBackend before = r.active();
        assertFalse("activating an absent backend fails", r.activate("skia"));
        assertSame("active unchanged on failed activate", before, r.active());
    }

    @Test
    public void idsListsRegistered() {
        DefaultBackendRegistry r = new DefaultBackendRegistry();
        r.register(fake("imgui"));
        r.register(fake("compose"));
        assertTrue(r.ids().contains("null"));
        assertTrue(r.ids().contains("imgui"));
        assertTrue(r.ids().contains("compose"));
        assertEquals(3, r.ids().size());
    }

    @Test
    public void hotSwapActiveVisible() {
        DefaultBackendRegistry r = new DefaultBackendRegistry();
        r.register(fake("imgui"));
        r.register(fake("skia"));
        r.activate("imgui");
        assertEquals("imgui", r.active().id());
        r.activate("skia");
        assertEquals("hot-swap to skia visible", "skia", r.active().id());
    }
}
