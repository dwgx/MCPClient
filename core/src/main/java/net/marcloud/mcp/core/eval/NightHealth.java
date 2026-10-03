package net.marcloud.mcp.core.eval;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.marcloud.mcp.core.drivers.world.Daylight;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * The second north-star ruler, running: how low the health bar has been over a night, and whether
 * it was ever hit.
 *
 * <p><b>Why this is not {@link SurvivalDamage}.</b> That class is the SERVER's arithmetic,
 * transcribed: it holds a health bar and applies vanilla's damage rules to hazards the caller
 * already knows about. Its only constructor call anywhere in this repository is
 * {@code SimWorld.java:272}, which is test tree, so on a running client it does not exist. This
 * class is the other half -- it reads a bar the SERVER has already moved and answers where it got
 * to. The two are not alternatives: one says "what would this cost", the other says "so apply
 * it", and only the second is reachable from a live client.
 *
 * <p><b>Why the accumulator reads a field per tick rather than tapping the wire.</b> The packet
 * route exists ({@code S06PacketUpdateHealth} to {@code NetHandlerPlayClient.handleUpdateHealth}
 * to {@code EntityPlayerSP.setPlayerSPHealth}) and was the previous answer, but the edge is
 * computed by PRODUCTION code that runs on the game thread on every real transition:
 *
 * <ul>
 *   <li>{@code EntityPlayerSP.setPlayerSPHealth:350} -- {@code float f = this.getHealth() - health}
 *       is the size of the drop;</li>
 *   <li>{@code :363} -- {@code this.lastDamage = f}, i.e. the drop is remembered in a field;</li>
 *   <li>{@code :367} -- {@code this.hurtTime = this.maxHurtTime = 10}, the "you were hit"
 *       signature;</li>
 *   <li>{@code :370-374} -- the FIRST packet takes the else branch and is a BASELINE, not an edge,
 *       so a caller must not read it as "fell to X".</li>
 * </ul>
 *
 * <p>So taking the MIN of {@code getHealth()} per tick sees every drop the client was told about,
 * without a Netty tap, without an opt-in, and without a new component. What per-tick sampling
 * cannot do is see a drop that was REFUSED: {@code EntityPlayerSP.damageEntity:319-325}
 * overrides the base and is a subtraction guarded by {@code isEntityInvulnerable}, so when that
 * guard is true the health bar does not move -- and yet {@code :363} and {@code :367} have
 * already been written. That is why this class publishes THREE series rather than one number, and
 * why they are not combined into a boolean: see {@link #absorbedHits()}.
 *
 * <p><b>Why three series and not one verdict.</b> Each one has a case the others cannot see:
 *
 * <ul>
 *   <li>{@link #minHealth()} sees "the bar really got low", and is blind to a hit the
 *       invulnerability guard refused -- the bar never moved, so there is nothing to take a min
 *       of.</li>
 *   <li>{@link #hitEdges()} sees "something hit the player", and is blind to how big it was.</li>
 *   <li>{@link #maxLastDamage()} sees how big the biggest registered hit was, and is blind to
 *       whether it moved the bar.</li>
 * </ul>
 *
 * <p>Reducing them to one boolean throws away the difference between "the player was never in
 * danger" and "the player was hit and the game refused the damage", which are opposite situations
 * with opposite correct behaviour. {@link #floorHeld()} is the north-star verdict and is derived
 * from {@link #minHealth()} alone -- deliberately, because the claim is about the BAR.
 *
 * <p><b>The window is ONE night, and the reset is the shelter's reset, not a new one.</b>
 * {@link NightEnclosure} already resets on the 24,000-tick day cycle
 * ({@code Math.floorDiv(worldTime, 24000L)}) plus a backwards-clock detection, and
 * {@link NightShelter} publishes that ledger. Copying the shape rather than inventing a second one
 * is not tidiness: three rulers that reset on different boundaries would each be describing a
 * different night, and a report reading all three would be joining numbers from different windows.
 * The floor claim is about a NIGHT, so this samples only when {@link Daylight#isNight(long)} says
 * the clock is past dusk -- the same production call {@code SimWorld.isNight()} makes, so there is
 * one rule about when night begins and this class cannot be the second place it is written.
 *
 * <p><b>Driven by the tick, for the reason {@link NightShelter} gives.</b> A poll-driven
 * accumulator would make the model's polling rate the sample rate, and that decision would be
 * made silently. This subscribes to the kernel's own {@link TickEvent} and reads the bar on the
 * game thread. One tick costs three field reads and zero world reads: {@code getHealth()} is a
 * {@code DataWatcher} float lookup, {@code hurtTime} is a public int, and {@code lastDamage} is a
 * {@code protected} float reached through a {@code Field} resolved ONCE (see {@code ClientVitals}).
 *
 * <p><b>The first reading is a baseline and can only read HIGH.</b>
 * {@code EntityLivingBase:201} calls {@code setHealth(getMaxHealth())} in the constructor, so
 * before the server has ever sent an S06 the bar reads a full 20. The first sample therefore
 * cannot manufacture a low reading; it can only be replaced by the server's own value on the
 * first packet. The direction of that blind spot is the safe one, and it is stated here rather
 * than left for a reader to assume.
 *
 * <p><b>What this cannot see, in the order a reader is likely to need it.</b> Damage dealt while
 * the chunk holding the player is unloaded, and any damage the server applied and then reversed
 * between two S06 packets -- both leave no trace on a per-tick read of a client-side field. And
 * {@link #hitEdges()} counts EDGES of {@code hurtTime}, which vanilla also sets on a KNOCKBACK
 * ({@code EntityLivingBase.knockBack:1184-1186}) and which is written once per received hit, so a
 * window containing two hits on one tick sees one edge. {@link #absorbedHits()} reports the
 * difference as a difference and does not claim to pair the two series tick by tick.
 *
 * <p><b>Threading.</b> Every field a reader can see is written by {@link #onTick} on the game
 * thread and may be read from any thread, so every one is {@code volatile} and every accessor
 * hands back a copy of a scalar -- the same SET OF SNAPSHOTS rule {@link NightShelter} publishes
 * and documents. The accumulator is game-thread owned and is never handed out.
 */
public final class NightHealth {

    /** One sample per tick. The default, and the reason {@link #NightHealth(int)} exists. */
    public static final int DEFAULT_PERIOD_TICKS = 1;

    /**
     * The project's north-star floor, re-exported rather than restated.
     *
     * <p>{@link SurvivalDamage#NORTH_STAR_FLOOR} is where the number is decided, because that is
     * the class the damage that can cross it lives in. A threshold written twice is a threshold
     * that will be edited on one side, and the edit would then be a constant nobody notices.
     */
    public static final float NORTH_STAR_FLOOR = SurvivalDamage.NORTH_STAR_FLOOR;

    private final int periodTicks;

    private Supplier<? extends Vitals> source;

    /** The world identity the current ledger belongs to, or null before the first sample. */
    private Object world;

    /**
     * Game-thread owned and never published directly, for the reason
     * {@link NightShelter}'s ledger field gives: it is rewritten in place on every sampled tick and
     * its counters are plain fields, so handing it to another thread would give that thread
     * neither a current answer nor a happens-before edge to the game thread.
     */
    private final Ledger ledger = new Ledger();

    // ===== everything below is what a reader off the game thread can see =====

    private volatile long lastClock;
    private volatile boolean lastTickSeen;
    private volatile boolean ticksDelivered;
    private volatile boolean sampled;
    private volatile boolean lastSampleBelowFloor;
    private volatile boolean lastDamageReadable;
    private volatile float lowest = Float.NaN;
    private volatile float biggestHit = Float.NaN;
    private volatile long healthDrops;
    private volatile long hitEdges;
    private volatile long firstHitTick = -1L;
    private volatile long firstBelowFloorTick = -1L;
    private volatile long firstNightTick = -1L;
    private volatile long lastNightTick = -1L;
    private volatile int samples;

    /** What the accumulator gets at a player. GAME THREAD ONLY, like {@link NightShelter.Body}. */
    public interface Vitals {

        /**
         * The world this player is in, or null when the client is not in one. Identity is the
         * reset trigger, exactly as it is for {@link NightShelter.Body#world()}: a change of
         * object is a change of world, not a change of bar.
         */
        Object world();

        /** The world clock, in ticks. */
        long worldTime();

        /**
         * {@code Entity.getHealth()} -- the datawatcher float at index 6.
         *
         * <p>Negative when the bar could not be read, so an unreadable reading is never mistaken
         * for a full one: a full bar is 20 and the floor of 18 sits below it, so a clamped-to-zero
         * unreadable would read as a corpse.
         */
        float health();

        /**
         * {@code EntityLivingBase.lastDamage} -- the largest drop the client was told about,
         * written at {@code EntityPlayerSP.setPlayerSPHealth:363}.
         *
         * <p>{@link Float#NaN} when the field could not be read at all (see {@code ClientVitals}),
         * which the accumulator SKIPS rather than folding in as a zero -- a zero here would be a
         * claim that no damage ever landed.
         */
        float lastDamage();

        /** {@code EntityLivingBase.hurtTime} -- the public int, 10 for ten ticks after a hit. */
        int hurtTime();
    }

    /** One night's worth of counters, folded on the game thread and never published. */
    private static final class Ledger {

        private int samples;
        private float lowest = Float.NaN;
        private float biggestHit = Float.NaN;
        private long healthDrops;
        private long hitEdges;
        private long firstHitTick = -1L;
        private long firstBelowFloorTick = -1L;
        private long firstNightTick = -1L;
        private long lastNightTick = -1L;
        private long dayCycle = Long.MIN_VALUE;
        private int sinceLastSample;
        private int prevHurtTime;
        private float prevHealth = Float.NaN;
        private boolean lastSampleBelowFloor;
        /** Whether {@code lastDamage} produced a real reading at any sample this window. */
        private boolean lastDamageReadable;

        /**
         * Empties the window so the next sample is the first of a night, and takes the sampling
         * countdown with it.
         *
         * <p>The countdown goes too, for the reason {@link NightEnclosure} gives: a period above
         * one leaves the countdown part-way through the previous night's cadence, so without
         * resetting it here the first night tick of the new night would go unsampled for a period
         * nobody chose.
         *
         * <p><b>The day-cycle field is deliberately NOT cleared here</b>, for the same reason
         * {@link NightEnclosure} leaves its own {@code nightCycle} alone: {@code observe} assigns
         * the new cycle immediately BEFORE calling this, so clearing it again here would make the
         * very next tick look like a cycle change -- resetting the window on every single tick and
         * leaving a ledger that could never hold more than one sample. That was this class's first
         * shipped shape and it reported {@code samples == 1} on every night.
         */
        private void beginNight() {
            sinceLastSample = 0;
            samples = 0;
            lowest = Float.NaN;
            biggestHit = Float.NaN;
            healthDrops = 0L;
            hitEdges = 0L;
            firstHitTick = -1L;
            firstBelowFloorTick = -1L;
            firstNightTick = -1L;
            lastNightTick = -1L;
            // dayCycle is NOT reset here -- see the javadoc above. The caller sets it.
            prevHurtTime = 0;
            prevHealth = Float.NaN;
            lastSampleBelowFloor = false;
            lastDamageReadable = false;
        }
    }

    /** The default: one sample per tick. */
    public NightHealth() {
        this(DEFAULT_PERIOD_TICKS);
    }

    /**
     * @param periodTicks ticks between samples; 1 means every night tick. Anything larger can
     *                    miss a shorter dip, and the floor claim is about the BAR, so a coarser
     *                    period reports a floor that was never actually held.
     */
    public NightHealth(int periodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("sampling period must be at least one tick, got "
                    + periodTicks);
        }
        this.periodTicks = periodTicks;
    }

    /** Point the accumulator at a player source. Null is legal and means "measures nothing". */
    public NightHealth reading(Supplier<? extends Vitals> source) {
        this.source = source;
        return this;
    }

    /**
     * Drive this accumulator from the kernel's tick seam, so the sample rate is the game's and not
     * a model's polling rate.
     *
     * <p>Returns the handler so a caller can {@link EventBus#unsubscribe} exactly what it
     * subscribed, the same discipline {@code ActTickLoop.detach} follows.
     */
    public Consumer<TickEvent> attach(EventBus bus) {
        Consumer<TickEvent> handler = e -> onTick();
        bus.subscribe(TickEvent.class, handler);
        return handler;
    }

    /**
     * One tick's worth of accounting.
     *
     * <p>A consumer rather than a hard {@code bus.subscribe} so a caller can drive the accumulator
     * by hand, which is how the controls drive it: putting the SAME sequence of bars in front of
     * the SAME code twice and changing one value between the runs is the only way to prove a
     * counter moved because of that value.
     */
    public void onTick() {
        ticksDelivered = true;
        Vitals v = source == null ? null : source.get();
        if (v == null || v.world() == null) {
            // No player, no sample, and NOT a reset: the client can be mid-world-load for a tick
            // or two, and throwing the ledger away on that would be a far worse lie than a gap.
            return;
        }
        Object w = v.world();
        if (w != this.world) {
            this.world = w;
            ledger.beginNight();
        }
        long now = v.worldTime();
        lastClock = now;
        sampled = observe(v, now);
        lastTickSeen = true;
        if (sampled) {
            Ledger l = ledger;
            samples = l.samples;
            lowest = l.lowest;
            biggestHit = l.biggestHit;
            healthDrops = l.healthDrops;
            hitEdges = l.hitEdges;
            firstHitTick = l.firstHitTick;
            firstBelowFloorTick = l.firstBelowFloorTick;
            firstNightTick = l.firstNightTick;
            lastNightTick = l.lastNightTick;
            lastDamageReadable = l.lastDamageReadable;
            lastSampleBelowFloor = l.lastSampleBelowFloor;
        }
    }

    /**
     * Records one night tick: what the three production fields read.
     *
     * @return whether a sample was taken
     */
    private boolean observe(Vitals v, long worldTime) {
        Ledger l = ledger;
        if (!Daylight.isNight(worldTime)) {
            return false;
        }
        long cycle = dayCycleOf(worldTime);
        if (cycle != l.dayCycle || worldTime <= l.lastNightTick) {
            // Two ways to be looking at a night this ledger is not holding, and both restart the
            // window: the clock has entered the NEXT cycle's night, or it has been moved BACKWARDS
            // inside the night it was already in (`/time set`). floorDiv of 14080 and of 22192 are
            // both 0, so a day-cycle test alone cannot see the second one, and folding it again
            // would report one night as nearly two.
            l.dayCycle = cycle;
            l.beginNight();
        }
        if (l.sinceLastSample > 0) {
            l.sinceLastSample--;
            return false;
        }
        l.sinceLastSample = periodTicks - 1;
        l.samples++;
        if (l.firstNightTick < 0L) {
            l.firstNightTick = worldTime;
        }
        l.lastNightTick = worldTime;

        float hp = v.health();
        float dmg = v.lastDamage();
        int hurt = v.hurtTime();

        // lastDamage is SKIPPED when it could not be read, rather than folded in as a zero: a zero
        // here is a claim that the biggest registered hit was worth nothing, and the honest reading
        // of an unreadable field is "there is no reading".
        if (!Float.isNaN(dmg)) {
            l.lastDamageReadable = true;
            if (Float.isNaN(l.biggestHit) || dmg > l.biggestHit) {
                l.biggestHit = dmg;
            }
        }

        if (!Float.isNaN(hp)) {
            if (Float.isNaN(l.lowest) || hp < l.lowest) {
                l.lowest = hp;
            }
            if (!Float.isNaN(l.prevHealth) && hp < l.prevHealth) {
                // The bar went DOWN between two consecutive samples. This is the series that
                // distinguishes "really low" from "hit and refused", and it is deliberately a
                // count of BARS rather than a count of packets: two S06 packets landing on one
                // tick would move it once, and 1.8.9's regen rate cannot reach that window.
                l.healthDrops++;
            }
            l.prevHealth = hp;
            l.lastSampleBelowFloor = hp < NORTH_STAR_FLOOR;
            if (l.lastSampleBelowFloor && l.firstBelowFloorTick < 0L) {
                l.firstBelowFloorTick = worldTime;
            }
        }

        if (hurt > 0 && l.prevHurtTime <= 0) {
            // A RISING edge of hurtTime, which vanilla writes at
            // EntityPlayerSP.setPlayerSPHealth:367 and decrements at EntityLivingBase:337-339.
            // Rising rather than "> 0" because the field stays at 10 for ten ticks: counting
            // "> 0" would call one hit ten.
            //
            // This edge is NOT proof of damage. EntityLivingBase.knockBack:1184-1186 sets the same
            // field, so a knockback counts here. Stated in the payload rather than filtered out,
            // because a filtered edge would make "hit" and "damaged" the same word again.
            l.hitEdges++;
            if (l.firstHitTick < 0L) {
                l.firstHitTick = worldTime;
            }
        }
        l.prevHurtTime = hurt;
        return true;
    }

    /**
     * Which night of the clock a tick belongs to: the index of the 24,000-tick day cycle.
     *
     * <p>{@link Math#floorDiv} rather than {@code /} so a negative world time lands in the cycle
     * it belongs to, and asked of the CYCLE rather than compared against a remembered tick
     * because a night can be crossed without any of its ticks being sampled.
     */
    private static long dayCycleOf(long worldTime) {
        return Math.floorDiv(worldTime, 24000L);
    }

    // ===== the three series, independently =====

    /**
     * The lowest the bar has been on any sampled night tick of this window.
     *
     * <p>{@link Float#NaN} before the first sample, which is a different answer from 20.0 and from
     * 0.0: a ruler that had never looked must not be able to print a number that reads as a
     * verdict in either direction.
     */
    public float minHealth() {
        return lastTickSeen ? lowest : Float.NaN;
    }

    /**
     * The largest drop the client was told about on any sampled night tick of this window, in
     * half-hearts -- {@code EntityPlayerSP.setPlayerSPHealth:350} computes {@code f} as
     * {@code old - new} and {@code :363} stores it.
     *
     * <p>{@link Float#NaN} when the field could not be read, or before the first sample. The two
     * are different: a NaN after a window has been sampled means the field was unreadable, and a
     * NaN before one means nothing has been looked at.
     */
    public float maxLastDamage() {
        return lastTickSeen ? biggestHit : Float.NaN;
    }

    /**
     * How many times the bar went DOWN between two consecutive samples of this window.
     *
     * <p>Not a count of packets and not a count of damage events: a count of bars, which is what
     * the client can actually see. It is the series that separates "the bar really dropped" from
     * "something was registered against the player", and {@link #absorbedHits()} is the difference
     * between the two.
     */
    public long healthDrops() {
        return lastTickSeen ? healthDrops : 0L;
    }

    /**
     * How many rising edges of {@code EntityLivingBase.hurtTime} this window saw -- how many times
     * the client registered a hit or a knockback.
     *
     * <p>One hit writes the field once and it stays at 10 for ten ticks, so this counts EDGES.
     * Two hits landing on one tick are one edge, and the payload says so.
     */
    public long hitEdges() {
        return lastTickSeen ? hitEdges : 0L;
    }

    /**
     * How many registered hits did not move the bar, as a DIFFERENCE between two series rather
     * than a pairing of them.
     *
     * <p>This is the number that makes "hit but invulnerable" visible. {@code
     * EntityPlayerSP.damageEntity:319-325} overrides the base class with a subtraction guarded by
     * {@code isEntityInvulnerable}, so when that guard holds the bar does not move -- and yet
     * {@code :363 lastDamage = f} and {@code :367 hurtTime = 10} have already been written. A
     * min-of-health alone is blind to that event entirely; a max of {@code lastDamage} is not.
     *
     * <p><b>It is a difference, and the difference is not a causal proof.</b> The two series are
     * counted independently and are not matched tick by tick, because they are not guaranteed to
     * land on the same tick: the packet handler and the tick subscriber are both on the game
     * thread and their order within a tick is not pinned by anything in this repository. So a
     * non-zero value means "some registered hit did not move the bar in this window", which is the
     * honest reading; attributing it to a specific tick, or to invulnerability specifically, would
     * be a claim this instrument cannot make. Never negative: two hits on one tick are one edge
     * and one drop, so the edge count can trail the drop count rather than lead it.
     */
    public long absorbedHits() {
        if (!lastTickSeen) {
            return 0L;
        }
        return Math.max(0L, hitEdges - healthDrops);
    }

    /** Whether {@link #maxLastDamage()} has a real reading rather than an unreadable field. */
    public boolean lastDamageReadable() {
        return lastTickSeen && lastDamageReadable;
    }

    /**
     * THE NORTH-STAR VERDICT: the bar never went below 18 over this night.
     *
     * <p>Derived from {@link #minHealth()} ALONE, and that is the whole point rather than an
     * omission. The claim is about the BAR, so it is the bar's low-water mark that decides it --
     * and adding {@link #hitEdges()} to the conjunction would turn a night in which the player was
     * hit and absorbed into a reported floor breach, which is a different event with different
     * consequences. The hits are published beside it, never folded into it.
     */
    public boolean floorHeld() {
        return lastTickSeen && !Float.isNaN(lowest) && lowest >= NORTH_STAR_FLOOR;
    }

    public boolean measured() {
        return lastTickSeen && samples > 0;
    }

    /**
     * Whether this instrument has EVER completed a measurement -- the one bit that separates a
     * live ledger from an empty one, copied from {@link NightShelter#measuredOnce()} rather than
     * reinvented.
     *
     * <p>A LATCH, not a level: set at the first tick that had a world to measure and never
     * cleared, because a world change starts a new ledger rather than un-starting this one. It is
     * checked BEFORE {@link #measured()} in the payload and in {@link #fact()}, because
     * {@code measured} is also false in daylight and the two states demand opposite readings.
     */
    public boolean measuredOnce() {
        return lastTickSeen;
    }

    /** How many night ticks were sampled, which is the resolution of the window. */
    public int samples() {
        return lastTickSeen ? samples : 0;
    }

    /** The clock tick of the first sample below the floor, or -1 when it never was. */
    public long firstBelowFloorTick() {
        return lastTickSeen ? firstBelowFloorTick : -1L;
    }

    /** The clock tick of the first registered hit or knockback, or -1 when there was none. */
    public long firstHitTick() {
        return lastTickSeen ? firstHitTick : -1L;
    }

    public long firstNightTick() {
        return lastTickSeen ? firstNightTick : -1L;
    }

    public long lastNightTick() {
        return lastTickSeen ? lastNightTick : -1L;
    }

    /** The world clock of the most recent tick, or 0 before the first one. */
    public long worldTime() {
        return lastTickSeen ? lastClock : 0L;
    }

    /** Whether the MOST RECENT sample found the bar below the floor. */
    public boolean belowFloorNow() {
        return lastTickSeen && sampled && lastSampleBelowFloor;
    }

    public int periodTicks() {
        return periodTicks;
    }

    /** How long one night is in ticks, taken from the instrument that already derives it. */
    public static int nightTicks() {
        return NightEnclosure.nightTicks();
    }

    /**
     * One sentence for a surface, carrying the window, the resolution, the verdict and -- when the
     * verdict is positive -- the hits that did not cause it.
     *
     * <p>The trailing clauses are load-bearing rather than decorative. A row that printed only
     * {@code floorHeld=true} would read as "the player was never in danger tonight", and the
     * player may have been hit five times and absorbed all five. That is a survivable night and a
     * dangerous one, and a model choosing whether to fight or flee needs the difference.
     */
    public String fact() {
        if (!lastTickSeen) {
            // Two causes, two sentences, for the reason NightShelter splits them: naming the SEAM
            // in the state where the seam is demonstrably running is itself the defect.
            if (!ticksDelivered) {
                return "floor: NOT MEASURED -- the tick seam has not delivered a tick yet, so this"
                        + " says nothing about health. A client that never ticked never looked";
            }
            return "floor: NOT MEASURED -- ticks ARE arriving but none of them could be measured:"
                    + " there is no world to stand in yet (title screen, still loading, or just"
                    + " left one), so every number below is a placeholder rather than a reading."
                    + " A client ticking in a world it has not loaded has ticked without ever"
                    + " looking";
        }
        if (!measured()) {
            return "floor: NOT MEASURED -- no night tick was sampled (clock " + lastClock
                    + "). A world with the daylight cycle off never reaches night, and a window"
                    + " measured over zero ticks must not report a floor";
        }
        return String.format(Locale.ROOT,
                "floor: %s -- the bar's low-water mark over %d night sample(s) at %d tick(s) per"
                        + " sample, clock %d..%d, was %.1f against a floor of %.1f%s;"
                        + " %d registered hit(s) or knockback(s) of which %d moved the bar and %d"
                        + " did not, and the largest registered drop was %s",
                floorHeld() ? "HELD" : "BROKEN",
                samples, periodTicks, firstNightTick, lastNightTick, lowest, NORTH_STAR_FLOOR,
                floorHeld() ? "" : ", first below the floor at clock " + firstBelowFloorTick,
                hitEdges, healthDrops, absorbedHits(),
                lastDamageReadable ? String.format(Locale.ROOT, "%.1f", biggestHit)
                        : "an unreadable field (lastDamage could not be read on this build)");
    }

    @Override
    public String toString() {
        return fact();
    }
}