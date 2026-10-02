package net.marcloud.mcp.core.eval;

import java.util.EnumMap;
import java.util.Map;

import net.marcloud.mcp.core.drivers.world.DrowningDamage;
import net.marcloud.mcp.core.drivers.world.FallDamage;
import net.marcloud.mcp.core.drivers.world.FireDamage;

/**
 * Vanilla's per-tick damage, applied to a health bar, in vanilla's order, from state the caller
 * already holds.
 *
 * <p><b>Why this class exists.</b> {@link FireDamage}, {@link FallDamage} and
 * {@link DrowningDamage} already know every number the game uses, and all three were correct and
 * all three were unused: nothing in the evaluation harness ever moved a health bar, so every
 * survival assertion in the suite evaluated the constant {@code 20.0 > 0.0}. Those three classes
 * answer "what would this cost"; this one answers "so apply it", which is the missing producer.
 * It holds the server-side state vanilla's {@code Entity} holds and applies the damage the vendored
 * source applies.
 *
 * <p><b>The order is the source's, not a convenient one.</b> One server tick for a living entity
 * runs, in this order:
 * <ol>
 *   <li>{@code Entity.onEntityUpdate} first, which is {@code Entity.update:479-524}: the fire
 *       clock ({@code :481-505}), then lava contact ({@code :508-512}).</li>
 *   <li>{@code EntityLivingBase.onEntityUpdate:264+}, which calls the above at {@code :267} and
 *       then runs the air countdown and the drown tick ({@code :297-326}) and decrements
 *       {@code hurtResistantTime} ({@code :342-344}).</li>
 *   <li>{@code EntityLivingBase.onUpdate:1836} -> {@code onLivingUpdate} -> travel -> move ->
 *       {@code Entity.moveEntity:839} -> {@code updateFallState:1034-1056} -> {@code fall()}.</li>
 * </ol>
 * So the burn tick is evaluated before the lava hit that reignites it, and the fall lands after
 * both. Reordering these is not cosmetic: it changes which damage opens the hurt window, and the
 * window decides how much of the next hit lands.
 *
 * <p><b>The hurt window is transcribed, not approximated, because it is what makes lava
 * survivable in the real game and lethal in a naive model.</b>
 * {@code EntityLivingBase.attackEntityFrom:896} tests
 * {@code hurtResistantTime > maxHurtResistantTime / 2.0F}: inside that band a hit no larger than
 * the last one is REFUSED outright and a larger one lands only the difference
 * ({@code :897-902}); outside it the whole hit lands and the window reopens ({@code :903-911}).
 * {@code maxHurtResistantTime} is {@code 20} ({@code :95}), so the band is 10 ticks. That is why a
 * body standing in lava does not lose 10 half-hearts a tick: the first 4.0F lands, the next nine
 * are refused, and the tenth starts the cycle over. A model that applies raw damage per tick
 * reports lava as ten times deadlier than it is, and an eval built on that model would refuse
 * crossings a real player walks through.
 *
 * <p><b>What this class deliberately does not carry.</b> No armour, no absorption, no Resistance,
 * no fire-resistance enchantment and no difficulty scaling, all of which are server-side and none of
 * which the caller can see. Those are listed in {@code SimWorld.KNOWN_GAPS} rather than guessed at.
 * What it does carry is every number the vendored source states, and {@code SimWorld} is the only
 * caller, so an assertion about survival here is an assertion about the substrate's model of the
 * server -- never about a live session.
 *
 * <p><b>Half-hearts throughout</b>, matching the three classes this composes and
 * {@code SelfView.health}, so nothing downstream has to know a factor of two.
 */
public final class SurvivalDamage {

    /** {@code EntityLivingBase.getMaxHealth} for a player: {@code SharedMonsterAttributes.maxHealth}. */
    public static final float MAX_HEALTH = 20.0F;

    /**
     * The project's north-star floor, in half-hearts: health never below 18.
     *
     * <p>Not a vanilla constant and not a tuning value of the game. It is the threshold the whole
     * project exists to serve, and it lives here next to the damage that can cross it because a
     * threshold nothing can violate is not a threshold.
     */
    public static final float NORTH_STAR_FLOOR = 18.0F;

    /** {@code EntityLivingBase:95} -- {@code maxHurtResistantTime = 20}. */
    public static final int MAX_HURT_RESISTANT_TICKS = 20;

    /**
     * {@code EntityLivingBase:896} compares against {@code maxHurtResistantTime / 2.0F}, so the
     * refusal band is HALF the window: 10 ticks, not 20. Reading {@code 20} here is the single
     * easiest transcription error in this slice, and it makes every hazard twice as survivable as
     * the game.
     */
    public static final int HURT_WINDOW_TICKS = MAX_HURT_RESISTANT_TICKS / 2;

    /** {@code Entity.isInLava:1217} -- the bounding box is shrunk by this much before the test. */
    public static final double LAVA_SHRINK_XZ = -0.10000000149011612D;
    /** {@code Entity.isInLava:1217} -- and by this much vertically. */
    public static final double LAVA_SHRINK_Y = -0.4000000059604645D;

    /** {@code Entity:112} / {@code EntityPlayer:580} -- the body's own half-width. */
    public static final double BODY_HALF_WIDTH = 0.3D;
    /** {@code EntityPlayer:580} -- the body's height. */
    public static final double BODY_HEIGHT = 1.8D;

    /** Which vanilla {@code DamageSource} took the health, for a failure message that names it. */
    public enum Source {
        /** {@code DamageSource.lava} -- {@code Entity.setOnFireFromLava:543}. */
        LAVA,
        /** {@code DamageSource.onFire} -- {@code Entity.update:501}. */
        FIRE,
        /** {@code DamageSource.drown} -- {@code EntityLivingBase:315}. */
        DROWN,
        /** {@code DamageSource.fall} -- {@code EntityLivingBase.fall:1160}. */
        FALL,
        /**
         * Not a {@code DamageSource} at all: {@code Entity.update:514-517} calls {@code kill()}
         * outright below y = -64, so there is no amount to record and no window to consult. It is
         * a SOURCE here because the thing a task needs back is "which hazard ended this run", and
         * "the body walked off the edge of the world" is an answer a void bucket keeps.
         */
        VOID,
        /** Anything a mob swung: {@code DamageSource.mob}/{@code playerAttack}. */
        MOB
    }

    /**
     * The three facts a caller already holds about where the body is, and how it landed.
     *
     * <p>Deliberately three booleans and a number rather than a world handle. Everything the damage
     * rules need is "is any cell of the shrunk bounding box lava", "is the eye in water" and "what
     * was fallDistance on the tick the body landed" -- and the caller has all three without asking
     * anything. That is what keeps a per-tick damage application off the world-read seam: see
     * {@code TheSurvivalDamageCostsNoWorldReadTest}.
     *
     * @param inLava          whether any cell of the shrunk bounding box is {@code Material.lava}
     * @param submerged       whether the body is inside {@code Material.water}, which is what
     *                        spends the air bar ({@code EntityLivingBase:297})
     * @param landingDistance {@code fallDistance} on the tick the body landed, or {@code 0} when it
     *                        did not land -- the value {@code Entity.updateFallState:1046} hands
     *                        to {@code fall()}
     * @param belowVoid       whether {@code posY} is under -64, which is the one hazard the game
     *                        resolves by calling {@code kill()} rather than by dealing damage
     *                        ({@code Entity.update:514-517})
     */
    public record Hazards(boolean inLava, boolean submerged, float landingDistance,
                          boolean belowVoid) {

        /** The three facts a caller had before the void existed, for a run over solid ground. */
        public static Hazards of(boolean inLava, boolean submerged, float landingDistance) {
            return new Hazards(inLava, submerged, landingDistance, false);
        }
    }

    private float health = MAX_HEALTH;
    private float lowest = MAX_HEALTH;
    /** {@code Entity.fire}: server-side only, zeroed on the client every tick at {@code :484}. */
    private int fire;
    /** {@code EntityLivingBase} air, 300 when out of water and reset to 0 by a drown tick. */
    private int air = DrowningDamage.AIR_FULL;

    private int hurtResistantTime;
    private float lastDamage;
    private int tick;
    private boolean dead;
    private int deathTick = -1;
    private int ticksInLava;
    private int ticksBurning;
    private int ticksSubmerged;
    private int lavaContacts;
    private int drownTicks;
    private int fallLandings;
    private int voidKills;
    private final EnumMap<Source, Float> damage = new EnumMap<>(Source.class);

    /**
     * One server tick, in the order the vendored source runs it.
     *
     * <p>See the class doc for the three steps and where each one lives. The method returns the
     * health the tick ended on so a caller never has to ask twice.
     */
    public float step(Hazards hazards) {
        if (dead) {
            return health;
        }
        tick++;

        // 1. Entity.update:481-505 -- the burning clock. The tick it is on is the one where the
        //    counter is still a multiple of 20, and the decrement happens after the hit, so a body
        //    that just caught fire at 300 ticks burns on the NEXT tick and not this one.
        if (fire > 0) {
            if (fire % FireDamage.TICKS_PER_DAMAGE_TICK == 0) {
                attackFrom(FireDamage.DAMAGE_PER_TICK, Source.FIRE);
            }
            fire--;
            ticksBurning++;
        }

        // 2. Entity.update:508-512 -> setOnFireFromLava:539-546. Contact damage, then ignition.
        //    setFire only ever RAISES the counter (Entity:556), which is why the burn below does
        //    not restart at 300 while the body is still standing in the lava.
        if (hazards.inLava() && !dead) {
            ticksInLava++;
            lavaContacts++;
            attackFrom(FireDamage.LAVA_CONTACT_DAMAGE, Source.LAVA);
            setFire(FireDamage.LAVA_FIRE_SECONDS);
        }

        // 2b. Entity.update:514-517. `if (this.posY < -64.0D) this.kill();` -- the one hazard with
        //     no DamageSource and no amount, so it is applied outright rather than through the
        //     window. It sits between the lava block and the drown tick because that is where
        //     vanilla puts it, and because a body that fell out of the world is not going to
        //     drown on the way past.
        if (hazards.belowVoid() && !dead) {
            voidKills++;
            health = 0.0F;
            lowest = 0.0F;
            dead = true;
            deathTick = tick;
        }

        // 3. EntityLivingBase:297-326 -- the air bar, and the one-shot drown tick. Out of water the
        //    bar is refilled to 300 (:326); under it, one decrement per tick (:301), and at exactly
        //    -20 the bar resets to 0 (:305) and 2.0F is applied (:315).
        //
        //    The decrement is UNCONDITIONAL, and that is the whole point of the -20. A bar clamped
        //    at zero is a bar that can never reach -20, so the drown tick is unreachable code and a
        //    body could sit at the bottom of an ocean forever. The band -1..-19 is real and means
        //    "drowning has begun and the hit is coming", which is why
        //    DrowningDamage.isDrowning is `air < 0` and not `air == 0`.
        if (hazards.submerged() && !dead) {
            ticksSubmerged++;
            air--;
            if (air == DrowningDamage.AIR_DAMAGE_TICK) {
                air = 0;
                drownTicks++;
                attackFrom(DrowningDamage.DAMAGE_PER_TICK, Source.DROWN);
            }
        } else {
            air = DrowningDamage.AIR_FULL;
        }

        // 4. EntityLivingBase:342-344 -- the window runs down once per tick. Order matters: it is
        //    decremented AFTER this tick's damage was decided, so the tick that reopens the window
        //    is the tick the next hit is measured against.
        if (hurtResistantTime > 0) {
            hurtResistantTime--;
        }

        // 5. Entity.updateFallState:1036-1050 -> EntityLivingBase.fall:1151-1162. Only a landing
        //    pays, and only above the 3-block floor.
        if (hazards.landingDistance() > 0.0F && !dead) {
            fallLandings++;
            int halfHearts = FallDamage.damageFor(hazards.landingDistance(), -1, 1.0F);
            if (halfHearts > FallDamage.NONE) {
                attackFrom(halfHearts, Source.FALL);
            }
        }
        return health;
    }

    /**
     * {@code EntityLivingBase.attackEntityFrom:863-912}, reduced to the health arithmetic.
     *
     * <p>Returns whether damage landed, which is what a caller counting hits needs. The refusal
     * rule is {@code :897-900} verbatim: inside the band a hit no larger than the last is dropped,
     * and a larger one keeps only the excess.
     *
     * @param halfHearts the hit as vanilla states it, in half-hearts
     * @param source     which {@code DamageSource}, for the tally
     */
    public boolean attackFrom(double halfHearts, Source source) {
        if (dead || halfHearts <= 0.0D) {
            return false;
        }
        double lands = halfHearts;
        if (hurtResistantTime > HURT_WINDOW_TICKS) {
            if (halfHearts <= lastDamage) {
                return false;
            }
            lands = halfHearts - lastDamage;
        }
        lastDamage = (float) halfHearts;
        hurtResistantTime = MAX_HURT_RESISTANT_TICKS;
        apply(lands, source);
        return true;
    }

    /**
     * {@code Entity.setFire:551-560}: seconds become ticks, and the counter only ever rises.
     *
     * <p>Fire protection is not modelled -- {@code EnchantmentProtection.getFireTimeForEntity} is
     * an inventory read this class does not make -- so this is the unenchanted duration.
     */
    public void setFire(int seconds) {
        int wanted = FireDamage.fireTicksForSeconds(seconds);
        if (fire < wanted) {
            fire = wanted;
        }
    }

    /** {@code EntityLivingBase.heal} as the food path uses it: {@code ELB:846}. */
    public void heal(float halfHearts) {
        if (dead || halfHearts <= 0.0F) {
            return;
        }
        health = Math.min(MAX_HEALTH, health + halfHearts);
    }

    /**
     * The fixture setter, kept out of {@link #step} so a task can place a body at a known health
     * and the damage rules are the only thing that moves it from there.
     */
    public void setHealth(float halfHearts) {
        health = Math.max(0.0F, Math.min(MAX_HEALTH, halfHearts));
        lowest = Math.min(lowest, health);
        if (health <= 0.0F) {
            dead = true;
        }
    }

    private void apply(double halfHearts, Source source) {
        if (dead || halfHearts <= 0.0D) {
            return;
        }
        health = (float) Math.max(0.0D, health - halfHearts);
        damage.merge(source, (float) halfHearts, Float::sum);
        if (health < lowest) {
            lowest = health;
        }
        if (health <= 0.0D) {
            dead = true;
            deathTick = tick;
        }
    }

    // ===== reading the bar back =====

    public float health() {
        return health;
    }

    /**
     * The lowest the bar has ever been on this run, which is the number a "never below 18" claim
     * is actually about.
     *
     * <p>The end-of-run value is not: a body can be hurt and healed inside one run, and a task that
     * reads only the final figure reports a survivor who was nearly dead three hundred ticks ago.
     */
    public float lowestHealth() {
        return lowest;
    }

    /** Whether the bar has ever gone below the north-star floor. */
    public boolean brokeNorthStar() {
        return lowest < NORTH_STAR_FLOOR;
    }

    public boolean alive() {
        return !dead && health > 0.0F;
    }

    public boolean dead() {
        return dead;
    }

    /** The tick the bar reached zero, or -1. */
    public int deathTick() {
        return deathTick;
    }

    /** {@code Entity.fire}: the server-side burn counter. Zero on a client, always, at :484. */
    public int fire() {
        return fire;
    }

    /** {@code EntityLivingBase} air. Negative inside the -1..-19 band, 0 after a drown tick. */
    public int air() {
        return air;
    }

    /** Ticks whose bounding box held lava. Zero means the lava row was never exercised. */
    public int ticksInLava() {
        return ticksInLava;
    }

    /** How many {@code setOnFireFromLava} calls landed, i.e. contacts and not ticks. */
    public int lavaContacts() {
        return lavaContacts;
    }

    /** Ticks with a live fire counter. */
    public int ticksBurning() {
        return ticksBurning;
    }

    /** Ticks spent inside water, which is what spends the air bar. */
    public int ticksSubmerged() {
        return ticksSubmerged;
    }

    /** How many drown ticks paid damage. */
    public int drownTicks() {
        return drownTicks;
    }

    /** How many landings exceeded zero fall distance. */
    public int fallLandings() {
        return fallLandings;
    }

    /** Half-hearts taken from one source over the whole run. */
    public float damageFrom(Source source) {
        return damage.getOrDefault(source, 0.0F);
    }

    /** Every source that took health, for a failure message. */
    public Map<Source, Float> damageBySource() {
        return java.util.Collections.unmodifiableMap(damage);
    }

    /**
     * Whether this run can kill the player at all, and what did it.
     *
     * <p>The question a survival assertion should ask about itself. A task whose world contains no
     * hazard answers false here, and saying so is the difference between a row that measured
     * survival and a row that measured a constant.
     */
    public boolean anyHazard() {
        return ticksInLava > 0 || ticksBurning > 0 || ticksSubmerged > 0 || fallLandings > 0
                || voidKills > 0 || damage.getOrDefault(Source.MOB, 0.0F) > 0.0F;
    }

    /** A one-line account of what moved the bar, for a result string. */
    public String ledger() {
        StringBuilder sb = new StringBuilder();
        for (Source s : Source.values()) {
            float d = damageFrom(s);
            if (d > 0.0F) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(s).append('=').append(String.format(java.util.Locale.ROOT, "%.1f", d));
            }
        }
        if (voidKills > 0) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("VOID=killed below y=-64");
        }
        return sb.length() == 0 ? "nothing damaged the player" : sb.toString();
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "SurvivalDamage[hp=%.1f lowest=%.1f fire=%d air=%d lavaTicks=%d burnTicks=%d"
                        + " submergedTicks=%d drownTicks=%d landings=%d ledger=%s]",
                health, lowest, fire, air, ticksInLava, ticksBurning, ticksSubmerged, drownTicks,
                fallLandings, ledger());
    }
}
