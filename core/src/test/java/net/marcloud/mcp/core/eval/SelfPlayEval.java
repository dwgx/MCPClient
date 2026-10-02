package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs every task once, prints what happened, and exits non-zero if any of them failed.
 *
 * <p><b>One run, one world, no re-roll.</b> Each task builds its own world from a fixed
 * declarative description and is executed exactly once. A failing task is reported as a failing
 * task, not retried until it passes: a suite that re-rolls until green is a suite whose green
 * means nothing. The one nondeterministic element in the whole stack is
 * {@code NavController}'s reaction delay (a 4..8 tick draw), and no task asserts a tick count --
 * every one of them asserts an endpoint -- so a rerun cannot change a verdict.
 *
 * <p><b>Prints the fact, not a verdict alone.</b> A line saying FAIL is not actionable; a line
 * saying where the player ended and what the inventory holds is.
 *
 * <pre>
 * mvn -q -pl core test -Dtest=SelfPlayEvalTest
 * </pre>
 */
public final class SelfPlayEval {

    private SelfPlayEval() {
    }

    /** One task's outcome plus how long it took, for the summary line. */
    public record Scored(EvalSuite.Result result, long millis) {
    }

    /**
     * Run every task, in order, exactly once.
     *
     * @param only when non-empty, run just these ids (a task id is matched by prefix)
     */
    public static List<Scored> runAll(List<String> only) {
        List<Scored> out = new ArrayList<>();
        for (EvalSuite.Task task : EvalSuite.all()) {
            if (!only.isEmpty() && only.stream().noneMatch(p -> task.id().startsWith(p))) {
                continue;
            }
            long start = System.nanoTime();
            EvalSuite.Result r;
            try {
                r = task.run();
            } catch (Throwable t) {
                // A task that THROWS is a failing task, never a skipped one. Swallowing it as an
                // exception and continuing would report a suite where the count happens to add up.
                r = new EvalSuite.Result(task.id(), false,
                        "threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            out.add(new Scored(r, (System.nanoTime() - start) / 1_000_000L));
        }
        // A filter that matches NOTHING must not read as a suite that passed everything. With the
        // silent-continue above, asking for a task id that does not exist printed an empty PASS
        // list and the gate went green on zero tasks -- the same shape as a check that cannot fail.
        if (!only.isEmpty() && out.isEmpty()) {
            throw new IllegalArgumentException("no eval task id starts with any of " + only
                    + "; the suite would report success having run nothing");
        }
        return out;
    }

    /** The report text, one line per task plus a total. */
    public static String render(List<Scored> scored) {
        StringBuilder sb = new StringBuilder();
        int pass = 0;
        for (Scored s : scored) {
            if (s.result().pass()) {
                pass++;
            }
            sb.append(String.format(Locale.ROOT, "%-4s %-46s %4d ms  %s%n",
                    s.result().pass() ? "PASS" : "FAIL", s.result().id(), s.millis(),
                    s.result().fact()));
        }
        sb.append(String.format(Locale.ROOT, "%n%d/%d tasks passed%n", pass, scored.size()));
        return sb.toString();
    }

    public static void main(String[] args) {
        List<String> only = args == null ? List.of() : List.of(args);
        List<Scored> scored = runAll(only);
        System.out.print(render(scored));
        boolean allPass = scored.stream().allMatch(s -> s.result().pass());
        // A non-zero exit is the whole point: this is meant to be a gate, not a report.
        System.exit(allPass ? 0 : 1);
    }
}
