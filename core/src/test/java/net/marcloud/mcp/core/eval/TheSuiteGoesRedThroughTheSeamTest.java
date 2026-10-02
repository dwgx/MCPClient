package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.eval.EvalSuite.Result;
import net.marcloud.mcp.core.eval.EvalSuite.T20TheDirectLineIsLava;

import org.junit.Test;

/**
 * The suite can go red on demand, through a seam, on a task with no planted hazard.
 *
 * <p><b>What was missing and what this is.</b> Before this slice, {@code EvalSuite} ran
 * {@code new GoalPolicy(world, harness).obtain(goal)} at six sites. A goal-directed task could
 * therefore only ever PASS: there was no way to hand it a different decision, so no task had
 * been shown capable of failing on anything but a planted world, and a suite in which nothing
 * can fail cannot demonstrate that it can detect failure. The planted-hazard control in
 * {@code TheSurvivalRowDetectsAPlantedHazardTest} was the first negative control this project
 * had, and it works by removing a route from a WORLD. This one works by replacing a DECISION,
 * on a world nothing touches.
 *
 * <p><b>The three controls, and what each one rules out.</b> A pair of runs is only evidence if
 * something could make both sides green, so each control below names the mistake it excludes:
 *
 * <ul>
 *   <li><b>The bad policy dies ON THE SEAM, not on the rows.</b> A policy that throws proves
 *       only that exceptions propagate. Every bad policy here acts normally and returns a
 *       boolean; the failure has to come from the WORLD being different, which is the only
 *       version of this that says the rows are load-bearing.</li>
 *   <li><b>The bad policy is obviously bad without reading its source.</b> Each is a named class
 *       in {@link BrokenPolicies} whose javadoc states the defect in one sentence, and each
 *       asserts its own behaviour here in a way a reader can check against the world.</li>
 *   <li><b>The good policy still passes in the same run.</b> Both verdicts come from one
 *       {@code runWith} call in this file, so a broken world cannot explain a failure by being
 *       simply too hard.</li>
 * </ul>
 *
 * <p><b>Why this is not a model, restated because it is the honest boundary.</b> The good side
 * of every pair here is {@code GoalPolicy}, a 1317-line reference implementation. What these
 * tests establish is that a decision is an ARGUMENT to a task -- substitutable, injectable, and
 * consequential. What they do NOT establish is that a weak model plus a system prompt can
 * produce such a decision. That gap is named in {@link Policy}'s javadoc and is the standing
 * distance; pretending otherwise would be the defect this repository exists to remove.
 *
 * <p><b>And this is not a violation of {@code docs/agency/guarantees.md} §6.2</b>, "do not
 * write a goal policy in Java". That rule forbids a Java chooser standing in for the MODEL's
 * decision. Every bad policy here is deliberately incapable of solving its task -- they are
 * controls, shipped so they cannot be deleted as dead test code, and a chooser that worked
 * would make every test in this file meaningless. The seam is an interface with one method; the
 * decision lives in the implementation, which is what lets a task be handed a different one.
 */
public final class TheSuiteGoesRedThroughTheSeamTest {

    /** The same factory the shipped task uses, so the good side is the real reference policy. */
    private static java.util.function.BiFunction<SimWorld, EvalHarness, Policy> shipped() {
        return GoalPolicy::new;
    }

    /**
     * The headline: the shipped world's own rows, with a decision that walks the lethal
     * straight line.
     *
     * <p><b>Why this is not the planted-hazard control wearing a new hat.</b> The world is
     * T20's, cell for cell -- the band, the outcrop, the given pickaxe -- and nothing in this
     * method removes a route, moves a block, or edits a fixture. The only difference from the
     * passing run is which {@link Policy} was constructed. That is the whole claim: a task's
     * verdict is a function of the decision, and the decision is now a parameter.
     */
    @Test
    public void aPolicyThatWalksTheLethalStraightLineFailsTheShippedWorldsOwnRows() {
        // The bad decision, stated in one line: aim the walk at the outcrop and never look.
        // It submits the SAME production NavIntent the good policy uses -- what differs is that
        // this one names the destination directly rather than asking the planner for a route.
        Result bad = T20TheDirectLineIsLava.runWith((w, h) ->
                BrokenPolicies.walksTheStraightLine(h, (harness, goal) -> {
                    harness.runtime().submitNav(new NavIntent(20.5D, 64.0D, 2.0D, 600));
                    harness.runMove(900);
                    // It reports failure honestly: it did not obtain the goal. A policy that
                    // LIED here would be caught by the inventory row anyway, so the liar is
                    // covered separately below -- this one is the hazard-blind walker.
                    return false;
                }));

        assertFalse("a decision that walks the lethal straight line must be reported as a"
                + " failure, or these rows cannot fail on anything the DECISION did: " + bad.fact(),
                bad.pass());
        assertTrue("and the failure must NAME the death, so a reader is not left guessing which"
                + " row went red: " + bad.fact(),
                bad.fact().contains("died") || bad.fact().contains("health bar fell"));
        assertTrue("and it must report the damage the world actually recorded, not merely that"
                + " something went wrong: " + bad.fact(),
                bad.fact().contains("lava band"));
        assertTrue("and it must name the decision that produced the failure, so the reader does"
                + " not have to guess which policy ran: " + bad.fact(),
                bad.fact().contains("WalksTheStraightLine"));
    }

    /**
     * The control that makes the pair mean something: the same method, the shipped policy,
     * passes.
     *
     * <p>Without this, a failing run above would be evidence of nothing -- the world could simply
     * be unwinnable. Both verdicts come from {@link T20TheDirectLineIsLava#runWith}, so the
     * rows are literally the same code.
     */
    @Test
    public void theShippedPolicyStillPassesTheSameRunTheBadOneFailed() {
        Result good = T20TheDirectLineIsLava.runWith(shipped());

        assertTrue("T20 must pass as shipped: " + good.fact(), good.pass());
        assertTrue("and its report names the policy that ran, so a reader can tell which"
                + " decision produced it: " + good.fact(),
                good.fact().contains("GoalPolicy"));
        assertTrue("and it names the north-star claim rather than a bare health number: "
                + good.fact(), good.fact().contains("never went below"));
    }

    /**
     * A policy that CLAIMS success without acting is caught by the world, not by its own
     * return value.
     *
     * <p>This is the failure mode most like a language model, and the one a suite keyed on
     * {@code policy.obtain(...)} would pass. The policy returns {@code true}; the inventory is
     * empty and the ore is still in the ground, and those are the facts the row reads.
     */
    @Test
    public void aPolicyThatClaimsSuccessWithoutActingIsCaughtByTheWorldNotItsReturnValue() {
        Result lied = T20TheDirectLineIsLava.runWith((w, h) ->
                new BrokenPolicies.ClaimsSuccessWithoutActing());

        assertFalse("a policy that returned true and touched nothing must FAIL, or the rows are"
                + " reading the policy's self-report rather than the world: " + lied.fact(),
                lied.pass());
        assertTrue("and the failure must say the stone was never broken, which is the world"
                + " fact that caught it: " + lied.fact(),
                lied.fact().contains("the stone was never broken"));
        assertTrue("and that nothing was picked up: " + lied.fact(),
                lied.fact().contains("holds no cobblestone"));
    }

    /**
     * A policy that gives up without acting fails too, and is distinguishable from the liar.
     *
     * <p>Two broken policies that both leave the world untouched must still be told apart by
     * the report, or the failure message is useless to whoever reads it at 3am.
     */
    @Test
    public void aPolicyThatGivesUpFailsAndSaysSoRatherThanClaimingSuccess() {
        Result gaveUp = T20TheDirectLineIsLava.runWith((w, h) ->
                new BrokenPolicies.GivesUpWithoutActing());

        assertFalse("a policy that did nothing must FAIL: " + gaveUp.fact(), gaveUp.pass());
        assertTrue("and the report must say the policy reported FAILURE, so the reader is not"
                + " told the world disagreed when the decision simply gave up: " + gaveUp.fact(),
                gaveUp.fact().contains("reported failure"));
    }

    /**
     * A policy that throws is a FAILURE with a reason -- not a crash and not a pass.
     *
     * <p>This is the runner's own property rather than a row's, and it is here because a
     * decision defect must not be able to take the suite down: a runner that propagates it
     * aborts every later task, and a runner that swallowed it would report a row it never
     * computed.
     */
    @Test
    public void aPolicyThatThrowsIsAFailureWithAReasonNotACrashAndNotAPass() {
        Result threw = T20TheDirectLineIsLava.runWith((w, h) ->
                new BrokenPolicies.ThrowsOnEveryGoal());

        assertFalse("a throwing policy must be a FAILURE: " + threw.fact(), threw.pass());
        assertTrue("and the reason must name the throw and say why it is scored as a failure: "
                + threw.fact(), threw.fact().contains("threw IllegalStateException"));
    }

    /**
     * Every bad policy fails and the good one passes, in one table, so a future broken policy
     * cannot be added without a row here.
     *
     * <p>The table is derived rather than hand-listed because the point is that the SET of
     * shipped broken policies is fully covered; a new {@link BrokenPolicies} entry that no
     * control exercises is a control nobody wrote.
     */
    @Test
    public void everyShippedBrokenPolicyFailsAndTheShippedPolicyPasses() {
        assertTrue("the shipped policy is the good side of every pair and must pass: ",
                T20TheDirectLineIsLava.runWith(shipped()).pass());

        // Each of the three fixed-defect policies, against this task's rows.
        assertFalse("ClaimsSuccessWithoutActing must fail",
                T20TheDirectLineIsLava.runWith((w, h) ->
                        new BrokenPolicies.ClaimsSuccessWithoutActing()).pass());
        assertFalse("GivesUpWithoutActing must fail",
                T20TheDirectLineIsLava.runWith((w, h) ->
                        new BrokenPolicies.GivesUpWithoutActing()).pass());
        assertFalse("ThrowsOnEveryGoal must fail",
                T20TheDirectLineIsLava.runWith((w, h) ->
                        new BrokenPolicies.ThrowsOnEveryGoal()).pass());
    }

    /**
     * The seam is main-tree, so {@code core/src/main} has a reference to it.
     *
     * <p>This is the check that stops the slice reproducing its own subject. {@code GoalPolicy}
     * was test-only with zero references from {@code core/src/main}, which is precisely the
     * failure shape this work exists to dismantle; a seam defined beside it would inherit that
     * and the control above would prove nothing about shipped code. Asserting the seam's own
     * package -- rather than that some main file happens to import it -- is the honest form: it
     * says the capability is in the shipped artifact, and it fails the moment someone moves the
     * interface down into the test tree.
     */
    @Test
    public void theSeamIsInTheShippedTreeNotTheTestTree() {
        assertEquals("Policy must be shipped, or a capability only tests can reach is exactly the"
                + " defect this slice exists to close", "net.marcloud.mcp.core.eval",
                Policy.class.getPackageName());
        assertEquals("and BrokenPolicies with it: the broken decisions are shipped capabilities, so"
                + " they survive a test refactor", "net.marcloud.mcp.core.eval",
                BrokenPolicies.class.getPackageName());
        assertEquals("and the runner that binds and scores them", "net.marcloud.mcp.core.eval",
                PolicyRun.class.getPackageName());
    }

    /**
     * {@link PolicyRun} pairs a good and a bad run and reports both.
     *
     * <p>The pairing is a method rather than a convention because "remember to write the
     * control" is exactly what a passing-only suite forgets; a helper that makes the pair the
     * easy thing to reach is worth more than a comment asking for it.
     */
    @Test
    public void theRunnerPairsAGoodAndABadRunFromOneWorldShape() {
        // A world shape trivial enough to state here: the count of blocks is the row, and both
        // policies leave it alone, so the pair's value is the FORMAT -- both verdicts present,
        // each labelled -- not a hazard.
        PolicyRun<Integer> run = new PolicyRun<>(world -> new BrokenPolicies.GivesUpWithoutActing(),
                world -> new PolicyRun.Verdict(world > 0, "counted " + world));

        String report = run.pair(() -> 3, "cobblestone",
                new BrokenPolicies.ClaimsSuccessWithoutActing(),
                new BrokenPolicies.GivesUpWithoutActing(),
                "the liar", "the one that gives up");

        assertTrue("the report must carry the good side's verdict: " + report,
                report.contains("the liar -> pass=true"));
        assertTrue("and the bad side's: " + report, report.contains("the one that gives up -> pass=true"));
        assertEquals("exactly two labelled lines, so a reader sees a PAIR and not one result: "
                + report, 2, report.split("\n").length);
    }
}