package net.marcloud.mcp.core.drivers.world;

/**
 * Environment context: dimension, biome, time-of-day bucket, and raw world time.
 * Reference-free.
 */
public record EnvView(String dimension, String biome, String timeOfDay, long worldTime) {
}
