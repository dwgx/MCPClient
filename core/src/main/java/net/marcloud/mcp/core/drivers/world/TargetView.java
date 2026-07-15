package net.marcloud.mcp.core.drivers.world;

/**
 * PHASE W.6 — the crosshair target, read from the vanilla {@code objectMouseOver}
 * (same line-of-sight as the player sees). {@code hitType} is "miss" | "block" |
 * "entity". Block fields (block/bx/by/bz/side) or entity fields (entityId/type/hp)
 * are populated per hit type; the rest are null.
 */
public record TargetView(String hitType, String block, Integer bx, Integer by, Integer bz,
                         String side, Integer entityId, String entityType, Integer entityHp,
                         double distance) {

    public static TargetView miss() {
        return new TargetView("miss", null, null, null, null, null, null, null, null, 0.0);
    }
}
