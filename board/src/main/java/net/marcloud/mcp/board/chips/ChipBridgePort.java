package net.marcloud.mcp.board.chips;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import net.marcloud.mcp.board.Backplane;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Matrix;

/**
 * The board-side producer of the ChipBridge seam: it publishes the live feature roster
 * (READ) and a toggle-by-id command (WRITE) onto the {@link Backplane} as pure JDK
 * functional values, so the zero-core {@code dwm} launcher can enumerate and drive REAL
 * chips reflectively — mirroring exactly how {@code KernelStatePanel} reads
 * {@code "kernel.state"}, but adding the first WRITE across the seam (owner-authorized
 * 2026-07-17, hard-scoped to board's public frozen {@link Chip} API — never a generic
 * reflective mutator).
 *
 * <p>Only pure JDK types cross the boundary: a {@code Supplier<List<Map<String,String>>>}
 * for the roster (each chip projected to {@code id/name/category/enabled} strings, read
 * fresh every call so the launcher is live) and a {@code Function<String,Boolean>} for the
 * toggle (flips the chip by id, returns its new enabled state; false for an unknown id).
 * Neither exposes a {@link Chip} or {@link Matrix} type, so the consumer needs none of them.
 *
 * <p><b>Threading.</b> {@link Chip#setEnabled} runs {@code onEnable}/{@code onDisable} with
 * real game side effects and {@link Matrix} is not thread-safe, so the toggle is marshalled
 * onto the MC game thread here (the producer side) via {@code Minecraft.addScheduledTask},
 * keeping the launcher thread-ignorant. Headless (no game present), it runs inline — the
 * standalone/test path. Every path is fault-isolated; a fault degrades to a no-op, never a
 * thrown exception into the render/input thread.
 */
public final class ChipBridgePort {

    /** Backplane key for the live chip roster (read). Hardcoded on the dwm side too. */
    public static final String KEY_ROSTER = "chip.roster";
    /** Backplane key for the toggle-by-id command (write). Hardcoded on the dwm side too. */
    public static final String KEY_TOGGLE = "chip.toggle";

    private static final String MC_CLASS = "net.minecraft.client.Minecraft";
    /** Bounded wait for an enqueued game-thread toggle (matches core GameBridge's 5s default). */
    private static final long TOGGLE_TIMEOUT_MS = 5000L;

    private ChipBridgePort() {
    }

    /**
     * Register the roster supplier and toggle function for {@code matrix} onto the
     * {@link Backplane}. Idempotent-friendly: a second call replaces the services bound to
     * the same live matrix. Called from {@code Board.init()} after the roster is installed.
     *
     * @param matrix the live feature matrix to enumerate and toggle (must not be null)
     */
    public static void publish(Matrix<Chip> matrix) {
        if (matrix == null) {
            throw new IllegalArgumentException("matrix must not be null");
        }
        Supplier<List<Map<String, String>>> roster = () -> projectRoster(matrix);
        Function<String, Boolean> toggle = id -> toggleById(matrix, id);
        Backplane.register(KEY_ROSTER, roster);
        Backplane.register(KEY_TOGGLE, toggle);
    }

    /**
     * Project every chip in {@code matrix} to a pure-String map, source order. Each field
     * read is guarded so one misbehaving chip degrades to a partial row rather than throwing
     * the whole list — the producer-side fault isolation the KernelStatePort precedent uses.
     */
    private static List<Map<String, String>> projectRoster(Matrix<Chip> matrix) {
        List<Map<String, String>> out = new ArrayList<>();
        List<Chip> chips;
        try {
            chips = matrix.all();
        } catch (Throwable t) {
            // matrix.all() copies a non-thread-safe LinkedHashMap; a concurrent structural
            // mutation (add/remove/clear on the game thread during init/shutdown) can throw
            // ConcurrentModificationException. Degrading this one frame to an empty roster is
            // INTENTIONAL — the launcher re-reads next frame; never propagate into render.
            return out;
        }
        for (Chip c : chips) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", safe(() -> c.id(), ""));
            row.put("name", safe(() -> c.name(), ""));
            row.put("category", safe(() -> c.category(), ""));
            row.put("enabled", safe(() -> Boolean.toString(c.isEnabled()), "false"));
            out.add(row);
        }
        return out;
    }

    /**
     * Flip the chip with {@code id} and return its new enabled state; {@code false} for a
     * null/unknown id or any fault. The mutation is marshalled onto the MC game thread
     * (chip lifecycle touches live, non-thread-safe game state); headless it runs inline.
     */
    private static boolean toggleById(Matrix<Chip> matrix, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        Callable<Boolean> task = () -> {
            Chip c = matrix.byId(id);
            return c == null ? Boolean.FALSE : Boolean.valueOf(c.toggle());
        };
        try {
            Boolean result = onGameThread(task);
            return result != null && result;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Run {@code task} on the MC game thread and return its result. Reflectively uses
     * {@code Minecraft.getMinecraft().addScheduledTask(Callable)} (vanilla 1.8.9) which runs
     * inline when already on the game thread (so a same-thread overlay resolves instantly);
     * when no game is present (headless/tests) or the mapping has drifted, the task is run
     * inline on the caller. Never throws for a missing game — that is the standalone path.
     *
     * <p>When the task is enqueued (caller is off the game thread), the wait is BOUNDED to
     * {@link #TOGGLE_TIMEOUT_MS} (mirroring the core {@code GameBridge} 5s default) so a
     * stalled/frozen game thread can never hang the caller forever — the resulting
     * {@code TimeoutException} propagates to {@link #toggleById}'s {@code catch} and degrades
     * to a no-op {@code false} rather than blocking the launcher's thread indefinitely.
     */
    private static Boolean onGameThread(Callable<Boolean> task) throws Exception {
        Object mc = minecraftOrNull();
        if (mc == null) {
            return task.call(); // headless: run inline
        }
        try {
            // addScheduledTask(Callable) returns a Future; runs inline if already on the MC
            // thread, else enqueues onto the game loop. Block for the boolean result, but
            // only up to TOGGLE_TIMEOUT_MS so a frozen game thread cannot hang the caller.
            Object future = mc.getClass().getMethod("addScheduledTask", Callable.class).invoke(mc, task);
            Object value = future.getClass().getMethod("get", long.class, TimeUnit.class)
                    .invoke(future, TOGGLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return value instanceof Boolean ? (Boolean) value : Boolean.FALSE;
        } catch (NoSuchMethodException e) {
            return task.call(); // mapping drift: degrade to inline
        }
    }

    /** {@code Minecraft.getMinecraft()} reflectively, or null when headless / mapping drift. */
    private static Object minecraftOrNull() {
        try {
            return Class.forName(MC_CLASS).getMethod("getMinecraft").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Evaluate {@code get}, returning {@code fallback} on null result or any throwable. */
    private static String safe(Callable<String> get, String fallback) {
        try {
            String v = get.call();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
