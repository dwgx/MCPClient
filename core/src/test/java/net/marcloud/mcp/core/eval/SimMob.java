package net.marcloud.mcp.core.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mob that acts, transcribed from {@code client/src/main/java/}.
 *
 * <p><b>Why this is not "a mob that charges".</b> A hand-rolled approximation that always walks at
 * the player makes every combat task a lie: the agent would be scored against a target that never
 * loses sight, never runs out of reach, never has to turn, and never waits. So the parts of vanilla
 * that decide an <em>exchange</em> are transcribed with their line numbers, and everything else is
 * declared in {@link SimWorld#KNOWN_GAPS} rather than quietly approximated. The rules that are in
 * here are the ones a player's survival depends on:
 *
 * <ol>
 *   <li><b>Acquisition is a range, and the range is per type.</b>
 *       {@code EntityAINearestAttackableTarget.shouldExecute:87-109} expands the mob's own bounding
 *       box by {@code getTargetDistance()} in X and Z and by 4.0 in Y, and takes the nearest of what
 *       it finds; {@code EntityAITarget.getTargetDistance:112-115} is the {@code followRange}
 *       attribute. A zombie is 35 blocks ({@code EntityZombie.java:96}), a spider is the
 *       {@code EntityLiving:100} default of 16 -- so a spider 20 blocks away is genuinely a thing
 *       that is not happening to you yet, and that difference is measured, not assumed.</li>
 *   <li><b>Acquisition has a duty cycle.</b> The same method opens with
 *       {@code nextInt(targetChance) != 0} and {@code targetChance} is 10
 *       ({@code EntityAINearestAttackableTarget:31}), so the scan runs about twice a second rather
 *       than twenty times. The eval has no dice, so this uses a tick counter at the same rate; the
 *       substitution is named in {@link SimWorld#KNOWN_GAPS}.</li>
 *   <li><b>Sight gates both chasing and swinging.</b> {@code EntityAIAttackOnCollide:113} only
 *       refreshes the path and swings when {@code longMemory || canSee}, and
 *       {@code EntitySenses.canSee:31-58} is {@code canEntityBeSeen} ({@code EntityLivingBase:2155-
 *       2157}), which is a ray from eye to eye that must not hit a block.</li>
 *   <li><b>The mob turns at 30 degrees a tick and cannot do anything else.</b>
 *       {@code EntityAIAttackOnCollide:107} calls
 *       {@code setLookPositionWithEntity(target, 30.0F, 30.0F)} and
 *       {@code EntityLookHelper.updateRotation:108-123} clamps the step to that argument. A mob
 *       that has just been flanked spends several ticks swinging its body around, which is exactly
 *       the window a player uses to disengage.</li>
 *   <li><b>The mob walks toward a stale point.</b> {@code EntityAIAttackOnCollide:113-131}
 *       re-issues {@code tryMoveToEntityLiving} only when {@code delayCounter} runs out, and sets
 *       that counter to {@code 4 + rand(7)} plus 10 beyond 32 blocks or 5 beyond 16. So between
 *       refreshes the mob is walking at where the target <em>was</em>, up to half a second ago.
 *       <code>delayCounter</code> here is the deterministic stand-in for that draw, at the same
 *       rate; {@code getPathRefreshes()} is the observable.</li>
 *   <li><b>Reach is not the player's reach.</b>
 *       {@code EntityAIAttackOnCollide:135-147} swings when {@code d0 <= d1} with
 *       {@code d1 = width*2*width*2 + target.width} (or the spider's override), and then arms
 *       {@code attackTick = 20} for a full second. A zombie has to close to about 1.43 blocks; the
 *       player can answer from 5. That asymmetry is vanilla's and it is why circling works.</li>
 *   <li><b>Damage is the flat attribute.</b> {@code EntityMob.attackEntityAsMob:104-115} reads
 *       {@code SharedMonsterAttributes.attackDamage} and adds only enchantment modifiers, of which
 *       there are none in this world.</li>
 *   <li><b>The player has an invulnerability window.</b>
 *       {@code EntityLivingBase.attackEntityFrom:893-912} with
 *       {@code maxHurtResistantTime = 20} ({@code :95}): a second hit equal to or below the last is
 *       refused outright, and a bigger one only lands the difference. Two zombies swapping swings
 *       therefore halve each other's damage, and that is the same for a player.</li>
 *   <li><b>Death removes the entity from the world.</b> {@code World:1713-1724} drops a dead
 *       entity out of {@code loadedEntityList} in the same tick it dies, and
 *       {@code EntityLivingBase.onDeath:1035} is what marks it. So "the mob is gone" is a fact
 *       about the world, not about a flag.</li>
 * </ol>
 *
 * <p><b>What is deliberately NOT here is in {@link SimWorld#KNOWN_GAPS}.</b> The short version: no
 * {@code PathNavigate} (a mob walks straight at its target, so a wall is a wall), no wander, no
 * home range, no despawn, no sunlight burning, no loot, no armour, no ranged AI and no lighting.
 *
 * <p><b>Damage here is decided locally, and that is a difference from the game.</b> In real 1.8.9 a
 * mob's {@code attackEntityAsMob} runs on the SERVER and nothing reports the resulting health to a
 * client: the client learns a mob died from {@code S19PacketEntityStatus(3)} and otherwise draws a
 * mob with no health bar at all. So a combat fact measured in this world is "the substrate's model
 * of the server decided it", and never "the game confirmed it". Every combat task says so where it
 * scores.
 */
final class SimMob {

    /** How a type's attack reach is computed. The two forms are genuinely different in vanilla. */
    enum Reach {
        /** {@code EntityAIAttackOnCollide:151-154} -- {@code width*2*width*2 + target.width}. */
        WIDTH_SQUARED {
            @Override
            double of(Kind k, double targetWidth) {
                return k.width() * 2.0D * k.width() * 2.0D + targetWidth;
            }
        },
        /** {@code EntitySpider.AISpiderAttack:264-267} -- {@code 4.0 + target.width}. */
        SPIDER_FORMULA {
            @Override
            double of(Kind k, double targetWidth) {
                return 4.0D + targetWidth;
            }
        };

        abstract double of(Kind k, double targetWidth);
    }

    /**
     * One mob type, and every number on it read out of the frozen client source.
     *
     * @param type the vanilla class name, which is what {@link
     *             net.marcloud.mcp.core.drivers.world.EntityCombat#hostile} keys on
     */
    record Kind(String spawn, String type, double maxHealth, double followRange,
                double movementSpeed, double attackDamage, double width, double height,
                Reach reach, boolean longMemory) {

        /** The squared reach this type swings at a body of {@code targetWidth}. */
        double reachSq(double targetWidth) {
            return reach.of(this, targetWidth);
        }
    }

    /**
     * The two types this substrate has.
     *
     * <p><b>Zombie and spider, and nothing else.</b> A skeleton would need {@code EntityAIArrowAttack}
     * and an {@code EntityArrow}, a creeper needs {@code EntityAICreeperSwell} and an explosion, a
     * witch needs potions, a slime needs the size-splitting rule. None of those are transcribed, so
     * none of those are offered: a "kill the creeper" task against a mob that walks at you and hits
     * for 2 would be measuring the absence of a transcription while looking like a combat test.
     */
    private static final Map<String, Kind> KINDS = Map.of(
            "zombie", new Kind("zombie", "Zombie",
                    20.0D,                        // SharedMonsterAttributes:18 (maxHealth default)
                    35.0D,                        // EntityZombie.java:96
                    0.23000000417232513D,         // EntityZombie.java:97
                    3.0D,                         // EntityZombie.java:98
                    0.6D, 1.8D,                   // Entity.java:269-270 (neither calls setSize)
                    Reach.WIDTH_SQUARED,           // EntityAIAttackOnCollide:151-154
                    false),                        // EntityZombie.java:73, longMemory = false
            "spider", new Kind("spider", "Spider",
                    16.0D,                        // EntitySpider.java:86
                    16.0D,                        // EntityLiving:100 (the followRange default)
                    0.30000001192092896D,         // EntitySpider.java:87
                    2.0D,                         // SharedMonsterAttributes:22 (attackDamage default)
                    0.6D, 1.8D,                   // Entity.java:269-270
                    Reach.SPIDER_FORMULA,          // EntitySpider.java:264-267
                    true));                       // EntitySpider.java:246, longMemory = true

    /** The types this world can spawn, for a task that wants to name what exists. */
    public static List<String> kinds() {
        return List.of("zombie", "spider");
    }

    /** The mob type a spawn name names, or null when it names no mob here. */
    public static Kind kindOf(String spawnName) {
        return KINDS.get(spawnName == null ? "" : spawnName.toLowerCase(Locale.ROOT));
    }

    /** Whether this world has a mob type by this spawn name. */
    public static boolean isMob(String spawnName) {
        return kindOf(spawnName) != null;
    }

    /** {@code EntityAIAttackOnCollide:139} -- {@code this.attackTick = 20} on a landed swing. */
    static final int ATTACK_COOLDOWN_TICKS = 20;

    /** {@code EntityAINearestAttackableTarget:31} -- the default {@code targetChance}. */
    private static final int TARGET_CHANCE = 10;

    /**
     * {@code EntityAIAttackOnCollide:123} -- {@code 4 + rand(7)}, taken deterministically at the
     * top of the range so the re-path rate is the same and the run is reproducible. Declared in
     * {@link SimWorld#KNOWN_GAPS}.
     */
    private static final int PATH_REFRESH_TICKS = 4 + 7;

    /** {@code EntityAINearestAttackableTarget:94} -- the vertical expansion, a constant. */
    private static final double TARGET_Y_EXPANSION = 4.0D;

    final int id;
    final Kind kind;
    final SimBody body;
    double health;
    /** Swings that reached the player, i.e. what {@code EntityMob.attackEntityAsMob} returned true for. */
    int hits;
    /** {@code EntityLiving.hurtTime}-shaped counter: how many times this mob has been hit. */
    int hitsTaken;
    /** Ticks of {@code EntityAIAttackOnCollide.attackTick} left. */
    int attackTick;
    /** {@code EntityAIAttackOnCollide.delayCounter}: ticks until the next path refresh. */
    int delayCounter;
    /** How many times {@code tryMoveToEntityLiving} has been re-issued. Observable, so not a hidden knob. */
    int pathRefreshes;
    /** Where the current path is aiming: the target's feet at the last refresh, so it goes stale. */
    private double pathX;
    private double pathY;
    private double pathZ;
    private boolean hasPath;
    float yaw;

    SimMob(int id, Kind kind, double x, double y, double z, double health) {
        this.id = id;
        this.kind = kind;
        this.body = new SimBody(kind.width(), kind.height(), x, y, z);
        this.health = health;
    }

    /**
     * Whether this mob's type attacks players, read through {@link
     * net.marcloud.mcp.core.drivers.world.EntityCombat#hostile} on the vanilla CLASS name rather
     * than through a list of this file's own.
     */
    boolean hostile() {
        return net.marcloud.mcp.core.drivers.world.EntityCombat.hostile(kind.type());
    }

    /** Whether the player is inside the type's follow range, and inside 4.0 vertically. */
    boolean canNotice(SimWorld world) {
        if (Math.abs(body.eyeY() - world.eyeY()) > TARGET_Y_EXPANSION) {
            return false;
        }
        double dx = body.x - world.posX();
        double dz = body.z - world.posZ();
        return dx * dx + dz * dz < kind.followRange() * kind.followRange();
    }

    /**
     * One tick of {@code EntityLiving.onUpdate}: acquire, look, path, swing.
     *
     * <p>The order is the goal/task order vanilla runs ({@code EntityLiving.onUpdate:278-286} runs
     * the target tasks then the task list) and it matters in one place only: a mob that acquires a
     * target this tick has its path refreshed on the SAME tick, because
     * {@code startExecuting} sets {@code delayCounter = 0}
     * ({@code EntityAIAttackOnCollide:93}).
     */
    void tick(SimWorld world, SimBody.Solid cells) {
        if (health <= 0.0D) {
            return;
        }
        if (attackTick > 0) {
            attackTick--;
        }

        // EntityAINearestAttackableTarget.shouldExecute:87-109, on the 1-in-targetChance duty cycle.
        if (world.ticks() % TARGET_CHANCE == 0) {
            acquire(world);
        }
        if (!hasPath) {
            // EntityAIAttackOnCollide.updateTask returns without moving when there is no target.
            return;
        }

        // EntitySenses.canSee:31-58 -> EntityLivingBase.canEntityBeSeen:2155-2157.
        boolean canSee = world.canSee(new double[] {body.x, body.eyeY(), body.z}, world.eyePos());

        // EntityAIAttackOnCollide:107 -> EntityLookHelper.onUpdateLook:71-106, clamped per tick
        // by updateRotation:108-123 to the 30.0F the task passed.
        double dx = pathX - body.x;
        double dz = pathZ - body.z;
        if (dx * dx + dz * dz > 1.0E-6D) {
            float want = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
            yaw = updateRotation(yaw, want, 30.0F);
        }

        // EntityAIAttackOnCollide:113-131. The path refresh gate, and the counter it sets.
        if ((kind.longMemory() || canSee) && delayCounter <= 0) {
            pathX = world.posX();
            pathY = world.posY();
            pathZ = world.posZ();
            delayCounter = PATH_REFRESH_TICKS;
            // The distance penalties at :117-123 are on d0, the CURRENT foot-to-foot distance.
            double d0 = distanceSqToFeetOf(world);
            if (d0 > 1024.0D) {
                delayCounter += 10;
            } else if (d0 > 256.0D) {
                delayCounter += 5;
            }
            pathRefreshes++;
            // The `+15` at :129-131 is for a navigator that could not path, and there is no
            // navigator here -- see KNOWN_GAPS.
        }
        if (delayCounter > 0) {
            delayCounter--;
        }

        // Walking. EntityLivingBase:2031-2034 damp then travels, and the speed is this type's own
        // movementSpeed attribute -- so a zombie really is more than twice the player's base
        // walk, which is why "outrun it" is not one of the answers in this world.
        if (dx * dx + dz * dz > ARRIVED * ARRIVED) {
            body.yaw = yaw;
            body.travel(cells, 0.0D, (double) SimWorld.INPUT_DAMP, kind.movementSpeed());
        }

        // EntityAIAttackOnCollide:135-147. d0 is the LIVE foot-to-foot distance, not the stale path
        // point, and the cooldown is armed by the swing that landed.
        double live = distanceSqToFeetOf(world);
        if (live <= kind.reachSq(SimWorld.WIDTH) && attackTick <= 0) {
            attackTick = ATTACK_COOLDOWN_TICKS;
            if (world.damagePlayer(kind.attackDamage(), this)) {
                hits++;
            }
        }
    }

    /**
     * How close the mob has to be before it stops walking toward its aim point.
     *
     * <p>A completed path stops at its last node; there is no path here to complete, so this is the
     * one place a number had to be chosen. It is a third of a block, i.e. inside the body, and the
     * mob's own swing range is 1.43 blocks -- so a mob standing at the limit of its path is still
     * well inside its own reach and is never standing still while a target is next to it.
     */
    private static final double ARRIVED = 1.0D / 3.0D;

    /** {@code Entity.getDistanceSqToEntity} against the target's bounding-box minimum Y. */
    private double distanceSqToFeetOf(SimWorld world) {
        double dx = body.x - world.posX();
        double dy = body.y - world.posY();
        double dz = body.z - world.posZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** {@code EntityLookHelper.updateRotation:108-123}, the per-tick turn clamp, verbatim. */
    private static float updateRotation(float from, float to, float maxStep) {
        float f = wrapAngleTo180(to - from);
        if (f > maxStep) {
            f = maxStep;
        }
        if (f < -maxStep) {
            f = -maxStep;
        }
        return from + f;
    }

    /** {@code MathHelper.wrapAngleTo180_float}. */
    private static float wrapAngleTo180(float value) {
        float f = value % 360.0F;
        if (f >= 180.0F) {
            f -= 360.0F;
        }
        if (f < -180.0F) {
            f += 360.0F;
        }
        return f;
    }

    /** {@code EntityLiving.setAttackTarget} through {@code EntityAITarget.startExecuting}. */
    private void acquire(SimWorld world) {
        if (canNotice(world)) {
            hasPath = true;
            // startExecuting():92-94 -- the counter starts at zero so the first updateTask
            // re-paths immediately rather than walking toward a null destination.
            delayCounter = 0;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s(id=%d, hp=%.1f, at %.2f/%.2f/%.2f, hits=%d, refreshes=%d)",
                kind.type(), id, health, body.x, body.y, body.z, hits, pathRefreshes);
    }
}
