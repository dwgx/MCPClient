package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.1 — the structured world-observation snapshot: Self / LocalGrid /
 * Entities / Inventory / Target(ray) / Env, stamped with the {@link
 * net.marcloud.mcp.core.ke.GameClock} tickId so diffs (W.7) and the PHASE-T
 * timeline share one clock. Immutable and reference-free; serialized via
 * {@link WorldViewJson} (the dep-free Json writer only handles Map/List/scalars).
 */
public record WorldView(boolean present, long tickId, String profile,
                        SelfView self, LocalGrid grid, List<EntityView> entities,
                        InventoryView inventory, TargetView target, EnvView env) {

    /** The "not in world" snapshot (mirrors Surroundings.absent()). */
    public static WorldView absent() {
        return new WorldView(false, 0L, null, null, null, List.of(), null, null, null);
    }
}
