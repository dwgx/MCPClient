package net.marcloud.mcp.core.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The live round as a plain {@code main}, so it can be run OUTSIDE the surefire fork.
 *
 * <p><b>Why this exists.</b> The first two attempts to run the round hung: every model call
 * hit the transport deadline. Reproduced by hand from a shell, the identical command answers in
 * twenty seconds, so the command line was never the problem -- something about the environment a
 * forked test JVM runs in is. Guessing at that from inside the fork is how a diagnostic turns
 * into a rumour, so this runner takes the round out of the fork entirely and lets the difference
 * be observed rather than theorised.
 *
 * <p><b>It runs exactly the same code as the test.</b> {@link ModelRound#run} and
 * {@link ModelRound#writeArtifacts} are called here and from
 * {@code ModelRoundTest.aRealModelDrivesOneRoundAndTheArtifactLandsOnDisk}, in the same order,
 * with the same world, goal, turn budget and system prompt. There is no second implementation to
 * drift: if this runner and the test ever disagree, the disagreement is a fact worth reading.
 *
 * <p><b>How to run it.</b> The classpath is the one maven already wrote:
 * <pre>
 *   sh ./mvnw -pl client,core dependency:build-classpath \
 *       "-Dmdep.outputFile=D:\Project\MCPClient\core\target\cp.txt" -DincludeScope=test
 *   "$JAVA_HOME/bin/java" -cp "core/target/test-classes;core/target/classes;$(cat core/target/cp.txt)" \
 *       net.marcloud.mcp.core.eval.ModelRoundRunner
 * </pre>
 * {@code JAVA_HOME} must be {@code D:\Software\Developer\jdk\25}. The model is read from the
 * {@code round.model} property and defaults to {@link ModelRound#DEFAULT_MODEL}.
 */
public final class ModelRoundRunner {

    private ModelRoundRunner() {
    }

    /**
     * ONE call, thirty seconds, every field printed.
     *
     * <p>Written after two consecutive full rounds burned fifty minutes between them and measured
     * nothing. Both of those runs had a round-trip deadline that kept firing, which is a fact about
     * the transport and not about the north star, and the only reason it took fifty minutes to
     * learn that is that nobody asked a single question first. A round costs fourteen calls; the
     * question "is the transport alive" costs one, and it is the same call.
     *
     * <p>Deliberately outside {@link #main}'s flow, so it can be run on its own and it cannot be
     * mistaken for a measurement of anything.
     */
    private static void probe(String model) throws Exception {
        Path cwd = Files.createTempDirectory("modelround-probe-cwd");
        System.out.println("PROBE cwd=" + cwd);
        StringBuilder transcript = new StringBuilder();
        transcript.append(ModelRound.INSTRUCTIONS).append("\n\n");
        transcript.append("<SIGHTING>\n")
                .append(StandInObservation.cannotSee())
                .append("\n=== TURN 1 ===\nReply with one JSON object naming act_status.\n");

        long t0 = System.nanoTime();
        ModelBridge.Turn t = ModelBridge.call(model, ModelRound.INSTRUCTIONS,
                transcript.toString(), cwd);
        System.out.println("  wallMs          = " + (System.nanoTime() - t0) / 1_000_000L);
        System.out.println("  transportFailed = " + t.transportFailed());
        System.out.println("  failure         = " + t.failure());
        System.out.println("  parsed          = " + t.parsed());
        System.out.println("  millis          = " + t.millis());
        System.out.println("  inputTokens     = " + t.inputTokens());
        System.out.println("  outputTokens    = " + t.outputTokens());
        System.out.println("  totalTokens     = " + t.totalTokens());
        System.out.println("  cacheRead       = " + t.cacheRead());
        System.out.println("  costUsd         = " + t.costUsd());
        System.out.println("  systemPromptChars = " + ModelRound.INSTRUCTIONS.length());
        System.out.println("  fullPromptChars   = " + ModelRound.systemPrompt().length());
        System.out.println("  reply            = " + (t.reply() == null ? "(null)"
                : t.reply().substring(0, Math.min(400, t.reply().length()))));
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "probe".equals(args[0])) {
            probe(System.getProperty("round.model", ModelRound.DEFAULT_MODEL));
            return;
        }
        String model = System.getProperty("round.model", ModelRound.DEFAULT_MODEL);
        String stamp = System.getProperty("round.stamp", "2026-10-03-model-round");
        Path cwd = Files.createTempDirectory("modelround-empty-cwd");

        System.out.println("model  : " + model);
        System.out.println("cwd    : " + cwd + " (empty by construction)");
        System.out.println("stamp  : " + stamp);

        // Vanilla's own registry bootstrap, and it is REQUIRED here rather than incidental. The
        // recipe table is built by CraftingManager's static initialiser, which reads Blocks; run
        // without it, the first call dies with "Accessed Blocks before Bootstrap!". Inside the
        // surefire fork something else already registered the blocks, which is why the JUnit test
        // passed while this runner threw -- an ordering dependency, and the sort that makes a test
        // green for a reason nobody wrote down.
        net.minecraft.init.Bootstrap.register();

        ModelRound.ToolSurface surface = ModelRound.toolSurfaceRow();
        System.out.println("surface: substrate can craft=" + surface.substrateCanMakeAChest()
                + ", a registered tool that performs a craft=" + surface.craftingTool()
                + ", model can reach a chest=" + surface.modelCanReachAChest());

        System.out.println("control edge (GoalPolicy) running...");
        ModelRound.Run control = ModelRound.run("GoalPolicy (control)", false,
                (sys, tr) -> new ModelBridge.Turn("(n/a)", "(n/a)", "{}", 0, 0, 0.0D, 0L, true,
                        null, 0L, 0L));
        System.out.println("  obtained=" + control.edge().obtained()
                + " placed=" + control.edge().placed()
                + " chestAtDawn=" + control.edge().conditionChestAtDawn());

        System.out.println("model edge running, up to " + ModelRound.MAX_TURNS + " calls...");
        ModelRound.Run run = ModelRound.run("model: " + model, true,
                (sys, tr) -> ModelBridge.call(model, sys, tr, cwd));

        for (int i = 0; i < run.transcript().size(); i++) {
            String line = run.transcript().get(i);
            if (line.startsWith("<MODEL>")) {
                String said = line.substring(8).strip().replaceAll("\\s+", " ");
                System.out.println("  turn " + (i + 1) + " model said: "
                        + (said.length() > 220 ? said.substring(0, 220) + "..." : said));
            } else if (line.startsWith("TRANSPORT FAILURE")) {
                System.out.println("  TRANSPORT FAILURE: "
                        + line.substring(0, Math.min(300, line.length())));
            }
        }

        System.out.println("cost: calls=" + run.cost().calls()
                + " freshInput=" + run.cost().inputTokens()
                + " promptInclCache=" + run.cost().promptTokens()
                + " cacheRead=" + run.cost().cacheRead()
                + " usd=" + run.cost().usd()
                + " wallMs=" + run.cost().millis());

        List<Path> written = ModelRound.writeArtifacts(stamp, surface, run.edge(),
                control.edge(), run.cost(), run.transcript());
        System.out.println("VERDICT: " + ModelRound.verdictOf(surface, run.edge()));
        for (Path p : written) {
            System.out.println("  wrote " + p + " (" + Files.size(p) + " bytes)");
        }
    }
}
