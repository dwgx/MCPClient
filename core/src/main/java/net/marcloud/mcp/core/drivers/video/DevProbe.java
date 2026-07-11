package net.marcloud.mcp.core.drivers.video;

import java.util.LinkedHashMap;
import java.util.Map;

import net.marcloud.mcp.core.GameAccess;

/**
 * The aggregation core behind the {@code dev_probe} MCP tool: one call → a
 * structured diagnostic of the LIVE game — connection/world presence, GL context,
 * and (when wired) player/world/seam sections. It is the first rung across the
 * "live gap": headless tests prove logic; dev_probe proves what the RUNNING game
 * actually is, and carries the GL section KI-1 needs.
 *
 * <p><b>Threading:</b> {@link #capture} reads live game + GL state and MUST run on
 * the game thread — the {@code DevTools} MCP wrapper marshals via
 * {@code GameBridge.onGameThread(...)}. Every section degrades to a small
 * "absent" map instead of throwing when the game (or a subsystem) is not up, so a
 * probe against a not-yet-connected client returns a well-formed JSON skeleton
 * rather than an error.
 *
 * <p>Pure aggregation with injected suppliers so it is unit-testable headless
 * (no live game, no GL context): the game-absent path returns a stable shape.
 */
public final class DevProbe {

    private DevProbe() {
    }

    /**
     * Build the diagnostic map from the given game façade. Safe to call with a
     * game that is not connected / not in a world — those sections report absent.
     * The GL section is captured directly ({@link GlContextProbe#capture()}); on a
     * headless / off-render-thread caller it reports "no GL context".
     *
     * @param game the live-game façade (may report not-connected/absent)
     * @return an insertion-ordered map ready to serialize to JSON
     */
    public static Map<String, Object> capture(GameAccess game) {
        Map<String, Object> out = new LinkedHashMap<>();

        // --- game: liveness / connection / world presence ---
        Map<String, Object> g = new LinkedHashMap<>();
        boolean up = false;
        try {
            up = game != null && game.mc() != null;
        } catch (Throwable t) {
            up = false;
        }
        g.put("up", up);
        g.put("connected", safeBool(() -> game != null && game.isConnected()));
        g.put("inWorld", safeBool(() -> game != null && game.isInWorld()));
        out.put("game", g);

        // --- gl: the live GL context (KI-1 relevant + real-GPU proof) ---
        out.put("gl", glSection());

        return out;
    }

    private static Map<String, Object> glSection() {
        GlContextProbe gl = GlContextProbe.capture();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", gl.present());
        if (gl.present()) {
            m.put("version", gl.version());
            m.put("vendor", gl.vendor());
            m.put("renderer", gl.renderer());
            m.put("shadingLanguage", gl.shadingLanguage());
            m.put("profileMask", gl.profileMask());
            m.put("coreProfile", gl.coreProfile());
            m.put("compatibilityProfile", gl.compatibilityProfile());
        }
        m.put("note", gl.note());
        return m;
    }

    private static boolean safeBool(BoolSupplier s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return false;
        }
    }

    @FunctionalInterface
    private interface BoolSupplier {
        boolean get();
    }
}
