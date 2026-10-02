package net.marcloud.mcp.core.eval;

import java.util.function.BiFunction;

/**
 * Policies that are broken on purpose, so a suite's rows can be shown to have teeth.
 *
 * <p><b>Why these are main-tree and not test fixtures.</b> A control that lives only in the
 * test tree is a control that vanishes the moment someone refactors the test, and the whole
 * claim it supports -- "this assertion CAN fail" -- goes with it. Shipping them means the
 * broken behaviour is a named, versioned capability rather than a lambda somebody typed into
 * a test method and can delete as dead code.
 *
 * <p><b>Why each one is obviously bad without reading its source.</b> Every class here has a
 * one-line javadoc stating the defect in the imperative, and each is a handful of lines. A
 * reader of the control test sees the name, reads one sentence, and knows what the run will
 * show -- which is the property that makes the control legible rather than another thing to
 * take on faith. If any of these grew a hundred lines of cleverness it would stop serving that
 * purpose, and the test that uses it says so.
 *
 * <p><b>How this sits with {@code docs/agency/guarantees.md} §6.2, "do not write a goal
 * policy in Java".</b> That rule is not being bent, and the reason is the distinction the
 * rule itself draws: it forbids a Java chooser standing in for the MODEL's decision, because a
 * chooser would make the acceptance criterion permanently untestable. Nothing here stands in for
 * a model -- these are negative controls, they exist to be CAUGHT, and a chooser that solved the
 * task would defeat the entire purpose of shipping them. The interface they implement
 * ({@link Policy}) declares no behaviour; its one method takes a goal string, and what a caller
 * does with it is the caller's business. What this file adds is the ability to hand a task a
 * decision it should FAIL on, which is the missing half of "the suite can detect failure" and
 * not the half §6.2 is about.
 *
 * <p><b>What "broken" means here.</b> Not throwing, not returning a constant, not asserting
 * false. Each of these returns the same {@code boolean} a working policy would return -- it
 * does its job as a policy and does it WRONGLY. A policy that threw would prove only that the
 * harness propagates exceptions; a policy that returned {@code false} without acting would
 * prove only that the harness reads a return value. What the eval needs to show is that the
 * WORLD ends up different, and that the rows notice.
 */
public final class BrokenPolicies {

    private BrokenPolicies() {
    }

    /**
     * Does nothing at all and claims the goal is in hand.
     *
     * <p>The defect is the return value, not the inaction: this returns {@code true} without
     * having touched the world, which is exactly the shape of a language model that says it
     * did the thing. A row that only checked "did the policy say yes" would pass it; a row
     * that reads the inventory cannot.
     */
    public static final class ClaimsSuccessWithoutActing implements Policy {
        @Override
        public boolean obtain(String item) {
            return true;
        }

        /**
         * The defect, in the words a failure report will print.
         *
         * <p>Every policy in this file overrides this, and that is the reason: a task's report
         * prints its policy, so a red row that says
         * {@code policy ClaimsSuccessWithoutActing@2b1e5d9f} makes a reader go and look, while
         * one that says "claims success without acting, returns true and touches nothing"
         * does not. The defect is the point of the class; it belongs in what gets printed.
         */
        @Override
        public String toString() {
            return "ClaimsSuccessWithoutActing (reports success and touches nothing)";
        }
    }

    /**
     * Throws on the first goal.
     *
     * <p>The defect is honesty about failure, not about the world. A policy that lied would be
     * caught by any world fact; a policy that throws is caught only by a runner that does not
     * let an exception read as a pass, which is a different property and is why this one is
     * here rather than left implicit.
     */
    public static final class ThrowsOnEveryGoal implements Policy {
        @Override
        public boolean obtain(String item) {
            throw new IllegalStateException("this policy cannot act: injected to prove the runner"
                    + " does not read an exception as a pass");
        }

        @Override
        public String toString() {
            return "ThrowsOnEveryGoal (throws IllegalStateException)";
        }
    }

    /**
     * Submits one direct walk to the goal and calls it done.
     *
     * <p>The defect is <b>no hazard check</b>, and it is the defect a language model actually
     * makes: it has a goal, it has a direction, and nothing in its reasoning asks whether the
     * block between here and there kills. The action is handed in rather than reimplemented
     * here, because this class is main-tree and the simulator is not -- and because the action
     * is the SAME production {@code NavIntent} submit the working policy uses. The only
     * difference between the two runs is the refusal to look.
     *
     * <p>A NAMED class rather than a lambda, because a task's report prints
     * {@code policy.getClass().getSimpleName()} so a reader can tell which decision produced a
     * verdict -- and {@code BrokenPolicies$$Lambda/0x00000007f9a3c1b0} tells nobody anything.
     *
     * @param <S>          the simulator type the action drives
     * @param world        the world this policy is bound to, exactly as the working policy's is
     * @param walkStraight submits a direct walk toward the goal, bypassing any route search,
     *                     and reports what the world said
     */
    public static <S> Policy walksTheStraightLine(S world,
                                                  BiFunction<S, String, Boolean> walkStraight) {
        return new StraightLineWalker<>(world, walkStraight);
    }

    /**
     * The defect, in a class whose name is the defect.
     *
     * @param <S> the simulator type the action drives
     */
    private static final class StraightLineWalker<S> implements Policy {
        private final S world;
        private final BiFunction<S, String, Boolean> walkStraight;

        StraightLineWalker(S world, BiFunction<S, String, Boolean> walkStraight) {
            this.world = world;
            this.walkStraight = walkStraight;
        }

        @Override
        public boolean obtain(String item) {
            return walkStraight.apply(world, item);
        }

        /**
         * The defect in the name, so a failure report says what was wrong rather than printing
         * {@code StraightLineWalker}, which says only that there was a class.
         */
        @Override
        public String toString() {
            return "WalksTheStraightLine (no hazard check)";
        }
    }

    /**
     * Gives up on the first goal without touching the world.
     *
     * <p>The defect is <b>no attempt</b>, and it is worth having beside the liar above because the
     * two failures a task must distinguish are different ones: a policy that says yes and did
     * nothing, and a policy that says no and did nothing. The first is caught by any world fact;
     * the second is what a weak model does when a task looks hard, and it is the one a runner
     * that only checks "did the policy report success" would read as a pass.
     *
     * <p>The name is about the OBSERVABLE, not about a shelter: nothing in this class builds or
     * fails to build one, and a javadoc claiming otherwise would be the exact defect this
     * repository spends its audits removing.
     */
    public static final class GivesUpWithoutActing implements Policy {
        @Override
        public boolean obtain(String item) {
            return false;
        }

        @Override
        public String toString() {
            return "GivesUpWithoutActing (reports failure and touches nothing)";
        }
    }
}