package net.marcloud.mcp.dwm.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import net.marcloud.mcp.board.Backplane;

/**
 * The dwm-side consumer of the ChipBridge seam: reflectively reads the board's live chip
 * roster into {@link SoftwareView}s (READ) and routes a click to the board's toggle-by-id
 * command (WRITE), so the Desktop launcher shows and drives REAL board chips instead of the
 * {@link FakeSoftware} placeholder. This is phase 2 of the launcher.
 *
 * <p>Follows the {@code KernelStatePanel} precedent exactly: only pure JDK values cross the
 * {@link Backplane} (a {@code Supplier<List<Map<String,String>>>} roster and a
 * {@code Function<String,Boolean>} toggle), the string keys are hardcoded here (dwm imports
 * board but not core, and cannot reference the producer-side key constants), every lookup is
 * {@code instanceof}-guarded and wrapped in {@code try/catch(Throwable)}, and any
 * absent / wrong-type / throwing service degrades to a benign default — an empty roster or a
 * no-op toggle — so the render/input thread never breaks when board is offline.
 *
 * <p><b>dwm holds no security privilege</b> and the write is owner-authorized and
 * hard-scoped: this class only calls the board-published toggle command, which itself is
 * limited to the public frozen {@code Chip} API and marshals to the game thread on the
 * producer side. dwm stays thread-ignorant and never reflects board internals directly.
 */
public final class ChipBridge {

    /** Backplane key for the live chip roster (read). Mirrors {@code ChipBridgePort.KEY_ROSTER}. */
    public static final String KEY_ROSTER = "chip.roster";
    /** Backplane key for the toggle-by-id command (write). Mirrors {@code ChipBridgePort.KEY_TOGGLE}. */
    public static final String KEY_TOGGLE = "chip.toggle";

    /**
     * Read the live roster into {@link SoftwareView}s. Returns an empty list (never null)
     * when board is absent, the service is the wrong type, or the supplier throws.
     */
    @SuppressWarnings("unchecked")
    public List<SoftwareView> roster() {
        List<SoftwareView> out = new ArrayList<>();
        try {
            Object registered = Backplane.find(KEY_ROSTER);
            if (registered instanceof Supplier<?> supplier) {
                Object result = supplier.get();
                if (result instanceof List<?> rows) {
                    for (Object element : rows) {
                        if (element instanceof Map<?, ?> row) {
                            out.add(viewOf(row));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            return new ArrayList<>(); // board offline / faulting — degrade to empty
        }
        return out;
    }

    /**
     * Route a toggle for {@code chipId} to the board's toggle command. Silent no-op when the
     * command is absent, the wrong type, or throws — a dead button, never a crash. The result
     * is intentionally ignored: the UI reflects the new state from the next {@link #roster()}
     * read.
     *
     * <p>Note on blocking: the board-side command marshals the real chip toggle onto the game
     * thread. When this call runs ON the game thread (the usual in-process overlay case) it
     * resolves inline and returns at once; when it runs off-thread the producer bounds its
     * wait (~5s) so a stalled game thread degrades to a no-op rather than hanging here forever.
     */
    public void toggle(String chipId) {
        if (chipId == null || chipId.isEmpty()) {
            return;
        }
        try {
            Object registered = Backplane.find(KEY_TOGGLE);
            if (registered instanceof Function<?, ?> fn) {
                ((Function<String, Boolean>) fn).apply(chipId);
            }
        } catch (Throwable t) {
            // command absent / faulting — no-op
        }
    }

    /** True when the board roster service is currently published (for offline messaging). */
    public boolean isOnline() {
        try {
            return Backplane.find(KEY_ROSTER) instanceof Supplier<?>;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Project one roster row (String kv) into a {@link SoftwareView}, defensively. */
    private static SoftwareView viewOf(Map<?, ?> row) {
        String id = str(row, "id");
        String name = str(row, "name");
        String category = str(row, "category");
        boolean enabled = "true".equalsIgnoreCase(str(row, "enabled"));
        return new SoftwareView(id, name, category, 0, enabled);
    }

    private static String str(Map<?, ?> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
