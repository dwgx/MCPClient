package net.marcloud.mcp.dwm.backend.imgui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.util.function.LongConsumer;

import org.junit.Test;

/**
 * Contract test for the reflective entry point core reaches by name. Core resolves
 * {@code net.marcloud.mcp.dwm.backend.imgui.ImGuiOverlayEntry.frameSink(long)} via
 * reflection (the FQN is hard-registered in core's RenderOverlayCoordinator candidate
 * table) and REQUIRES a non-null {@link LongConsumer} back — a null would NPE the seam
 * install. This locks the exact signature the coordinator depends on.
 *
 * <p>Note: {@code frameSink} itself does not touch GL or load the imgui native (that
 * happens later, on the first {@code driveFrame} → {@code onAttach}). But it does
 * construct an {@link ImGuiContentBackend}, so this stays a signature/degrade contract
 * test; actually driving it is LIVE-ONLY (real GL context + imgui-java64.dll), validated
 * in-game via the overlay run script.
 */
public class ImGuiOverlayEntryTest {

    @Test
    public void frameSinkSignatureIsReflectivelyResolvable() throws Exception {
        var m = ImGuiOverlayEntry.class.getMethod("frameSink", long.class);
        assertTrue("frameSink must be static", Modifier.isStatic(m.getModifiers()));
        assertTrue("frameSink must return a LongConsumer",
                LongConsumer.class.isAssignableFrom(m.getReturnType()));
    }

    @Test
    public void frameSinkFqnMatchesCoordinatorRegistration() throws Exception {
        // Core's RenderOverlayCoordinator registers exactly this FQN for the imgui id.
        // A rename here that broke that link would silently disable the backend at runtime.
        Class<?> c = Class.forName("net.marcloud.mcp.dwm.backend.imgui.ImGuiOverlayEntry");
        assertNotNull(c.getMethod("frameSink", long.class));
    }
}
