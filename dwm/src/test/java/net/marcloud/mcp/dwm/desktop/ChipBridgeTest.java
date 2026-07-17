package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Test;

import net.marcloud.mcp.board.Backplane;

/**
 * Teeth for the dwm-side {@link ChipBridge} — the consumer that reflectively reads the
 * board's {@code "chip.roster"} supplier into {@link SoftwareView}s and routes a toggle
 * through the {@code "chip.toggle"} function. Mirrors the {@code KernelStatePanel} read
 * precedent's degradation discipline: absent / wrong-type / throwing services degrade to a
 * benign default (empty roster, no-op toggle) and never propagate into the render/input
 * thread.
 *
 * <p>Drives the REAL {@link Backplane} (board is on the test classpath), registering the JDK
 * functional values a live board would publish, so these are non-vacuous end-to-end checks.
 */
public class ChipBridgeTest {

    @After
    public void tearDown() {
        Backplane.clear();
    }

    private static Map<String, String> row(String id, String name, String cat, boolean enabled) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("category", cat);
        m.put("enabled", Boolean.toString(enabled));
        return m;
    }

    private static void publishRoster(Supplier<List<Map<String, String>>> s) {
        Backplane.register(ChipBridge.KEY_ROSTER, s);
    }

    @Test
    public void rosterReadsRegisteredSupplierIntoSoftwareViews() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("Alpha", "Alpha Feature", "Render", true));
        rows.add(row("Beta", "Beta Feature", "", false));
        publishRoster(() -> rows);

        ChipBridge bridge = new ChipBridge();
        List<SoftwareView> views = bridge.roster();
        assertEquals(2, views.size());
        assertEquals("Alpha", views.get(0).chipId());
        assertEquals("Alpha Feature", views.get(0).displayName());
        assertEquals("Render", views.get(0).category());
        assertTrue("enabled parsed from string", views.get(0).enabled());
        assertEquals("Beta", views.get(1).chipId());
        assertTrue("disabled parsed from string", !views.get(1).enabled());
    }

    @Test
    public void rosterDegradesToEmptyWhenNothingPublished() {
        ChipBridge bridge = new ChipBridge();
        assertTrue("no board service -> empty roster, not a crash", bridge.roster().isEmpty());
    }

    @Test
    public void rosterDegradesWhenSupplierThrows() {
        publishRoster(() -> {
            throw new RuntimeException("boom");
        });
        ChipBridge bridge = new ChipBridge();
        assertTrue("throwing supplier -> empty roster", bridge.roster().isEmpty());
    }

    @Test
    public void rosterDegradesWhenServiceIsWrongType() {
        Backplane.register(ChipBridge.KEY_ROSTER, "not a supplier");
        ChipBridge bridge = new ChipBridge();
        assertTrue("wrong-type service -> empty roster", bridge.roster().isEmpty());
    }

    @Test
    public void toggleRoutesThroughRegisteredFunction() {
        AtomicReference<String> seen = new AtomicReference<>();
        Function<String, Boolean> fn = id -> {
            seen.set(id);
            return Boolean.TRUE;
        };
        Backplane.register(ChipBridge.KEY_TOGGLE, fn);

        ChipBridge bridge = new ChipBridge();
        bridge.toggle("Alpha");
        assertEquals("toggle routed the chip id to the board function", "Alpha", seen.get());
    }

    @Test
    public void toggleIsNoOpWhenNoFunctionOrThrows() {
        ChipBridge bridge = new ChipBridge();
        bridge.toggle("Alpha"); // no function registered — must not throw

        Backplane.register(ChipBridge.KEY_TOGGLE, (Function<String, Boolean>) id -> {
            throw new RuntimeException("boom");
        });
        bridge.toggle("Alpha"); // throwing function — must be swallowed
        // reaching here without an exception is the assertion
        assertTrue(true);
    }
}
