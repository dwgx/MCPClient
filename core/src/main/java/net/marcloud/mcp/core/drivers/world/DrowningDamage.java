package net.marcloud.mcp.core.drivers.world;

/**
 * Vanilla's air clock: how long a held breath lasts and what running out costs.
 *
 * <p><b>The air counter is a client fact; the damage is not.</b> That split is the whole reason
 * this class can exist, and it is the same shape {@link FallDamage} already exploits for falls.
 * {@code EntityLivingBase.onLivingUpdate:297-326} decrements air underwater with
 * {@code this.setAir(this.decreaseAirSupply(this.getAir()))} and the branch carries no
 * {@code !worldObj.isRemote} guard, so the client runs the same countdown the server does and
 * {@code SelfView.air} is a locally-maintained number rather than a value relayed from elsewhere.
 *
 * <p>The damage that follows is {@code this.attackEntityFrom(DamageSource.drown, 2.0F)} at
 * {@code :315}, inside {@code attackEntityFrom}, which is server-only. So the client can say
 * "you have N ticks of air and then you take 2 HP, repeatedly" and cannot say "you are drowning
 * now". {@link #ticksUntilDamage} prices the next hit; it is a hypothetical in the same sense
 * {@link FallDamage#damageFor} is.
 *
 * <p><b>Why the countdown is not simply {@code air}.</b> {@code decreaseAirSupply:439-443} rolls
 * {@code this.rand.nextInt(i + 1) > 0} against the Respiration enchantment level and, when it
 * wins, returns the air <em>unchanged</em> -- one tick is skipped. So with Respiration the drain is
 * stochastic and {@code air} alone does not determine the remaining time. The caller has to say
 * which case it wants, which is why {@link #ticksUntilDamage} takes an explicit
 * {@code respiration} level rather than reading one: it is modelling vanilla's rule, not
 * predicting a seeded roll, and pretending to know the roll would be a number nobody can check.
 */
public final class DrowningDamage {

    /**
     * Air value on the last tick out of water, in half-seconds of breath.
     *
     * <p>{@code EntityLivingBase:326} calls {@code this.setAir(300)} whenever the entity is not in
     * water, and {@code SelfView.AIR_FULL} already carries the same number for the same reason.
     */
    public static final int AIR_FULL = 300;

    /**
     * Air value at which the next tick deals damage and resets to zero.
     *
     * <p>{@code EntityLivingBase:303-315}: {@code if (this.getAir() == -20) { this.setAir(0); ...
     * this.attackEntityFrom(DamageSource.drown, 2.0F); }}. It is an equality test, not a
     * threshold, so the damage tick exists at exactly one value and the value is never observed
     * at rest -- the reset happens in the same tick.
     */
    public static final int AIR_DAMAGE_TICK = -20;

    /** Half-hearts per drown tick. {@code 2.0F} at {@code EntityLivingBase:315}, in half-hearts. */
    public static final int DAMAGE_PER_TICK = 4;

    /** No damage. Also the only non-positive this returns. */
    public static final int NONE = 0;

    private DrowningDamage() {
    }

    /**
     * Whether {@code air} is already negative, i.e. vanilla's countdown has passed zero.
     *
     * <p>{@code SelfView.AIR_DROWNING_STARTS} is {@code -1} for the same boundary, and the whole
     * band {@code -1..-19} is real and means "drowning has begun" -- which is why air is boxed
     * and omitted rather than sentinelled when a read fails. This is that band as a question.
     */
    public static boolean isDrowning(int air) {
        return air < 0;
    }

    /**
     * Ticks until the next drown tick, from a current air value.
     *
     * <p>Air falls by one per tick, so the distance to {@link #AIR_DAMAGE_TICK} is the number of
     * decrements still owed: {@code air - AIR_DAMAGE_TICK}. A player at full air owes 320, which
     * is the 300 ticks of visible countdown plus the 20 ticks vanilla spends in the negative band
     * before the hit -- and that gap is the whole reason {@code SelfView.air} documents
     * {@code -1..-19} as "drowning has started" rather than "about to be damaged".
     *
     * <p>A value at or past {@link #AIR_DAMAGE_TICK} owes nothing and returns {@link #NONE}, since
     * the damage tick is the current tick, not a future one.
     *
     * <p><b>Respiration MULTIPLIES the wait, and the sign of that is easy to get backwards.</b>
     * {@code decreaseAirSupply:439-443} reads
     * {@code i > 0 && this.rand.nextInt(i + 1) > 0 ? air : air - 1}: with level {@code i} the skip
     * fires on {@code i} of the {@code i + 1} equally likely rolls, so air drains on only
     * {@code 1 / (i + 1)} of ticks and each of the owed decrements costs {@code i + 1} ticks on
     * average. Level 1 therefore halves the drain rate and doubles the breath. Dividing by
     * {@code i + 1} instead -- the intuitive slip -- reports Respiration as a head start that ends
     * your dive sooner, which is precisely backwards and talks a model into a fatal dive.
     *
     * <p>This is an expectation, because the roll is random and unknowable from here. The
     * <em>worst</em> case is {@link #ticksUntilDamageWorstCase}, which ignores Respiration
     * entirely -- an unlucky run skips nothing -- and is the number a caller should use before
     * committing to a dive it cannot abort.
     *
     * @param air         the current air value, negative included
     * @param respiration the Respiration enchantment level, 0 when absent
     */
    public static int ticksUntilDamage(int air, int respiration) {
        if (air <= AIR_DAMAGE_TICK) {
            return NONE;
        }
        int owed = air - AIR_DAMAGE_TICK;
        int ticksPerDecrement = Math.max(0, respiration) + 1;
        return owed * ticksPerDecrement;
    }

    /**
     * Ticks until the next drown tick if every roll goes against the player.
     *
     * <p>Equal to {@link #ticksUntilDamage} with no Respiration, and deliberately not a function
     * of the level: {@code rand.nextInt} can return {@code 0} every single time, so no
     * enchantment makes the short case impossible. A caller planning an uninterruptible dive
     * wants this; a caller watching a live air counter wants the expectation, because the
     * countdown it is reading already has the enchantment's effect baked in.
     */
    public static int ticksUntilDamageWorstCase(int air) {
        return ticksUntilDamage(air, 0);
    }

    /**
     * Half-hearts of drown damage over {@code ticks} spent underwater, in vanilla's arithmetic.
     *
     * <p><b>Two different intervals, and conflating them is the trap.</b> The first hit costs a
     * whole breath: air runs 300 down through 0 to {@link #AIR_DAMAGE_TICK}, which is
     * {@link #ticksUntilDamage}'s band. But {@code EntityLivingBase:305} resets air with
     * {@code this.setAir(0)}, <em>not</em> to {@link #AIR_FULL} -- so every hit after the first
     * arrives a mere {@code -AIR_DAMAGE_TICK} later, one per 20 ticks. A 640-tick dive is
     * therefore 1 first hit plus 16 more, not two hits: the damage accelerates the moment the
     * first tick lands, and a caller modelling a uniform interval concludes that a drowning body
     * is nearly harmless.
     *
     * <p>Deliberately a hypothetical, like {@link FallDamage#damageFor} and
     * {@link FireDamage#damageOverTicks}: "if you stay under for this long".
     *
     * @param ticks       ticks underwater
     * @param respiration the Respiration enchantment level, 0 when absent
     */
    public static int damageOverTicks(int ticks, int respiration) {
        if (ticks <= 0) {
            return NONE;
        }
        int firstBand = ticksUntilDamage(AIR_FULL, respiration);
        if (ticks < firstBand) {
            return NONE;
        }
        // After the first hit air restarted at ZERO, so the next one is only -AIR_DAMAGE_TICK
        // away -- and Respiration stretches that band too, because decreaseAirSupply skips a
        // decrement on its roll whatever the starting air value is. Scaling only the first band
        // would make Respiration look like a liability: it would shorten the wait to the first
        // hit and then hand back an unstretched cadence.
        int laterBand = -AIR_DAMAGE_TICK * Math.max(1, Math.max(0, respiration) + 1);
        int afterFirst = ticks - firstBand;
        int laterBands = afterFirst / laterBand;
        return (1 + laterBands) * DAMAGE_PER_TICK;
    }

    /**
     * Whether a dive is long enough to cost at least one drown tick, i.e. whether the breath runs
     * out inside it.
     *
     * <p>Uses {@link #ticksUntilDamage}'s own arithmetic so it cannot disagree with
     * {@link #damageOverTicks}: the same band decides both, and the comparison is {@code >=}
     * because the hit lands <em>on</em> the boundary tick, not after it. Getting that wrong makes
     * the two methods contradict each other at exactly one value -- the value a caller hits when
     * it times a dive to the tick -- which is the worst place for two answers to differ.
     *
     * <p>About the arithmetic, not the journey: a body with a full bar can still be stranded
     * mid-dive by a current or a mob, and neither is this class's concern. A caller must word it
     * as "you will not reach the bottom with air left", not "you will survive".
     */
    public static boolean outOfBreath(int ticksUnderwater, int respiration) {
        return ticksUnderwater >= ticksUntilDamage(AIR_FULL, respiration);
    }
}
