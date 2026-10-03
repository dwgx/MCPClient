package net.marcloud.mcp.core.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.action.ActToolsHeadless;
import net.marcloud.mcp.core.drivers.craft.Craft;
import net.marcloud.mcp.core.eval.EvalSuite;
import net.marcloud.mcp.core.io.http.Json;

/**
 * ONE REAL MODEL-DRIVEN ROUND, plus the control it is only interpretable beside.
 *
 * <p><b>What this is for.</b> The Owner's north star is "can a weak LLM plus a system prompt plus
 * the tool surface we already have finish a round". It had never been run. This class runs it,
 * and -- because a round alone is uninterpretable -- runs {@code GoalPolicy} on the same world
 * with the same rows so the two edges differ in exactly one thing: who decided.
 *
 * <p><b>The two edges.</b> Both start from {@link EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn#buildWorld()},
 * the same fixture T26 already ships, so the comparison is against a world this repository has
 * run twenty-six tasks on rather than a world invented here. Both are handed the same goal string
 * and the same mechanical placement step, and both are graded by the SAME three conditions,
 * measured by re-reading the world rather than by asking either decision whether it succeeded.
 *
 * <p><b>The three conditions, and what "proven" means for each.</b>
 * <ol>
 *   <li><i>a shelter</i> -- {@link NightShelter}, the production accumulator, fed one sample per
 *       tick of the night at the PLAYER's feet cell. {@code sheltered()} is
 *       {@code measured() && openSamples == 0}.</li>
 *   <li><i>health never below 18 all night</i> -- {@code SimWorld.minimumHealth()}, which the
 *       survival ledger owns and which this class never writes to.</li>
 *   <li><i>the chest still standing at dawn</i> -- {@link DawnChest}, the production box
 *       instrument, one sample per tick, PLUS a direct {@code blockAt} read at dawn. The
 *       instrument alone would be a single opinion; the block read is the world answering
 *       again.</li>
 * </ol>
 * Every one is re-read after the fact. Neither the model nor {@code GoalPolicy} is asked whether
 * it succeeded, and {@code Policy.obtain}'s boolean is not used as evidence for any of the three.
 *
 * <p><b>And the tool-surface row, which is the part that decides the verdict.</b>
 * {@link #toolSurfaceRow()} asks the recipe table whether a chest is craftable at all and asks
 * the registered tool surface whether any tool PERFORMS a craft. If the second answer is no, then
 * no model, however good, can obtain a chest through the tool surface, and the round's failure
 * localises to a missing producer rather than to a weak mind. That row is computed from the
 * registry and the recipe table alone: it is red or green before the model has said a word, which
 * is what makes it independent of everything the model did.
 *
 * <p><b>Where the artifacts go.</b> {@code .ai-notes/docs/audits/} for the readable round and
 * {@code .ai-notes/docs/audits/} for the machine-readable one, both written by
 * {@link #writeArtifacts}. The report is written BEFORE the verdict is returned, because a
 * conclusion that exists only in a chat message is a conclusion nobody can check.
 */
public final class ModelRound {

    /** The model this round reached for. Recorded in the artifact; a rerun may change it. */
    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash";

    /**
     * The system prompt, deliberately the SHORTEST thing that can possibly work.
     *
     * <p><b>Why it is not the real one.</b> The brief for this round is to measure whether the
     * path works at all, and the prompt's content is an EXPERIMENT VARIABLE, not the deliverable.
     * A short prompt that fails and a long prompt that succeeds is a result about prompts; a short
     * prompt that fails and no long prompt ever tried is a result about the TOOL SURFACE. This
     * round has to answer the second question before the first is worth asking, and it cannot if
     * the prompt is load-bearing.
     *
     * <p>It also states the goal, the tool names, and the reply format, because those are the
     * minimum for a parse and withholding them would measure the model's willingness to guess at a
     * wire format rather than its decisions.
     */
    public static final String INSTRUCTIONS = """
            You are playing Minecraft through a tool interface, one call at a time.

            Your goal is to end up HOLDING one chest in your inventory.

            Each turn you are shown what your tools can see, and you reply with exactly ONE JSON
            object and nothing else -- no prose, no markdown fence:

            {"thought": "one short sentence", "tool": "NAME", "arguments": { ... }}

            An argument the tool refuses comes back to you as an ERROR with the reason. Read it
            and send something different. You have a limited number of turns; spend them acting,
            not explaining.

            The tools you have are described below, EXACTLY as this project ships them. Read the
            schema: every 'enum' lists the values that are actually accepted, and a value that is
            not in an enum will be refused. If a tool's own description says it cannot do
            something, believe it -- it is telling you the truth about its own limits.""";

    /**
     * The system prompt actually sent: the instructions above, then the SHIPPED tool surface.
     *
     * <p><b>Why the tool surface is pasted in rather than summarised by hand.</b> An MCP client
     * sends the model the real tool schemas -- name, description, and the closed sets as JSON
     * enums. A hand-written list is a different experiment: it is the experiment where the tool
     * surface has been rewritten by whoever wrote the prompt, and its discoverability is no
     * longer being measured at all. So the descriptions and schemas here are read off the
     * production {@code SyncToolSpecification} objects, which means the {@code interact.kind}
     * enum the model sees is the same closed set {@code ActIntentParser} enforces, including any
     * kind that has been added since.
     *
     * <p>This is also what makes the surface's discoverability measurable. When
     * {@code act_set} grew {@code kind="craft"}, this prompt grew it too, at the same moment, with
     * no edit to this file. A prompt that had to be edited would have measured the editor.
     */
    public static String systemPrompt() {
        StringBuilder sb = new StringBuilder(32768);
        sb.append(INSTRUCTIONS).append("\n\n");
        for (var spec : List.of(
                ActToolsHeadless.actSet(new ActRuntime()),
                ActToolsHeadless.actStatus(new ActRuntime()),
                ActToolsHeadless.actCancel(new ActRuntime()),
                ActToolsHeadless.actPlan(new ActRuntime()))) {
            var tool = spec.tool();
            sb.append("### ").append(tool.name()).append("\n")
                    .append(tool.description()).append("\n")
                    .append("input schema: ")
                    .append(Json.write(tool.inputSchema()))
                    .append("\n\n");
        }
        sb.append("### craft_plan\n")
                .append("Tells you the recipe for an item and what is missing. arguments")
                .append(" {\"item\": \"chest\"}.\n\n");
        return sb.toString();
    }

    /** How many turns the model gets. Small on purpose: the point is the trace, not endurance. */
    public static final int MAX_TURNS = 14;

    private ModelRound() {
    }

    /**
     * One graded edge: the decision, and what the world said afterwards.
     *
     * @param label           which edge this is
     * @param obtained        what {@code Policy.obtain} returned -- recorded, NOT trusted
     * @param bagsChests      how many chests the bag holds, read after obtain
     * @param placed          whether the mechanical placement step put a chest on the box cell
     * @param sheltered       NightEnclosure's verdict over the whole night
     * @param minHealth       the lowest health the survival ledger ever recorded
     * @param chestAtDawn     a direct block read at the box cell after dawn
     * @param chestsStanding  every chest in the swept volume after dawn
     * @param chestItemsAtDawn how many chest items are still in the bag at dawn
     * @param nightSamples    night ticks sampled
     * @param missingSamples  night ticks the box cell did not hold a chest
     * @param detail          the policy's own trace or transcript, already summarised
     */
    public record Edge(String label, boolean obtained, int bagsChests, boolean placed,
                       boolean sheltered, int openSamples, int nightSamples, double minHealth,
                       boolean alive, boolean chestAtDawn, int chestsStanding, int chestItemsAtDawn,
                       int missingSamples, String detail) {

        /** The Owner's three conditions, each from a world read rather than a self-report. */
        public boolean conditionShelter() {
            return sheltered;
        }

        public boolean conditionHealthFloor() {
            return minHealth >= 18.0D;
        }

        public boolean conditionChestAtDawn() {
            return chestAtDawn;
        }

        public boolean allThree() {
            return conditionShelter() && conditionHealthFloor() && conditionChestAtDawn();
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("obtain_returned", obtained);
            m.put("chests_in_bag_after_obtain", bagsChests);
            m.put("box_placed", placed);
            m.put("condition_1_shelter", conditionShelter());
            m.put("night_samples", nightSamples);
            m.put("night_open_samples", openSamples);
            m.put("condition_2_health_floor_18", conditionHealthFloor());
            m.put("lowest_health_all_night", minHealth);
            m.put("alive_at_dawn", alive);
            m.put("condition_3_chest_standing_at_dawn", conditionChestAtDawn());
            m.put("chests_standing_in_world_at_dawn", chestsStanding);
            m.put("chest_items_still_in_bag_at_dawn", chestItemsAtDawn);
            m.put("night_ticks_the_box_was_missing", missingSamples);
            m.put("all_three", allThree());
            m.put("detail", detail);
            return m;
        }
    }

    /**
     * What the tool surface itself can do, asked of the registry and the recipe table.
     *
     * @param chestCraftable   whether the recipe table makes a chest at all
     * @param planksCraftable  whether the recipe table makes planks
     * @param craftingTool     the name of any registered tool that performs a craft, or null
     * @param craftableFromLogs the whole chain, recipe by recipe, or the first link that is missing
     */
    public record ToolSurface(boolean chestCraftable, boolean planksCraftable, String craftingTool,
                              String craftableFromLogs) {

        /**
         * Whether the chain from a tree to a chest exists in the SUBSTRATE at all.
         *
         * <p>This is the control that makes the round interpretable. If it is false, the round's
         * failure says nothing about the model; if it is true, the round's failure is about the
         * model or the surface, and the difference between the two is a separate row.
         */
        public boolean substrateCanMakeAChest() {
            return chestCraftable && planksCraftable;
        }

        /**
         * Whether a MODEL can reach that chest with the tools registered for it.
         *
         * <p>Requires both halves: a recipe, and a tool that actually performs the craft. The
         * second is the one that is missing, and it is missing by construction rather than by
         * accident -- {@code craft_plan} says so in its own description.
         */
        public boolean modelCanReachAChest() {
            return substrateCanMakeAChest() && craftingTool != null;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chest_has_a_recipe", chestCraftable);
            m.put("planks_have_a_recipe", planksCraftable);
            m.put("registered_tool_that_performs_a_craft", craftingTool);
            m.put("substrate_can_make_a_chest", substrateCanMakeAChest());
            m.put("model_can_reach_a_chest_with_registered_tools", modelCanReachAChest());
            m.put("chain", craftableFromLogs);
            return m;
        }
    }

    /**
     * The tool-surface row, computed before any model speaks.
     *
     * <p>Deliberately independent of the model: it reads the recipe table and the tool registry.
     * A row that depended on the model's behaviour could not distinguish "the model failed" from
     * "the surface has no producer", and those have opposite fixes.
     */
    public static ToolSurface toolSurfaceRow() {
        boolean chest = !Craft.recipesFor("chest").recipes().isEmpty();
        boolean planks = !Craft.recipesFor("planks").recipes().isEmpty();
        StringBuilder chain = new StringBuilder();
        chain.append("log --(recipe)--> planks --(recipe)--> chest: ")
                .append(planks ? "yes" : "NO").append(" / ").append(chest ? "yes" : "NO");
        return new ToolSurface(chest, planks, scanForACraftPerformer(), chain.toString());
    }

    /**
     * SCANS the shipped {@code act_set} schema for a kind that performs a craft.
     *
     * <p><b>This used to be a hardcoded {@code null}, and that was a test that could not fail.</b>
     * The first version of this row asserted a constant equalled null, which reads as a
     * measurement and is actually an echo -- and it went on asserting "no tool performs a craft"
     * for exactly as long as nobody edited the constant, including through the arrival of
     * {@code act_set interact kind="craft"}. That is the same defect this project keeps paying
     * for, committed by the very slice written to detect it.
     *
     * <p>So it is derived now, from the production schema's own {@code interact.kind} enum, which
     * is the closed set {@code ActIntentParser} actually enforces. If someone adds a kind that
     * crafts, this returns non-null with no edit here; if the kind is removed, it returns null
     * again. The read is the same closed set the model is shown in its system prompt, which is
     * what makes "can a model reach a chest" and "does the surface have the tool" two facts about
     * one thing rather than two things.
     *
     * @return the tool and kind that crafts, or null when the shipped surface has neither
     */
    private static String scanForACraftPerformer() {
        var schema = ActToolsHeadless.actSet(new ActRuntime()).tool().inputSchema();
        Object interact = ((Map<?, ?>) schema).get("properties");
        Object kindNode = null;
        if (interact instanceof Map<?, ?> props) {
            Object slot = props.get("interact");
            if (slot instanceof Map<?, ?> im
                    && im.get("properties") instanceof Map<?, ?> ip) {
                kindNode = ip.get("kind");
            }
        }
        if (kindNode instanceof Map<?, ?> k && k.get("enum") instanceof List<?> vals) {
            for (Object v : vals) {
                if ("craft".equals(String.valueOf(v))) {
                    return "act_set(interact.kind=\"craft\")";
                }
            }
        }
        return null;
    }

    /**
     * Runs one edge: the decision, then the shared mechanical placement, then the whole night,
     * graded by re-reading the world at dawn.
     *
     * <p>The night loop is deliberately NOT {@code EvalSuite}'s: that one measures the BOX cell,
     * and the Owner's first condition is about the PLAYER. So this drives the same
     * {@link EvalHarness#tick()} over the same production instruments, with the player as the
     * subject, and the box cell sampled alongside so both readings come from one night.
     */
    public static Run run(String label, boolean modelEdge, ModelPolicy.Bridge bridge) {
        return runWithCollector(label, modelEdge, bridge, p -> {
        });
    }

    /**
     * As {@link #run}, handing the model policy to {@code take} before the world is measured.
     *
     * <p>The seam exists so the live round can capture the transcript and the token ledger BEFORE
     * the night is run, since a world read after dawn cannot recover what the model said.
     */
    public static Run runWithCollector(String label, boolean modelEdge, ModelPolicy.Bridge bridge,
                                       java.util.function.Consumer<ModelPolicy> take) {
        SimWorld w = EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.buildWorld();
        EvalHarness h = new EvalHarness(w);

        NightShelter shelter = new NightShelter(1).reading(() -> bodyOf(w));

        Policy policy = modelEdge
                ? new ModelPolicy(w, h, bridge, MAX_TURNS, shelter, systemPrompt())
                : new GoalPolicy(w, h);

        boolean obtained = policy.obtain("chest");
        if (modelEdge) {
            take.accept((ModelPolicy) policy);
        }
        int bagsChests = w.count("chest");

        boolean placed = standTheBox(w, h);

        // The night. One tick at a time through the production harness, feeding the production
        // accumulator and the production box instrument on every one.
        int night = NightEnclosure.nightTicks();
        w.atWorldTime(NightEnclosure.duskTick() - 1L);
        DawnChest box = new DawnChest();
        for (int tick = 0; tick <= night; tick++) {
            h.tick();
            shelter.onTick();
            box.observe(w.enclosureGrid(),
                    EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_X,
                    EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_Y,
                    EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_Z,
                    w.worldTime());
        }

        // Dawn. Everything below is a re-read; nothing above this line is evidence.
        boolean chestAtDawn = DawnChest.isChest(w.blockObjectAt(
                EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_X,
                EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_Y,
                EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_Z));
        int standing = EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.chestsStanding(w);
        String detail = policy instanceof ModelPolicy mp
                ? summariseModelTurns(mp)
                : ((GoalPolicy) policy).traceSummary();

        Edge edge = new Edge(label, obtained, bagsChests, placed, shelter.sheltered(),
                shelter.openSamples(), shelter.samples(), w.minimumHealth(), w.alive(),
                chestAtDawn, standing, w.count("chest"), box.missingSamples(), detail);

        List<String> transcript = modelEdge ? ((ModelPolicy) policy).transcript() : List.of();
        ModelBridge.Cost cost = modelEdge ? ((ModelPolicy) policy).cost() : ModelBridge.Cost.zero();
        boolean gap = modelEdge && ((ModelPolicy) policy).sawToolSurfaceGap();
        return new Run(edge, transcript, cost, modelEdge, gap);
    }

    /**
     * An edge plus everything the artifact needs from the decision itself.
     *
     * <p>Separate from {@link Edge} because {@code Edge} is deliberately only the world facts, and
     * a reader of an edge must not be able to mistake the policy's account of itself for one of
     * them. The transcript lives here, where it is unmistakably the model's own words.
     *
     * @param modelEdge whether this run was the model edge, which decides the transcript's owner
     * @param sawToolSurfaceGap whether the model itself said the surface cannot craft
     */
    public record Run(Edge edge, List<String> transcript, ModelBridge.Cost cost,
                      boolean modelEdge, boolean sawToolSurfaceGap) {
    }


    /**
     * The mechanical placement, identical for both edges.
     *
     * <p>Both edges get it, so the only thing varying between them is who decided -- which is the
     * whole point of a control. It uses the production {@code RouteIntent} and
     * {@code InteractIntent.place} through the harness, exactly as T26 does, so what lands in the
     * grid is whatever {@code SimWorld.rightClickBlock} does.
     */
    private static boolean standTheBox(SimWorld w, EvalHarness h) {
        int bx = EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_X;
        int by = EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_Y;
        int bz = EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.BOX_Z;
        boolean emptyBefore = !DawnChest.isChest(w.blockObjectAt(bx, by, bz));
        h.submit(new RouteIntent(bx - 1, by, bz, 0));
        h.runMove(1200);
        if (w.horizontalDistanceTo(bx - 0.5D, bz + 0.5D) > 1.2D) {
            return false;
        }
        h.submit(InteractIntent.place(bx, by - 1, bz, ActActuator.Face.UP.index(),
                0.5D, 0.5D, 0.5D));
        h.runInteract(60);
        return emptyBefore && DawnChest.isChest(w.blockObjectAt(bx, by, bz));
    }

    /** The {@link NightShelter.Body} view of a simulation world. */
    private static NightShelter.Body bodyOf(SimWorld w) {
        return new NightShelter.Body() {
            @Override
            public Object world() {
                return w;
            }

            @Override
            public long worldTime() {
                return w.worldTime();
            }

            @Override
            public Enclosure.CellGrid grid() {
                return w.enclosureGrid();
            }

            @Override
            public int feetX() {
                return w.stance().x();
            }

            @Override
            public int feetY() {
                return w.stance().y();
            }

            @Override
            public int feetZ() {
                return w.stance().z();
            }
        };
    }

    private static String summariseModelTurns(ModelPolicy mp) {
        StringBuilder sb = new StringBuilder();
        for (String t : mp.transcript()) {
            if (t.startsWith("<MODEL>")) {
                sb.append(t.substring(8).strip()).append(" || ");
            }
        }
        String s = sb.toString().strip();
        return s.length() <= 4000 ? s : s.substring(0, 4000) + " [TRUNCATED IN THIS SUMMARY; the"
                + " full transcript is in the artifact]";
    }

    // ===== artifacts =====

    /**
     * Writes the readable round and the machine-readable one, and returns where they landed.
     *
     * <p>Both are written before this returns, and the paths are returned rather than logged, so
     * a caller can print them and a reader can find them without hunting.
     */
    public static List<Path> writeArtifacts(String stamp, ToolSurface surface, Edge model,
                                            Edge control, ModelBridge.Cost cost,
                                            List<String> modelTranscript) throws IOException {
        Path dir = repoRoot().resolve(Path.of(".ai-notes", "docs", "audits"));
        Files.createDirectories(dir);
        Path md = dir.resolve(stamp + "-model-round.md");
        Path json = dir.resolve(stamp + "-model-round.json");

        Files.writeString(md, report(stamp, surface, model, control, cost, modelTranscript),
                StandardCharsets.UTF_8);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("stamp", stamp);
        root.put("model", DEFAULT_MODEL);
        root.put("verdict", verdictOf(surface, model));
        root.put("tool_surface", surface.toJson());
        root.put("edge_model", model.toJson());
        root.put("edge_control_goalpolicy", control.toJson());
        Map<String, Object> costRow = new LinkedHashMap<>();
        costRow.put("calls", cost.calls());
        costRow.put("fresh_input_tokens", cost.inputTokens());
        costRow.put("output_tokens", cost.outputTokens());
        costRow.put("prompt_tokens_incl_cache", cost.promptTokens());
        costRow.put("cache_read_tokens", cost.cacheRead());
        costRow.put("usd", cost.usd());
        costRow.put("wall_millis", cost.millis());
        root.put("cost", costRow);
        root.put("stand_in_observation_boundary", StandInObservation.cannotSee());
        root.put("scaffold_input_tokens_per_call", ModelBridge.SCAFFOLD_INPUT_TOKENS);
        root.put("rerun_command", RERUN_COMMAND);
        Files.writeString(json, Json.write(root), StandardCharsets.UTF_8);
        return List.of(md.toAbsolutePath(), json.toAbsolutePath());
    }

    /**
     * The repository root, found by walking up for {@code .git}.
     *
     * <p>Not {@code Paths.get(".ai-notes")}. Surefire runs with the MODULE directory as its
     * working directory, so a relative path landed the artifact in
     * {@code core/.ai-notes/...} -- a second, silently divergent copy of the control plane, in
     * exactly the repository whose standing rule is that {@code .ai-notes} is not versioned. An
     * artifact nobody finds is an artifact nobody reads, and a control-plane directory in the
     * wrong place is worse than no report.
     */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path up = here; up != null; up = up.getParent()) {
            if (Files.isDirectory(up.resolve(".git"))) {
                return up;
            }
        }
        throw new IllegalStateException("no .git above " + here + ", so the artifact has nowhere"
                + " canonical to land");
    }

    /** The one command that reruns this round, verbatim in the artifact. */
    public static final String RERUN_COMMAND = "export JAVA_HOME='D:\\Software\\Developer\\jdk\\25'; "
            + "sh ./mvnw -pl client,core test -Dtest=ModelRoundTest -Dround.modelRun=true";

    /** The one-line conclusion, and it is allowed to be a falsification. */
    public static String verdictOf(ToolSurface surface, Edge model) {
        if (!surface.modelCanReachAChest()) {
            return "FALSIFIED AT THE TOOL SURFACE, before the model is consulted: the recipe table"
                    + " makes a chest and the substrate can build one, but no registered tool"
                    + " PERFORMS a craft (craft_plan says so in its own description), so the"
                    + " north star's path -- a weak model plus a system prompt plus the tools we"
                    + " already have -- cannot reach a chest at any level of model competence. The"
                    + " model edge returned " + (model.obtained() ? "a chest" : "no chest")
                    + ", which is a fact about this model and not the limiting factor.";
        }
        return model.allThree()
                ? "the model edge met all three conditions on a world read back after dawn"
                : "the model edge did not meet all three; see the per-condition rows";
    }

    private static String report(String stamp, ToolSurface surface, Edge model, Edge control,
                                 ModelBridge.Cost cost, List<String> transcript) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("# ").append(stamp).append(" -- a real model-driven round\n\n");
        sb.append("Verdict: **").append(verdictOf(surface, model)).append("**\n\n");

        sb.append("## 1. What the model saw, decided, and what the world did\n\n");
        sb.append("Verbatim, turn by turn. `<SIGHTING>` is the stand-in observation, `<MODEL>` is")
                .append(" the model's own reply, `<TOOL ...>` is the production handler's reply.\n\n");
        sb.append("```\n");
        for (String t : transcript) {
            sb.append(t).append('\n');
        }
        sb.append("```\n\n");

        sb.append("## 2. The three hard conditions, each from a world read at dawn\n\n");
        sb.append("| condition | model edge | GoalPolicy edge (control) |\n");
        sb.append("|---|---|---|\n");
        sb.append("| 1. sheltered all night (NightEnclosure, one sample per night tick) | ")
                .append(model.conditionShelter() ? "YES" : "NO").append(" (openSamples=")
                .append(model.openSamples()).append(" of ").append(model.nightSamples())
                .append(" samples) | ").append(control.conditionShelter() ? "YES" : "NO")
                .append(" (openSamples=").append(control.openSamples()).append(" of ")
                .append(control.nightSamples()).append(" samples) |\n");
        sb.append("| 2. lowest health all night >= 18 | ")
                .append(model.conditionHealthFloor() ? "YES" : "NO").append(" (lowest ")
                .append(String.format(Locale.ROOT, "%.1f", model.minHealth())).append(") | ")
                .append(control.conditionHealthFloor() ? "YES" : "NO").append(" (lowest ")
                .append(String.format(Locale.ROOT, "%.1f", control.minHealth())).append(") |\n");
        sb.append("| 3. chest still standing at dawn (block read at the box cell) | ")
                .append(model.conditionChestAtDawn() ? "YES" : "NO").append(" (missing on ")
                .append(model.missingSamples()).append(" night ticks) | ")
                .append(control.conditionChestAtDawn() ? "YES" : "NO").append(" (missing on ")
                .append(control.missingSamples()).append(" night ticks) |\n\n");
        sb.append("Neither edge was asked whether it succeeded. `Policy.obtain`'s return value is")
                .append(" recorded in the JSON but is not evidence for any row above.\n\n");

        sb.append("## 3. The two edges, side by side\n\n");
        sb.append("Both start from `T26.buildWorld()`, the fixture twenty-six tasks already run")
                .append(" on. Both get the same goal string, the same mechanical placement step and")
                .append(" the same three instruments. The only difference is who decided.\n\n");
        sb.append("```json\n").append(Json.write(model.toJson())).append("\n\n")
                .append(Json.write(control.toJson())).append("\n```\n\n");

        sb.append("## 4. The tool-surface row, computed before the model spoke\n\n");
        sb.append("```json\n").append(Json.write(surface.toJson())).append("\n```\n\n");
        sb.append("This row is why the verdict is a falsification of the PATH and not of the")
                .append(" model. It reads the recipe table and the registered tool surface only.\n\n");

        sb.append("## 5. The stand-in observation: what the model saw, and what it did not\n\n");
        sb.append("```\n").append(StandInObservation.cannotSee()).append("\n```\n\n");
        sb.append("Plus a contamination that is not about vision: the model was reached through")
                .append(" the `omp` CLI, which prepends roughly ")
                .append(ModelBridge.SCAFFOLD_INPUT_TOKENS)
                .append(" tokens of its own agent scaffolding that this project did not write and")
                .append(" cannot switch off from the command line. So the model did not see")
                .append(" \"a system prompt\"; it saw that plus 14.5k tokens of harness. If")
                .append(" anything this makes the round GENEROUS.\n\n");

        sb.append("## 6. Cost, as measured\n\n");
        sb.append("model calls: ").append(cost.calls())
                .append("; fresh input tokens: ").append(cost.inputTokens())
                .append("; output tokens: ").append(cost.outputTokens())
                .append("; prompt tokens including cache: ").append(cost.promptTokens())
                .append("; of which cacheRead: ").append(cost.cacheRead())
                .append("; usd: ").append(String.format(Locale.ROOT, "%.5f", cost.usd()))
                .append("; wall: ").append(cost.millis() / 1000L).append("s\n\n");
        sb.append("Read the two token columns together. Fresh input looks tiny because the")
                .append(" scaffolding arrives as a cache hit, which is exactly why a cost table")
                .append(" that summed only fresh input would report this round as nearly free AND")
                .append(" would hide the ").append(ModelBridge.SCAFFOLD_INPUT_TOKENS)
                .append(" tokens standing in front of the model.\n\n");

        sb.append("## 7. Reproducing this\n\n");
        sb.append("```\n").append(RERUN_COMMAND).append("\n```\n\n");
        sb.append("The model is sampled, not seeded. `temperature` is not exposed by the")
                .append(" headless entry point used here, so a rerun is a fresh sample and may")
                .append(" differ. What IS fixed is the world, the goal, the tool surface, the")
                .append(" turn budget (").append(MAX_TURNS)
                .append(") and the system prompt, all of which are constants in `ModelRound`.\n\n");

        sb.append("## 8. The design questions, answered where they were decided\n\n");
        sb.append("1. `obtain` runs an INTERNAL LOOP (option a), because option b would make the")
                .append(" harness the planner and measure it instead of the model. The cost is")
                .append(" that a wrong run is long rather than short.\n");
        sb.append("2. The model's arguments go to the PRODUCTION `ActTools` handler as a real")
                .append(" `CallToolRequest`, so `ActIntentParser` does the validating and the")
                .append(" refusing and the refusals land in the transcript verbatim.\n");
        sb.append("3. The world is FROZEN while the model thinks and ticks only after a call lands.")
                .append(" A live client runs at 20Hz throughout, so this understates the night")
                .append(" running out and mob pressure.\n");
        sb.append("4. The WHOLE transcript goes back every turn, untruncated, because a real MCP")
                .append(" session carries everything.\n");
        return sb.toString();
    }

    /** The transcript lines that belong in the artifact, in order. */
    public static List<String> transcriptOf(ModelPolicy p) {
        List<String> out = new ArrayList<>(p.transcript());
        out.add("-- end of transcript --");
        out.add("move slot phase at exit: " + ActSlot.MOVE);
        return out;
    }
}
