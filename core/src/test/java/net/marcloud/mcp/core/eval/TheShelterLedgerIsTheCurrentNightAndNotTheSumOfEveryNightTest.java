package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.plan.Stance;

import org.junit.Test;

/**
 * The shelter ledger is the CURRENT night, and it says so in a tool description.
 *
 * <p><b>The defect this pins.</b> {@code night_shelter}'s description promises "how the CURRENT
 * night has gone" and tells the model that {@code (nightTicksTotal - samples)} is the budget left
 * to keep the night's claim winnable. The ledger did not keep that promise: it reset only when
 * the {@link Object} identity of the {@code World} changed, so in one world -- which is what a
 * single-player client is for the life of a session -- the counters folded every night since the
 * client connected into a single window. {@code samples} passed 8,386 on the second night, the
 * budget the description names went NEGATIVE, and the model was handed a number below zero to
 * decide "is there still time to build a roof tonight" from. {@link NightEnclosure}'s own class
 * doc claimed the opposite ("folds every night tick into one ledger") while its {@code observe}
 * returned {@code false} on every daylight tick without resetting anything, so the two halves of
 * one file described two different instruments.
 *
 * <p><b>Why "one night" and not "since the client connected".</b> The anchor is the north star:
 * one of its four verdicts is "the box still standing at dawn", and {@link DawnChest} measures
 * that over a SINGLE night, from {@link NightEnclosure#duskTick()} to the last tick the
 * {@code Daylight} curve calls night. There are therefore two consumers of a night window in this
 * tree and they have to be looking at the same night; a cumulative shelter ledger beside a
 * per-night box ledger quotes two different spans of one sentence. The rejected alternative --
 * never reset, and say "cumulative since connect" in the description -- hands the model a running
 * total with no night index beside it (it has {@code intoNight}, not "which night is this") and
 * keeps a budget that can go negative, which is not a number a model can act on.
 *
 * <p><b>What is measured rather than argued.</b> Every window here comes from
 * {@link NightEnclosure#duskTick()} and {@link NightEnclosure#nightTicks()}, so a curve that moves
 * moves these tests with it. The roof over the shaft is removed for a chosen number of ticks into
 * a chosen night, which is how night 1 and night 2 are made to disagree about exactly one block
 * while the world object, the body and the harness stay identical.
 *
 * <p><b>The controls, and why they are green on the old code too.</b> Two of the five tests below
 * are controls and they must stay green, because a file whose every test goes red proves only
 * that something changed:
 * <ul>
 *   <li>{@link #oneNightAloneIsUnchangedByThisDefect()} -- the premise. A ledger that has seen
 *       ONE night holds that night, on the old code and the new. Without it, a ledger that reset
 *       on every tick would pass the three tests above.</li>
 *   <li>{@link #twoFreshLedgersSeeTheTwoNightsDifferently()} -- the fixture can produce a
 *       difference. Two ledgers, each built fresh, measurably disagree about the two nights of
 *       this world, so the headline cannot pass by having both nights be the same night.</li>
 * </ul>
 */
public final class TheShelterLedgerIsTheCurrentNightAndNotTheSumOfEveryNightTest {

    /** The sealed shaft's feet cell, on the geometry the enclosure row and its controls share. */
    private static final int X = EvalSuite.T25ShelterThroughTheNight.SHELTER_X;
    private static final int Z = EvalSuite.T25ShelterThroughTheNight.SHELTER_Z;
    private static final int FEET_Y = EvalSuite.T25ShelterThroughTheNight.FEET_Y;
    /** The cell that closes the shaft's column, i.e. the one block this file moves between nights. */
    private static final int ROOF_Y = EvalSuite.T25ShelterThroughTheNight.ROOF_Y;

    /**
     * A cell on the same plain for {@link DawnChest}, away from the shaft so that putting a box
     * there cannot change what {@link Enclosure} says about the body. The comparison this file is
     * after is between two WINDOWS, and two windows are only comparable if the instruments behind
     * them were measuring something the same size.
     */
    private static final int BOX_X = 6;
    private static final int BOX_Y = FEET_Y;
    private static final int BOX_Z = 6;

    /** Night ticks in one day, and the first of them, both asked of the production clock. */
    private static final int NIGHT = NightEnclosure.nightTicks();
    private static final long DUSK = NightEnclosure.duskTick();

    /** One day's ticks, the cycle the clock divides into; night 2 starts one whole cycle later. */
    private static final int DAY = 24000;

    /**
     * One whole night of the clock, with the roof gone for {@code openTicks} consecutive ticks
     * starting {@code openFrom} ticks after that night's dusk.
     *
     * <p>{@code nightIndex} counts nights from the first the clock reaches, so night 1 is the
     * second one and its window starts at {@code DUSK + 24000}. The clock is placed one tick
     * before dusk and the loop runs one tick past the end of the night, so after iteration
     * {@code tick} the world's {@code worldTime} is exactly {@code dusk + tick}: the offset from
     * dusk is the loop variable rather than an arithmetic slip.
     *
     * <p>The SAME {@link NightShelter} and the SAME {@link SimWorld} are driven on every call --
     * that is the whole point. {@code World} identity is the only reset trigger the driver has,
     * and a single-player client hands it one object for the life of the session, so two calls
     * with the same {@code w} are exactly what the live path sees when the second night arrives.
     *
     * @param extra ledgers of their own to fold over the same ticks, for the controls that need an
     *              accumulator which never saw the previous night
     * @return a {@link DawnChest} built fresh for THIS window -- the instrument the fourth
     *         north-star verdict is read off, and therefore the one the ledger must agree with
     */
    private static DawnChest night(SimWorld w, NightShelter shelter, int nightIndex,
                                   int openFrom, int openTicks, NightEnclosure... extra) {
        long dusk = DUSK + (long) nightIndex * DAY;
        w.atWorldTime(dusk - 1L);
        EvalHarness h = new EvalHarness(w);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            boolean holed = tick >= openFrom && tick < openFrom + openTicks;
            w.remove(X, ROOF_Y, Z);
            if (!holed) {
                w.put(X, ROOF_Y, Z, "stone");
            }
            // The shipped driver, fed by hand -- the same way the negative control in
            // NightShelterPublishesSnapshotsAndNeverTheAccumulatorTest drives it.
            shelter.onTick();
            for (NightEnclosure ledger : extra) {
                Stance feet = w.stance();
                ledger.observe(w.enclosureGrid(), feet.x(), feet.y(), feet.z(), w.worldTime());
            }
            box.observe(w.enclosureGrid(), BOX_X, BOX_Y, BOX_Z, w.worldTime());
        }
        return box;
    }

    /** The world, with a box on the plain for the {@link DawnChest} half of the comparison. */
    private static SimWorld world() {
        return EvalSuite.T25ShelterThroughTheNight.buildWorld().put(BOX_X, BOX_Y, BOX_Z, "chest");
    }

    /** The shipped accumulator, pointed at a simulation world exactly as {@code ModelRound} does. */
    private static NightShelter shelterOf(SimWorld w) {
        return new NightShelter(1).reading(() -> new NightShelter.Body() {
            @Override
            public Object world() {
                return w;
            }

            @Override
            public long worldTime() {
                return w.worldTime();
            }

            @Override
            public Enclosure.CellGrid grid() {
                return w.enclosureGrid();
            }

            @Override
            public int feetX() {
                return w.stance().x();
            }

            @Override
            public int feetY() {
                return w.stance().y();
            }

            @Override
            public int feetZ() {
                return w.stance().z();
            }
        });
    }

    /**
     * THE HEADLINE, and it is RED ON OLD CODE.
     *
     * <p>Night 1 is spent with no roof at all -- all 8,386 of its ticks unsheltered. Night 2 is
     * spent sealed, in the same world, through the same accumulator, on the same body. The ledger
     * the model reads at the end of night 2 has to be night 2's: sealed throughout, one night
     * wide, and starting at night 2's dusk.
     *
     * <p>Measured on the code as it stood: {@code samples = 16772}, {@code openSamples = 8386},
     * {@code sheltered() = false}, {@code firstNightTick = 13807}. Each of those is a sentence the
     * tool description tells the model is about the CURRENT night, and each was about two nights.
     */
    @Test
    public void theSecondNightsLedgerHoldsTheSecondNightAndNotTheSumOfTwo() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        night(w, shelter, 0, 0, NIGHT);
        night(w, shelter, 1, 0, 0);

        assertEquals("the ledger covers ONE night, not every night since the client connected."
                        + " Two nights were driven here and the model is being told about the"
                        + " second. " + shelter.fact(),
                NIGHT, shelter.samples());
        assertEquals("and the open samples it reports are this night's, so a night the model"
                        + " already failed is not stamped on the night it is still playing",
                0, shelter.openSamples());
        assertEquals("which is the same window from the other end: this ledger's first sample is"
                        + " this night's dusk and not the previous night's",
                DUSK + DAY, shelter.firstNightTick());
        assertTrue("a body that stood in the open all of night 1 and was sealed all of night 2 is"
                        + " ENCLOSED THROUGHOUT on the night the claim is about. " + shelter.fact(),
                shelter.sheltered());
    }

    /**
     * The mirror of the headline, so the fix cannot be "throw the counters away at dusk".
     *
     * <p>Night 1 is sealed; night 2 has a one-tick hole four thousand ticks in. The ledger must
     * then report exactly one open sample -- not zero, because this night was exposed, and not
     * 8,387, because the open ticks of a night that is not this one are not in it. A ledger that
     * cleared itself every tick passes the headline and fails here.
     */
    @Test
    public void aNightThatIsHoleyIsStillCountedAndOnlyThisNightsHoleIsCounted() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        night(w, shelter, 0, 0, 0);
        night(w, shelter, 1, 4000, 1);

        assertEquals("one tick of open sky in THIS night is one open sample",
                1, shelter.openSamples());
        assertEquals("and the sample window is still one night wide", NIGHT, shelter.samples());
        assertFalse("so this night's own answer is the exposed one, whatever last night was",
                shelter.sheltered());
        assertEquals("and firstOpenTick names this night's hole rather than last night's",
                DUSK + DAY + 4000L, shelter.firstOpenTick());
        assertEquals("with the exposure measured in ticks, as a report should print it",
                1L, shelter.longestUnreachableTicks());
    }

    /**
     * THE ALIGNMENT GUARD: the two consumers of a night window must be looking at the same night.
     *
     * <p>{@code night_shelter} and {@link DawnChest} are separate instruments over separate
     * grids -- one folded by the tick seam, one folded by an eval harness -- and the fourth
     * north-star verdict reads both. {@link DawnChest} is built fresh here for night 2 and driven
     * from night 2's dusk; its own window is asserted first, from the production clock rather than
     * from the ledger, so the alignment assertion cannot be satisfied by two instruments agreeing
     * on the wrong night.
     *
     * <p>RED ON OLD CODE: the ledger's first sample was night 1's dusk, 24,000 ticks away from the
     * box instrument's.
     */
    @Test
    public void theSheltersWindowIsTheOneTheBoxInstrumentMeasures() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        night(w, shelter, 0, 0, NIGHT);
        DawnChest nightTwo = night(w, shelter, 1, 0, 0);

        assertEquals("the box instrument's window comes from the production clock and is one night"
                        + " of the second day, whatever the shelter ledger is doing",
                DUSK + DAY, nightTwo.firstNightTick());
        assertEquals("and its sample count is that night's night ticks", NIGHT, nightTwo.samples());
        assertEquals("THE ALIGNMENT. The shelter ledger and the box instrument must start at the"
                        + " same tick: one number is the budget a model acts on and the other is"
                        + " the fourth north-star verdict, and a night that is not the same night"
                        + " to both of them is a run that can pass one and fail the other",
                nightTwo.firstNightTick(), shelter.firstNightTick());
        assertEquals("and they must end at the same tick as well",
                nightTwo.lastNightTick(), shelter.lastNightTick());
        assertEquals("at the same resolution", nightTwo.samples(), shelter.samples());
        assertTrue("and the box really did stand all night, or the window comparison above is"
                        + " measuring an instrument that saw nothing",
                nightTwo.heldThroughout());
    }

    /**
     * THE DESCRIPTION'S ARITHMETIC, checked on every night tick of two nights.
     *
     * <p>{@code ShelterTools} tells the model, in as many words, that while {@code openSamples} is
     * 0 "the claim is still winnable and {@code (nightTicksTotal - samples)} is the budget left to
     * keep it that way". That is the only number the model has for deciding whether there is time
     * to build a roof, so it is asserted rather than believed: on every night tick of two nights,
     * in one world, through the shipped accumulator, the budget is not negative and {@code samples}
     * never exceeds one night's worth of ticks.
     *
     * <p>The day between the two nights is crossed by moving the clock rather than by ticking it
     * out, which is what {@code /time set} does to a live client and therefore a path the shipped
     * ledger has to survive anyway. Ticking all 24,000 of them would buy the same boundary for
     * three times the wall clock.
     *
     * <p>RED ON OLD CODE: on night 2 the count crosses 8,386 and the budget goes to {@code -8386}
     * and below, which is arithmetic a description is asking a model to trust.
     */
    @Test
    public void theBudgetTheDescriptionAsksTheModelToSubtractIsNeverNegative() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        EvalHarness h = new EvalHarness(w);
        w.atWorldTime(DUSK - 1L);

        for (int tick = 0; tick <= NIGHT + 1000; tick++) {
            if (tick == NIGHT + 1) {
                w.atWorldTime(DUSK + DAY - 1L);
            }
            h.tick();
            shelter.onTick();
            if (shelter.intoNight() <= 0) {
                continue;
            }
            int budget = NIGHT - shelter.samples();
            assertTrue("the budget the description names went negative at clock "
                            + shelter.worldTime() + ": " + NIGHT + " - " + shelter.samples()
                            + " = " + budget + ". " + shelter.fact(),
                    budget >= 0);
            assertTrue("and samples must never exceed one night's ticks, or every budget derived"
                            + " from it is nonsense. clock " + shelter.worldTime() + ", samples "
                            + shelter.samples(),
                    shelter.samples() <= NIGHT);
        }
        assertTrue("the loop really did reach night 2, or it proved nothing at all. clock "
                        + shelter.worldTime() + ", samples " + shelter.samples(),
                shelter.firstNightTick() >= DUSK + DAY);
    }

    /**
     * CONTROL, green on the old code and on the new: one night alone is untouched by this defect,
     * and it still answers at dawn.
     *
     * <p>This is the premise the three tests above need. If the ledger broke in the other
     * direction -- reset on every tick, or reset at dusk's midpoint -- a ledger that had seen
     * exactly one night would stop holding it, and this goes red while the headline may not.
     *
     * <p>The last three assertions are the load-bearing design choice and they are why the reset
     * lands at dusk rather than at dawn: {@code NightShelter}'s own javadoc promises one shape
     * "read early for the gradient and at dawn for the verdict", because at dawn nothing further
     * can change the night's answer. A ledger emptied at the first daylight tick would throw that
     * reading away exactly when it is the only reading left -- so the window is closed when the
     * NEXT night opens, and held across the day until then. On the old code these three pass for
     * the wrong reason (the ledger never resets at all); they are here to keep a reset-at-dawn
     * "fix" from passing the headline.
     */
    @Test
    public void oneNightAloneIsUnchangedByThisDefect() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        DawnChest box = night(w, shelter, 0, 4000, 3);

        assertEquals("a ledger that has seen one night holds that night", NIGHT, shelter.samples());
        assertEquals("including its hole", 3, shelter.openSamples());
        assertEquals("starting at that night's dusk", DUSK, shelter.firstNightTick());
        assertEquals("and ending at that night's last night tick",
                DUSK + NIGHT - 1L, shelter.lastNightTick());
        assertEquals("which is the window the box instrument measured over the same ticks",
                box.firstNightTick(), shelter.firstNightTick());
        assertEquals("so a fully observed night leaves a budget of zero, not a negative one",
                0, NIGHT - shelter.samples());
        assertTrue("at the first tick after the night the window is still measured, so a reader"
                        + " polling at dawn gets the night's verdict rather than nothing. "
                        + shelter.fact(),
                shelter.measured());
        assertEquals("and it holds every sample the night produced", NIGHT, shelter.samples());
        assertEquals("while the clock says the night is over, so a reader is not told a night is"
                + " still running", 0, shelter.intoNight());
    }

    /**
     * CONTROL, green on both: two ledgers built FRESH see the two nights of this world disagree.
     *
     * <p>The headline would also be satisfied by a world in which both nights are sealed -- then
     * any reset policy yields "one night of samples" and proves nothing. So this pins the premise
     * in the other direction: with an accumulator of its own per night, night 1 is measurably
     * exposed and night 2 measurably is not, in the same {@link SimWorld} object. The difference
     * the headline measures is therefore real, and the reset policy alone decides what the
     * long-lived ledger reports about it.
     */
    @Test
    public void twoFreshLedgersSeeTheTwoNightsDifferently() {
        SimWorld w = world();
        NightEnclosure exposed = new NightEnclosure();
        night(w, shelterOf(w), 0, 0, NIGHT, exposed);
        NightEnclosure sealed = new NightEnclosure();
        night(w, shelterOf(w), 1, 0, 0, sealed);

        assertEquals("the fixture's first night really was exposed on every tick, so a ledger"
                        + " that failed to reset had something to fail to reset",
                NIGHT, exposed.openSamples());
        assertEquals("and the fixture's second night really was sealed on every tick",
                0, sealed.openSamples());
        assertTrue("so the two nights disagree, and any 'one night wide' answer above is the reset"
                        + " policy's doing rather than the world's",
                exposed.sheltered() != sealed.sheltered());
    }

    /**
     * GUARD, RED ON THE DAY-CYCLE FIX ALONE: a clock jumped BACKWARDS inside one night cannot
     * count the same night twice.
     *
     * <p><b>This is the hole the day-cycle trigger leaves, and it is not hypothetical.</b>
     * {@code /time set} is a vanilla command and the Owner's north-star intent
     * ({@code 2026-10-01-northstar-intent.md}, rule R5) asks for an agent that goes beyond what a
     * human does; a human under pressure does use {@code /time}. The day cycle a tick falls in
     * does not change when the clock is moved <em>backwards inside the night it is already in</em>
     * -- {@code floorDiv} of 14,807 and of 22,192 are both 0 -- so a day-cycle trigger alone lets
     * the ledger fold the same night's ticks into the same window twice. It did: measured on that
     * code, a night driven to its end and then rewound to 1,000 ticks past dusk reported
     * {@code samples = 15772} for a night that has 8,386, and a budget of {@code -7386}.
     *
     * <p><b>What the right answer is, and why it is a reset rather than a cap.</b> After a
     * rewind the clock is going to walk the ground it already walked, so the ticks before the jump
     * were measured and the ticks after it are about to be measured AGAIN. Counting both would
     * make one night look like 1.9 nights; capping at {@code nightTicks()} instead would report a
     * fully observed night when the later part is barely sampled. So the window restarts at the
     * tick the clock was moved to, and what the payload then shows is honest and actionable: the
     * budget is the night ticks still to come from the jump point, not a negative number.
     *
     * <p>A jump FORWARD inside the night is the other half and is deliberately NOT a reset: the
     * window keeps running and simply has a gap, which is what {@code samples < intoNight} already
     * tells a reader. Only a backward move re-counts ground already folded.
     */
    @Test
    public void aClockJumpedBackwardsInsideANightCannotCountThatNightTwice() {
        SimWorld w = world();
        NightShelter shelter = shelterOf(w);
        EvalHarness h = new EvalHarness(w);

        // A whole sealed night, start to finish.
        w.atWorldTime(DUSK - 1L);
        for (int tick = 0; tick <= NIGHT; tick++) {
            h.tick();
            w.put(X, ROOF_Y, Z, "stone");
            shelter.onTick();
        }
        assertEquals("the premise: one whole night is one night's worth of samples",
                NIGHT, shelter.samples());
        assertEquals("and it ends on that night's last night tick",
                DUSK + NIGHT - 1L, shelter.lastNightTick());

        // /time set back to 1,000 ticks past dusk -- the SAME night, the clock now going
        // backwards, which is the one move a day-cycle trigger cannot see.
        long rewoundTo = DUSK + 1000L;
        w.atWorldTime(rewoundTo);
        // The tick the clock was moved TO is fed before the loop, because that is the tick the
        // window has to restart on: feeding it afterwards would test the tick after the rewind and
        // let a ledger that restarted one tick late pass.
        shelter.onTick();
        assertEquals("the rewind restarts the window on the tick the clock was moved to, so the"
                        + " ledger describes the night the clock is now walking rather than the"
                        + " one it already finished",
                rewoundTo, shelter.firstNightTick());
        assertEquals("with that tick counted once and nothing before it",
                1, shelter.samples());
        for (long t = rewoundTo; t < DUSK + NIGHT; t++) {
            h.tick();
            shelter.onTick();
            assertTrue("the budget the description names went negative at clock "
                            + shelter.worldTime() + ": " + NIGHT + " - " + shelter.samples()
                            + ". A clock moved backwards must not count one night twice. "
                            + shelter.fact(),
                    NIGHT - shelter.samples() >= 0);
        }

        assertTrue("and samples can never exceed one night's ticks however the clock is moved",
                shelter.samples() <= NIGHT);
        assertEquals("the window restarts at the tick the clock was moved to, so the budget the"
                        + " model is handed is the night still ahead of it rather than a negative"
                        + " number. " + shelter.fact(),
                NIGHT - 1000, shelter.samples());
        assertEquals("and firstNightTick names that same tick, so the window the ledger describes"
                        + " is the one the clock is actually walking",
                rewoundTo, shelter.firstNightTick());
        assertTrue("while the verdict still holds: the body was under a roof on every tick of the"
                        + " window, so one night is still one night rather than a night plus a"
                        + " rewind. " + shelter.fact(),
                shelter.sheltered());
    }
}