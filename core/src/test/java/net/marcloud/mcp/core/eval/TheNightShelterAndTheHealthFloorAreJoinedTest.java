package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.eval.EvalSuite.NightClaim;
import net.marcloud.mcp.core.eval.EvalSuite.Result;
import org.junit.Test;

/**
 * The two halves joined, and the two runs that make the join impossible to fake.
 *
 * <p><b>What was missing until this slice.</b> The north star is a shelter and a health floor, and
 * until now the eval could measure the second and not the first, so the composition -- <em>was
 * the player unsheltered during the night, and did it cost health</em> -- was not writable in
 * either direction. Both halves exist now. This file is where the composition stops being a
 * sentence in a report and becomes a value the suite computes.
 *
 * <p><b>Why a conjunction is not enough, and what is asserted instead.</b>
 * {@code held == sheltered && floorHeld} is false in three of the four combinations and true in
 * one, so a test that only ever looks at {@code held} cannot tell "sheltered and hurt" from
 * "exposed and untouched". Either of those two runs is a way for one half to do all the work:
 *
 * <ul>
 *   <li><b>run C</b> (inside the shaft, dropped in) is sheltered AND hurt. A health floor that had
 *       quietly reverted to bare survival -- {@code minimumHealth() > 0} -- would call this run a
 *       pass, because the body is left on a bar of 13 and alive. So MUTATION 2, which reverts
 *       {@code EvalSuite.neverBelowNorthStar} to exactly that, turns this run green.</li>
 *   <li><b>run D</b> (open plain, standing) is exposed AND untouched. A predicate that answered
 *       "sheltered" unconditionally would call this run the north star, so MUTATION 1, which makes
 *       {@code Enclosure.Verdict.enclosed()} return true, turns this run green.</li>
 * </ul>
 *
 * <p>So the test asserts the whole four-cell table, not the one cell that passes. That is what the
 * acceptance means by <em>if deleting one half leaves it green, it is not a join test</em>. The
 * measured counts, each one from the full run of 68 tests listed in the audit report: MUTATION 1
 * gives 12 failures, MUTATION 2 gives 4, and MUTATION 3 -- {@code nightClaim} quietly dropping the
 * enclosure half so {@code held == floorHeld} -- gives 3. MUTATION 1 and MUTATION 2 fail
 * <em>different</em> tests inside this file, which is the whole claim: neither half alone can
 * carry the row.
 *
 * <p><b>Nothing in the fixture consults the enclosure predicate.</b> A run is "the body is at this
 * cell at this height". The enclosure is then measured from the block grid and the health from the
 * survival ledger, independently, over the same clock. If either half were reading the other,
 * runs C and D could not both exist.
 *
 * <p><b>And the damage is planted, because nothing else in this substrate can move the bar.</b>
 * There is no lighting here, so no hostile mob spawns in the dark and nothing walks in from
 * anywhere, and the only hazard whose damage is paid once rather than followed up for the next
 * 300 ticks is a fall. This file joins two MEASUREMENTS of one body over one night; it does not
 * show that a roof caused a survival, and the task's own result string says so.
 */
public final class TheNightShelterAndTheHealthFloorAreJoinedTest {

    private static final int SHELTER_X = EvalSuite.T25ShelterThroughTheNight.SHELTER_X;
    private static final int SHELTER_Z = EvalSuite.T25ShelterThroughTheNight.SHELTER_Z;
    private static final int FEET_Y = EvalSuite.T25ShelterThroughTheNight.FEET_Y;
    private static final int OPEN_X = EvalSuite.T25ShelterThroughTheNight.OPEN_X;
    private static final int OPEN_Z = EvalSuite.T25ShelterThroughTheNight.OPEN_Z;
    private static final int ROOF_Y = EvalSuite.T25ShelterThroughTheNight.ROOF_Y;

    /** The project's own floor, restated rather than re-exported, so a reader sees the number. */
    private static final double FLOOR = 18.0D;

    // ===== the four runs, built the way the task builds them =====

    private static SimWorld sealedShaft() {
        return EvalSuite.T25ShelterThroughTheNight.buildWorld();
    }

    private static SimWorld openPlain() {
        return EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .standOn(OPEN_X, EvalSuite.T25ShelterThroughTheNight.FEET_Y, OPEN_Z);
    }

    /** Run A: inside the shaft, standing still. */
    private static SimWorld runA() {
        return sealedShaft();
    }

    /** Run B: on the open plain, dropped in from ten blocks up. */
    private static SimWorld runB() {
        return EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .standOn(OPEN_X, EvalSuite.T25ShelterThroughTheNight.DROP_Y, OPEN_Z);
    }

    /** Run C: inside the shaft, dropped in from ten blocks up. */
    private static SimWorld runC() {
        return EvalSuite.T25ShelterThroughTheNight.buildWorld()
                .standOn(SHELTER_X, EvalSuite.T25ShelterThroughTheNight.DROP_Y, SHELTER_Z);
    }

    /** Run D: on the open plain, standing still. */
    private static SimWorld runD() {
        return openPlain();
    }

    /** Runs one night for a world the fixture has already placed a body in. */
    private static NightClaim nightOf(SimWorld w) {
        return EvalSuite.nightClaim(w, EvalSuite.T25ShelterThroughTheNight.nightAt(w));
    }

    // ===== the claims =====

    /**
     * The whole table, in one place.
     *
     * <p>Four runs on one world's geometry, each a full night, each reported as a pair. Exactly one
     * row is a conjunction of two trues; the other three each contradict one half. This is the
     * assertion that a run cannot satisfy by having one of its two measurements carry the other.
     */
    @Test
    public void oneWorldTwoPositionsAndTheTwoHalvesDisagreeInBothDirections() {
        SimWorld a = runA();
        NightClaim claimA = nightOf(a);

        SimWorld b = runB();
        NightClaim claimB = nightOf(b);

        SimWorld c = runC();
        NightClaim claimC = nightOf(c);

        SimWorld d = runD();
        NightClaim claimD = nightOf(d);

        // ---- run A: the north star ----
        assertTrue("run A stands in the shaft, so every night sample is enclosed: " + claimA,
                claimA.sheltered());
        assertTrue("and nothing in the world reached it, so the bar never left full (low point "
                + a.minimumHealth() + "): " + claimA.healthFact(), claimA.floorHeld());
        assertTrue("so this is the row the project is asking for: " + claimA, claimA.held());

        // ---- run B: the negative ----
        assertFalse("run B stands on the open plain, so no night sample is enclosed: " + claimB,
                claimB.sheltered());
        assertFalse("and the planted ten-block drop took the bar below the floor (low point "
                + b.minimumHealth() + "): " + claimB.healthFact(), claimB.floorHeld());
        assertFalse("so neither half holds: " + claimB, claimB.held());

        // ---- run C: the control a health floor cannot fake ----
        assertTrue("run C is ENCLOSED, which is the half that is true: " + claimC,
                claimC.sheltered());
        assertFalse("and the floor moved anyway (low point " + c.minimumHealth() + "), so a"
                + " `neverBelowNorthStar` that had reverted to bare survival would call this run a"
                + " pass and the join would be one measurement wearing the other's name: "
                + claimC.healthFact(), claimC.floorHeld());
        assertFalse("so held is false even though the shelter half is true, which is what"
                + " MUTATION 2 would turn green", claimC.held());

        // ---- run D: the control an enclosure predicate cannot fake ----
        assertFalse("run D is EXPOSED, which is the half that is false: " + claimD,
                claimD.sheltered());
        assertTrue("and nothing was within reach, so the floor held (low point "
                + d.minimumHealth() + "): " + claimD.healthFact(), claimD.floorHeld());
        assertFalse("so held is false even though the health half is true, which is what"
                + " MUTATION 1 would turn green", claimD.held());
    }

    /**
     * The threshold is a threshold.
     *
     * <p>Run B's body does not die. It lands on a bar of 13 half-hearts -- 20 less
     * {@code ceil(10 - 3)} = 7 for the drop -- which is alive, which is exactly the state in which
     * a bare survival check says the run was fine and the north-star row says it was not. If this
     * assertion were only {@code minimumHealth() < 18} then reverting the floor to {@code > 0}
     * would change nothing anywhere in this file, and the join would be untestable in the
     * direction that matters.
     *
     * <p>The exact figure is asserted rather than a range, because a range is what a plant drifts
     * through and a threshold does not.
     */
    @Test
    public void theDroppedRunIsAliveAndBelowTheFloorSoTheFloorIsNotTheSameAsSurviving() {
        SimWorld b = runB();
        NightClaim claimB = nightOf(b);

        assertEquals("FallDamage.damageFor is ceil(distance - 3) and the fixture dropped the body"
                + " from ten blocks up, so the bar is 20 - 7 and nothing after it -- lava could not"
                + " be used here because its 300 burning ticks would take this to zero",
                13.0D, b.minimumHealth(), 0.0001D);
        assertTrue("so the run reached the bottom of the bar but not through it: health "
                + b.health() + ", low point " + b.minimumHealth(), EvalSuite.survived(b));
        assertFalse("so a bare survival check would pass it and the north-star row must not",
                EvalSuite.neverBelowNorthStar(b));
        assertTrue("and the run is strictly INSIDE the threshold rather than at zero, which is what"
                + " makes MUTATION 2 detectable: a body at zero is dead under both readings",
                b.minimumHealth() > 0.0D && b.minimumHealth() < FLOOR);
        assertFalse("the join reports the two halves rather than the bar alone: " + claimB,
                claimB.held());
    }

    /**
     * The suite's own row, in the shape a reader sees it in the report.
     *
     * <p>The tests above assert the values; this one asserts that T25 reports them, with the
     * reason attached, because a control only the test class can see is not a control on the
     * suite.
     */
    @Test
    public void theTaskRowItselfCarriesBothHalvesAndSaysWhatItCannotShow() {
        Result row = new EvalSuite.T25ShelterThroughTheNight().run();
        assertTrue("T25 must pass as shipped: " + row.fact(), row.pass());
        assertTrue("and the row states the definition rather than using the word 'shelter' alone,"
                + " because the definition is the claim: " + row.fact(),
                row.fact().contains("Chunk.canSeeSky") && row.fact().contains("all eight horizontal"));
        assertTrue("and it says the claim is over a WINDOW, not a moment: " + row.fact(),
                row.fact().contains("The claim is over a WINDOW, not a moment"));
        assertTrue("and it names the window in ticks, derived from Daylight: " + row.fact(),
                row.fact().contains("dusk at clock 13807 and 8386 night ticks"));
        assertTrue("and it says the resolution, because a window without a resolution is a"
                + " suggestion: " + row.fact(), row.fact().contains("One sample per tick"));
        assertTrue("and it carries all four runs, each with both halves: " + row.fact(),
                row.fact().contains("RUN A") && row.fact().contains("RUN B")
                        && row.fact().contains("RUN C") && row.fact().contains("RUN D"));
        assertTrue("and it says what it cannot show, in the row a reader actually reads: "
                + row.fact(), row.fact().contains("NOT COVERED"));
        assertTrue("specifically that the hazard is PLANTED and the night itself damages nobody,"
                + " so this is a join of two measurements and not a causal claim: " + row.fact(),
                row.fact().contains("PLANTED by the fixture")
                        && row.fact().contains("no hostile mob spawns in the dark"));
    }

    /**
     * The task's four runs come out of the same fixture the tests above use.
     *
     * <p>Written as a test because a task with its own private copy of the world is a task whose
     * controls are about a different world than the one the controls ran on. This asserts the
     * identity directly: same builder, same cells, same plant.
     */
    @Test
    public void theTaskAndTheControlsShareOneFixtureRatherThanTwoCopies() {
        SimWorld fromTask = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        SimWorld fromTest = sealedShaft();
        for (int by = FEET_Y; by <= ROOF_Y; by++) {
            assertEquals("the task's builder and this file's builder put the same block at y=" + by,
                    fromTest.blockObjectAt(SHELTER_X, by, SHELTER_Z),
                    fromTask.blockObjectAt(SHELTER_X, by, SHELTER_Z));
        }
        assertTrue("and the open cell really is open on the task's own world, so the runs in this"
                + " file are the runs in the task",
                !Enclosure.at(fromTask.enclosureGrid(), OPEN_X, FEET_Y, OPEN_Z).enclosed());
        assertTrue("while the sealed cell is not",
                Enclosure.at(fromTask.enclosureGrid(), SHELTER_X, FEET_Y, SHELTER_Z).enclosed());
    }
}