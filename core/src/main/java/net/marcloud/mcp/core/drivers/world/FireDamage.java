package net.marcloud.mcp.core.drivers.world;

/**
 * Vanilla's burning clock: how long fire lasts, how often it hurts, and what the client can see.
 *
 * <p><b>The split this class exists to make explicit.</b> The gap matrix marks row 5.7 (burning)
 * PARTIAL with the note "lava is warned about, never escaped". That is a locomotion gap and it
 * is not this class. What this class settles is the other half: how much damage standing in fire
 * is going to cost, and whether the client can tell that it is standing in fire at all.
 *
 * <p><b>The damage is server-side.</b> {@code Entity.update:482-505} ticks {@code fire} and calls
 * {@code attackEntityFrom(DamageSource.onFire, 1.0F)}, and the whole block sits behind
 * {@code else if (this.fire > 0)} which follows {@code if (this.worldObj.isRemote) this.fire = 0}
 * at {@code :481-482}. On the client {@code fire} is therefore <em>always zero</em>, which is
 * stronger than "the client cannot see the damage": the client's own fire counter is not a stale
 * copy of the server's, it is a field the client never fills. Reading {@code entity.fire} on the
 * client would answer "not burning" for a player standing in lava. That is the trap.
 *
 * <p><b>What the client CAN see.</b> Two real facts, both client-owned:
 * <ul>
 *   <li><b>Are you burning.</b> {@code Entity.isBurning:2126-2130} reads
 *       {@code this.fire > 0 || flag && this.getFlag(0)}, where {@code flag} is
 *       {@code worldObj.isRemote}. On the client the first term is always false, so the answer
 *       comes entirely from DataWatcher flag 0 -- and flag 0 IS synced
 *       ({@code Entity.setFlag:2215-2227} writes watchable byte 0, which the server populates at
 *       {@code Entity.update:519-522} under {@code !isRemote}). So "the server says I am on fire"
 *       arrives at the client as a tracked byte. That is a client-checkable world fact.</li>
 *   <li><b>How much it will cost.</b> 1 HP per second of fire ({@code :499-503}), a fixed number,
 *       plus the 4 HP-per-tick lava hit ({@code setOnFireFromLava:539-545}). Both are arithmetic
 *       on published constants, the same kind of fact {@link FallDamage} prices a drop from.</li>
 * </ul>
 *
 * <p><b>What it is not.</b> Not a claim that damage was dealt, and not a survival prediction.
 * {@link #damageOverTicks} answers "if you stay in fire for this long, this is the bill", and the
 * caller must word it that way -- exactly the contract {@link FallDamage} keeps. Fire resistance
 * and Resistance are also deliberately absent: both are applied on the server
 * ({@code EnchantmentProtection.getFireTimeForEntity:113-123} reshapes the duration, Resistance
 * reshapes the hit), and their inputs are inventory enchantments a caller may read but this class
 * will not assume.
 */
public final class FireDamage {

    /**
     * Half-hearts a body takes per second of fire.
     *
     * <p>{@code Entity.update:499-503} verbatim: {@code if (this.fire % 20 == 0)
     * this.attackEntityFrom(DamageSource.onFire, 1.0F);} -- one HP, once every 20 ticks.
     */
    public static final int TICKS_PER_DAMAGE_TICK = 20;

    /** {@code 1.0F} in {@code Entity.update:500}, in half-hearts. */
    public static final int DAMAGE_PER_TICK = 2;

    /**
     * Half-hearts of contact damage from lava itself, separate from the burning it ignites.
     *
     * <p>{@code Entity.setOnFireFromLava:539-545} deals {@code DamageSource.lava, 4.0F} and then
     * calls {@code setFire(15)}. Note this is NOT unblockable, so full diamond reduces it -- see
     * {@link ArmourSlots#damageAfter}.
     */
    public static final int LAVA_CONTACT_DAMAGE = 8;

    /**
     * Seconds {@code setOnFireFromLava} sets fire for.
     *
     * <p>{@code Entity.setOnFireFromLava:544} calls {@code this.setFire(15)}, and
     * {@code Entity.setFire:551-560} turns seconds into ticks by multiplying by 20. So lava
     * contact buys 300 ticks of burning, i.e. 15 damage ticks.
     */
    public static final int LAVA_FIRE_SECONDS = 15;

    /** Ticks per second, the conversion {@code Entity.setFire:551-560} performs. */
    public static final int TICKS_PER_SECOND = 20;

    /** No damage. Also the only non-positive this returns. */
    public static final int NONE = 0;

    /**
     * Vanilla DataWatcher index holding the entity flag byte, so a caller reading the fire flag
     * out of the raw watcher reads the right cell.
     *
     * <p>{@code Entity:286-287} registers {@code addObject(0, Byte.valueOf(0))} and
     * {@code addObject(1, Short.valueOf((short)300))}: flag 0 and air 1. {@code getFlag}/{@code
     * setFlag} are {@code protected}, so a caller outside the {@code net.minecraft} hierarchy --
     * which is all of this project -- reaches flag 0 through the watchable object instead.
     */
    public static final int FLAG_DATAWATCHER_INDEX = 0;

    /** Bit within flag byte 0 that means "burning". {@code Entity.update:521} sets it. */
    public static final int FLAG_BURNING_BIT = 0;

    private FireDamage() {
    }

    /**
     * Whether a DataWatcher flag byte says the entity is burning.
     *
     * <p>{@code Entity.isBurning:2129} reads bit 0 of watchable byte 0 on the client, because
     * {@code fire} is zeroed there every tick. This is the client-side half of that expression,
     * taking the byte as it comes out of the watcher rather than an entity, so a caller with only
     * a captured byte -- and no world, no entity and no live game -- can still answer the question.
     *
     * <p>{@code isBurning} also requires {@code !isImmuneToFire}, which is why this returns the
     * flag alone and says so: a caller that cares has to combine it with the fire-resistance
     * answer, because {@code Entity.isBurning:2129} would report false for an immune entity even
     * with the bit set. Reading the bit is the narrower, more honest question.
     */
    public static boolean isBurningFlag(byte flags) {
        return (flags & (1 << FLAG_BURNING_BIT)) != 0;
    }

    /**
     * Ticks {@code Entity.setFire(seconds)} would put on the clock.
     *
     * <p>{@code Entity.setFire:551-560}: {@code int i = seconds * 20;}, then fire protection is
     * subtracted and the result only ever <em>raises</em> the counter. Fire resistance is not
     * modelled here, so this is the unenchanted duration; a caller who knows the protection level
     * should say so rather than expect it folded in.
     */
    public static int fireTicksForSeconds(int seconds) {
        return Math.max(NONE, seconds) * TICKS_PER_SECOND;
    }

    /**
     * Half-hearts of burning damage over {@code ticks} of fire, in vanilla's own arithmetic.
     *
     * <p>{@code Entity.update:499-504} tests {@code this.fire % 20 == 0} and then decrements, so
     * the number of hits over a fire counter that starts at {@code ticks} is the count of
     * multiples of {@link #TICKS_PER_DAMAGE_TICK} in {@code [20, ticks]} -- a 300-tick burn hits
     * 15 times for 30 half-hearts, and 19 ticks hits zero times rather than one. It is
     * deliberately a hypothetical, like {@link FallDamage#damageFor}: "if you stay in it this
     * long", because extinguishing early is exactly what a caller is usually deciding.
     *
     * @param ticks the fire counter to run down, i.e. how long the body stays alight
     *
     *
     */
    public static int damageOverTicks(int ticks) {
        if (ticks <= NONE) {
            return NONE;
        }
        return (ticks / TICKS_PER_DAMAGE_TICK) * DAMAGE_PER_TICK;
    }

    /**
     * Half-hearts a body takes from lava contact plus the burning it ignites.
     *
     * <p>One {@link #LAVA_CONTACT_DAMAGE} tick of contact damage plus the full burn from
     * {@link #LAVA_FIRE_SECONDS} of fire, which is 15 damage ticks. The two halves are separable
     * on purpose: the contact hit is a one-off that armour reduces, while the burn afterwards is
     * {@code DamageSource.onFire} and is unblockable, so the second half lands in full whatever
     * the player is wearing. A caller told only "lava costs 38 half-hearts" would look for armour
     * that does not exist.
     */
    public static int damageForOneLavaContact() {
        return LAVA_CONTACT_DAMAGE + damageOverTicks(fireTicksForSeconds(LAVA_FIRE_SECONDS));
    }

    /**
     * Ticks of fire still to run, given how many remain.
     *
     * <p>{@code Entity.update:504} does {@code --this.fire} once per tick, so a caller holding
     * the remaining count can convert it to a wall-clock deadline. It is exposed because the
     * remaining count is server-held and this is the only arithmetic a client can do about it --
     * and, like {@link #damageOverTicks}, it describes fire that is still burning rather than
     * fire that has been confirmed.
     */
    public static int ticksRemaining(int fire) {
        return Math.max(NONE, fire);
    }
}
