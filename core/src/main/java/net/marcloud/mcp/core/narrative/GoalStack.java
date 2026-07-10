package net.marcloud.mcp.core.narrative;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The agent's intent + story: a stack of goals (GITM-style hierarchy — top goal,
 * pushed sub-goals) plus a rolling narration log of what it's been doing. This is
 * the "fable" layer: the AI maintains a high-level objective and narrates its
 * journey, giving continuity and a sense of purpose across many tool calls.
 *
 * <p>Thread-safe (tools run on worker threads).
 */
public final class GoalStack {

    private final Deque<String> goals = new ArrayDeque<>();
    private final List<String> narration = new ArrayList<>();
    private final int narrationCap;
    private final Object lock = new Object();

    public GoalStack(int narrationCap) {
        this.narrationCap = Math.max(16, narrationCap);
    }

    /** Replace the whole stack with a single top-level goal. */
    public void setGoal(String goal) {
        synchronized (lock) {
            goals.clear();
            goals.push(goal);
            narrate("goal set: " + goal);
        }
    }

    /** Push a sub-goal (refine the current objective). */
    public void pushSubgoal(String subgoal) {
        synchronized (lock) {
            goals.push(subgoal);
            narrate("subgoal: " + subgoal);
        }
    }

    /** Complete/abandon the current sub-goal, returning to the parent. */
    public String popGoal() {
        synchronized (lock) {
            if (goals.isEmpty()) {
                return null;
            }
            String done = goals.pop();
            narrate("done: " + done);
            return done;
        }
    }

    /** The current (top) goal, or null. */
    public String currentGoal() {
        synchronized (lock) {
            return goals.peek();
        }
    }

    /** Append a narration line (timestamped). */
    public void narrate(String line) {
        synchronized (lock) {
            narration.add(line);
            while (narration.size() > narrationCap) {
                narration.remove(0);
            }
        }
    }

    /** Full goal stack, top first. */
    public List<String> goals() {
        synchronized (lock) {
            return new ArrayList<>(goals);
        }
    }

    /** Recent narration lines, oldest first. Negative {@code n} yields none. */
    public List<String> recentNarration(int n) {
        synchronized (lock) {
            int k = Math.max(0, n);
            int from = Math.max(0, narration.size() - k);
            return new ArrayList<>(narration.subList(from, narration.size()));
        }
    }

    /** A rendered summary: goal stack + recent story. */
    public String summary(int narrationLines) {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            sb.append("goals (top first):").append(System.lineSeparator());
            if (goals.isEmpty()) {
                sb.append("  (none)").append(System.lineSeparator());
            } else {
                int depth = 0;
                for (String g : goals) {
                    sb.append("  ").append("  ".repeat(depth++)).append("- ").append(g)
                            .append(System.lineSeparator());
                }
            }
            sb.append("recent story:").append(System.lineSeparator());
            List<String> recent = recentNarration(narrationLines);
            if (recent.isEmpty()) {
                sb.append("  (nothing yet)");
            } else {
                for (String line : recent) {
                    sb.append("  · ").append(line).append(System.lineSeparator());
                }
            }
            return sb.toString().stripTrailing();
        }
    }
}
