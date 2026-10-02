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
 * night is a claim about a REGION of time, so the region is the unit: this class folds every
 * night tick into one ledger and answers from the ledger.
 *
 * <p><b>The rule, stated so it can be argued with.</b> A run is sheltered when it has at least one
 * night sample and <em>every one of those samples</em> is enclosed. Not a majority, not an
 * average, not "at dawn": a single unsheltered night tick means the player was standing in the
 * open at some instant, and one instant is all it takes to be reached. The symmetric error is the
 * one this rejects: {@code openSamples == 0} over a list of zero samples is vacuously true, so
 * {@link #sheltered()} requires {@link #measured()} first. A clock that never reaches night
 * produces zero samples, and the answer there is NOT MEASURED -- which is the same discipline
 * {@code SimWorld.KNOWN_GAPS} states for lighting and {@code EvalSuite.survived} states for a run
 * no hazard reached.
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

    private long firstNightTick = -1L;
    private long lastNightTick = -1L;
    private long firstOpenTick = -1L;

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
     * Records one tick of a run: an enclosure verdict, if the clock says it is night.
     *
     * <p>A tick outside the night is not counted at all, which is what lets a caller drive the
     * whole night without deciding for itself where the window starts.
     *
     * @return whether a sample was taken
     */
    public boolean observe(Enclosure.CellGrid grid, int feetX, int feetY, int feetZ,
                           long worldTime) {
        if (!Daylight.isNight(worldTime)) {
            return false;
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
        if (!Enclosure.at(grid, feetX, feetY, feetZ).enclosed()) {
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
     * Whether this run was sheltered, and the requirement that it was MEASURED first.
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

    /** How many night ticks were sampled, which is the resolution of the window. */
    public int samples() {
        return samples;
    }

    public int enclosedSamples() {
        return samples - openSamples;
    }

    public int openSamples() {
        return openSamples;
    }

    public int periodTicks() {
        return periodTicks;
    }

    /**
     * The longest unbroken run of unsheltered samples, in ticks.
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