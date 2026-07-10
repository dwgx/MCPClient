package net.marcloud.mcp.core.narrative;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.registry.CapabilityRegistry;

/**
 * MCP tools over the {@link GoalStack}: let the AI hold a high-level objective,
 * refine it into sub-goals, narrate its journey, and read back its own intent +
 * story. The "fable" layer — continuity of purpose across many tool calls.
 */
public final class NarrativeTools {

    private final GoalStack goals;

    public NarrativeTools(GoalStack goals) {
        this.goals = goals;
    }

    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : List.of(setGoal(), pushSubgoal(), completeGoal(),
                narrate(), getStory())) {
            var t = spec.tool();
            registry.register(t.name(), spec, null, t.description(), true);
        }
    }

    private static CallToolResult ok(String s) {
        return CallToolResult.builder().addTextContent(s).isError(false).build();
    }

    private static CallToolResult err(String s) {
        return CallToolResult.builder().addTextContent(s).isError(true).build();
    }

    private static String arg(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return v == null ? null : v.toString();
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String d) {
        return Map.of("type", "string", "description", d);
    }

    private SyncToolSpecification setGoal() {
        Tool tool = Tool.builder()
                .name("set_goal")
                .description("Set your top-level objective (replaces the whole goal stack). "
                        + "Everything you do should serve the current goal.")
                .inputSchema(schema(Map.of("goal", str("the objective")), List.of("goal")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String g = arg(request.arguments(), "goal");
            if (g == null) {
                return err("goal is required");
            }
            goals.setGoal(g);
            return ok("goal set: " + g);
        });
    }

    private SyncToolSpecification pushSubgoal() {
        Tool tool = Tool.builder()
                .name("push_subgoal")
                .description("Refine the current goal by pushing a sub-goal onto the stack "
                        + "(work on this next; complete_goal returns to the parent).")
                .inputSchema(schema(Map.of("subgoal", str("the sub-goal")), List.of("subgoal")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String g = arg(request.arguments(), "subgoal");
            if (g == null) {
                return err("subgoal is required");
            }
            goals.pushSubgoal(g);
            return ok("subgoal pushed: " + g);
        });
    }

    private SyncToolSpecification completeGoal() {
        Tool tool = Tool.builder()
                .name("complete_goal")
                .description("Mark the current (top) goal done/abandoned and pop back to the "
                        + "parent goal.")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String done = goals.popGoal();
            if (done == null) {
                return err("no goal on the stack");
            }
            String now = goals.currentGoal();
            return ok("completed: " + done + (now == null ? " (stack empty)" : "; back to: " + now));
        });
    }

    private SyncToolSpecification narrate() {
        Tool tool = Tool.builder()
                .name("narrate")
                .description("Record a line in your story log — what you just did or observed. "
                        + "Builds a continuous narrative of your journey.")
                .inputSchema(schema(Map.of("line", str("what happened")), List.of("line")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            String line = arg(request.arguments(), "line");
            if (line == null) {
                return err("line is required");
            }
            goals.narrate(line);
            return ok("noted");
        });
    }

    private SyncToolSpecification getStory() {
        Tool tool = Tool.builder()
                .name("get_story")
                .description("Read back your current goal stack and recent narration — your "
                        + "intent and the story so far. Use it to reorient.")
                .inputSchema(schema(Map.of(
                        "lines", Map.of("type", "integer",
                                "description", "how many recent narration lines (default 20)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            int lines = 20;
            Object v = request.arguments() == null ? null : request.arguments().get("lines");
            if (v instanceof Number num) {
                lines = Math.max(1, num.intValue());
            }
            return ok(goals.summary(lines));
        });
    }
}
