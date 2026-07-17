package net.marcloud.mcp.dwm.desktop;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PHASE 1 SCAFFOLD — a fixed fake software list so the launcher layout can be built, seen,
 * and clicked before the live board chip bridge lands. Each entry has a mutable enabled
 * flag so clicking a row/tile visibly flips its state dot (proving the click→toggle path).
 * DELETED / replaced by the real {@code ChipBridge} in phase 2.
 */
public final class FakeSoftware {

    private FakeSoftware() {
    }

    /** chipId → current enabled state (mutable so phase-1 clicks flip it). */
    private static final Map<String, Boolean> STATE = new ConcurrentHashMap<>();

    private record Def(String id, String name, String category) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("kernel-state", "Kernel State", "System"),
            new Def("esp", "ESP", "Render"),
            new Def("tracers", "Tracers", "Render"),
            new Def("fullbright", "Fullbright", "Render"),
            new Def("auto-sprint", "Auto Sprint", "Movement"),
            new Def("no-fall", "No Fall", "Movement"),
            new Def("speed", "Speed", "Movement"),
            new Def("auto-tool", "Auto Tool", "Player"),
            new Def("fast-place", "Fast Place", "Player"),
            new Def("packet-log", "Packet Log", "Network"),
            new Def("chat-filter", "Chat Filter", "Network"),
            new Def("hud-editor", "HUD Editor", "Interface"));

    /** Build the current fake software views (enabled flags reflect prior toggles). */
    public static List<SoftwareView> views() {
        return DEFS.stream()
                .map(def -> new SoftwareView(def.id(), def.name(), def.category(), 0,
                        STATE.getOrDefault(def.id(), Boolean.FALSE)))
                .toList();
    }

    /** Flip a fake software's enabled state (phase-1 click target). */
    public static void toggle(String chipId) {
        STATE.merge(chipId, Boolean.TRUE, (old, ignored) -> !old);
    }
}
