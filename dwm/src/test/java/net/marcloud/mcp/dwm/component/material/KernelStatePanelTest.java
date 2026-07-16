package net.marcloud.mcp.dwm.component.material;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.marcloud.mcp.board.Backplane;
import net.marcloud.mcp.dwm.component.KernelStatePanel;

/**
 * Teeth for {@link KernelStatePanel}: it renders whatever live kernel-state map core
 * published on the {@link Backplane} (the seam), and degrades to an offline placeholder
 * when nothing is registered — never crashing the render pass. Lives in this package to
 * reuse the recording {@link FakeComponentContext}.
 */
public class KernelStatePanelTest {

    @Before
    @After
    public void resetBackplane() {
        Backplane.clear();
    }

    @Test
    public void rendersRowsFromThePublishedSnapshot() {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("Clearance", "R_MINUS_1");
        state.put("Armed patches", "2");
        Supplier<Map<String, String>> supplier = () -> state;
        Backplane.register("kernel.state", supplier);

        FakeComponentContext ctx = new FakeComponentContext("overlay");
        KernelStatePanel panel = new KernelStatePanel();
        panel.render(ctx, 0f, 0f, 400f, 300f);

        var texts = drawnText(ctx);
        assertTrue("row label drawn", texts.stream().anyMatch(t -> t.contains("Clearance")));
        assertTrue("row value drawn", texts.stream().anyMatch(t -> t.contains("R_MINUS_1")));
        assertTrue("armed-patch value drawn", texts.stream().anyMatch(t -> t.contains("2")));
        assertFalse("no offline placeholder when live data present",
                texts.stream().anyMatch(t -> t.contains("offline")));
    }

    @Test
    public void reflectsAliveSnapshotChangeBetweenFrames() {
        // A mutable source proves the panel re-reads every frame (live, not cached at ctor).
        Map<String, String> state = new LinkedHashMap<>();
        state.put("Disabled privileges", "none");
        Backplane.register("kernel.state", (Supplier<Map<String, String>>) () -> state);

        FakeComponentContext ctx1 = new FakeComponentContext("overlay");
        new KernelStatePanel().render(ctx1, 0f, 0f, 400f, 300f);
        assertTrue("frame 1 shows 'none'",
                drawnText(ctx1).stream().anyMatch(t -> t.contains("none")));

        // Simulate a runtime disable_privilege changing the live posture.
        state.put("Disabled privileges", "SE_NET_RAW");
        FakeComponentContext ctx2 = new FakeComponentContext("overlay");
        new KernelStatePanel().render(ctx2, 0f, 0f, 400f, 300f);
        assertTrue("frame 2 reflects the updated posture",
                drawnText(ctx2).stream().anyMatch(t -> t.contains("SE_NET_RAW")));
    }

    @Test
    public void degradesToOfflinePlaceholderWhenNothingPublished() {
        // No Backplane registration (core absent / not started) — must not throw.
        FakeComponentContext ctx = new FakeComponentContext("overlay");
        new KernelStatePanel().render(ctx, 0f, 0f, 400f, 300f);
        assertTrue("offline placeholder shown",
                drawnText(ctx).stream().anyMatch(t -> t.contains("offline")));
    }

    @Test
    public void degradesWhenSupplierThrows() {
        Backplane.register("kernel.state", (Supplier<Map<String, String>>) () -> {
            throw new IllegalStateException("boom");
        });
        FakeComponentContext ctx = new FakeComponentContext("overlay");
        // A throwing source must be swallowed into the offline placeholder, not propagated.
        new KernelStatePanel().render(ctx, 0f, 0f, 400f, 300f);
        assertTrue("offline placeholder on faulty supplier",
                drawnText(ctx).stream().anyMatch(t -> t.contains("offline")));
    }

    /** Pull the string of every TEXT draw call recorded. */
    private static java.util.List<String> drawnText(FakeComponentContext ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (RecordingDrawContext.Call c : ctx.recording().of(RecordingDrawContext.Op.TEXT)) {
            if (c.extra() instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
