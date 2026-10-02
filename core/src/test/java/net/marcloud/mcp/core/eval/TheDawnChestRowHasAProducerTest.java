package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.eval.EvalSuite.Result;
import net.marcloud.mcp.core.eval.EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn;
import net.marcloud.mcp.core.eval.EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.Plant;

import org.junit.Test;

/**
 * The fourth north-star row has a producer, and the producer is the agent.
 *
 * <p><b>What this file is a receipt for.</b> Before this slice {@link DawnChest} was a predicate
 * with two test files and no caller: {@code grep DawnChest core/src} returned its own file, those
 * two tests, and one sentence inside {@code SimWorld.KNOWN_GAPS}. {@code EvalSuite.all()} ran T01
 * through T25 and not one of them built a chest. A criterion whose only producer is a comment is
 * the shape {@code docs/agency/failure-shapes.md} catalogues, and a criterion no task can fail is a
 * constant wearing a row's name.
 *
 * <p><b>The Owner's ruling this exists to enforce.</b> Asked what "a box standing at dawn" has to
 * mean for the row to measure anything, the Owner chose <em>the world starts from zero; the agent
 * must build it</em>, over "the world has a chest and we measure that it survived", because
 * nothing in this substrate removes a chest except the agent's own dig -- so a predicate that only
 * asks whether a cell holds a {@code BlockChest} scores a world that was HANDED one exactly as high
 * as a world where the agent MADE one, and a criterion a pre-existing block satisfies is not
 * measuring the agent.
 *
 * <p><b>Why the answer is a census and not a fixture constraint.</b> Both are defensible and the
 * row does BOTH, deliberately. Building the world with no chest in it is a constraint: a promise
 * about the setup that no assertion checks, so an edit that plants a chest leaves the row green
 * and nobody notices -- the promise is exactly what this criterion may not rest on. Carrying the
 * provenance inside the predicate is the other answer, and it is the right one, but it has to be a
 * census taken <em>before the agent's first action</em> rather than a flag on the instrument: the
 * night window is the night, and an agent that builds its box during the day has a chest standing
 * on the first night tick, so a predicate that asked "did the chest appear during my window" would
 * reject a correct run. The census is read off the block grid and the inventory before the policy
 * is even constructed, and the pass condition requires both to have been zero. A box in the world
 * at the end therefore cannot be a found one.
 *
 * <p><b>The control is the world's own rows.</b> {@code T26.runWith(factory, Plant.AT_BOX_CELL)}
 * builds the same world, plants one chest at the box cell before the census, and scores the same
 * pass condition. There is no second copy of anything here: if this file had re-implemented the
 * row, a future change to the row would leave the control green and prove nothing.
 *
 * <p><b>And there are TWO planted controls, because one cannot do both jobs.</b>
 * {@link Plant#AT_BOX_CELL} is the sharp one -- the chest the criterion looks at is the one that
 * was already there, so {@code DawnChest} says "a chest stood throughout" and the row still says
 * no. {@link Plant#ELSEWHERE} is the isolating one -- the agent builds and pays for its own box
 * exactly as in the passing run and a second chest sits out on the plain, so every other row is
 * green and the census is the only thing refusing the run. Without the second, deleting the
 * census from the pass condition would leave the first control red and prove nothing about it.
 *
 * <p><b>And the control's negative half is makeable to be green.</b> The failure this has to rule
 * out is a control that is red because the box is absent -- which would make the row's provenance
 * guard untested. So the planted run is asserted to have a chest standing on all 8,386 night
 * samples and to still be reported as a failure, which is the only arrangement in which the red is
 * about provenance rather than about a missing block.
 *
 * <p><b>No mutation numbers are asserted here.</b> A mutation count asserted inside the test it
 * constrains is a number that can be edited to match whatever the mutation happened to do. The
 * counts for this slice are measured against the full {@code core} suite and reported in
 * {@code .ai-notes/docs/audits/2026-10-02-wave17-chest-producer.md}.
 */
public final class TheDawnChestRowHasAProducerTest {

    /** The one factory the shipped task uses, so the good side is the real reference policy. */
    private static java.util.function.BiFunction<SimWorld, EvalHarness, Policy> shipped() {
        return GoalPolicy::new;
    }

    /**
     * The row is in the suite, and a green row means the agent built the box.
     *
     * <p>The membership assertion is not ceremony: the whole defect being closed is a predicate
     * nothing ran, and {@code EvalSuite.all()} is the list a runner consumes. A task that exists
     * and is not in that list is the original defect wearing a new class name.
     */
    @Test
    public void theFourthNorthStarRowIsInTheSuiteAndAGreenRowMeansTheAgentBuiltIt() {
        List<String> ids = EvalSuite.all().stream().map(EvalSuite.Task::id).toList();
        assertTrue("T26 must be in EvalSuite.all(), or the fourth north-star row is a predicate"
                + " nothing runs -- which is the defect this slice closes. The suite has: " + ids,
                ids.contains(T26TheAgentBuiltTheBoxAndItStoodAtDawn.ID));

        Result good = T26TheAgentBuiltTheBoxAndItStoodAtDawn.runWith(shipped(), Plant.NONE);

        assertTrue("T26 must pass as shipped: " + good.fact(), good.pass());
        assertTrue("and the report must carry the PROVENANCE census, so a reader can see the world"
                + " was empty before the agent acted rather than being told so: " + good.fact(),
                good.fact().contains("PROVENANCE: 0 chest block(s) and 0 chest item(s) in the bag"
                        + " BEFORE the agent's first action"));
        assertTrue("and it must say the item was placed rather than conjured, because a chest in"
                + " the grid that no chest item paid for would be a fabrication: " + good.fact(),
                good.fact().contains("1 block(s) standing and 0 item(s) in the bag at dawn"));
        assertTrue("and it must carry the box ledger over the production night window, not a tick"
                + " count of its own: " + good.fact(),
                good.fact().contains("box: MEASURED, a chest stood throughout"));
    }

    /**
     * THE control. A chest the FIXTURE placed does not satisfy the row.
     *
     * <p>Same world, same policy, same rows, one difference: the chest was on the grid before the
     * census rather than after a craft chain. Everything the old predicate asked about is
     * satisfied here -- a {@code BlockChest} stands in the cell on every night tick -- and the row
     * is still a failure.
     */
    @Test
    public void aBoxTheFixturePlacedDoesNotSatisfyTheRow() {
        Result planted = T26TheAgentBuiltTheBoxAndItStoodAtDawn.runWith(shipped(),
                Plant.AT_BOX_CELL);

        assertFalse("a chest the FIXTURE planted must NOT satisfy the fourth north-star row, or"
                + " the row measures the setup rather than the agent: " + planted.fact(),
                planted.pass());
        assertTrue("and the report must name the provenance as the reason, in its own words,"
                + " before any other clause: " + planted.fact(),
                planted.fact().contains("the world already held 1 chest block(s) and 0 chest"
                        + " item(s) before the agent acted, so a box at dawn measures the FIXTURE"
                        + " and not the agent"));
        assertTrue("and it must NOT claim the agent stood the box down. It did not:"
                + " rightClickBlock refuses a placement into an occupied cell, so the click did"
                + " nothing and the chest on the grid is the one the fixture put there -- a row"
                + " that reported 'placed' here was reading the planted chest back at itself: "
                + planted.fact(),
                planted.fact().contains("was never stood down by the agent"));
    }

    /**
     * The control's negative half really is negative, and not red because the box is missing.
     *
     * <p>This is what makes the control a control rather than a tautology. If the planted run were
     * red because no box stood at dawn, the provenance guard would have done nothing observable
     * and the row would still be satisfiable by a block the fixture put there. So the planted run
     * is asserted to have measured the FULL night with a chest in the cell on every sample --
     * which is the whole of what {@code DawnChest.standingAtDawn()} and
     * {@code DawnChest.heldThroughout()} ask -- and to have been refused anyway.
     */
    @Test
    public void thePlantedBoxStoodAllNightAndWasStillRefused() {
        Result planted = T26TheAgentBuiltTheBoxAndItStoodAtDawn.runWith(shipped(),
                Plant.AT_BOX_CELL);

        assertTrue("premise: the planted world DOES have a chest standing throughout the night, so"
                + " the row's refusal is about provenance and not about a missing block -- and"
                + " without this assertion the control above proves nothing: " + planted.fact(),
                planted.fact().contains("box: MEASURED, a chest stood throughout"));
        assertTrue("and it was the whole window, on every tick of it, not a point at dawn:"
                + " " + planted.fact(),
                planted.fact().contains("absent on 0"));
    }

    /**
     * A world with no box at all is red for a DIFFERENT reason, so the two reds are told apart.
     *
     * <p>A reader at 3am has to be able to tell "nothing was here" from "something was here and
     * the agent did not make it". Both are failures and they call for opposite investigations, so
     * the report has to say which one happened.
     */
    @Test
    public void aWorldWithNoBoxAtAllIsRedForADifferentReason() {
        Result empty = T26TheAgentBuiltTheBoxAndItStoodAtDawn.runWith(
                (w, h) -> new BrokenPolicies.GivesUpWithoutActing(), Plant.NONE);

        assertFalse("a decision that gave up must FAIL: " + empty.fact(), empty.pass());
        assertTrue("and the report must say the agent never obtained a chest: " + empty.fact(),
                empty.fact().contains("the agent never obtained a chest"));
        assertTrue("and it must NOT blame the fixture, because nothing was planted here:"
                + " " + empty.fact(),
                !empty.fact().contains("measures the FIXTURE"));
    }

    /**
     * The ISOLATING control: the agent built and paid for its own box, and the world still had a
     * chest in it before anybody acted.
     *
     * <p>This is the control that makes the provenance census load-bearing rather than decorative,
     * and it is the reason the row's "one box" row is scoped to the NAMED CELL rather than to the
     * whole world. Here everything except the census is green: the agent obtained a chest, its own
     * click stood it down on an empty cell, no chest item is left in the bag, the box at the named
     * cell stood on all 8,386 night samples, and the player is alive at 20.0 half-hearts. Delete
     * the census from the pass condition and this run goes GREEN -- which is the measurement, not
     * an argument. With a world-wide count instead, this control would stay red after the guard
     * was removed and would prove nothing about the guard.
     */
    @Test
    public void aWorldThatAlreadyHadABoxElsewhereIsStillRefusedAndOnlyTheCensusSaysWhy() {
        Result planted = T26TheAgentBuiltTheBoxAndItStoodAtDawn.runWith(shipped(), Plant.ELSEWHERE);

        assertFalse("the Owner's ruling is that the world starts from zero: a world that already"
                + " held a chest does not satisfy this row even when the agent went on to build its"
                + " own: " + planted.fact(), planted.pass());
        assertTrue("and the report must say exactly that, and nothing else, because here every"
                + " other row is green and a reader who is told the craft failed would be chasing"
                + " the wrong thing: " + planted.fact(),
                planted.fact().contains("the world already held 1 chest block(s) and 0 chest"
                        + " item(s) before the agent acted, so a box at dawn measures the FIXTURE"
                        + " and not the agent"));
        assertTrue("premise: the agent DID build its own box here -- the placement, the spend and"
                + " the whole night ledger are green, so the census is the only thing refusing"
                + " this run: " + planted.fact(),
                planted.fact().contains("the box stood down by the agent's own click, on a cell"
                        + " that was empty"));
        assertTrue("and it stood throughout the night as well: " + planted.fact(),
                planted.fact().contains("box: MEASURED, a chest stood throughout"));
        assertTrue("and there are two chests in the world at dawn, one found and one built --"
                + " which is why the count is printed next to the census: " + planted.fact(),
                planted.fact().contains("2 block(s) standing and 0 item(s) in the bag at dawn"));
        assertTrue("and it must NOT claim the craft or the placement failed, because neither did:"
                + " " + planted.fact(),
                !planted.fact().contains("never obtained a chest")
                        && !planted.fact().contains("did not stand one down"));
    }

    /**
     * The world the row runs in really does start from zero, measured rather than promised.
     *
     * <p>The pass condition asserts this too, so a fixture edit that planted a chest would turn
     * the shipped row red rather than quietly changing what it means. This is the premise of the
     * whole criterion stated on its own, so a failure here names the fixture rather than the
     * policy.
     */
    @Test
    public void theWorldTheRowRunsInHasNoChestAndNoChestItemBeforeAnythingHappens() {
        SimWorld w = T26TheAgentBuiltTheBoxAndItStoodAtDawn.buildWorld();

        assertEquals("the world this row runs in must start with no chest block in it, or the"
                + " criterion is measuring a fixture",
                0, T26TheAgentBuiltTheBoxAndItStoodAtDawn.chestsStanding(w));
        assertEquals("and no chest item in the bag, so the box cannot have been handed over",
                0, w.count("chest"));
    }
}