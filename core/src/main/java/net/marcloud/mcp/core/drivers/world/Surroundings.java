package net.marcloud.mcp.core.drivers.world;

import java.util.List;
import java.util.Map;

/**
 * Immutable symbolic snapshot of the player's situation — the primary
 * observation channel for the AI (per the research: structured text beats
 * screenshots for high-level decisions, and is far cheaper in tokens). Field set
 * follows Voyager's 32-block observation template.
 *
 * <p>Captured on the game thread (world/entity state isn't thread-safe). A
 * {@code present=false} snapshot means "not in a world".
 */
public record Surroundings(boolean present,
                           double x, double y, double z,
                           float yaw, float pitch,
                           float health, int hunger,
                           String dimension,
                           String biome,
                           String timeOfDay,
                           Map<String, Integer> inventory,
                           String blockBelow,
                           String blockAtLegs,
                           String blockAtHead,
                           Map<String, Integer> nearbyBlocks,
                           List<NearbyEntity> nearbyEntities) {

    /** A nearby entity, with distance from the player (blocks). */
    public record NearbyEntity(String name, double distance) {
    }

    public static Surroundings absent() {
        return new Surroundings(false, 0, 0, 0, 0, 0, 0, 0,
                null, null, null, Map.of(), null, null, null, Map.of(), List.of());
    }

    /** Render as a compact human/LLM-readable text block. */
    public String toText() {
        if (!present) {
            return "not in world";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("position: (%.1f, %.1f, %.1f) yaw=%.0f pitch=%.0f%n", x, y, z, yaw, pitch));
        sb.append(String.format("health: %.1f/20  hunger: %d/20%n", health, hunger));
        sb.append(String.format("dimension: %s  biome: %s  time: %s%n", dimension, biome, timeOfDay));
        sb.append(String.format("column: below=%s legs=%s head=%s%n", blockBelow, blockAtLegs, blockAtHead));
        sb.append("inventory: ").append(inventory.isEmpty() ? "(empty)" : inventory).append(System.lineSeparator());
        sb.append("nearby blocks: ").append(nearbyBlocks.isEmpty() ? "(none)" : nearbyBlocks).append(System.lineSeparator());
        sb.append("nearby entities: ");
        if (nearbyEntities.isEmpty()) {
            sb.append("(none)");
        } else {
            for (NearbyEntity e : nearbyEntities) {
                sb.append(String.format("%s@%.1f ", e.name(), e.distance()));
            }
        }
        return sb.toString().stripTrailing();
    }
}
