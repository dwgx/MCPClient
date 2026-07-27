package net.marcloud.mcp.dwm.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import net.marcloud.mcp.board.Backplane;

import org.junit.After;
import org.junit.Test;

/**
 * Guards the reflective seam dwm reads live kernel and board state through.
 *
 * <p>Two properties matter here and neither is about happy-path plumbing.
 *
 * <p><b>Absence must be a normal answer.</b> Running without core, without board, or before either
 * has booted are all ordinary states for a detachable auxiliary, and these accessors are called from
 * QML bindings evaluated during layout on the render thread. One thrown exception there faults the
 * frame; the module contract says dwm may never be able to take the client down.
 *
 * <p><b>The seam must stay reflective.</b> The temptation is to import the port classes and get
 * compile-time safety, which would give dwm a hard edge to the kernel and end its zero-security-power
 * status. {@code DwmEntryTest} already scans for {@code import net.marcloud.mcp.core}; this file adds
 * the behavioural half by driving the seam through a Backplane populated with nothing but JDK types,
 * exactly as a real core would.
 *
 * <p>Board is a test dependency here, so a real {@link Backplane} can be used rather than a stand-in
 * — the registry is the actual thing dwm looks into at runtime.
 */
public final class LiveStateTest {

    @After
    public void clearRegistry() {
        // The Backplane is process-global, so a leftover registration would leak into the next test
        // and make an absence assertion pass for the wrong reason.
        Backplane.clear();
    }

    /** With nothing registered, every accessor must answer emptily rather than throw. */
    @Test
    public void everythingDegradesWhenNothingIsPublished() {
        Backplane.clear();

        assertTrue("kernel state must be empty, not null or thrown",
            LiveState.kernelState().isEmpty());
        assertTrue("the chip roster must be empty", LiveState.chipRoster().isEmpty());
        assertFalse("and the presence checks must say so", LiveState.hasKernelState());
        assertFalse(LiveState.hasChipRoster());
        assertFalse("a toggle with nothing to toggle must report failure, not throw",
            LiveState.toggleChip("anything"));
    }

    /** Kernel rows must come through in order, with the values the supplier produced. */
    @Test
    public void kernelStateIsReadThroughTheBackplane() {
        Map<String, String> posture = new LinkedHashMap<>();
        posture.put("Clearance", "R-2");
        posture.put("Integrity", "high");
        posture.put("Armed patches", "2 of 3");
        Supplier<Map<String, String>> supplier = () -> posture;
        Backplane.register("kernel.state", supplier);

        assertTrue(LiveState.hasKernelState());
        Map<String, String> read = LiveState.kernelState();
        assertEquals("every row must arrive", 3, read.size());
        assertEquals("R-2", read.get("Clearance"));
        assertEquals("2 of 3", read.get("Armed patches"));
        assertEquals("order must be preserved — core emits the rows deliberately",
            Arrays.asList("Clearance", "Integrity", "Armed patches"),
            new java.util.ArrayList<>(read.keySet()));
    }

    /**
     * The snapshot must be re-read, not cached.
     *
     * <p>Core recomputes its posture per call so a runtime privilege change is visible; caching on
     * this side would silently undo that and the UI would show a startup snapshot forever.
     */
    @Test
    public void kernelStateIsLiveNotCached() {
        Map<String, String> posture = new LinkedHashMap<>();
        posture.put("Clearance", "R-2");
        Backplane.register("kernel.state", (Supplier<Map<String, String>>) () -> posture);

        assertEquals("R-2", LiveState.kernelState().get("Clearance"));
        posture.put("Clearance", "R-4");
        assertEquals("a later read must see the new value", "R-4",
            LiveState.kernelState().get("Clearance"));
    }

    /** The returned map must be ours, so a caller cannot mutate core's structure through it. */
    @Test
    public void theReturnedMapIsACopy() {
        Map<String, String> posture = new LinkedHashMap<>();
        posture.put("Clearance", "R-2");
        Backplane.register("kernel.state", (Supplier<Map<String, String>>) () -> posture);

        LiveState.kernelState().put("Clearance", "tampered");
        assertEquals("the supplier's own map must be untouched", "R-2", posture.get("Clearance"));
    }

    /** The chip roster arrives as board projects it: four string fields per chip. */
    @Test
    public void chipRosterIsReadThroughTheBackplane() {
        List<Map<String, String>> roster = Arrays.asList(
            chip("fullbright", "Fullbright", "render", "false"),
            chip("coords", "Coordinates", "hud", "true"));
        Backplane.register("chip.roster", (Supplier<List<Map<String, String>>>) () -> roster);

        assertTrue(LiveState.hasChipRoster());
        List<Map<String, String>> read = LiveState.chipRoster();
        assertEquals(2, read.size());
        assertEquals("Fullbright", read.get(0).get("name"));
        assertEquals("the board's own string form must survive unconverted",
            "true", read.get(1).get("enabled"));
    }

    /** A toggle must reach board's command and return its verdict. */
    @Test
    public void toggleReachesTheBoardsCommand() {
        final List<String> calls = new java.util.ArrayList<>();
        Function<String, Boolean> toggle = id -> {
            calls.add(id);
            return "fullbright".equals(id);
        };
        Backplane.register("chip.toggle", toggle);

        assertTrue("board's answer must be returned as-is", LiveState.toggleChip("fullbright"));
        assertFalse("including a refusal", LiveState.toggleChip("nope"));
        assertEquals("and the id must be passed through unchanged",
            Arrays.asList("fullbright", "nope"), calls);
    }

    /** A blank id must never reach board. */
    @Test
    public void aBlankIdIsRejectedLocally() {
        final List<String> calls = new java.util.ArrayList<>();
        Backplane.register("chip.toggle", (Function<String, Boolean>) id -> {
            calls.add(id);
            return true;
        });

        assertFalse(LiveState.toggleChip(null));
        assertFalse(LiveState.toggleChip(""));
        assertTrue("neither may be forwarded", calls.isEmpty());
    }

    /**
     * A supplier that throws must not propagate.
     *
     * <p>Core guards each row internally, but "the source faulted entirely" is still reachable — and
     * this runs on the render thread, so the only acceptable outcome is an empty result.
     */
    @Test
    public void aFaultingSupplierYieldsEmptyRatherThanThrowing() {
        Backplane.register("kernel.state", (Supplier<Map<String, String>>) () -> {
            throw new IllegalStateException("posture source exploded");
        });
        Backplane.register("chip.roster", (Supplier<List<Map<String, String>>>) () -> {
            throw new IllegalStateException("roster exploded");
        });
        Backplane.register("chip.toggle", (Function<String, Boolean>) id -> {
            throw new IllegalStateException("toggle exploded");
        });

        assertTrue("a faulting posture source must read as empty",
            LiveState.kernelState().isEmpty());
        assertTrue("a faulting roster must read as empty", LiveState.chipRoster().isEmpty());
        assertFalse("a faulting toggle must report failure", LiveState.toggleChip("x"));
    }

    /**
     * A service of the wrong shape must be ignored, not cast blindly.
     *
     * <p>The keys are a string ABI shared by three modules that do not import each other, so a
     * mismatch is a realistic failure rather than a hypothetical one.
     */
    @Test
    public void aServiceOfTheWrongTypeIsIgnored() {
        Backplane.register("kernel.state", "not a supplier");
        Backplane.register("chip.roster", 42);
        Backplane.register("chip.toggle", "not a function");

        assertTrue(LiveState.kernelState().isEmpty());
        assertTrue(LiveState.chipRoster().isEmpty());
        assertFalse(LiveState.toggleChip("x"));
    }

    /** A supplier returning the wrong payload type must also be ignored. */
    @Test
    public void aSupplierReturningTheWrongPayloadIsIgnored() {
        Backplane.register("kernel.state", (Supplier<Object>) () -> "a string, not a map");
        Backplane.register("chip.roster", (Supplier<Object>) () -> "a string, not a list");

        assertTrue(LiveState.kernelState().isEmpty());
        assertTrue(LiveState.chipRoster().isEmpty());
    }

    private static Map<String, String> chip(String id, String name, String cat, String enabled) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("category", cat);
        m.put("enabled", enabled);
        return m;
    }
}
