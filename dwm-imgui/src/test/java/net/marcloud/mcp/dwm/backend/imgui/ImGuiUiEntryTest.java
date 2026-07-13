package net.marcloud.mcp.dwm.backend.imgui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Modifier;
import java.util.function.LongConsumer;

import org.junit.Test;

/**
 * Contract test for the imgui MD3-UI reflective entry point core reaches by name.
 * {@code ImGuiUiEntry.frameSink(long)} drives the DWM MD3 component tree through
 * {@code UiComposer} + {@code ImGuiRenderBackend}. Core requires a non-null
 * {@link LongConsumer} (null would NPE the seam install). Driving it is LIVE-ONLY
 * (real GL context + imgui native), so this locks signature + FQN + non-null only.
 */
public class ImGuiUiEntryTest {

    @Test
    public void frameSinkSignatureIsReflectivelyResolvable() throws Exception {
        var m = ImGuiUiEntry.class.getMethod("frameSink", long.class);
        assertTrue("frameSink must be static", Modifier.isStatic(m.getModifiers()));
        assertTrue("frameSink must return a LongConsumer",
                LongConsumer.class.isAssignableFrom(m.getReturnType()));
    }

    @Test
    public void frameSinkFqnMatchesCoordinatorRegistration() throws Exception {
        // Core's RenderOverlayCoordinator registers exactly this FQN for the imgui-ui id.
        Class<?> c = Class.forName("net.marcloud.mcp.dwm.backend.imgui.ImGuiUiEntry");
        assertNotNull(c.getMethod("frameSink", long.class));
    }
}
