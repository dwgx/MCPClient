package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One run of a {@link Policy} against one world, plus the rows that judge it.
 *
 * <p><b>Why this is main-tree and not test-tree.</b> The failure this slice exists to close is
 * a capability whose producer only tests can reach: {@code GoalPolicy} was test-only, and a
 * seam defined beside it would have inherited that. A seam here is reachable from
 * {@code core/src/main}, so "run a policy against a world and score it" is a capability the
 * shipped code has, not one the suite borrows.
 *
 * <p><b>What it deliberately does not do.</b> It does not know how to build a world, tick it,
 * or judge a task. Those are the simulator's job and the task's job respectively, and this
 * class knows neither -- it is handed both. That is what makes the control possible: a control
 * that owned the rows would be a second suite, and two suites drift.
 *
 * <p><b>The order matters and is the reason for the class.</b> The world is built FIRST and
 * the policy is bound to it, which is the shape {@code GoalPolicy} already has -- it takes
 * {@code (world, harness)} in its constructor. So a control pair is two bindings of the SAME
 * builder to different policies, and the fixture cannot drift between the two runs because
 * neither policy existed when the fixture was built.
 *
 * @param <W> the world type. Generic because this class is main-tree and the simulator is
 *            test-tree: {@code PolicyRun} must not be able to name {@code SimWorld}, or
 *            {@code core/src/main} would not compile.
 */
public final class PolicyRun<W> {

    /** What a run found. Every field is read off the world; none is a policy's opinion. */
    public record Verdict(boolean pass, String fact) {
    }

    /**
     * Binds a policy to an already-built world.
     *
     * <p>A function rather than a constructor reference because the world is generic here and
     * the policy classes are not: {@code new GoalPolicy(world, harness)} is two arguments and
     * {@link Policy} knows about neither of them.
     */
    public interface Binder<W> {
        Policy bind(W world);
    }

    /** The rows, as a function of the finished world. */
    public interface Scorer<W> {
        Verdict score(W world);
    }

    private final Binder<W> binder;
    private final Scorer<W> scorer;

    public PolicyRun(Binder<W> binder, Scorer<W> scorer) {
        this.binder = binder;
        this.scorer = scorer;
    }

    /**
     * Binds {@code policy} to {@code world}, lets it try for {@code goal}, and scores what
     * happened.
     *
     * <p>An exception thrown by the policy is a FAILURE, never a pass and never an escape. That
     * is not defensive coding: a runner that lets a throw read as anything else is a runner
     * whose green rows mean nothing, which is why
     * {@link BrokenPolicies.ClaimsSuccessWithoutActing} exists -- to be caught by the WORLD
     * rows rather than by the return value.
     */
    public Verdict run(W world, String goal, Policy policy) {
        try {
            policy.obtain(goal);
            return scorer.score(world);
        } catch (RuntimeException thrown) {
            return new Verdict(false, "the policy threw " + thrown.getClass().getSimpleName()
                    + " (" + thrown.getMessage() + "), which this runner reports as a FAILURE:"
                    + " a runner that reads an exception as a pass, or lets it escape as a"
                    + " crash, has green rows that mean nothing");
        }
    }

    /**
     * Builds a fresh world, binds {@code policy} to it, and scores the result.
     *
     * <p>Fresh per call, so two policies in one control pair cannot inherit each other's
     * mutations. That is the whole reason a control is trustworthy: the second run is not the
     * first run's leftovers.
     */
    public Verdict run(Supplier<W> worldBuilder, String goal, Policy policy) {
        return run(worldBuilder.get(), goal, policy);
    }

    /**
     * Runs both policies against their own copies of one world and reports both verdicts.
     *
     * <p>The pairing is the point. A single run cannot distinguish "the task is hard and the
     * policy solved it" from "the assertion cannot fail" -- only a run that PASSES beside a
     * run that FAILS, on the same world with the same rows, says the rows have teeth. This
     * method exists so no caller has to remember to build that pair, because "remember to
     * write the control" is exactly what a passing-only suite forgets.
     *
     * @param worldBuilder builds the world; called once per policy
     * @param goal         the item name handed to both policies
     * @param goodLabel    what the passing policy is called, for the report
     * @param badLabel     what the failing policy is called, for the report
     */
    public String pair(Supplier<W> worldBuilder, String goal, Policy good, Policy bad,
                       String goodLabel, String badLabel) {
        Verdict g = run(worldBuilder.get(), goal, good);
        Verdict b = run(worldBuilder.get(), goal, bad);
        List<String> lines = new ArrayList<>();
        lines.add(goodLabel + " -> pass=" + g.pass() + ": " + g.fact());
        lines.add(badLabel + " -> pass=" + b.pass() + ": " + b.fact());
        return String.join("\n", lines);
    }
}