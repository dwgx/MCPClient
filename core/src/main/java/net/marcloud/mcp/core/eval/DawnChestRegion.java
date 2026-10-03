package net.marcloud.mcp.core.eval;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.marcloud.mcp.core.drivers.world.Daylight;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * The third north-star ruler, running on a live client: did a chest stand through the night, and
 * where the answer came from.
 *
 * <p><b>What closed the gap this class exists for.</b> {@link DawnChest} takes ONE cell
 * ({@code observe(grid, bx, by, bz, worldTime)}), and on the north-star path nothing supplies that
 * cell: the placement path deliberately does not name it. The audit
 * ({@code .ai-notes/docs/audits/2026-10-03-instrument3-architecture.md}) found two ways forward --
 * teach the placement path to say "placed a block at X", or stop asking for a cell at all. The
 * first is refused there and refused here: {@code InteractController.java:132-137} states the
 * "placed/activated AGAINST" wording is what earns it, and {@code RouteExecutor.java:617-623}
 * records what changing it back cost -- {@code blockPresent} is {@code getMaterial() !=
 * Material.air}, true for water, lava, gravel, tall grass and torches, so the old check confirmed a
 * bridge that was never built, charged the player for a block still in the bag, and walked them
 * onto the water or lava it should have covered. That is a shipped defect; this class does not
 * re-import it.
 *
 * <p>So the second way: <b>watch a REGION and judge every chest cell that appears in it.</b> The
 * Owner's north star says "天亮后箱子还立着" -- a box still standing at dawn -- and it does not name
 * a box. Requiring the agent to name its own box makes the claim stricter than the Owner's and
 * needs a tool surface it does not have; a region census needs none.
 *
 * <p><b>The price, stated where a reader will meet it.</b> This judges "a chest stood through this
 * night", not "the chest the agent built stood through this night". A world that already held a
 * chest satisfies it. That is the cost of closing the gap and it is repeated in {@link #fact()}
 * rather than left to this javadoc, because the sentence a model reads is not this javadoc.
 *
 * <p><b>What this measures on a LIVE client, and what it cannot.</b> The protocol layer is the
 * hard limit and it was verified line by line: {@code NetHandlerPlayClient.java:1276} recognises
 * exactly six tile-entity types (spawner, command block, beacon, skull, flower pot, banner) and
 * {@code TileEntityChest} is not among them, and the chunk-load path carries no tile-entity section
 * either. So "the chest still has its things in it" is <b>not measurable on a real client</b> --
 * there is no accessor here that could pretend otherwise, and this class has none. What IS
 * measurable is "a chest BLOCK is standing", which is what {@link DawnChest#isChest(Block)} asks:
 * pure block identity, no contents.
 *
 * <p><b>Three honest boundaries, in the directions that matter.</b>
 *
 * <ol>
 *   <li><b>The server-to-client window.</b> On a live client the server changes the block and the
 *       change arrives in a packet a few ticks later, so "held throughout" strictly means "held
 *       throughout, or has just now gone". The direction is a FALSE POSITIVE -- a chest destroyed
 *       moments before dawn can still read as standing -- and the eval fixture has no such window
 *       at all, which is exactly why the fixture cannot find this bug.</li>
 *   <li><b>{@code blockAt} answers null for three different things.</b> Air, an unloaded chunk, and
 *       a failed read all arrive as null ({@code RouteExecutor.java:655-661} says so in its own
 *       words). So "moved out of load range" and "really gone" are indistinguishable here. The
 *       direction is a FALSE NEGATIVE -- something alive reported as not standing -- which is the
 *       safe direction but leaves the criterion undecidable rather than decided. The direction
 *       REVERSAL fixed earlier this session ({@code EmptyChunk} answering {@code canSeeSky} false
 *       for a chunk that does not exist) is not re-imported: this class asks
 *       {@link DawnChest#isChest(Block)}, which is false for a null, and never inverts a "could
 *       not read" into a "there is something there".</li>
 *   <li><b>A live client has mobs, explosions and fire.</b> The disclaimer this class replaces said
 *       "no fire / no creeper / no explosion". Those three are true of an eval fixture and FALSE of
 *       a running client, which has hostile mobs, creepers, TNT and lava. A sentence printed to a
 *       model has to be true of the world the model is in.</li>
 * </ol>
 *
 * <p><b>How a cell enters the ledger.</b> A census asks the region for every chest cell in it, and
 * each newly-seen cell is added to the set this night is judged on. From then on the cell is
 * re-read EVERY tick, so a chest removed three ticks after it appeared is caught, and a chest that
 * appeared at 3am is judged only from 3am -- which is why {@link #firstSeenTick()} and
 * {@link #chestsSeen()} are published. The claim "a chest stood through the night" is only as old
 * as the oldest chest in the ledger, and the payload says which tick that is rather than letting a
 * reader assume dusk.
 *
 * <p><b>The census is not per tick, on purpose.</b> A census is a scan of a region and the scan is
 * the expensive half; re-verifying the handful of cells already in the ledger is the cheap half and
 * is what has to happen every tick. So {@link #censusPeriodTicks} defaults to
 * {@value #DEFAULT_CENSUS_PERIOD_TICKS}: a chest that appears is picked up within that many ticks,
 * and the cost of one night is {@code nightTicks / censusPeriodTicks} scans rather than
 * {@code nightTicks} of them. One census walks {@code (2r+1)^3} cells, so at the defaults that is
 * {@code 17^3 = 4913} reads every 40 ticks -- about 123 reads per tick amortised, against the 8
 * {@link NightShelter} spends per sample. That is the price of the region reading and it is a real
 * number rather than an estimate.
 *
 * <p><b>The window is ONE night and the reset is the shelter's reset.</b> {@link NightEnclosure}
 * already resets on the 24,000-tick day cycle plus a backwards-clock detection, and
 * {@link NightShelter} and {@link NightHealth} both copy it. Three rulers that reset on different
 * boundaries would each be describing a different night, and a report reading all three would be
 * joining numbers from different windows.
 *
 * <p><b>Threading.</b> Every field a reader can see is written by {@link #onTick} on the game
 * thread and may be read from any thread, so every one is {@code volatile} and every accessor hands
 * back a copy of a scalar. The ledger -- including the mutable cell map -- is game-thread owned and
 * never published, for the reason {@link NightShelter}'s ledger field gives.
 */
public final class DawnChestRegion {

    /** One re-verification per tick: the cheap half, and the default. */
    public static final int DEFAULT_PERIOD_TICKS = 1;

    /**
     * Ticks between region censuses, the expensive half.
     *
     * <p>Not 1 because a census is a scan of a radius and doing it on every tick of an 8,386-tick
     * night would be 8,386 scans. Not huge either, because a chest the agent just placed should be
     * in the ledger while it still has most of the night left to be judged over. 40 ticks is two
     * seconds at 20Hz, so a box built at 3am is judged from at most two seconds after it was built
     * -- and {@link #latestFirstSeenTick()} makes that lag visible rather than assumed.
     */
    public static final int DEFAULT_CENSUS_PERIOD_TICKS = 40;

    /**
     * The radius, in cells, of the cube the census walks around the player's feet cell.
     *
     * <p>Written down rather than left to a caller because it is a COST, and a cost only described
     * is a cost nobody can decide about.
     */
    public static final int DEFAULT_RADIUS = 8;

    /** Where the ruler gets at the world. GAME THREAD ONLY, like {@link NightShelter.Body}. */
    public interface Area {

        /** The world this area is in, or null when the client is not in one. */
        Object world();

        /** The world clock, in ticks. */
        long worldTime();

        /** The player's feet cell, which is the centre of the censused cube. */
        int feetX();

        int feetY();

        int feetZ();

        /** The cell lookup for this world. Never {@code ActActuator.blockAt}. */
        Enclosure.CellGrid grid();
    }

    private final int periodTicks;
    private final int censusPeriodTicks;

    private Supplier<? extends Area> source;

    /** Game-thread owned and never published: identity is the reset trigger. */
    private Object world;

    /** Game-thread owned and never published: packed cell to the tick it was first censused. */
    private final LinkedHashMap<Long, Long> cells = new LinkedHashMap<>();

    private final Ledger ledger = new Ledger();

    /** Every counter, folded on the game thread and republished as scalars. */
    private static final class Ledger {

        private int samples;
        private int missingSamples;
        private int unreadableSamples;
        private long censuses;
        private long firstNightTick = -1L;
        private long lastNightTick = -1L;
        private long firstAbsentTick = -1L;
        private long earliestFirstSeen = -1L;
        private long latestFirstSeen = -1L;
        private boolean presentOnLastSample;
        private boolean somePresentOnLastSample;
        private long dayCycle = Long.MIN_VALUE;
        private int sinceLastSample;
        private int sinceLastCensus;

        /**
         * Empties the window so the next sample is the first of a night, and takes BOTH countdowns
         * with it.
         *
         * <p>The census countdown goes too, for the reason {@link NightEnclosure} gives about the
         * sampling countdown: a period above one leaves the countdown part-way through the previous
         * night's cadence, and the first night tick of the new night must not go un-censused for a
         * period nobody chose. A new night also starts with an EMPTY cell map -- the cells judged
         * last night are not evidence about this one.
         *
         * <p><b>The day-cycle field is deliberately NOT cleared here</b>, for the same reason
         * {@link NightHealth}'s ledger does not clear its own: {@code observe} assigns the new
         * cycle immediately BEFORE calling this, so clearing it again here would make the very next
         * tick look like a cycle change and reset the window on every single tick.
         */
        private void beginNight() {
            samples = 0;
            missingSamples = 0;
            unreadableSamples = 0;
            censuses = 0L;
            firstNightTick = -1L;
            lastNightTick = -1L;
            firstAbsentTick = -1L;
            earliestFirstSeen = -1L;
            latestFirstSeen = -1L;
            presentOnLastSample = false;
            somePresentOnLastSample = false;
            // dayCycle is NOT reset here -- see the javadoc above. The caller sets it.
            sinceLastSample = 0;
            // The census countdown starts ARMED rather than at zero, and the reason is that the
            // ledger cannot start without one: the cell set is EMPTY on a fresh window, so a
            // countdown that began at zero would spend its first whole period finding nothing and
            // every early tick would re-verify an empty set. The same applies to each new night.
            sinceLastCensus = Integer.MAX_VALUE;
        }
    }

    private volatile long lastClock;
    private volatile boolean lastTickSeen;
    private volatile boolean ticksDelivered;
    private volatile boolean sampled;
    private volatile int reportedSamples;
    private volatile int reportedMissing;
    private volatile int reportedUnreadable;
    private volatile long reportedCensuses;
    private volatile int reportedCells;
    private volatile long reportedFirstNightTick = -1L;
    private volatile long reportedLastNightTick = -1L;
    private volatile long reportedFirstAbsent = -1L;
    private volatile long reportedEarliestFirstSeen = -1L;
    private volatile long reportedLatestFirstSeen = -1L;
    private volatile boolean reportedPresentOnLast;
    private volatile boolean reportedSomePresentOnLast;

    /** The default: one re-verification per tick, a census every 40, radius 8. */
    public DawnChestRegion() {
        this(DEFAULT_PERIOD_TICKS, DEFAULT_CENSUS_PERIOD_TICKS);
    }

    /**
     * @param periodTicks       ticks between re-verifications; 1 means every night tick
     * @param censusPeriodTicks ticks between region censuses; at least 1
     */
    public DawnChestRegion(int periodTicks, int censusPeriodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("sampling period must be at least one tick, got "
                    + periodTicks);
        }
        if (censusPeriodTicks < 1) {
            throw new IllegalArgumentException("census period must be at least one tick, got "
                    + censusPeriodTicks);
        }
        this.periodTicks = periodTicks;
        this.censusPeriodTicks = censusPeriodTicks;
    }

    /** Point the ruler at an area source. Null is legal and means "measures nothing". */
    public DawnChestRegion reading(Supplier<? extends Area> source) {
        this.source = source;
        return this;
    }

    /**
     * Drive this ruler from the kernel's tick seam, so the sample rate is the game's and not a
     * model's polling rate.
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
     * One tick's worth of accounting: a census when one is due, then a re-read of every cell this
     * night is being judged on.
     *
     * <p>A method rather than a hard {@code bus.subscribe} so a caller can drive the ruler by hand,
     * which is how the controls drive it: putting the SAME sequence of worlds in front of the SAME
     * code twice and changing one block between the runs is the only way to prove a counter moved
     * because of that block.
     */
    public void onTick() {
        ticksDelivered = true;
        Area a = source == null ? null : source.get();
        if (a == null || a.world() == null) {
            // No world, no sample, and NOT a reset: the client can be mid-world-load for a tick or
            // two, and throwing the ledger away on that would be a far worse lie than a gap.
            return;
        }
        Object w = a.world();
        if (w != this.world) {
            this.world = w;
            cells.clear();
            ledger.beginNight();
        }
        long now = a.worldTime();
        lastClock = now;
        sampled = observe(a, now);
        lastTickSeen = true;
        if (sampled) {
            Ledger l = ledger;
            reportedSamples = l.samples;
            reportedMissing = l.missingSamples;
            reportedUnreadable = l.unreadableSamples;
            reportedCensuses = l.censuses;
            reportedCells = cells.size();
            reportedFirstNightTick = l.firstNightTick;
            reportedLastNightTick = l.lastNightTick;
            reportedFirstAbsent = l.firstAbsentTick;
            reportedEarliestFirstSeen = l.earliestFirstSeen;
            reportedLatestFirstSeen = l.latestFirstSeen;
            reportedPresentOnLast = l.presentOnLastSample;
            reportedSomePresentOnLast = l.somePresentOnLastSample;
        }
    }

    /**
     * Records one night tick: census if one is due, then re-read every watched cell.
     *
     * @return whether a sample was taken
     */
    private boolean observe(Area a, long worldTime) {
        Ledger l = ledger;
        if (!Daylight.isNight(worldTime)) {
            return false;
        }
        long cycle = dayCycleOf(worldTime);
        if (cycle != l.dayCycle || worldTime <= l.lastNightTick) {
            // Two ways to be looking at a night this ledger is not holding, and both restart the
            // window: the clock entered the NEXT cycle's night, or it was moved BACKWARDS inside
            // the night it was already in (`/time set`), which a day-cycle test alone cannot see
            // because floorDiv of two ticks in the same cycle is the same number.
            l.dayCycle = cycle;
            l.beginNight();
            cells.clear();
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

        if (l.sinceLastCensus >= censusPeriodTicks) {
            l.sinceLastCensus = 0;
            census(a, worldTime, l);
            l.censuses++;
        } else {
            l.sinceLastCensus++;
        }

        Enclosure.CellGrid grid = a.grid();
        boolean allPresent = !cells.isEmpty();
        boolean somePresent = false;
        boolean anyAbsent = false;
        boolean anyUnreadable = false;
        for (Map.Entry<Long, Long> e : cells.entrySet()) {
            long k = e.getKey();
            Block b = grid.at(cellX(k), cellY(k), cellZ(k));
            if (b == null) {
                // NULL IS NOT "there is nothing here" and is not "there is something there"
                // either: air, an unloaded chunk and a failed read all arrive as the same value.
                // Counted apart from a definite non-chest so a failure message can say which it
                // saw, and NEVER counted as present -- inverting an unreadable into a survivor is
                // the direction that was fixed earlier this session and is not re-imported here.
                anyUnreadable = true;
                allPresent = false;
                continue;
            }
            if (DawnChest.isChest(b)) {
                somePresent = true;
            } else {
                anyAbsent = true;
                allPresent = false;
            }
        }
        l.presentOnLastSample = allPresent;
        l.somePresentOnLastSample = somePresent;
        if (anyAbsent) {
            l.missingSamples++;
            if (l.firstAbsentTick < 0L) {
                l.firstAbsentTick = worldTime;
            }
        }
        if (anyUnreadable) {
            l.unreadableSamples++;
        }
        return true;
    }

    /**
     * Adds every chest cell in the region to this night's ledger.
     *
     * <p>The region is a cube of {@link #DEFAULT_RADIUS} around the player's feet cell, walked
     * through the SAME {@link Enclosure.CellGrid} the shelter counter reads -- never through
     * {@code ActActuator.blockAt}, which is the controller seam and carries a per-call world-read
     * bill this instrument must not add to.
     */
    private void census(Area a, long worldTime, Ledger l) {
        Enclosure.CellGrid grid = a.grid();
        int cx = a.feetX();
        int cy = a.feetY();
        int cz = a.feetZ();
        int r = DEFAULT_RADIUS;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (!DawnChest.isChest(grid.at(x, y, z))) {
                        continue;
                    }
                    long key = key(x, y, z);
                    if (cells.putIfAbsent(key, worldTime) == null) {
                        if (l.earliestFirstSeen < 0L || worldTime < l.earliestFirstSeen) {
                            l.earliestFirstSeen = worldTime;
                        }
                        if (worldTime > l.latestFirstSeen) {
                            l.latestFirstSeen = worldTime;
                        }
                    }
                }
            }
        }
    }

    /**
     * Which night of the clock a tick belongs to: the index of the 24,000-tick day cycle.
     *
     * <p>{@link Math#floorDiv} rather than {@code /} so a negative world time lands in the cycle it
     * belongs to, and asked of the CYCLE rather than compared against a remembered tick because a
     * night can be crossed without any of its ticks being sampled.
     */
    private static long dayCycleOf(long worldTime) {
        return Math.floorDiv(worldTime, 24000L);
    }

    /** Packs a cell into one long, the shape vanilla's own position packing uses. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * X occupies bits 38..63, so an arithmetic shift right by 38 sign-extends it.
     *
     * <p>The packing masks with {@code 0x3FFFFFF} rather than storing a raw int for exactly this
     * reason: a raw X would put its sign bit at bit 63 and every cell west of the origin would
     * unpack as a large positive number.
     */
    private static int cellX(long k) {
        return (int) (k >> 38);
    }

    /** Z occupies bits 12..37, so the key is shifted up 26 first and the sign comes back down. */
    private static int cellZ(long k) {
        return (int) (k << 26 >> 38);
    }

    private static int cellY(long k) {
        return (int) (k << 52 >> 52);
    }

    // ===== the readings =====

    /**
     * Whether this instrument has EVER completed a measurement -- the one bit that separates a live
     * ledger from an empty one, copied from {@link NightShelter#measuredOnce()} rather than
     * reinvented, and for the same reason: {@link #measured()} is also false in daylight, and the
     * two states demand opposite readings.
     */
    public boolean measuredOnce() {
        return lastTickSeen;
    }

    /**
     * Whether any night tick was sampled at all. False means the claim was not measured.
     *
     * <p><b>And note what this is NOT gated on: whether the MOST RECENT tick was itself a sample.</b>
     * Every counter below is gated on {@link #measuredOnce()} alone, exactly as
     * {@link NightShelter} gates its own. Gating them on "did this tick sample" looks tidier and is
     * wrong in the one state that matters most: the first daylight tick after a night WIPES the
     * whole night's reading, because that is precisely the tick that took no sample. A reader
     * polling at dawn -- the only moment the night's answer is final, and the moment
     * {@code NightEnclosure} says the window deliberately stays open for -- would get zeros. That
     * was this class's first shipped shape and every night read back as {@code samples == 0}.
     */
    public boolean measured() {
        return lastTickSeen && reportedSamples > 0;
    }

    /**
     * THE POINT READING: every chest cell this night is judging was holding a chest on the last
     * sampled tick.
     *
     * <p>Requires {@link #measured()}, so a clock that never reached night reports not-measured
     * rather than "no box" -- {@code cells.isEmpty()} over zero samples is vacuously true, and a
     * vacuous true is how an instrument becomes a constant that agrees with any world.
     */
    public boolean standingAtDawn() {
        return measured() && reportedPresentOnLast;
    }

    /**
     * THE REGION READING: every chest cell this night is judging held a chest on EVERY sampled tick
     * since it entered the ledger.
     *
     * <p><b>Unreadable samples count against it, deliberately.</b> A cell that could not be read is
     * not evidence that the chest is still there, so it is not treated as one. The direction is a
     * false negative -- something alive reported as not standing -- and that is the safe direction,
     * but it does mean a chest that walked out of load range fails the reading rather than passing
     * it. {@link #unreadableSamples()} is published beside the verdict precisely so a reader can see
     * that the failure was a read failure and not a demolition.
     */
    public boolean heldThroughout() {
        return measured() && reportedMissing == 0 && reportedUnreadable == 0 && reportedCells > 0;
    }

    /** How many distinct chest cells this night is judging. Zero means nothing was ever found. */
    public int chestsSeen() {
        return lastTickSeen ? reportedCells : 0;
    }

    /**
     * The clock tick of the EARLIEST cell's first appearance, or -1 when none was found.
     *
     * <p>Published because it is what the region claim rests on: "a chest stood through the night"
     * is only as old as the oldest chest in the ledger, and a chest that appeared at 3am has only
     * been judged since 3am. A reader who wants "throughout" in the strong sense compares this
     * against {@link #firstNightTick()}.
     */
    public long firstSeenTick() {
        return lastTickSeen ? reportedEarliestFirstSeen : -1L;
    }

    /**
     * The clock tick of the LATEST cell's first appearance, or -1 when none was found.
     *
     * <p>This is the honest upper bound on how stale the ledger can be: a census runs every
     * {@link #censusPeriodTicks()} ticks, so a chest placed just after one is not seen until the
     * next, and this number is when the most recently discovered chest actually appeared.
     */
    public long latestFirstSeenTick() {
        return lastTickSeen ? reportedLatestFirstSeen : -1L;
    }

    public int samples() {
        return lastTickSeen ? reportedSamples : 0;
    }

    /** How many censuses ran this night. The cost, as a number rather than an adjective. */
    public long censuses() {
        return lastTickSeen ? reportedCensuses : 0L;
    }

    /** Night ticks on which a cell was DEFINITELY not a chest -- read back as some other block. */
    public int missingSamples() {
        return lastTickSeen ? reportedMissing : 0;
    }

    /**
     * Night ticks on which a cell could not be read at all -- null from the grid, which is air, an
     * unloaded chunk and a failed read arriving as one value.
     *
     * <p>Kept apart from {@link #missingSamples()} because the two mean different things, and a
     * failure message that cannot say which one it saw is a failure message nobody can act on.
     */
    public int unreadableSamples() {
        return lastTickSeen ? reportedUnreadable : 0;
    }

    /** The clock tick of the first definite non-chest, or -1 when there was none. */
    public long firstAbsentTick() {
        return lastTickSeen ? reportedFirstAbsent : -1L;
    }

    /** Whether AT LEAST ONE cell was still a chest on the last sampled tick. */
    public boolean someStandingAtDawn() {
        return measured() && reportedSomePresentOnLast;
    }

    public long firstNightTick() {
        return lastTickSeen ? reportedFirstNightTick : -1L;
    }

    public long lastNightTick() {
        return lastTickSeen ? reportedLastNightTick : -1L;
    }

    public long worldTime() {
        return lastTickSeen ? lastClock : 0L;
    }

    public int periodTicks() {
        return periodTicks;
    }

    public int censusPeriodTicks() {
        return censusPeriodTicks;
    }

    /** The censused radius, so a payload can print the cost it paid. */
    public int radius() {
        return DEFAULT_RADIUS;
    }

    /** How long one night is in ticks, taken from the instrument that already derives it. */
    public static int nightTicks() {
        return NightEnclosure.nightTicks();
    }

    /**
     * The sentence a result row carries: the window, the resolution, both readings, and every
     * honest boundary in words rather than in a javadoc a model never reads.
     *
     * <p>Two causes get two sentences, for the reason {@link NightShelter} splits them: a seam
     * that never delivered a tick and a client ticking with no world are different faults with
     * different fixes, and naming the seam in the state where the seam is demonstrably running is
     * itself the defect.
     */
    public String fact() {
        if (!lastTickSeen) {
            if (!ticksDelivered) {
                return "box: NOT MEASURED -- the tick seam has not delivered a tick yet, so this says"
                        + " nothing about a box. A client that never ticked never looked";
            }
            return "box: NOT MEASURED -- ticks ARE arriving but none of them could be measured: there"
                    + " is no world to stand in yet (title screen, still loading, or just left one),"
                    + " so every number below is a placeholder rather than a reading";
        }
        if (!measured()) {
            return "box: NOT MEASURED -- no night tick was sampled (clock " + lastClock + "). A world"
                    + " with the daylight cycle off never reaches night, and a window measured over"
                    + " zero ticks must not report a box";
        }
        if (reportedCells == 0) {
            return String.format(Locale.ROOT,
                    "box: NOT MEASURED -- %d night sample(s) at %d tick(s) per sample, clock %d..%d,"
                        + " and %d census(es) of a radius-%d region every %d tick(s) found NO chest"
                        + " at all, so this row is about a box that was never seen rather than a box"
                        + " that did not survive",
                    reportedSamples, periodTicks, reportedFirstNightTick, reportedLastNightTick,
                    reportedCensuses, DEFAULT_RADIUS, censusPeriodTicks);
        }
        return String.format(Locale.ROOT,
                "box: %s -- %d night sample(s) at %d tick(s) per sample, clock %d..%d; %d chest"
                    + " cell(s) censused (radius %d, %d census(es) at one per %d tick(s)), the oldest"
                    + " first seen at clock %d and the newest at %d; at the last sampled tick all of"
                    + " them standing: %s, at least one standing: %s; throughout: %s"
                    + " (definitely-gone samples %d, unreadable samples %d%s). This is a REGION"
                    + " reading: it says A chest stood through this night, NOT that the chest YOU"
                    + " built did. CONTENTS are not measured at all -- the 1.8.9 protocol layer"
                    + " carries no TileEntityChest, so a closed chest's inventory is not on this"
                    + " client. On a live client mobs, creepers, explosions and lava ARE present:"
                    + " nothing here rules any of them out. The server's change to a block also"
                    + " reaches the client a few ticks late, so 'throughout' includes 'has just now"
                    + " gone'; and a cell that left load range reads as no chest, which is"
                    + " indistinguishable from one that was demolished",
                reportedMissing == 0 && reportedUnreadable == 0
                        ? "MEASURED, every chest cell stood throughout"
                        : "MEASURED, at least one chest cell did not stand throughout",
                reportedSamples, periodTicks, reportedFirstNightTick, reportedLastNightTick,
                reportedCells, DEFAULT_RADIUS, reportedCensuses, censusPeriodTicks,
                reportedEarliestFirstSeen, reportedLatestFirstSeen,
                reportedPresentOnLast ? "yes" : "no",
                reportedSomePresentOnLast ? "yes" : "no",
                reportedMissing == 0 && reportedUnreadable == 0 ? "yes" : "no",
                reportedMissing, reportedUnreadable,
                reportedFirstAbsent < 0L ? "" : ", first definitely gone at clock "
                        + reportedFirstAbsent);
    }

    @Override
    public String toString() {
        return fact();
    }
}