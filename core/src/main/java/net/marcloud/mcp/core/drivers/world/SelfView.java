package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.2 — the player's authoritative self state, read from EntityPlayerSP.
 * Immutable, reference-free (boxed primitives/String/List only).
 *
 * @param air breath ticks, {@code null} when the read FAILED — not a number.
 *            Boxed alone among the NUMERIC fields because air is the only one whose natural
 *            failure sentinel is a value the game legitimately produces: vanilla ticks it
 *            300 down through 0 and on into the negatives, resetting only at exactly
 *            {@code -20} ({@code EntityLivingBase:301-305}), so {@code -1} through
 *            {@code -19} are REAL and mean "drowning has started, N-of-20 ticks until the
 *            next 2 HP". A sentinel there would have made "we could not read your air"
 *            indistinguishable from "you are 19 ticks from drowning damage". {@code null}
 *            is projected as ABSENCE, the same convention {@code surfaceDy} and {@code drop}
 *            already use.
 * @param effects active potion effects, or {@code null} when the read FAILED — the same
 *            three-state distinction air makes, for the same reason. An EMPTY list means the
 *            player genuinely has no effects; {@code null} means the capture could not ask.
 *            Collapsing the two is not cosmetic: {@code WorldViewDiff} compares effect sets,
 *            so a failed read arriving as an empty list makes every live effect report as
 *            {@code lost}, and a model reads that as its fire resistance having just expired.
 *            Next to lava, that is the dangerous direction to be wrong in. See
 *            {@code WorldViewCapture#effectsOrNull}.
 * @param fallDistance vanilla's {@code Entity.fallDistance} in blocks — a CLIENT fact, maintained
 *            locally at {@code Entity.updateFallState:1034-1055} and not a server value. It is
 *            here because a critical hit's precondition is a number ({@code EntityPlayer:1333})
 *            and nothing else reported it.
 * @param fallDamageIfLanded half-hearts {@link FallDamage} says a landing from
 *            {@code fallDistance} would cost, in vanilla's own arithmetic
 *            ({@code EntityLivingBase.fall:1156}). Named for a HYPOTHETICAL landing because the
 *            damage itself can never be read back: it is applied under {@code !worldObj.isRemote}.
 *            This is the number that lets a caller refuse a 12-block drop before taking it.
 * @param blocking vanilla's {@code EntityPlayer.isBlocking} — a use in progress AND the used
 *            item's use action being BLOCK. Distinct from "using an item" on purpose: a held meal
 *            and a raised shield are both uses and only one of them is a block.
 */
public record SelfView(
        double x, double y, double z,
        double vx, double vy, double vz,
        float yaw, float pitch,
        float health, int food, float saturation,
        int xpLevel, float xpProgress,
        int armor, Integer air,
        String gamemode, boolean sneaking, boolean sprinting, boolean onGround,
        List<Effect> effects,
        double fallDistance, int fallDamageIfLanded, boolean blocking) {
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
