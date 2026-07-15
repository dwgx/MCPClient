package net.marcloud.mcp.core.drivers.world;

/**
 * PHASE W.4 — one nearby entity. {@code hp}/{@code name} are null for non-living
 * entities (only surfaced "if visible" = living). Reference-free.
 */
public record EntityView(int id, String type, double x, double y, double z,
                         double dist, Integer hp, String name) {
}
