package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Phase-2 regression tests for the frozen {@link Chip} lifecycle contract:
 * the exact {@code onLoad -> onEnable -> onDisable -> onUnload} transition
 * sequence (driven by {@link Matrix} for load/unload and {@link Chip#toggle()}
 * / {@link Chip#setEnabled(boolean)} for enable/disable), change-only firing,
 * toggle return values, and fault isolation of the callback hooks.
 *
 * <p>These tests record the ordered sequence of hook invocations, so they fail
 * on any regression that reorders, drops, or double-fires a lifecycle hook.
 */
public class ChipLifecycleTest {

    /** A chip that appends each hook to a shared, ordered trace. */
    static final class TracingChip extends Chip {
        final List<String> events;
        final String id;

        TracingChip(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        protected void onLoad() {
            events.add("load");
        }

        @Override
        protected void onEnable() {
            events.add("enable");
        }

        @Override
        protected void onDisable() {
            events.add("disable");
        }

        @Override
        protected void onUnload() {
            events.add("unload");
        }
    }

    @Test
    public void fullLifecycleTransitionSequence() {
        List<String> events = new ArrayList<String>();
        Matrix<TracingChip> matrix = new Matrix<TracingChip>();

        TracingChip chip = matrix.add(new TracingChip("full", events)); // onLoad
        chip.setEnabled(true);   // onEnable
        chip.setEnabled(false);  // onDisable
        matrix.remove(chip);     // onUnload (already disabled -> no extra disable)

        assertEquals(4, events.size());
        assertEquals("load", events.get(0));
        assertEquals("enable", events.get(1));
        assertEquals("disable", events.get(2));
        assertEquals("unload", events.get(3));
    }

    @Test
    public void removeWhileEnabledDisablesThenUnloadsInOrder() {
        List<String> events = new ArrayList<String>();
        Matrix<TracingChip> matrix = new Matrix<TracingChip>();

        TracingChip chip = matrix.add(new TracingChip("hot", events)); // load
        chip.setEnabled(true);                                          // enable
        matrix.remove(chip);                                            // disable, then unload

        assertEquals("load", events.get(0));
        assertEquals("enable", events.get(1));
        assertEquals("disable", events.get(2));
        assertEquals("unload", events.get(3));
        assertEquals(4, events.size());
        assertFalse(chip.isEnabled());
    }

    @Test
    public void enableAndDisableFireOnlyOnActualStateChange() {
        List<String> events = new ArrayList<String>();
        TracingChip chip = new TracingChip("idem", events);

        chip.setEnabled(false); // already disabled -> no callback
        assertEquals(0, events.size());

        chip.setEnabled(true);  // enable
        chip.setEnabled(true);  // no-op, no second enable
        chip.setEnabled(false); // disable
        chip.setEnabled(false); // no-op, no second disable

        assertEquals(2, events.size());
        assertEquals("enable", events.get(0));
        assertEquals("disable", events.get(1));
    }

    @Test
    public void toggleFlipsStateAndReturnsNewValue() {
        List<String> events = new ArrayList<String>();
        TracingChip chip = new TracingChip("tog", events);

        assertFalse(chip.isEnabled());
        assertTrue(chip.toggle());   // -> enabled, returns true
        assertTrue(chip.isEnabled());
        assertFalse(chip.toggle());  // -> disabled, returns false
        assertFalse(chip.isEnabled());

        assertEquals(2, events.size());
        assertEquals("enable", events.get(0));
        assertEquals("disable", events.get(1));
    }

    @Test
    public void minimalChipUsesClassNameDefaultsForIdAndName() {
        Chip anon = new Chip() {
        };
        // Anonymous class simple name is empty; a named subclass reports its name.
        TracingChip named = new TracingChip("id-x", new ArrayList<String>());
        assertEquals("id-x", named.id());
        assertEquals("id-x", named.name()); // name() defaults to id()
        // default category is null; default pin is NO_PIN
        assertEquals(null, anon.category());
        assertEquals(Chip.NO_PIN, anon.pin());
    }

    @Test
    public void enableCallbackFaultDoesNotCorruptToggledState() {
        // A chip whose onEnable throws must still end up "enabled" (state flips
        // before the fault-isolated callback runs).
        Chip faulty = new Chip() {
            @Override
            protected void onEnable() {
                throw new RuntimeException("enable boom");
            }
        };
        faulty.setEnabled(true);
        assertTrue(faulty.isEnabled());
        // and it can still be toggled back off cleanly
        assertFalse(faulty.toggle());
        assertFalse(faulty.isEnabled());
    }

    @Test
    public void loadAndUnloadFaultsAreIsolatedByMatrix() {
        // onLoad / onUnload throwing must not break Matrix.add / remove.
        final int[] loadHits = {0};
        Matrix<Chip> matrix = new Matrix<Chip>();
        Chip faulty = new Chip() {
            @Override
            public String id() {
                return "faulty-lifecycle";
            }

            @Override
            protected void onLoad() {
                loadHits[0]++;
                throw new RuntimeException("load boom");
            }

            @Override
            protected void onUnload() {
                throw new RuntimeException("unload boom");
            }
        };
        // add must return the chip despite onLoad throwing
        assertTrue(matrix.add(faulty) == faulty);
        assertEquals(1, loadHits[0]);
        assertTrue(matrix.contains("faulty-lifecycle"));
        // remove must succeed despite onUnload throwing
        assertTrue(matrix.remove(faulty));
        assertFalse(matrix.contains("faulty-lifecycle"));
    }

    @Test
    public void pinAttributeIsIndependentOfLifecycle() {
        TracingChip chip = new TracingChip("pinned", new ArrayList<String>());
        assertEquals(Chip.NO_PIN, chip.pin());
        chip.setPin(57); // e.g. some key code
        assertEquals(57, chip.pin());
        chip.setEnabled(true);
        chip.setEnabled(false);
        // toggling did not disturb the pin binding
        assertEquals(57, chip.pin());
        chip.setPin(Chip.NO_PIN);
        assertEquals(Chip.NO_PIN, chip.pin());
    }
}
