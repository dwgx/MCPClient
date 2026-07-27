package net.marcloud.mcp.dwm.ui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads live kernel and board state off the Backplane, reflectively, so the UI can show what the
 * client is actually doing.
 *
 * <p><b>Why every call here is reflective.</b> dwm's module contract forbids importing a single
 * {@code core} class — {@code DwmEntryTest} scans the whole module's sources to enforce it — because
 * dwm is a detachable auxiliary with zero security-decision power, and a compile-time edge to the
 * kernel would quietly end that. Reflection is not a workaround for the rule; it IS the seam the
 * rule requires, and the same one {@code BoardClockBridge} uses in the other direction. Both sides
 * publish nothing but {@code Supplier}s of JDK collections precisely so this can stay type-free.
 *
 * <p><b>Absence is normal, not an error.</b> Running without core, or without board, or before
 * either has finished booting, are all ordinary states for a detachable module. Every accessor
 * degrades to an empty result rather than throwing, so a UI page renders "unavailable" instead of
 * taking the render thread down with it. That is the "reflect, miss, degrade" idiom the peer bridges
 * already use.
 *
 * <p><b>Read-only except for one thing.</b> The only write this class can perform is toggling a
 * board chip by id, through the command board itself published for the purpose. It cannot reach a
 * privilege, a capability, or a patch — those are the kernel's, and dwm having no way to touch them
 * is the whole point of the contract.
 */
public final class LiveState {

    /** Board's neutral registry. Named as a string so dwm links without board present. */
    private static final String BACKPLANE = "net.marcloud.mcp.board.Backplane";

    /** Keys the peers publish under. Hardcoded on both sides deliberately — they are the ABI. */
    private static final String KEY_KERNEL_STATE = "kernel.state";
    private static final String KEY_CHIP_ROSTER = "chip.roster";
    private static final String KEY_CHIP_TOGGLE = "chip.toggle";

    private LiveState() {
    }

    /**
     * The kernel's 7-layer security posture as ordered {@code label -> value} rows.
     *
     * <p>Recomputed on every call by core, so a runtime privilege change shows up the next time the
     * UI reads it. Empty when core is absent or has not published yet.
     */
    public static Map<String, String> kernelState() {
        Object supplier = lookup(KEY_KERNEL_STATE);
        if (supplier == null) {
            return Collections.emptyMap();
        }
        try {
            Method get = supplier.getClass().getMethod("get");
            get.setAccessible(true);
            Object value = get.invoke(supplier);
            if (!(value instanceof Map)) {
                return Collections.emptyMap();
            }
            // Copied into our own map: the supplier's result is core's, and holding a reference to
            // it across frames would be reading a structure we do not own the lifetime of.
            Map<String, String> rows = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                rows.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return rows;
        } catch (Throwable t) {
            // A changed signature or a source that faulted mid-snapshot. The UI shows nothing;
            // it must never propagate onto the render thread.
            return Collections.emptyMap();
        }
    }

    /**
     * The live board chip roster: one map per chip with {@code id}, {@code name},
     * {@code category} and {@code enabled} ("true"/"false").
     *
     * <p>Field names are board's, not ours — {@code ChipBridgePort} projects each chip to exactly
     * these four string keys so nothing typed has to cross the seam.
     */
    public static List<Map<String, String>> chipRoster() {
        Object supplier = lookup(KEY_CHIP_ROSTER);
        if (supplier == null) {
            return Collections.emptyList();
        }
        try {
            Method get = supplier.getClass().getMethod("get");
            get.setAccessible(true);
            Object value = get.invoke(supplier);
            if (!(value instanceof List)) {
                return Collections.emptyList();
            }
            List<Map<String, String>> chips = new ArrayList<>();
            for (Object row : (List<?>) value) {
                if (!(row instanceof Map)) {
                    continue;
                }
                Map<String, String> chip = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) row).entrySet()) {
                    chip.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                chips.add(chip);
            }
            return chips;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * Toggle the board chip with {@code id}. Returns its state afterwards, or false if the toggle
     * could not be performed at all.
     *
     * <p>The command is board's own, and board marshals it onto the game thread inside the port —
     * which matters because a chip's enable can touch live game state, and dwm calls this from the
     * render thread during input handling. Doing that marshalling here would duplicate logic that
     * already exists on the side that owns the chips.
     *
     * <p>Deliberately narrow: an id and a boolean back. dwm cannot enumerate privileges, arm a
     * patch, or reach anything the kernel guards, because the only write the Backplane offers it is
     * this one.
     */
    public static boolean toggleChip(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        Object function = lookup(KEY_CHIP_TOGGLE);
        if (function == null) {
            return false;
        }
        try {
            Method apply = function.getClass().getMethod("apply", Object.class);
            apply.setAccessible(true);
            Object result = apply.invoke(function, id);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when core has published its kernel-state facade. Diagnostic, and for empty-state UI. */
    public static boolean hasKernelState() {
        return lookup(KEY_KERNEL_STATE) != null;
    }

    /** True when board has published its chip roster. */
    public static boolean hasChipRoster() {
        return lookup(KEY_CHIP_ROSTER) != null;
    }

    /**
     * A service off the Backplane, or null when board is absent or nothing is registered.
     *
     * <p>Resolved fresh each time rather than cached: the peers publish during their own boot, which
     * can complete after the UI has already been opened once, and a cached null would make the
     * panels permanently empty for the rest of the run.
     */
    private static Object lookup(String key) {
        try {
            Class<?> backplane = Class.forName(BACKPLANE);
            Method find = backplane.getMethod("find", String.class);
            return find.invoke(null, key);
        } catch (Throwable t) {
            // board not on the classpath, or the registry moved. Either way: no live state.
            return null;
        }
    }
}
