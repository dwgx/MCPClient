package net.marcloud.mcp.core.flt.seam;

import static org.junit.Assert.assertNull;

import java.util.function.LongConsumer;

import org.junit.After;
import org.junit.Test;

/**
 * Teeth for {@link RenderOverlayCoordinator}'s reflective backend resolution — the logic
 * that lets core drive whichever overlay backend jar (pure-Java {@code dwm-gl}, Compose,
 * or imgui) is on the classpath WITHOUT a compile-time link to any of them.
 *
 * <p>None of the backend entry classes are on core's own test classpath (they live in
 * the detachable aux modules), so these lock the degrade-to-absent contract: with no
 * backend present, {@link RenderOverlayCoordinator#resolveDriver(long)} returns null
 * (the caller then installs no seam and the game is unaffected) — for the default
 * preference order AND for every explicit {@code -Dmcp.core.overlay.backend} selection,
 * including an unknown one. A regression that made resolution throw, or return non-null
 * against absent classes, fails here.
 */
public class RenderOverlayCoordinatorTest {

    private static final String BACKEND_PROP = "mcp.core.overlay.backend";

    @After
    public void clearProp() {
        System.clearProperty(BACKEND_PROP);
    }

    @Test
    public void defaultOrderDegradesToNullWhenNoBackendPresent() {
        System.clearProperty(BACKEND_PROP);
        LongConsumer driver = RenderOverlayCoordinator.resolveDriver(0L);
        assertNull("no backend jar on the classpath -> null (no overlay, game unaffected)", driver);
    }

    @Test
    public void explicitGlSelectionDegradesToNullWhenAbsent() {
        System.setProperty(BACKEND_PROP, "gl");
        assertNull(RenderOverlayCoordinator.resolveDriver(0L));
    }

    @Test
    public void explicitGlUiSelectionDegradesToNullWhenAbsent() {
        // The MD3-UI backend id (DrawContext axis, drives the MaterialButton tree).
        System.setProperty(BACKEND_PROP, "gl-ui");
        assertNull(RenderOverlayCoordinator.resolveDriver(0L));
    }

    @Test
    public void explicitSkikoUiSelectionDegradesToNullWhenAbsent() {
        // The Skiko MD3-UI backend id (highest-fidelity DrawContext axis).
        System.setProperty(BACKEND_PROP, "skiko-ui");
        assertNull(RenderOverlayCoordinator.resolveDriver(0L));
    }

    @Test
    public void explicitImguiSelectionDegradesToNullWhenAbsent() {
        System.setProperty(BACKEND_PROP, "imgui");
        assertNull(RenderOverlayCoordinator.resolveDriver(0L));
    }

    @Test
    public void explicitImguiUiSelectionDegradesToNullWhenAbsent() {
        // The imgui MD3-UI backend id (DrawContext axis).
        System.setProperty(BACKEND_PROP, "imgui-ui");
        assertNull(RenderOverlayCoordinator.resolveDriver(0L));
    }

    @Test
    public void unknownBackendSelectionIsNullNotThrow() {
        System.setProperty(BACKEND_PROP, "does-not-exist");
        // An unknown id matches no candidate: resolution must return null, never throw.
        assertNull(RenderOverlayCoordinator.resolveDriver(0L));
    }
}
