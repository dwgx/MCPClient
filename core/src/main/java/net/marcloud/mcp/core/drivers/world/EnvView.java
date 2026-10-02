package net.marcloud.mcp.core.drivers.world;

/**
 * Environment context: dimension, biome, time-of-day bucket, raw world time, and the weather.
 *
 * <p>The weather fields are not decoration. {@code EntityMob.isValidLightLevel} re-reads a cell
 * with {@code skylightSubtracted} forced to 10 while the world thunders, which turns a midday
 * reading of 15 into 5 and opens a mob spawn that a clear day closes. An agent told "noon" and
 * nothing about the storm is planning against the wrong afternoon.
 *
 * <p>{@code lightAtPlayer} is the light AT the player's own cell, which is the quantity the spawn
 * gate compares -- not the hour. It is read as {@code max(storedSky - skylightSubtracted,
 * storedBlock)}, i.e. {@code getLightSubtracted(pos, amount)} with the factor the world actually
 * holds, so it is directly comparable with {@link BlockInspector}'s number for any other cell.
 *
 * <p>Reference-free: primitives only.
 */
public record EnvView(String dimension, String biome, String timeOfDay, long worldTime,
                      boolean raining, boolean thundering, boolean daytime,
                      int lightAtPlayer) {

    /** The 4-argument shape, for a view captured before weather was reported. */
    public EnvView(String dimension, String biome, String timeOfDay, long worldTime) {
        this(dimension, biome, timeOfDay, worldTime, false, false, false, -1);
    }
}
