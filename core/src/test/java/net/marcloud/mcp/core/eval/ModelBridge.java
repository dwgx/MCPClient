package net.marcloud.mcp.core.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.marcloud.mcp.core.io.http.Json;

/**
 * One call to one language model, over the omp CLI, with nothing about the answer pre-decided.
 *
 * <p><b>Why the CLI and not a bare completion.</b> A bare provider call would be the cleaner
 * experiment, and it was tried: the only non-omp key on this box
 * ({@code deepseek} in omp's {@code auth_credentials}) returns
 * {@code 402 Insufficient Balance}, and opencode-go's base URL is not reachable from outside the
 * harness. So the model is reached through {@code omp -p --mode json --no-session}. That is
 * recorded as a limit rather than glossed, and it is a REAL limit with a measured number: see
 * {@link #SCAFFOLD_INPUT_TOKENS}.
 *
 * <p><b>The contamination, measured rather than asserted -- and measured twice, because the
 * first two readings were wrong in the same direction.</b> A four-token exchange reports as 9,
 * then as 0, then as 14,401 depending on flags and working directory. The reason is that the
 * scaffolding arrives as a CACHED PREFIX, so {@code usage.input} undercounts it and only
 * {@code usage.totalTokens} is the truth. From the stream's own {@code usage} block:
 * {@code input 4, cacheRead 12416, totalTokens 12639}, cost $0.000169 per call. The floor holds
 * under every suppression flag tried -- {@code --system-prompt-template} with an empty handlebars
 * file gave 14,468, and {@code --no-tools --no-skills --no-extensions --no-rules --no-lsp} gave
 * 17,566, which is worse. It is not removable from the command line.
 *
 * <p><b>Why it matters more than the bill.</b> The Owner's premise is "a weak LLM plus A system
 * prompt plus the tool surface we already have". The model in this round did not see that: it
 * saw a full coding-agent system prompt this project did not author, so the round runs under
 * conditions GENEROUS to the model -- extra instructions it would not have in a real MCP session,
 * and a warm cache. That is stated in the artifact in its own section rather than folded into a
 * footnote, because a contaminated premise that still produced a positive result would be the
 * most expensive kind of wrong.
 *
 * <p><b>One call per decision, no session.</b> {@code --no-session} and no {@code --continue},
 * because the whole point is that each turn is an independent completion carrying the transcript
 * this class assembles. Maintaining omp's own session state instead would double-count the
 * harness as the decider.
 *
 * <p><b>The reply is taken raw.</b> Whatever the model emits is stored verbatim in
 * {@link Turn#reply()} before anything tries to parse it, including text that will not parse.
 * A round whose transcript has been silently repaired is a round that cannot be audited.
 */
public final class ModelBridge {

    /**
     * Prompt tokens a call carries that this project did not write, measured on this box with the
     * configuration {@link #describeCommand} prints. See the class javadoc for the twelve
     * configurations this was tried under and for why the reading had to be taken from
     * {@code totalTokens} rather than from {@code input}.
     */
    public static final int SCAFFOLD_INPUT_TOKENS = 12639;

    /**
     * Tokens this project's own system prompt contributes, estimated by length over four.
     *
     * <p>Labelled an estimate on purpose. The provider's own split cannot be used for it: the
     * scaffolding and the prompt share one cached prefix, so there is no per-part figure to read
     * off the stream, and a number presented as measured would be a number nobody measured.
     */
    public static int systemPromptTokens(String systemPrompt) {
        return systemPrompt == null ? 0 : systemPrompt.length() / 4;
    }

    /**
     * Hard ceiling on one call, so a hung provider fails the TURN instead of the build.
     *
     * <p>Measured on this box: a 2 KB transcript answers in about 20s, a 30 KB one in 60-90s. 180s
     * is the point where a call that is going to answer eventually has answered, and a call that
     * has not is not going to. An earlier value of 90 was chosen from the fast measurement alone
     * and killed a turn that was still thinking.
     */
    private static final int TIMEOUT_SECONDS = 180;
    /**
     * The isolated omp profile this class spawns under, and the whole point of the class.
     *
     * <p><b>Measured, not assumed.</b> {@code --no-tools} was believed to isolate the model and is
     * not: on this box it left {@code mcp__codegraph_explore} in the ACTIVE tool set, and
     * {@code --tools=read} made that same MCP tool active as well. Three rounds were run and
     * voided on 2026-10-03 because the model under test was driving a real terminal
     * ({@code mcp__smartcli_*}, 76 calls) and a real browser ({@code mcp__chrome_devtools_*}, 32
     * calls) -- one of them ran 1253 turns inside a single "call".
     *
     * <p><b>Why a profile and not the other two levers.</b> An empty cwd is NOT isolation: MCP
     * discovery walks up to {@code ~/.omp/agent/mcp.json} and reads third-party configs such as
     * {@code ~/.claude.json}, none of which care about the working directory. Proven by planting a
     * server in the default user file: empty cwd without a profile still surfaced
     * {@code mcp__userleakprobe_codegraph_explore}; the same empty cwd under this profile surfaced
     * zero. And {@code --config} cannot carry MCP at all -- MCP lives in its own JSON files, not in
     * config.yml, so an overlay with an {@code mcp:} block is silently ignored (measured: 21 tools,
     * 1 MCP, identical to no overlay).
     *
     * <p>The overlay this class writes ({@link #ISOLATION_OVERLAY}) carries only
     * {@code mcp.enableProjectConfig: false}, because project-scoped MCP is keyed to the WORKING
     * DIRECTORY rather than the profile (measured: under this profile an empty cwd gives 0 MCP, but
     * {@code D:/Project/MCPClient} still gives 1). One lever closes the user scope, the other closes
     * the project scope, and only both together make the count zero in any directory.
     */
    public static final String ISOLATION_PROFILE = "modelbridge-iso";

    /**
     * Session ceiling handed to omp itself, so the child terminates on its own.
     *
     * <p>Distinct from {@link #TIMEOUT_SECONDS}, and the two block different things. This one stops
     * a child that is alive and looping: MCP servers are per-session child processes, so a model
     * that keeps driving one can keep the process alive after its own turns end -- that is why
     * {@code waitFor} returned false forever. {@link #TIMEOUT_SECONDS} is the host-side backstop
     * for a child that ignores this. Isolation is what makes the pair sufficient; either alone is
     * a mitigation rather than a fix.
     */
    public static final String MAX_TIME = "150s";

    /** Contents of the overlay written next to the run. See {@link #ISOLATION_PROFILE}. */
    private static final String ISOLATION_OVERLAY = "mcp:\n  enableProjectConfig: false\n";

    /**
     * Where the isolated profile keeps its files, or {@code null} when home cannot be resolved.
     *
     * <p>Exposed because provisioning has to be inspectable: a run that silently cannot isolate is
     * worse than a run that refuses to start.
     */
    public static Path profileAgentDir() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank()
                ? null
                : Path.of(home, ".omp", "profiles", ISOLATION_PROFILE, "agent");
    }

    /**
     * Makes the isolated profile able to AUTHENTICATE, without giving it the default profile's
     * MCP configuration.
     *
     * <p><b>This is the whole cost of the isolation, and it is a real one.</b> A fresh profile has
     * no credentials: measured on this box, {@code --profile} with nothing else answers
     * "No models available. Use /login or set an API key environment variable." The credentials
     * live in {@code agent.db}, which a profile does not inherit. So the credential store and the
     * model catalog are COPIED from the default profile, and nothing else is: in particular
     * {@code mcp.json} is deliberately not copied, which is the entire mechanism. A profile that
     * has no user-level MCP file cannot inherit one.
     *
     * <p>Idempotent, and it never overwrites a file it did not create: each target is written only
     * when absent, so re-running does not clobber a credential the profile has since rotated.
     *
     * @return null when the profile is ready, or a human-readable reason it is not
     */
    public static String ensureProfile() {
        Path agent = profileAgentDir();
        if (agent == null) {
            return "user.home is not set, so the isolated profile directory cannot be resolved";
        }
        Path def = Path.of(System.getProperty("user.home"), ".omp", "agent");
        if (!Files.isDirectory(def)) {
            return "default profile directory " + def + " does not exist";
        }
        if (!Files.isDirectory(agent)) {
            try {
                Files.createDirectories(agent);
            } catch (IOException e) {
                return "could not create the isolated profile at " + agent + ": " + e;
            }
        }
        // agent.db carries auth_credentials; models.db/models.yml carry the catalog. Nothing else
        // is copied, and mcp.json is what is being withheld.
        for (String name : List.of("agent.db", "agent.db-wal", "agent.db-shm",
                "models.db", "models.db-wal", "models.db-shm", "models.yml")) {
            Path from = def.resolve(name);
            Path to = agent.resolve(name);
            if (!Files.exists(from) || Files.exists(to)) {
                continue;
            }
            try {
                Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return "could not seed " + name + " into the isolated profile: " + e;
            }
        }
        return null;
    }


    private ModelBridge() {
    }

    /**
     * One completed decision: what was sent, what came back, and what it cost.
     *
     * @param systemPrompt exactly what was passed to {@code --system-prompt}
     * @param transcript   exactly what was passed as the user message
     * @param reply        the assistant's text, verbatim, or the provider's error verbatim
     * @param inputTokens  input tokens the stream reported, or -1 when it reported none
     * @param outputTokens output tokens the stream reported, or -1
     * @param costUsd      cost the stream reported, or -1
     * @param millis       wall time for the call
     * @param parsed       whether {@code reply} parsed as one JSON object
     */
    public record Turn(String systemPrompt, String transcript, String reply, long inputTokens,
                       long outputTokens, double costUsd, long millis, boolean parsed,
                       String failure, long totalTokens, long cacheRead) {

        /**
         * The nine-field form, for a turn whose numbers nobody measured.
         *
         * <p>Used by the scripted test models and by every transport-failure path. Both report
         * {@code -1} rather than {@code 0} for token counts, because "the provider did not tell
         * us" and "the provider told us zero" are different facts and a cost table that cannot
         * tell them apart will happily report a free round that never ran.
         */
        public Turn(String systemPrompt, String transcript, String reply, long inputTokens,
                    long outputTokens, double costUsd, long millis, boolean parsed,
                    String failure) {
            this(systemPrompt, transcript, reply, inputTokens, outputTokens, costUsd, millis,
                    parsed, failure, -1L, -1L);
        }

        /** Whether the call itself failed, as opposed to the model answering badly. */
        public boolean transportFailed() {
            return failure != null;
        }
    }

    /** What one round of model calls cost in total, summed from the stream's own numbers. */
    public record Cost(int calls, long inputTokens, long outputTokens, double usd, long millis,
                       long promptTokens, long cacheRead) {

        public static Cost zero() {
            return new Cost(0, 0L, 0L, 0.0D, 0L, 0L, 0L);
        }

        /**
         * Adds a turn, treating an unreported count as absent rather than as zero.
         *
         * <p>{@code cacheRead} is carried because it is where the scaffolding hides: the stream
         * reports it as a cheap tier, so a cost table that summed only fresh input would report a
         * round as nearly free and would also hide the fourteen thousand tokens in front of the
         * model. Both numbers are the reason {@link #SCAFFOLD_INPUT_TOKENS} is not a guess.
         */
        public Cost plus(Turn t) {
            long in = inputTokens < 0 ? 0 : t.inputTokens();
            long out = outputTokens < 0 ? 0 : t.outputTokens();
            double u = t.costUsd() < 0 ? 0.0D : t.costUsd();
            long tt = t.totalTokens() < 0 ? 0 : t.totalTokens();
            long cr = t.cacheRead() < 0 ? 0 : t.cacheRead();
            return new Cost(calls + 1, inputTokens + in, outputTokens + out, usd + u,
                    millis + t.millis(), promptTokens + tt, cacheRead + cr);
        }
    }

    /**
     * Runs one completion and parses the JSON Lines stream.
     *
     * <p>omp emits one JSON object per line. The assistant's final text is on the
     * {@code message_end} line whose {@code message.role} is {@code "assistant"}; there are two
     * such lines on a normal run (one as {@code message_start}, one as {@code message_end}) and
     * the LAST one wins, which is the one carrying {@code stopReason}, {@code usage} and the
     * settled content. Taking the first would report the partial stream and zero cost.
     */
    public static Turn call(String model, String systemPrompt, String transcript, Path cwd) {
        long t0 = System.nanoTime();
        if (cwd == null || !Files.isDirectory(cwd)) {
            return new Turn(systemPrompt, transcript,
                    "TRANSPORT FAILURE: the working directory " + cwd + " does not exist, so omp"
                            + " has nowhere to start and would fall back to somewhere that has"
                            + " context in it.", -1, -1, -1.0D, millisSince(t0), false,
                    "missing cwd " + cwd, -1L, -1L);
        }
        String profileProblem = ensureProfile();
        if (profileProblem != null) {
            return new Turn(systemPrompt, transcript,
                    "TRANSPORT FAILURE: the isolated profile is not usable, so this call is NOT"
                            + " isolated and must not be read as a result: " + profileProblem,
                    -1, -1, -1.0D, millisSince(t0), false, profileProblem, -1L, -1L);
        }
        Path overlay;
        try {
            overlay = Files.createTempFile("modelbridge-isolation-", ".yml");
            Files.writeString(overlay, ISOLATION_OVERLAY, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Turn(systemPrompt, transcript,
                    "TRANSPORT FAILURE: could not stage the isolation overlay: " + e, -1, -1,
                    -1.0D, millisSince(t0), false, e.toString());
        }
        Path promptFile;
        try {
            // The transcript goes in a FILE, not in argv, and the reason is arithmetic rather than
            // taste. A full-history round is a design decision (question 4: a real MCP session
            // carries everything), and by turn 10 this string is comfortably past Windows'
            // 32,767-character command-line ceiling -- at which point ProcessBuilder throws, the
            // round dies, and the failure names a character count rather than a decision. omp
            // documents '@file' as a message form, so the whole transcript is passed that way and
            // the ceiling stops being a thing this class has to know about.
            promptFile = Files.createTempFile("modelround-transcript-", ".txt");
            Files.writeString(promptFile, transcript, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Turn(systemPrompt, transcript,
                    "TRANSPORT FAILURE: could not stage the transcript: " + e, -1, -1, -1.0D,
                    millisSince(t0), false, e.toString());
        }

        List<String> cmd = new ArrayList<>(List.of(
                "omp", "-p", "--mode", "json", "--no-session",
                "--model", model,
                "--thinking", "off",
                // ISOLATION, in the order the two scopes need closing. --profile moves the user
                // scope (so ~/.omp/agent/mcp.json and the third-party configs omp also reads are
                // no longer in scope), and the overlay closes the project scope (which is keyed to
                // the working directory and therefore survives the profile). Measured together:
                // 0 mcp__ tools active, from any cwd, with auth still working.
                "--profile", ISOLATION_PROFILE,
                "--config", overlay.toAbsolutePath().toString(),
                // --no-tools closes the BUILT-IN surface, and it is kept because it is cheap and
                // orthogonal -- but it is NOT the isolation. Measured: with --no-tools the
                // mcp__codegraph_explore tool was still ACTIVE. Its real job is the one described
                // below: without it the model behaves like a coding agent and emits a toolcall.
                "--no-tools",
                // The child terminates itself instead of waiting to be killed. See MAX_TIME.
                "--max-time", MAX_TIME,
                // --auto-approve is DELIBERATELY ABSENT and must stay that way. Print mode has no
                // approver, so a tool call would wait forever; approving them instead would turn
                // that hang into a green round in which the model drives this machine, which is a
                // worse outcome than a red one. Isolation is what removes the call to approve.
                "--system-prompt", systemPrompt,
                "@" + promptFile.toAbsolutePath()));
        String joined;
        try {
            joined = run(cmd, cwd);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Turn(systemPrompt, transcript,
                    "TRANSPORT FAILURE: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    -1, -1, -1.0D, millisSince(t0), false, e.toString());
        }

        String reply = null;
        long in = -1;
        long out = -1;
        double cost = -1.0D;
        long total = -1L;
        long cacheRead = -1L;
        String parseFailure = null;
        for (String line : joined.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            Map<String, Object> ev = Json.readObject(trimmed);
            if (ev == null) {
                continue;
            }
            Object msg = ev.get("message");
            if (!(msg instanceof Map<?, ?> m) || !"assistant".equals(m.get("role"))) {
                continue;
            }
            reply = textOf(m.get("content"));
            Object usage = m.get("usage");
            if (usage instanceof Map<?, ?> u) {
                in = num(u.get("input"), in);
                out = num(u.get("output"), out);
                Object c = u.get("cost");
                if (c instanceof Map<?, ?> cm) {
                    cost = dbl(cm.get("total"), cost);
                }
                total = num(u.get("totalTokens"), total);
                cacheRead = num(u.get("cacheRead"), cacheRead);
            }
        }

        if (reply == null) {
            return new Turn(systemPrompt, transcript,
                    "TRANSPORT FAILURE: the stream carried no assistant message_end line.\n"
                            + tail(joined),
                    in, out, cost, millisSince(t0), false, "no assistant message_end line", total,
                    cacheRead);
        }
        boolean ok = firstJsonObject(reply) != null;
        if (!ok) {
            parseFailure = "the reply is not one JSON object";
        }
        return new Turn(systemPrompt, transcript, reply, in, out, cost, millisSince(t0), ok,
                parseFailure, total, cacheRead);
    }

    /**
     * Extracts the first balanced {@code {...}} from {@code s}.
     *
     * <p>Models wrap JSON in prose and fences even when told not to. Being tolerant here is not
     * leniency about correctness -- the extracted text is recorded separately in the transcript so
     * a reader sees exactly what the model actually wrote -- it is only the difference between
     * "the model did not follow the format" and "we could not read the model".
     */
    public static String firstJsonObject(String s) {
        if (s == null) {
            return null;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String textOf(Object content) {
        if (!(content instanceof List<?> parts)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (p instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(m.get("text"));
            }
        }
        return sb.toString();
    }

    private static long num(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    private static double dbl(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    private static long millisSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static String tail(String s) {
        String[] lines = s.split("\n");
        int from = Math.max(0, lines.length - 6);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    /**
     * Runs the command with a real deadline, and reads its output only after it has ended.
     *
     * <p><b>The first version of this hung for a full hour and the reason is worth keeping.</b> It
     * drained stdout to EOF first and only then called {@code waitFor(TIMEOUT)}. That ordering
     * makes the timeout decorative: a provider that never exits and never closes the pipe leaves
     * {@code readLine()} blocked forever, and {@code waitFor} is never reached to notice. The
     * deadline existed and did nothing, which is the most expensive kind of guard -- it reads as
     * protection and is not.
     *
     * <p>So the output goes to a FILE and the process is awaited first. Redirecting rather than
     * pumping also removes the reader/writer deadlock entirely: nothing holds a pipe buffer, so a
     * chatty provider cannot block on a full one. Whatever the process managed to write before it
     * was killed is still read back and reported, because a timed-out call that produced three
     * turns of stream is evidence and a timed-out call that reported nothing is not.
     */
    private static String run(List<String> cmd, Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        Path outFile = Files.createTempFile("modelround-stdout-", ".jsonl");
        Path errFile = Files.createTempFile("modelround-stderr-", ".txt");
        pb.redirectOutput(outFile.toFile());
        // STDIN FROM AN EMPTY FILE, and this was the second deadlock. A ProcessBuilder child gets
        // a PIPE on stdin that nobody writes to and nobody closes, so anything in the child that
        // reads stdin -- directly, or via a library asking whether a human is present -- blocks
        // forever waiting for an EOF that never arrives. Spawned from bash the same command
        // answers in twenty seconds; spawned from Java it never answered at all, once for a full
        // hour and once for ninety seconds. One line, and the kind that looks like noise until
        // the day it saves a build.
        Path inFile = Files.createTempFile("modelround-stdin-", ".txt");
        pb.redirectInput(inFile.toFile());
        pb.redirectError(errFile.toFile());
        Process p = pb.start();

        boolean exited = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }

        String out = Files.exists(outFile) ? Files.readString(outFile) : "";
        if (!exited) {
            String partial = tail(out);
            throw new IOException("the provider did not answer within " + TIMEOUT_SECONDS + "s."
                    + " It was killed, and whatever it had already streamed is below, because a"
                    + " call that produced three turns before hanging is evidence and a call that"
                    + " reported nothing is not:\n" + partial);
        }
        if (p.exitValue() != 0) {
            String err = Files.exists(errFile) ? Files.readString(errFile).strip() : "";
            throw new IOException("omp exited " + p.exitValue() + ": "
                    + err.substring(0, Math.min(400, err.length())));
        }
        Files.deleteIfExists(outFile);
        Files.deleteIfExists(errFile);
        return out;
    }

    /**
     * The command shape actually executed, for the artifact's reproducibility section.
     *
     * <p>The transcript is named as {@code @file} because that is what is passed; printing it
     * inline would describe a command that was never run and would itself blow past the argv
     * ceiling this class exists to avoid.
     */
    public static String describeCommand(String model, String systemPrompt, String transcript) {
        return String.format(Locale.ROOT,
                "omp -p --mode json --no-session --model %s --thinking off"
                        + " --profile %s --config <isolation-overlay.yml> --no-tools"
                        + " --max-time %s --system-prompt %s @<transcript-file>"
                        + "   # %d chars of full history. Isolation = --profile (closes the USER"
                        + " scope: ~/.omp/agent/mcp.json and the third-party MCP configs are no"
                        + " longer in scope) plus an overlay carrying mcp.enableProjectConfig=false"
                        + " (closes the PROJECT scope, which is keyed to the cwd and so survives"
                        + " the profile). Measured: 0 mcp__ tools, from any cwd. --auto-approve is"
                        + " absent on purpose.",
                model, ISOLATION_PROFILE, MAX_TIME, quote(systemPrompt), transcript.length());
    }

    private static String quote(String s) {
        String oneLine = s.replace("\r", " ").replace("\n", "\\n");
        return "\"" + oneLine + "\"";
    }
}
