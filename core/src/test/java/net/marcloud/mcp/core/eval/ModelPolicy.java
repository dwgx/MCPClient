package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.action.ActToolsHeadless;
import net.marcloud.mcp.core.io.http.Json;

/**
 * A {@link Policy} whose decisions come from a language model, one call at a time.
 *
 * <p><b>It sits beside {@code GoalPolicy}, not instead of it.</b> {@code GoalPolicy} is the only
 * PASS evidence in twenty-six tasks, and swapping it out would remove the control that says the
 * substrate can do this at all. So this class implements the same main-tree {@link Policy} seam
 * and the two run against the same world with the same rows; see {@link ModelRound}.
 *
 * <p><b>Why {@code obtain} runs a LOOP (design question 1, answered).</b> {@link Policy#obtain}
 * takes a goal string and returns a boolean, so it offers the model exactly one question. Option
 * (b) -- one model call per {@code obtain}, with the model emitting a whole plan -- would measure
 * the harness, not the model: the harness would have to be the thing that decides how many
 * times to re-ask, what to observe between asks, and when to stop, which is precisely the
 * sequencing {@code GoalPolicy} already does and precisely what we do not know. Option (a), an
 * internal loop of observe / decide / apply, is chosen because every one of those decisions then
 * belongs to the model. The cost of (a) is real and is stated here rather than paid quietly: a
 * model that misreads a tool description produces a long, plausible, wrong transcript instead of
 * a short wrong one, so a failure is harder to localise than under (b). What buys that cost back
 * is that every turn's observation, reply and tool result is recorded verbatim, so the transcript
 * IS the diagnosis.
 *
 * <p><b>The model's word for "done" is never believed (question 2's real cost).</b> A reply of
 * {@code {"tool":"done"}} does not end the run: the harness answers with a world reading and, if
 * the world disagrees, says so and keeps going. {@link #obtain} returns
 * {@code world.count(goal) > 0} -- a world read, not a self-report. That is the whole reason this
 * class is evidence about anything.
 *
 * <p><b>Translations go through the production parser (design question 2).</b> The model's
 * {@code arguments} object is handed to the production {@link ActTools} handler as a real
 * {@link CallToolRequest}; {@code ActIntentParser} does the validating and the refusing. So a
 * model that sends a block target to {@code interact.kind="use"} receives the production refusal
 * sentence and the next observation carries it -- the schema and the prose are under test, not
 * bypassed. No {@code ActIntent} is ever hand-built here.
 *
 * <p><b>The world is frozen while the model thinks (design question 3).</b> {@link EvalHarness}
 * ticks only after an {@code act_set} has landed, for a bounded drain. This is not real time: a
 * live client runs at 20Hz throughout the model's deliberation. So the round is OPTIMISTIC -- it
 * understates hunger, mob pressure, and above all the night running out while the model thinks.
 * Stated in the artifact as a limit on the conclusion, not hidden in a comment.
 *
 * <p><b>The whole transcript goes back every turn (design question 4).</b> No truncation, no
 * summarisation, because a real MCP session carries the entire conversation and truncating would
 * flatter the model with a memory it would not have. The cost is quadratic tokens; the number is
 * measured in {@link ModelBridge.Cost} and printed in the artifact rather than estimated.
 *
 * <p><b>What this class cannot be used to claim.</b> It drives four actuation tools and one read
 * planner. There is no tool in this tree that PERFORMS a craft -- {@code craft_plan} says so in
 * its own description -- so a model using only the tool surface cannot turn logs into a chest no
 * matter how well it reasons. That is a fact about the tool surface, proven independently in
 * {@link ModelRound}, and it is why a green model round would still not be the north star.
 */
public final class ModelPolicy implements Policy {

    /** Turns allowed per {@code obtain}. A model that cannot converge inside this has failed. */
    public static final int DEFAULT_TURNS = 14;

    /**
     * Ticks drained after an {@code act_set} before the model is asked again.
     *
     * <p>Small on purpose. A {@code go_to} walk is driven by {@code EvalHarness.runMove} inside
     * the harness, so a 40-tick drain lets a short interact or hotbar swap land without letting
     * the model observe a world that has run away from it.
     */
    public static final int DRAIN_TICKS = 40;

    /** Ticks a walk is allowed before it is declared not-arrived. Mirrors {@code runMove(1200)}. */
    public static final int WALK_BUDGET = 1200;

    private final SimWorld world;
    private final EvalHarness harness;
    private final Bridge bridge;
    private final int maxTurns;
    private final String systemPrompt;

    private final List<String> transcript = new ArrayList<>();
    private final List<ModelBridge.Turn> turns = new ArrayList<>();
    private ModelBridge.Cost cost = ModelBridge.Cost.zero();

    private String lastToolReply;
    private String lastToolName;
    private String goal;
    private NightShelter shelter;
    private boolean sawToolSurfaceGap;
    private int unreachable;

    /** Pluggable so the regression test can drive a scripted model without spending tokens. */
    public interface Bridge {
        ModelBridge.Turn ask(String systemPrompt, String transcript);
    }

    public ModelPolicy(SimWorld world, EvalHarness harness, Bridge bridge,
                       int maxTurns, NightShelter shelter, String systemPrompt) {
        this.world = world;
        this.harness = harness;
        this.bridge = bridge;
        this.maxTurns = maxTurns;
        this.shelter = shelter;
        this.systemPrompt = systemPrompt;
    }

    /** Every turn, in order: what it saw, what it said, what the tool said back. */
    public List<String> transcript() {
        return List.copyOf(transcript);
    }

    /** The raw model calls, for the token/cost ledger. */
    public List<ModelBridge.Turn> turns() {
        return List.copyOf(turns);
    }

    public ModelBridge.Cost cost() {
        return cost;
    }

    /**
     * Whether the model itself noticed that nothing can craft.
     *
     * <p>Reported separately from pass/fail, because a model that says "there is no tool that
     * does this" and stops is behaving correctly about a broken surface even though the round is
     * a failure. Conflating the two would either reward flailing or punish an honest diagnosis.
     */
    public boolean sawToolSurfaceGap() {
        return sawToolSurfaceGap;
    }

    @Override
    public boolean obtain(String item) {
        this.goal = item;
        for (int turn = 1; turn <= maxTurns; turn++) {
            String view = StandInObservation.render(world, shelter, lastToolReply, lastToolName,
                    slot -> String.valueOf(harness.phaseOf(slot)));
            transcript.add("### TURN " + turn + "\n<SIGHTING>\n" + view);

            ModelBridge.Turn reply = bridge.ask(systemPrompt, String.join("\n\n", transcript));
            turns.add(reply);
            cost = cost.plus(reply);
            transcript.add("<MODEL>\n" + reply.reply());

            if (reply.transportFailed()) {
                // A slow or dead provider ends the TURN, not the round. The first version broke
                // out here, so a single call that took longer than the deadline threw away every
                // turn after it -- the transcript ended at turn 11 of a fourteen-turn budget, and
                // the artifact read as if the model had stopped thinking when in fact nothing had
                // stopped it. What decides a round is what the MODEL did, and the harness losing
                // the provider is not the model choosing to quit.
                transcript.add("<HARNESS> the model was unreachable this turn; the round"
                        + " continues, and the world has not moved.\n");
                unreachable++;
                lastToolName = null;
                lastToolReply = "your previous answer never arrived. The world has not changed"
                        + " since the last sighting you saw. Continue from there.";
                continue;
            }

            String json = ModelBridge.firstJsonObject(reply.reply());
            if (json == null) {
                transcript.add("<HARNESS> that was not one JSON object, so no tool was called. "
                        + "Reply with the object alone.\n");
                continue;
            }
            Map<String, Object> call = Json.readObject(json);
            if (call == null) {
                transcript.add("<HARNESS> that JSON object did not parse; no tool was called.\n");
                continue;
            }

            String tool = str(call.get("tool"));
            if (tool == null) {
                transcript.add("<HARNESS> the object named no 'tool'; no call was made.\n");
                continue;
            }

            if ("done".equals(tool)) {
                boolean actually = world.count(item) > 0;
                transcript.add("<WORLD-READ> you said done. The bag holds " + world.count(item)
                        + " x " + item + ".\n");
                if (actually) {
                    transcript.add("<HARNESS> agreed, and that is a WORLD READ rather than your"
                            + " word. The round is over.\n");
                    return true;
                }
                lastToolName = null;
                lastToolReply = "not done: the bag does not hold " + item + ". Keep going.";
                continue;
            }

            lastToolName = tool;
            lastToolReply = dispatch(tool, call.get("arguments"));
            transcript.add("<TOOL " + tool + ">\n" + lastToolReply + "\n");
            drain();
        }

        boolean finallyHeld = world.count(item) > 0;
        if (unreachable > 0) {
            transcript.add("<HARNESS> " + unreachable + " of " + maxTurns
                    + " turns were lost to the provider being unreachable. That is a fault in the"
                    + " TRANSPORT, not a decision by the model, and the round's result below is"
                    + " about the " + (maxTurns - unreachable) + " turns that did answer.\n");
        }
        transcript.add("<HARNESS> turn budget " + maxTurns + " spent. The bag holds "
                + world.count(item) + " x " + item + ".\n");
        return finallyHeld;
    }

    /**
     * Hands the call to the production handler and returns its reply text, errors included.
     *
     * <p>Every refusal the model provokes is a production refusal, quoted into the transcript
     * whole. A harness that swallowed refusals would make the tool surface look kinder than it
     * is, which is the defect this project keeps paying for.
     */
    private String dispatch(String tool, Object rawArgs) {
        Map<String, Object> args = rawArgs instanceof Map<?, ?> m ? castArgs(m) : Map.of();
        try {
            switch (tool) {
                case "act_set":
                    return text(call(ActToolsHeadless.actSet(harness.runtime()), "act_set",
                            args));
                case "act_status":
                    return text(call(ActToolsHeadless.actStatus(harness.runtime()), "act_status",
                            args));
                case "act_cancel":
                    return text(call(ActToolsHeadless.actCancel(harness.runtime()), "act_cancel",
                            args));
                case "act_plan":
                    return text(call(ActToolsHeadless.actPlan(harness.runtime()), "act_plan",
                            args));
                case "craft_plan":
                    return craftPlan(args);
                default:
                    return "no such tool: '" + tool + "'. The tools you have are act_set,"
                            + " act_status, act_cancel, act_plan, craft_plan, done.";
            }
        } catch (RuntimeException e) {
            return "the tool threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * {@code craft_plan}, from the production recipe table.
     *
     * <p>The recipe half only, and that is a statement about this ROUND rather than about the
     * tree: {@code ModelPolicy} does not implement {@code craft_plan}'s own handler, because that
     * handler needs a live {@code GameAccess} and there is no client here. What the model gets is
     * the recipe table -- {@link net.marcloud.mcp.core.drivers.craft.Craft} -- over the bag it is
     * really holding, which is the read the tool exists to provide.
     *
     * <p>It deliberately does NOT tell the model that crafting is impossible. That sentence was
     * true when this was written and {@code CraftWire} has since added
     * {@code act_set interact kind="craft"}, so a prompt asserting it would be teaching the model
     * a falsehood about the surface it is being measured against -- the one thing this whole
     * slice exists not to do.
     */
    private String craftPlan(Map<String, Object> args) {
        String item = str(args.get("item"));
        if (item == null || item.isBlank()) {
            return "craft_plan needs 'item', a registry name such as \"chest\" or \"planks\".";
        }
        var recipes = net.marcloud.mcp.core.drivers.craft.Craft.recipesFor(item);
        if (recipes.recipes().isEmpty()) {
            return "no recipe makes " + item + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("craft_plan item=").append(item).append(" recipes=")
                .append(recipes.recipes().size()).append('\n');
        for (var r : recipes.recipes()) {
            sb.append("  output=").append(r.output()).append(" x").append(r.outputCount())
                    .append(" grid=").append(r.width()).append('x').append(r.height())
                    .append(" requiresTable=").append(r.requiresTable())
                    .append(" cells=");
            for (var c : r.cells()) {
                sb.append('(').append(c.row()).append(',').append(c.col()).append('=')
                        .append(c.item()).append(')');
            }
            sb.append('\n');
        }
        sb.append("This tool does not craft. To actually perform a craft, read the act_set schema"
                + " above: it is the only tool here that changes the world, and its"
                + " interact.kind enum lists every action it will accept.\n");
        return sb.toString();
    }

    /** Lets the world move only after a call has landed, for a bounded drain. */
    private void drain() {
        boolean walking = false;
        for (ActSlot slot : ActSlot.values()) {
            if (harness.phaseOf(slot) == ActPhase.ACTIVE) {
                walking = true;
            }
        }
        if (walking) {
            harness.runUntil(ActSlot.MOVE, WALK_BUDGET);
            return;
        }
        harness.ticks(DRAIN_TICKS);
    }

    private static CallToolResult call(
            io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec,
            String name, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(name, args));
    }

    private static String text(CallToolResult r) {
        StringBuilder sb = new StringBuilder();
        for (var c : r.content()) {
            if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent t) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        if (Boolean.TRUE.equals(r.isError())) {
            sb.insert(0, "ERROR: ");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArgs(Map<?, ?> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "ModelPolicy[%d turns, bag said %s]", turns.size(),
                String.valueOf(goal));
    }
}
