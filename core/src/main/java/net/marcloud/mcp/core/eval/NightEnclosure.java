package net.marcloud.mcp.core.eval;

import java.util.Locale;

import net.marcloud.mcp.core.drivers.world.Daylight;

/**
 * Was the player enclosed across a NIGHT, rather than at a moment.
 *
 * <p><b>Why this class exists at all.</b> {@link Enclosure} answers "is this body enclosed right
 * now", and that question has a correct answer at dawn that means nothing. A roof built at 4am is
 * standing when the sun comes up; a run that measures enclosure at dawn therefore reports a
 * shelter for a player who spent the whole night in the open. The claim a player makes about a
 * night is a claim about a REGION of time, so the region is the unit: this class folds one
 * night's ticks into one ledger and answers from the ledger.
 *
 * <p><b>The unit is ONE night</b>, not "every night since the client connected", and the reason
 * is a second consumer: {@link DawnChest}, which carries the fourth north-star verdict, is
 * measured over a single night as well. Two consumers of a night window that are looking at
 * different nights cannot both be right about the same run.
 *
 * <p><b>The rule, stated so it can be argued with.</b> The night the ledger is holding is
 * sheltered when it has at least one night sample and <em>every one of those samples</em> is
 * enclosed. Not a majority, not an average, not "at dawn": a single unsheltered night tick means
 * the player was standing in the open at some instant, and one instant is all it takes to be
 * reached. The symmetric error is the one this rejects: {@code openSamples == 0} over a list of
 * zero samples is vacuously true, so {@link #sheltered()} requires {@link #measured()} first. A
 * clock that never reaches night
 * produces zero samples, and the answer there is NOT MEASURED -- which is the same discipline
 * {@code SimWorld.KNOWN_GAPS} states for lighting and {@code EvalSuite.survived} states for a run
 * no hazard reached.
 *
 * <p><b>Where the window closes, and why that is a clock question.</b> The ledger starts empty
 * and fills from the first tick of the night the clock is currently in; when the clock enters
 * the NEXT cycle's night, the window it was holding is discarded and a new one begins. The
 * trigger is the day cycle derived from {@link Daylight#isNight(long)}, not the {@code World} a
 * caller happens to hand in: world identity changes when the player changes dimension, which is
 * a different event, and it does not change at all between two nights in one world -- which is
 * exactly the case that used to accumulate every night's ticks into one growing total. The reset
 * waits for the NEXT dusk rather than firing at dawn because a night's verdict is final at dawn
 * and {@code NightShelter}'s surface promises the same shape read early for the gradient and at
 * dawn for the answer.
 *
 * <p><b>The window is the clock's, not a tick count written here.</b> A sample is taken only when
 * {@link Daylight#isNight(long)} says the clock is on the far side of dusk, which is the same
 * production call {@code SimWorld.isNight()} and {@code EvalSuite.T24} already make, so there is
 * one rule about when night begins and this class cannot be the second one. The boundary ticks are
 * not written down either: {@link #duskTick()} and {@link #nightTicks()} derive them by asking
 * {@code Daylight} forward, the same way {@code SimWorld.ticksUntilDawn()} does.
 *
 * <p><b>Why the sampling period defaults to every tick.</b> The obvious cost of running this on
 * every tick of an 8,386-tick night is a block scan per tick, and the answer is that the scan
 * costs the controller seam nothing: {@link Enclosure.CellGrid} is a plain cell lookup, so
 * {@code SimWorld.seamReads()} is unchanged by it. The measurement is exact per sample -- it is
 * not a sampled-in-time approximation of the blocks -- so the only thing a period coarsens is
 * <em>when</em> the body is looked at, and a body that was exposed for three ticks in the middle
 * of a night would be reported as sheltered. That is the exact class of blindness this project's
 * controls exist to catch, so the default is one and a caller who wants a cheaper instrument has
 * to say so by passing a period. {@code TheEnclosureCostsTheWalkNoWorldReadTest} measures the cost
 * rather than arguing about it.
 */
public final class NightEnclosure {

    /**
     * One sample per tick. The default, and the reason the arguments below exist: see the class
     * doc.
     */
    public static final int DEFAULT_PERIOD_TICKS = 1;

    private final int periodTicks;
    private int sinceLastSample;

    private int samples;
    private int openSamples;
    private int currentOpenRun;
    private int longestOpenRun;

    /**
     * The 24,000-tick day cycle this ledger is currently folding the night out of, or
     * {@link Long#MIN_VALUE} before it has folded any.
     *
     * <p>Game-thread owned like every other counter here, and read only by {@link #observe}. It is
     * the reset trigger, and it is deliberately NOT the {@code World} the caller supplies: world
     * identity changes when the player changes dimension and never changes between two nights in
     * one world, so it is the wrong event for a per-night window.
     */
    private long nightCycle = Long.MIN_VALUE;

    private long firstNightTick = -1L;
    private long lastNightTick = -1L;
    private long firstOpenTick = -1L;

    /**
     * What the MOST RECENT sample saw, as a verdict rather than as a movement in a counter.
     *
     * <p>Written by {@link #observe} on every sampled tick and nowhere else. A caller that wants
     * "is the body reachable right now" reads this; a caller that worked it out by comparing
     * {@link #openSamples()} against its own previous reading gets a wrong answer at a window
     * boundary, because the counter is reset by the window and the caller's remembered value is
     * not. See {@link #lastSampleOpen()}.
     */
    private boolean lastSampleOpen;

    public NightEnclosure() {
        this(DEFAULT_PERIOD_TICKS);
    }

    /**
     * @param periodTicks ticks between samples; 1 means every tick, and anything larger can miss a
     *                    shorter exposure, which is why the default is 1
     */
    public NightEnclosure(int periodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("sampling period must be at least one tick, got "
                    + periodTicks);
        }
        this.periodTicks = periodTicks;
    }

    /**
     * Records one tick of a night: an enclosure verdict, if the clock says it is night.
     *
     * <p>A tick outside the night is not counted at all, which is what lets a caller drive the
     * whole night without deciding for itself where the window starts. Neither does a daylight
     * tick END the window: the ledger keeps the night it folded through every tick of the day
     * after, and starts again at the next dusk.
     *
     * <p>A caller therefore has to feed this every tick for the window to be the one the clock
     * describes. Drive night 1 and then night 2 through the SAME instance without a tick in
     * between and the answer at the end of night 2 is night 2's -- which is what
     * {@code TheShelterLedgerIsTheCurrentNightAndNotTheSumOfEveryNightTest} measures, including
     * on the code where it was the sum of both.
     *
     * @return whether a sample was taken
     */
    public boolean observe(Enclosure.CellGrid grid, int feetX, int feetY, int feetZ,
                           long worldTime) {
        if (!Daylight.isNight(worldTime)) {
            return false;
        }
        long cycle = nightCycleOf(worldTime);
        if (cycle != nightCycle || worldTime <= lastNightTick) {
            // Two ways to be looking at a night this ledger is not holding, and both restart the
            // window. Either the clock has entered the NEXT cycle's night, or it has been moved
            // BACKWARDS inside the night it was already in -- `/time set`, and floorDiv of 14,807
            // and of 22,192 are both 0, so a day-cycle test alone cannot see that one. The second
            // case is the one that would otherwise count the same night's ticks into one window
            // twice, which is how a per-night budget turns negative again. See beginNight().
            nightCycle = cycle;
            beginNight();
        }
        // The counter runs DOWN from the period, so the first night tick is always sampled and a
        // period of 5 lands on ticks 1, 6, 11 rather than 2, 7, 12. An instrument that skipped its
        // own first sample would shorten every window by a period, which is a quiet way to be
        // wrong about a night.
        if (sinceLastSample > 0) {
            sinceLastSample--;
            return false;
        }
        sinceLastSample = periodTicks - 1;
        samples++;
        if (firstNightTick < 0L) {
            firstNightTick = worldTime;
        }
        lastNightTick = worldTime;
        boolean open = !Enclosure.at(grid, feetX, feetY, feetZ).enclosed();
        // The verdict of THIS sample, stored here rather than left for a caller to reconstruct
        // from two counters. The counting below is a running total and cannot answer "what did the
        // last one see"; only the point that took the sample was in a position to know.
        lastSampleOpen = open;
        if (open) {
            openSamples++;
            currentOpenRun++;
            if (currentOpenRun > longestOpenRun) {
                longestOpenRun = currentOpenRun;
            }
            if (firstOpenTick < 0L) {
                firstOpenTick = worldTime;
            }
        } else {
            currentOpenRun = 0;
        }
        return true;
    }

    /**
     * Empties the ledger so the next sample is the first of a window, and takes the sampling
     * countdown with it.
     *
     * <p><b>The countdown goes too, and that is not a detail.</b> A period above one leaves the
     * countdown part-way through the previous night's cadence, so without resetting it here the
     * first night tick of the new night would go unsampled for a period nobody chose. The
     * invariant this class documents -- the first tick of a night is always sampled -- has to hold
     * for every window, not only for the first one.
     *
     * <p><b>The window closes at dusk, not at dawn.</b> Nothing after a night's last tick can
     * change that night's answer, so the verdict is final at dawn and a reader that polls at dawn
     * is asking the only question still worth asking. A ledger emptied at the first daylight tick
     * would throw that reading away at exactly the moment it is the only one left, which is why
     * {@code observe} returns early on a daylight tick WITHOUT resetting and the reset waits for
     * the next dusk instead.
     *
     * <p><b>It also runs when the clock was moved backwards, and that is not the same event.</b>
     * {@code /time set} is a vanilla command and the Owner's north-star intent asks for an agent
     * that goes beyond what a human does, so a clock walking ground it has already walked has to
     * be survivable. Folding those ticks again would report one night as nearly two, and capping
     * at {@link #nightTicks()} instead would report a fully observed night when the later part of
     * it is barely sampled. What the ledger reports after a rewind is the honest version: the
     * window restarts at the tick the clock was moved to, and the budget a reader subtracts from
     * it is the night still ahead rather than a negative number.
     */
    private void beginNight() {
        sinceLastSample = 0;
        samples = 0;
        openSamples = 0;
        currentOpenRun = 0;
        longestOpenRun = 0;
        firstNightTick = -1L;
        lastNightTick = -1L;
        firstOpenTick = -1L;
        lastSampleOpen = false;
    }

    /**
     * Which night of the clock a tick belongs to: the index of the 24,000-tick day cycle the tick
     * falls in.
     *
     * <p>Derived from the clock rather than compared against a remembered tick, so a curve that
     * moves moves this with it, and {@link Math#floorDiv} rather than {@code /} so a negative
     * world time lands in the day cycle it belongs to instead of truncating toward zero. It asks
     * for the CYCLE rather than the tick because a night can be crossed without any of its ticks
     * being sampled -- {@code /time set} and a lagging accumulator both skip ticks -- and a
     * boundary detection that needed to see dusk itself would miss those nights entirely. That
     * the night is one unbroken run inside its cycle is the same fact {@link #nightTicks()}
     * already relies on to subtract its two boundaries.
     */
    private static long nightCycleOf(long worldTime) {
        return Math.floorDiv(worldTime, 24000L);
    }

    /**
     * Whether the night this ledger is holding was sheltered, and the requirement that it was
     * MEASURED first. The verdict is about ONE night, so it says nothing about any other: a night
     * that went badly stops counting the moment the next one starts.
     *
     * <p>The two halves are separate on purpose. A caller that prints only this cannot tell a
     * sheltered night from a night that never happened, and the difference is the difference
     * between a measurement and a constant.
     */
    public boolean sheltered() {
        return measured() && openSamples == 0;
    }

    /** Whether any night tick was sampled at all. False means the claim was not measured. */
    public boolean measured() {
        return samples > 0;
    }

    /**
     * How many night ticks were sampled, which is the resolution of the window.
     *
     * <p>Never more than {@link #nightTicks()}: this counts one night, so a reader that subtracts
     * it from the length of a night cannot compute a negative budget on the second night. That
     * subtraction is the arithmetic {@code ShelterTools} hands a model, and it used to go
     * negative because the count was the sum of every night the client had seen.
     */
    public int samples() {
        return samples;
    }

    public int enclosedSamples() {
        return samples - openSamples;
    }

    public int openSamples() {
        return openSamples;
    }

    /**
     * What the most recent SAMPLE saw: true when that sample found the body reachable.
     *
     * <p><b>This is a value, not a difference, and that is the whole point of it.</b> The obvious
     * way for a driver to answer "is it exposed right now" is to remember {@link #openSamples()}
     * and compare it against the next reading -- "did the counter move" -- and that is wrong at
     * every window boundary, because the window is reset by {@link #beginNight()} and the
     * caller's remembered value is not. Measured on the code that derived it that way: a body
     * that had been out in the open for 100 ticks of night 1, sampled at the first tick of night 2
     * while genuinely exposed, was reported NOT exposed, because the counter had gone 100 → 1 and
     * 1 is not more than 100. One tick per night, on exactly the tick where the answer matters.
     *
     * <p>The other obvious way is worse: {@code openSamples() > 0} answers "was this body ever
     * exposed tonight", which is {@link #sheltered()}'s question and not this one -- a body that
     * was caught out for three ticks an hour ago and has been under a roof since is safe NOW and
     * would be reported exposed by that derivation.
     *
     * <p><b>It reports the last SAMPLE, which may not be this tick.</b> At the default period of
     * one that is the same tick. At a coarser period this answer is up to
     * {@code periodTicks() - 1} ticks old, and there is no reading anywhere in this class that
     * says when it was taken beyond {@link #lastNightTick()}. {@link #sampledLastTick()} does not
     * exist here; a driver that needs "and it was this very tick" gates on what {@link #observe}
     * returned, which is the only place that distinction exists.
     */
    public boolean lastSampleOpen() {
        return lastSampleOpen;
    }

    public int periodTicks() {
        return periodTicks;
    }

    /**
     * The longest unbroken run of unsheltered samples in the night this ledger is holding, in
     * ticks.
     *
     * <p>The number a report should print rather than the ratio, because the question a player
     * asks is not "what fraction of the night" but "how long was I out in it". It is multiplied
     * by the sampling period, so a caller who coarsened the instrument is not silently reporting
     * an undercount.
     */
    public long longestUnreachableTicks() {
        return (long) longestOpenRun * periodTicks;
    }

    /** The clock tick of the first night sample, or -1 if nothing was sampled. */
    public long firstNightTick() {
        return firstNightTick;
    }

    /** The clock tick of the last night sample, or -1 if nothing was sampled. */
    public long lastNightTick() {
        return lastNightTick;
    }

    /** The clock tick of the first unsheltered sample, or -1 if the body was enclosed throughout. */
    public long firstOpenTick() {
        return firstOpenTick;
    }

    // ===== the window, derived from the production clock rather than written down =====

    /**
     * The first tick of a day that vanilla's own curve calls night.
     *
     * <p>Asked of {@link Daylight#isNight(long)} forward from the start of the day rather than
     * written as 13807, so that if the curve ever moves this number moves with it instead of
     * leaving the window quietly stale. Measured on this tree it is 13807, which is the same
     * figure {@code DaylightMatchesTheVanillaCurveTest} pins against
     * {@code World.calculateSkylightSubtracted}.
     */
    public static long duskTick() {
        for (int t = 0; t < 24000; t++) {
            if (Daylight.isNight(t)) {
                return t;
            }
        }
        return -1L;
    }

    /**
     * How many ticks of one day vanilla's own curve calls night.
     *
     * <p>Counted rather than written as 8386 (22193 - 13807), because the subtraction is only
     * valid while night is one unbroken run, and {@code Daylight} is the thing that would have to
     * be wrong for that to stop being true. Measured on this tree it is 8386.
     */
    public static int nightTicks() {
        int n = 0;
        for (int t = 0; t < 24000; t++) {
            if (Daylight.isNight(t)) {
                n++;
            }
        }
        return n;
    }

    /**
     * How far into the current night the clock is, in ticks, or 0 when it is not night.
     *
     * <p><b>Why a shipped surface needs this at all.</b> {@link #samples()} counts the night
     * ticks this ledger folded, which is the right denominator only if the ledger has been
     * folding since dusk. It has not, if it was attached mid-night, disabled by
     * {@code -Dmcp.core.shelter=false}, or fed a body standing in an unloaded chunk. So a live
     * reader needs the tick count the CLOCK says has gone by, to subtract the one the ledger
     * counted, and the difference is the number of night ticks nobody looked at. A reader that
     * cannot compute the difference is reading a window it does not know the extent of.
     *
     * <p><b>It counts TICKS OBSERVED, not ticks elapsed since dusk.</b> This used to be
     * {@code t - duskTick()}, which is 0 on the first night tick -- and a reader subtracting
     * {@link #samples()} from that got {@code -1} on a run that had observed every single night
     * tick. The subtraction above is the whole reason this method exists, so the two sides have
     * to be the same quantity: the ledger counts the dusk tick as its first sample, so this does
     * too. Found by
     * {@code TheShippedShelterCounterCostsEightReadsASampleAndNothingADayTickTest}, which drives
     * a full 24,000 ticks through the ledger and compares the two tick by tick; that test was
     * written to MEASURE this rather than to assert the arithmetic in this comment, which is the
     * only reason the disagreement was visible at all.
     *
     * <p>Derived by asking {@link #duskTick()} and {@link Daylight#isNight(long)} rather than by
     * writing 13807 here, for the reason the rest of this class derives its own window: one rule
     * about when night begins. Returns 0 outside night, so a daylight reader cannot mistake
     * "late in the afternoon" for "deep into the night".
     */
    public static int ticksIntoNight(long worldTime) {
        if (!Daylight.isNight(worldTime)) {
            return 0;
        }
        int t = (int) Math.floorMod(worldTime, 24000L);
        int into = t - (int) duskTick() + 1;
        if (into < 0) {
            into += 24000;
        }
        // isNight(t) can be true on the far side of a NEGATIVE world time, where dusk has not
        // been passed yet in this cycle but the previous cycle's night is the one in progress.
        // Clamping keeps the answer inside the window the rest of this class reports.
        return Math.max(0, Math.min(into, nightTicks()));
    }

    /**
     * The sentence a result row carries, which states the window, the resolution and the verdict
     * together -- because a verdict without its window is the thing this whole slice exists to
     * stop being quotable.
     */
    public String fact() {
        if (!measured()) {
            return "shelter: NOT MEASURED -- no night tick was sampled, so this row says nothing"
                    + " about shelter. A clock with doDaylightCycle off never reaches night, and a"
                    + " window measured over zero ticks must not report a shelter";
        }
        return String.format(Locale.ROOT,
                "shelter: %s -- %d night sample(s) at %d tick(s) per sample, clock %d..%d;"
                        + " enclosed on %d of them, unsheltered on %d, longest unbroken stretch"
                        + " out in the open %d tick(s)%s",
                sheltered() ? "MEASURED, enclosed throughout" : "MEASURED, exposed at some point",
                samples, periodTicks, firstNightTick, lastNightTick, samples - openSamples,
                openSamples, longestUnreachableTicks(),
                openSamples == 0 ? "" : " beginning at clock " + firstOpenTick);
    }

    @Override
    public String toString() {
        return fact();
    }
}