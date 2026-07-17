package net.marcloud.mcp.board.chips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Test;

import net.marcloud.mcp.board.Backplane;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Matrix;

/**
 * Teeth for {@link ChipBridgePort} — the board-side producer that publishes the live chip
 * roster (read) and a toggle-by-id command (write) onto the {@link Backplane} as pure JDK
 * functional values, so the zero-core dwm launcher can drive real chips reflectively.
 *
 * <p>The contract these lock: the roster Supplier projects each live chip to a String map
 * (id/name/category/enabled) fresh on every call; the toggle Function flips a real chip by
 * id and returns its new enabled state (false for an unknown id); both are fault-isolated;
 * and the two are registered under the frozen keys {@code "chip.roster"} / {@code "chip.toggle"}.
 */
public class ChipBridgePortTest {

    @After
    public void tearDown() {
        Backplane.clear();
    }

    private static Matrix<Chip> matrixWith(Chip... chips) {
        Matrix<Chip> m = new Matrix<>();
        for (Chip c : chips) {
            m.add(c);
        }
        return m;
    }

    /** A minimal test chip with an explicit id/name/category (no game touch). */
    private static Chip chip(String id, String name, String category) {
        return new Chip() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String category() {
                return category;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Supplier<List<Map<String, String>>> roster() {
        Object s = Backplane.find(ChipBridgePort.KEY_ROSTER);
        assertNotNull("roster supplier registered", s);
        assertTrue(s instanceof Supplier);
        return (Supplier<List<Map<String, String>>>) s;
    }

    @SuppressWarnings("unchecked")
    private static Function<String, Boolean> toggle() {
        Object f = Backplane.find(ChipBridgePort.KEY_TOGGLE);
        assertNotNull("toggle function registered", f);
        assertTrue(f instanceof Function);
        return (Function<String, Boolean>) f;
    }

    @Test
    public void publishRegistersBothServicesUnderFrozenKeys() {
        ChipBridgePort.publish(matrixWith());
        assertTrue(Backplane.has(ChipBridgePort.KEY_ROSTER));
        assertTrue(Backplane.has(ChipBridgePort.KEY_TOGGLE));
        assertEquals("chip.roster", ChipBridgePort.KEY_ROSTER);
        assertEquals("chip.toggle", ChipBridgePort.KEY_TOGGLE);
    }

    @Test
    public void rosterProjectsEachChipToStringFields() {
        Chip a = chip("Alpha", "Alpha Feature", "Render");
        Chip b = chip("Beta", "Beta Feature", null); // null category -> empty string
        a.setEnabled(true);
        ChipBridgePort.publish(matrixWith(a, b));

        List<Map<String, String>> rows = roster().get();
        assertEquals("one row per chip, source order", 2, rows.size());

        Map<String, String> r0 = rows.get(0);
        assertEquals("Alpha", r0.get("id"));
        assertEquals("Alpha Feature", r0.get("name"));
        assertEquals("Render", r0.get("category"));
        assertEquals("true", r0.get("enabled"));

        Map<String, String> r1 = rows.get(1);
        assertEquals("Beta", r1.get("id"));
        assertEquals("null category becomes empty string", "", r1.get("category"));
        assertEquals("false", r1.get("enabled"));
    }

    @Test
    public void rosterIsLiveAcrossCalls() {
        Chip a = chip("Alpha", "Alpha", "");
        ChipBridgePort.publish(matrixWith(a));
        assertEquals("false", roster().get().get(0).get("enabled"));
        a.setEnabled(true);
        assertEquals("supplier re-reads live state each call",
                "true", roster().get().get(0).get("enabled"));
    }

    @Test
    public void toggleFlipsRealChipAndReturnsNewState() {
        Chip a = chip("Alpha", "Alpha", "");
        assertFalse(a.isEnabled());
        ChipBridgePort.publish(matrixWith(a));

        Boolean now = toggle().apply("Alpha");
        assertEquals("toggle returns the resulting enabled state", Boolean.TRUE, now);
        assertTrue("real chip actually toggled", a.isEnabled());

        Boolean off = toggle().apply("Alpha");
        assertEquals(Boolean.FALSE, off);
        assertFalse(a.isEnabled());
    }

    @Test
    public void toggleUnknownIdIsNoOpFalse() {
        ChipBridgePort.publish(matrixWith(chip("Alpha", "Alpha", "")));
        assertEquals("unknown id is a no-op returning false",
                Boolean.FALSE, toggle().apply("Nope"));
        assertEquals("null id is a no-op returning false",
                Boolean.FALSE, toggle().apply(null));
    }
}
