package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.1 — the structured world-observation snapshot: Self / LocalGrid /
 * Entities / Inventory / Target(ray) / Env, stamped with the {@link
 * net.marcloud.mcp.core.ke.GameClock} tickId so diffs (W.7) and the PHASE-T
 * timeline share one clock. Immutable and reference-free; serialized via
 * {@link WorldViewJson} (the dep-free Json writer only handles Map/List/scalars).
 *
 * @param entities nearby entities, or {@code null} when the section was NOT SAMPLED — the same
 *                 three-state distinction {@link SelfView#air} and {@link SelfView#effects} make,
 *                 for the same reason. An EMPTY list means nothing was nearby; {@code null} means
 *                 nobody looked. Collapsing them is not cosmetic: {@link WorldViewDiff} compares id
 *                 sets, so an unsampled section arriving as an empty list makes EVERY previously
 *                 known id report as {@code left}, and a caller reads {@code left} as "that creeper
 *                 is dead".
 * @param entitiesCapped true when the profile's {@code maxEntities} actually TRUNCATED the list, so
 *                 something alive and nearby was dropped. The other way an id lands in
 *                 {@code left} while the entity is alive: a nearer entity arriving EVICTS a farther
 *                 one and the eviction is indistinguishable from a departure. Carried rather than
 *                 inferred because {@code size() == cap} does not prove truncation -- a scan that
 *                 found exactly {@code cap} entities dropped none -- and guessing would be, in this
 *                 payload's own words, a guess dressed as data.
 */
public record WorldView(boolean present, long tickId, String profile,
                        SelfView self, LocalGrid grid, List<EntityView> entities,
                        boolean entitiesCapped,
                        InventoryView inventory, TargetView target, EnvView env) {

    /**
     * The "not in world" snapshot (mirrors Surroundings.absent()).
     *
     * <p>{@code entities} is null rather than empty here too: not being in a world is the strongest
     * possible case of "nobody looked", and an empty list would claim there were no entities nearby.
     */
    public static WorldView absent() {
        return new WorldView(false, 0L, null, null, null, null, false, null, null, null);
    }
}
