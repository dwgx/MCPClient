package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * A gate that reports success because it ran nothing is worse than no gate.
 *
 * <p><b>The shape this pins.</b> {@code SelfPlayEval.runAll(List)} filters tasks by id prefix and
 * silently skips everything that does not match. That is the right behaviour for a filter and the
 * wrong behaviour for a gate: asked for a task id that does not exist -- a typo, a renamed task, a
 * stale command in a runbook -- the runner returned an empty list, the gate printed an empty PASS
 * report, and {@code SelfPlayEvalTest} asserted that the failure list was empty. Green, having
 * scored zero tasks.
 *
 * <p>The same shape has already cost this project twice in production code: a probe that never
 * compiled and answered "no drops on the ground", and an {@code outcome=dawn} derived from a raw
 * tick accumulator so it was true forever. A check that cannot fail is not a weaker check, it is a
 * false one.
 *
 * <p>Two assertions, because either alone is satisfiable by the wrong thing: the empty filter must
 * still run the suite (so the guard cannot be "always throw"), and a filter that matches nothing
 * must be an error rather than an empty success.
 */
public class AnEvalFilterThatMatchesNothingIsNotASuccessTest {

    @Test
    public void anEmptyFilterStillRunsTheSuite() {
        List<SelfPlayEval.Scored> scored = SelfPlayEval.runAll(List.of());
        assertTrue("an empty filter means every task, not none; ran " + scored.size(),
                scored.size() > 1);
    }

    @Test
    public void aFilterThatMatchesNoTaskIsRefusedRatherThanReportedAsSuccess() {
        try {
            List<SelfPlayEval.Scored> scored = SelfPlayEval.runAll(List.of("T99-does-not-exist"));
            fail("runAll accepted a filter matching no task and returned " + scored.size()
                    + " results -- that is a green gate that ran nothing");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal must name the filter so a typo is visible: " + expected.getMessage(),
                    expected.getMessage().contains("T99-does-not-exist"));
        }
    }
}