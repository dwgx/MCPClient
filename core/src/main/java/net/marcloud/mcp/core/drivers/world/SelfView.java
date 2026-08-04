package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.2 — the player's authoritative self state, read from EntityPlayerSP.
 * Immutable, reference-free (boxed primitives/String/List only).
 *
 * @param air breath ticks, {@code null} when the read FAILED — not a number.
 *            Boxed alone among these fields because air is the only one whose natural
 *            failure sentinel is a value the game legitimately produces: vanilla ticks it
 *            300 down through 0 and on into the negatives, resetting only at exactly
 *            {@code -20} ({@code EntityLivingBase:301-305}), so {@code -1} through
 *            {@code -19} are REAL and mean "drowning has started, N-of-20 ticks until the
 *            next 2 HP". A sentinel there would have made "we could not read your air"
 *            indistinguishable from "you are 19 ticks from drowning damage". {@code null}
 *            is projected as ABSENCE, the same convention {@code surfaceDy} and {@code drop}
 *            already use.
 */
public record SelfView(
        double x, double y, double z,
        double vx, double vy, double vz,
        float yaw, float pitch,
        float health, int food, float saturation,
        int xpLevel, float xpProgress,
        int armor, Integer air,
        String gamemode, boolean sneaking, boolean sprinting, boolean onGround,
        List<Effect> effects) {

    /**
     * Vanilla's air scale, kept here because two files reason about it and both need the same
     * numbers: {@code 300} on the last tick out of water, one decrement per tick under it
     * ({@code EntityLivingBase:297-326}, no {@code isRemote} guard, so the client produces
     * these itself), and the drowning tick at {@code -20}, which then resets to {@code 0}
     * in the same tick and so is never observable.
     */
    public static final int AIR_FULL = 300;

    /** First air value that means drowning has already begun. */
    public static final int AIR_DROWNING_STARTS = -1;

    /** Air value at which vanilla deals 2 HP and resets to 0; never observed at rest. */
    public static final int AIR_DROWN_DAMAGE = -20;

    /** One active potion effect. */
    public record Effect(int id, String name, int amplifier, int durationTicks) {
    }
}
