package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.marcloud.mcp.core.drivers.action.ActToolsHeadless;

import org.junit.Test;

/**
 * The round's own regression tests, in three red sets that cannot be satisfied by each other.
 *
 * <p><b>Why three sets and not one.</b> The claim this slice makes is largely a NEGATIVE one --
 * "the tool surface cannot reach a chest" -- and a negative claim is trivially provable by a
 * broken world, a guard that refuses everything, or a test that never runs. So each set carries
 * its own control:
 *
 * <ul>
 *   <li><b>Set A, the tool surface</b> (A1, A2, A3). Reads the recipe table and the registry and
 *       consults no model at all, so A2 cannot be a story about the model. A3 is what keeps A2
 *       honest: {@code GoalPolicy} obtains a chest on this exact world, so the substrate can and
 *       only the surface cannot. Delete A3 and A2 stays green on a world where nothing can make a
 *       chest at all.</li>
 *   <li><b>Set B, the machinery</b> (B1, B2, B3, B4). Drives {@code ModelPolicy} with a SCRIPTED
 *       model, so it costs nothing and is deterministic. B1 proves the production path really
 *       moved the player; B2 proves the production refusal reached the transcript; B3 proves a
 *       "done" the world contradicts is not a success.</li>
 *   <li><b>Set C, the live round</b>, gated on {@code -Dround.modelRun=true}. The only test that
 *       spends money.</li>
 * </ul>
 *
 * <p><b>B3 is the non-vacuity guard for the whole slice.</b> A {@code ModelPolicy} that returned
 * true unconditionally, or a guard that refused every call and reported success, would satisfy
 * every other assertion in this file. B3 asserts the opposite, which is why it is asserted
 * directly rather than inferred from a pass somewhere else.
 *
 * <p><b>On {@link #check}.</b> JUnit 4's {@code assertTrue} takes the message first and has no
 * third "context" argument, which is where every failure detail in this file used to go. Rather
 * than concatenate ten long sentences by hand at each site, every conditional assertion routes
 * through one helper that folds the context into the message -- so a failure always shows WHAT
 * was expected AND what the world actually said, and there is no assertion in this file that can
 * fail without saying why.
 */
public class ModelRoundTest {

    /** How many entries a directory holds, so the emptiness above is a fact and not a hope. */
    private static int countEntries(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return (int) s.count();
        }
    }

    /** A scripted model reply, with no provider behind it. */
    private static ModelBridge.Turn scripted(String json) {
        return new ModelBridge.Turn("(scripted)", "(scripted)", json, 0, 0, 0.0D, 1L, true, null);
    }

    /**
     * {@code assertTrue} whose message carries the observed context.
     *
     * @param why what the assertion claims, and what a reader must check to believe it
     * @param cond the claim itself
     * @param seen what the world actually said, appended to the failure message
     */
    private static void check(String why, boolean cond, Object seen) {
        assertTrue(why + "\n  OBSERVED: " + seen, cond);
    }

    /** A bridge that replays a fixed list of replies and records what it was asked. */
    private static final class Script implements ModelPolicy.Bridge {
        private final List<String> replies;
        private final List<String> seen = new ArrayList<>();
        private int at;

        Script(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public ModelBridge.Turn ask(String systemPrompt, String transcript) {
            seen.add(transcript);
            return scripted(replies.get(Math.min(at++, replies.size() - 1)));
        }
    }

    /** The production accumulator, pointed at a simulation world by hand. */
    private static NightShelter shelterOf(SimWorld w) {
        return new NightShelter(1).reading(() -> new NightShelter.Body() {
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
        });
    }

    /** T26's own fixture, so both edges start from the world twenty-six tasks already run on. */
    private static SimWorld freshWorld() {
        return EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn.buildWorld();
    }

    // ===== set A: the tool surface, with no model anywhere in it =====

    /**
     * A1: the substrate can make a chest, so an unreachable one is a surface defect and not a
     * broken fixture.
     */
    @Test
    public void theSubstrateItselfCanMakeAChest() {
        ModelRound.ToolSurface surface = ModelRound.toolSurfaceRow();
        check("vanilla's own recipe table makes a chest from planks, so a failure to obtain one"
                        + " is about the SURFACE and not about a broken fixture",
                surface.substrateCanMakeAChest(), surface);
    }

    /**
     * A2: the surface DOES have a craft performer, found by SCANNING the shipped schema.
     *
     * <p><b>This assertion is inverted from what it was, and the inversion is the point.</b> It
     * used to read "no registered tool performs a craft", backed by a hardcoded {@code null}. That
     * was not a measurement -- it was a constant compared with itself, and it stayed green for as
     * long as nobody edited it, including straight through the arrival of
     * {@code act_set interact kind="craft"}. The scan replaced the constant, and on the very next
     * run this test went RED and named the tool. That is the whole argument for scanning: the
     * failure mode of a stale claim is not a crash, it is a green row that means nothing, and the
     * only defence is for the row to be reading something that can change under it.
     *
     * <p>So the claim is now positive and equally falsifiable. If a future change removes the
     * kind, this fails and names what to do.
     */
    @Test
    public void theShippedSurfaceHasACraftPerformer() {
        ModelRound.ToolSurface surface = ModelRound.toolSurfaceRow();
        assertTrue("the recipe table is still not the problem", surface.substrateCanMakeAChest());
        assertNotNull("act_set declares an interact.kind that performs a craft. If that kind is"
                        + " GONE, the north star's path is blocked again and this slice's verdict"
                        + " flips back -- update it, do not delete it. Tool surface: " + surface,
                surface.craftingTool());
        check("so a model, however good, can reach a chest with the tools we have -- which moves"
                        + " the question from 'can the surface' to 'can the model', where it"
                        + " belongs",
                surface.modelCanReachAChest(), surface);
    }

    /**
     * A3, the isolating control: the shipped reference decision DOES obtain a chest here.
     *
     * <p>So the world, the recipe table and the harness are all capable, and the only thing
     * standing between a model and a chest is the absence of a tool.
     */
    @Test
    public void theShippedPolicyObtainsAChestOnThisWorld() {
        SimWorld w = freshWorld();
        EvalHarness h = new EvalHarness(w);
        GoalPolicy p = new GoalPolicy(w, h);
        check("GoalPolicy must obtain a chest on T26's own world; if it does not, this slice's"
                        + " control edge is broken and no comparison against it means anything",
                p.obtain("chest"), p.traceSummary());
        check("and the world must agree the chest is in the bag", w.count("chest") > 0,
                w.describeInventory());
    }

    // ===== set B: the machinery, with a scripted model and no provider =====

    /**
     * B1: a well-formed {@code act_set} really moves the player in the world.
     *
     * <p>Proves the wiring is not decorative: the arguments travelled into the production
     * {@code ActIntentParser}, through the production {@code ActRuntime}, into the production
     * {@code MoveApplier}, and the body ended up measurably elsewhere. Asserted on the WORLD,
     * never on the tool's own {@code accepted:true} -- which is the reply this project has been
     * bitten by before.
     */
    @Test
    public void aWellFormedActSetMovesTheBody() {
        SimWorld w = freshWorld();
        EvalHarness h = new EvalHarness(w);
        double before = w.horizontalDistanceTo(4.5D, 0.5D);

        ModelPolicy p = new ModelPolicy(w, h, new Script(
                "{\"thought\":\"walk east\",\"tool\":\"act_set\",\"arguments\":{\"move\":"
                        + "{\"go_to\":[4,64,0]}}}",
                "{\"thought\":\"give up\",\"tool\":\"done\",\"arguments\":{}}"), 3,
                shelterOf(w), "(scripted)");
        p.obtain("chest");

        double after = w.horizontalDistanceTo(4.5D, 0.5D);
        check("the body must close distance to the go_to target; if it does not, the production"
                        + " parser / runtime / applier path is not what moved the player and B2's"
                        + " refusal proves nothing either",
                after < before - 1.0D,
                "distance " + before + " -> " + after + "\n"
                        + String.join("\n", p.transcript()));
    }

    /**
     * B2: an argument the tool cannot honour produces the PRODUCTION refusal, in the transcript.
     *
     * <p>Checked for the refusal marker and for the offending key rather than for the whole
     * sentence, so this pins that the SHIPPED refusal arrived instead of a paraphrase while still
     * failing if the harness ever starts summarising it.
     */
    @Test
    public void aBadActionGetsTheProductionRefusalVerbatim() {
        SimWorld w = freshWorld();
        EvalHarness h = new EvalHarness(w);
        // A block target on kind 'use' is the refusal ActTools documents at length.
        ModelPolicy p = new ModelPolicy(w, h, new Script(
                "{\"tool\":\"act_set\",\"arguments\":{\"interact\":{\"kind\":\"use\","
                        + "\"block\":[1,64,1]}}}",
                "{\"tool\":\"done\",\"arguments\":{}}"), 2, shelterOf(w), "(scripted)");
        p.obtain("chest");

        String transcript = String.join("\n", p.transcript());
        String lower = transcript.toLowerCase(Locale.ROOT);
        check("the PRODUCTION refusal must reach the transcript, marked as an error and naming"
                        + " the key it could not honour; a refusal that never reached the model"
                        + " would leave it free to repeat the same call forever",
                lower.contains("error:") && lower.contains("block"), transcript);
    }

    /**
     * B3, THE NON-VACUITY GUARD: a model that claims completion without holding anything is
     * contradicted by a world read, and the round does not end as a success.
     */
    @Test
    public void aDoneTheWorldContradictsIsNotASuccess() {
        SimWorld w = freshWorld();
        EvalHarness h = new EvalHarness(w);
        ModelPolicy p = new ModelPolicy(w, h, new Script(
                "{\"thought\":\"I am finished\",\"tool\":\"done\",\"arguments\":{}}"), 2,
                shelterOf(w), "(scripted)");

        boolean got = p.obtain("chest");

        assertFalse("the bag is empty, so obtain must return false however the model spoke", got);
        assertEquals("nothing was obtained", 0, w.count("chest"));
        String transcript = String.join("\n", p.transcript());
        check("the transcript must record the WORLD READ that contradicted the claim, or a reader"
                        + " cannot tell a model that was corrected from one that was believed",
                transcript.contains("<WORLD-READ>"), transcript);
    }

    /**
     * B4: the substitute view declares itself, in the text the model is actually shown.
     *
     * <p>Pinned on the rendered string rather than on a constant, because a boundary that lives
     * only in a field nobody passes to the model is not a boundary the model was given.
     */
    @Test
    public void theStandInViewNamesItselfAsAStandIn() {
        SimWorld w = freshWorld();
        String view = StandInObservation.render(w, shelterOf(w), null, null, null);

        check("the model must be told, in the text it is shown, that this is not world_view",
                view.contains("STAND-IN OBSERVATION, NOT world_view"), view.length() + " chars");
        assertFalse("and it must never quietly claim to be one",
                view.toLowerCase(Locale.ROOT).contains("this is world_view"));
        check("the honesty section must enumerate what a real world_view would have added",
                StandInObservation.cannotSee().contains("WHAT A REAL world_view WOULD HAVE ADDED"),
                StandInObservation.cannotSee().length() + " chars");
    }

    // ===== set D: the prompt must not contain a claim that has stopped being true =====

    /**
     * D1: every {@code interact.kind} the shipped schema declares is visible to the model.
     *
     * <p>Written after the round fed the model a sentence claiming no tool could craft, at a
     * moment when {@code act_set interact kind="craft"} had already shipped. The model believed
     * it for fourteen turns and the transcript recorded a MODEL failure, when the truth was that
     * this harness lied to it. A prompt assembled from the production schema cannot rot that way;
     * this pins the assembly rather than trusting it.
     */
    @Test
    public void everyKindTheShippedSchemaDeclaresIsVisibleToTheModel() {
        String prompt = ModelRound.systemPrompt();
        Object props = ActToolsHeadless.actSet(new net.marcloud.mcp.core.drivers.act.ActRuntime())
                .tool().inputSchema().get("properties");
        Map<?, ?> interact = (Map<?, ?>) ((Map<?, ?>) props).get("interact");
        Map<?, ?> iprops = (Map<?, ?>) interact.get("properties");
        Object enumNode = ((Map<?, ?>) iprops.get("kind")).get("enum");

        check("the system prompt must carry the whole schema, so a kind that is added to the"
                        + " surface cannot be invisible to the model",
                enumNode instanceof List<?> && !((List<?>) enumNode).isEmpty(),
                "no enum on interact.kind");
        for (Object v : (List<?>) enumNode) {
            String kind = String.valueOf(v);
            check("the model must be able to see the kind '" + kind + "', because the shipped"
                            + " schema offers it and a prompt that hides it measures the prompt",
                    prompt.contains("\"" + kind + "\""),
                    kind + " is declared by act_set but absent from the system prompt");
        }
    }

    /**
     * D2: no reply this harness composes may claim a capability the surface has or lacks.
     *
     * <p>The narrow, mechanical form of the same lesson: the phrase "no tool that performs a
     * craft" was hardcoded into a reply and labelled VERBATIM, so when the tree gained that tool
     * the label stayed true-looking and the content became false. A harness that states facts
     * about the surface is a second, drifting copy of the surface.
     */
    @Test
    public void theHarnessStatesNoFactsAboutTheSurfaceItDoesNotRead() {
        SimWorld w = freshWorld();
        EvalHarness h = new EvalHarness(w);
        net.minecraft.init.Bootstrap.register();
        ModelPolicy p = new ModelPolicy(w, h, new Script(
                "{\"tool\":\"craft_plan\",\"arguments\":{\"item\":\"chest\"}}",
                "{\"tool\":\"done\",\"arguments\":{}}"), 2, shelterOf(w),
                ModelRound.systemPrompt());
        p.obtain("chest");

        String transcript = String.join("\n", p.transcript()).toLowerCase(Locale.ROOT);
        assertFalse("this harness must not tell the model a capability is impossible. It is not"
                        + " reading the tool surface to find out, so any such sentence is a copy"
                        + " that can be wrong, and this one WAS wrong for a whole round.\n"
                        + transcript,
                transcript.contains("no tool that performs a craft")
                        || transcript.contains("crafting is impossible"));
        assertFalse("and it must not quote a claim as verbatim from a description it never read",
                transcript.contains("verbatim from the shipped"));
    }

    /**
     * D3: the honesty list may not assert that a CAPABILITY is absent.
     *
     * <p>Written because this list is rendered to the model on EVERY turn, so a false entry in it
     * is not one stale sentence in a document -- it is a false sentence the model reads fourteen
     * times. And it is worse than a document, because the list's OTHER entries are still true. A
     * list that is mostly honest teaches the model to trust the false one too; that is the whole
     * hazard of mixing facts and claims in one trusted artefact.
     *
     * <p>Gaps in what the model can SEE are fine here -- "you cannot see five blocks" is exactly
     * what this list is for. Claims about what the TOOL SURFACE can do are not, because this class
     * does not read the tool surface. Anything of that kind is a copy that can go stale, and one
     * did.
     */
    @Test
    public void theHonestyListMakesNoClaimsAboutTheToolSurface() {
        String gaps = String.join("\n", StandInObservation.CANNOT_SEE).toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("no tool that performs a craft", "crafting is impossible",
                "cannot be crafted", "there is no tool", "not a model can", "impossible")) {
            assertFalse("the list rendered to the model on every turn must not claim '" + forbidden
                            + "'. This class does not read the tool surface, so any statement"
                            + " about it is a copy that can be wrong, and one of them was.\n"
                            + gaps,
                    gaps.contains(forbidden));
        }
        assertFalse("and the same holds for the rendered view itself",
                StandInObservation.cannotSee().toLowerCase(Locale.ROOT)
                        .contains("no tool that performs a craft"));
    }

    // ===== set C: the live round, the only that spends money =====

    /**
     * C: one real model-driven round, graded by world re-read, written to disk.
     *
     * <p>Off unless {@code -Dround.modelRun=true}, so an ordinary {@code mvn test} never spends
     * money or needs a network. See {@link ModelRound#RERUN_COMMAND} for the whole invocation.
     */
    @Test
    public void aRealModelDrivesOneRoundAndTheArtifactLandsOnDisk() throws Exception {
        if (!Boolean.getBoolean("round.modelRun")) {
            return;
        }
        String model = System.getProperty("round.model", ModelRound.DEFAULT_MODEL);

        // A FRESH EMPTY directory, and not java.io.tmpdir. The first run pointed at the system
        // temp directory and every call timed out at 90s. omp keeps a per-directory memory cache,
        // and the Windows temp directory is where IT keeps one -- several files of prose this
        // project never wrote, injected into the prompt ahead of our own. An empty directory is
        // not a stylistic choice here; it is the difference between measuring the model and
        // measuring omp's memory of some other directory.
        Path tmp = Files.createTempDirectory("modelround-empty-cwd");
        assertEquals("the round's working directory must start EMPTY, or omp injects whatever it"
                        + " has cached for it: " + tmp,
                0, countEntries(tmp));

        ModelRound.ToolSurface surface = ModelRound.toolSurfaceRow();

        // The control runs first and costs nothing: if the substrate cannot build a chest there
        // is no point paying for a model to try.
        ModelRound.Run control = ModelRound.run("GoalPolicy (control)", false,
                (sys, transcript) -> scripted("{}"));
        check("the control edge must obtain a chest, or the model edge is uninterpretable",
                control.edge().obtained(), control.edge().detail());

        ModelRound.Run modelRun = ModelRound.run("model: " + model, true,
                (sys, transcript) -> ModelBridge.call(model, sys, transcript, tmp));

        String stamp = System.getProperty("round.stamp",
                "2026-10-03-model-round-" + model.replaceAll("[^A-Za-z0-9]+", "-"));
        List<Path> written = ModelRound.writeArtifacts(stamp, surface, modelRun.edge(),
                control.edge(), modelRun.cost(), modelRun.transcript());

        assertFalse("the round wrote nothing", written.isEmpty());
        for (Path p : written) {
            assertTrue("artifact on disk: " + p, Files.exists(p));
            String why = "artifact must not be empty: " + p + " -- a round that wrote an empty"
                    + " report has reported nothing";
            check(why, Files.exists(p) && Files.size(p) > 2000L,
                    Files.exists(p) ? Files.size(p) + " bytes" : "absent");
        }
        assertFalse("the model edge must have produced turns to report",
                modelRun.transcript().isEmpty());
        assertNotNull("the verdict is a sentence, never null",
                ModelRound.verdictOf(surface, modelRun.edge()));
    }
}
