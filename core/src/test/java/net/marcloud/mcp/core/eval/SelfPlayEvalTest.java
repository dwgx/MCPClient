package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * The regression gate: every task must pass, or the build is red.
 *
 * <p><b>What this is for.</b> The rest of the suite answers "does this code do what it says".
 * This answers the question that matters to the Owner -- "can the agent actually DO the task" --
 * as a property of the world rather than of any self-report, and it makes a change that makes
 * the agent worse fail the build instead of passing review.
 *
 * <p><b>Why it is one test and not thirteen.</b> Split per task, a red build would say which task
 * broke; one test says the same thing in its failure message and cannot be partially skipped by
 * a {@code -Dtest=...} filter. The report is printed either way.
 *
 * <p><b>It is not tuned to pass.</b> A task that fails is a finding, and the honest response is to
 * find out why -- not to loosen the assertion until it goes green. That rule is why every task
 * asserts a world fact: there is no assertion about wording, formatting or a controller's own
 * opinion of itself for a future edit to quietly satisfy.
 */
public class SelfPlayEvalTest {

    @Test
    public void everyTaskPasses() {
        List<SelfPlayEval.Scored> scored = SelfPlayEval.runAll(List.of());
        String report = SelfPlayEval.render(scored);
        System.out.println(report);
        StringBuilder failures = new StringBuilder();
        for (SelfPlayEval.Scored s : scored) {
            if (!s.result().pass()) {
                failures.append("\n  FAIL ").append(s.result().id())
                        .append("\n       ").append(s.result().fact());
            }
        }
        assertTrue("the self-play eval regressed; " + failures + "\n\nfull report:\n" + report,
                failures.length() == 0);
    }
}
