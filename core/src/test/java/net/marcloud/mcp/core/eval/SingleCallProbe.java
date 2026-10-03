package net.marcloud.mcp.core.eval;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一次模型调用的最小探针。不跑整局，只回答一件事：传输层活着吗。
 *
 * <p>存在的理由是实测出来的：2026-10-03 的两局共花 50 分钟，两次都没有测到北极星。
 * 第一次死在一个已修的传输层缺陷上，第二次 14/14 全部 180 秒超时。
 * **而 30 秒的单调用能区分「传输层坏了」与「局本身有问题」。**
 *
 * <p>用法：
 * <pre>
 * java -cp "&lt;test-classes&gt;:&lt;classes&gt;:&lt;deps&gt;" net.marcloud.mcp.core.eval.SingleCallProbe
 * </pre>
 */
public final class SingleCallProbe {

    private SingleCallProbe() {
    }

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("probe.model", ModelRound.DEFAULT_MODEL);
        Path cwd = Files.createTempDirectory("probe-empty-cwd");
        System.out.println("model : " + model);
        System.out.println("cwd   : " + cwd + " (empty by construction)");


        // --- THE ACCEPTANCE SIGNAL -------------------------------------------------
        // "It ran" is not evidence of isolation. The only evidence is the tool table the spawned
        // process actually built, so it is dumped from omp's own registry (via the /tooltable
        // hook) rather than asked of the model: a model asked to list its tools under --no-tools
        // was MEASURED inventing ten of them for a run that had none.
        Path tableOut = Files.createTempFile("probe-tooltable-", ".json");
        tableOut.toFile().deleteOnExit();
        // toolTable() is what MAKES the file, so it has to run before the file is read.
        String tableSummary = toolTable(tableOut);
        System.out.println("toolTable: " + tableSummary);
        // An unreadable table is not a pass. If the registry cannot be dumped then nothing about
        // this run's tool surface is known, and "unknown" must not be reported as "clean".
        int mcpActive = Files.exists(tableOut)
                ? count(Files.readString(tableOut), "\"mcpActive\"")
                : -1;
        if (mcpActive != 0) {
            // A contaminated run is not a slow run, it is a run about a different subject, so it
            // must not be reported as a transport result at all. Exit 3, distinct from the
            // transport-failure code, because "the call failed" and "the call was not isolated"
            // are different facts and a reader has to be able to tell them apart.
            System.out.println("ISOLATION: FAILED -- " + (mcpActive < 0
                    ? "the tool table could not be read, so isolation is UNKNOWN"
                    : mcpActive + " mcp__ tools were active")
                    + ". Do not read anything below as a result about the model.");
            System.exit(3);
        }
        System.out.println("ISOLATION: OK -- 0 mcp__ tools active");
        long t0 = System.nanoTime();
        ModelBridge.Turn turn = ModelBridge.call(model,
                "You are a Minecraft agent. Reply with one tool call and nothing else.",
                "You stand on dirt. Your inventory is empty. Say ok.",
                cwd);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("wallMs: " + ms);
        System.out.println("reply : " + oneLine(turn.reply()));
        System.out.println("in/out: " + turn.inputTokens() + " / " + turn.outputTokens());
        System.out.println("usd   : " + turn.costUsd());
        System.out.println("TRANSPORT: " + (turn.reply() == null || turn.reply().startsWith("TRANSPORT FAILURE")
                ? "FAILED" : "OK"));
        if (turn.reply() == null || turn.reply().startsWith("TRANSPORT FAILURE")) {
            System.out.println("---- what it streamed before the kill ----");
            System.out.println(oneLine(turn.reply()));
            System.exit(2);
        }
        System.exit(0);
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "(null)";
        }
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + " ...(" + flat.length() + " chars)";
    }

    /**
     * Runs the same isolated spawn as a real call, but with a hook that dumps the live tool
     * registry instead of asking the model anything.
     *
     * <p>Costs about a second and makes no provider call, so it is cheap enough to run before every
     * probe. The numbers it reports are the acceptance criterion: {@code mcp__} must be 0.
     *
     * @return a one-line summary, or the reason the table could not be read
     */
    private static String toolTable(java.nio.file.Path out) throws Exception {
        java.nio.file.Path hook = out.resolveSibling("tooltable.ts");
        Files.writeString(hook, HOOK_SOURCE, java.nio.charset.StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder("omp", "-p", "--mode", "json", "--no-session",
                "--profile", ModelBridge.ISOLATION_PROFILE,
                "--hook", hook.toAbsolutePath().toString(),
                "--max-time", ModelBridge.MAX_TIME, "/tooltable");
        pb.directory(Files.createTempDirectory("probe-tooltable-cwd").toFile());
        pb.environment().put("TOOLTABLE_OUT", out.toAbsolutePath().toString());
        pb.redirectInput(Files.createTempFile("probe-tooltable-stdin-", ".txt").toFile());
        java.nio.file.Path err = Files.createTempFile("probe-tooltable-stderr-", ".txt");
        pb.redirectError(err.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "UNREADABLE: the registry dump did not finish in 120s";
        }
        if (!Files.exists(out)) {
            String e = Files.readString(err).strip();
            return "UNREADABLE (exit " + p.exitValue() + "): "
                    + e.substring(0, Math.min(300, e.length()));
        }
        String json = Files.readString(out);
        return "mcp__ tools = " + count(json, "\"mcpActive\"") + " active / "
                + count(json, "\"mcpTotal\"") + " registered; "
                + count(json, "\"total\"") + " tools in the registry"
                + System.lineSeparator() + "    raw: " + json.replaceAll("\\s+", " ");
    }

    /** Reads one integer field out of the dump, rather than pulling in a parser for two numbers. */
    private static int count(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return -1;
        }
        i += key.length();
        while (i < json.length() && json.charAt(i) != ':') {
            i++;
        }
        int end = i + 1;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return end > i + 1 ? Integer.parseInt(json.substring(i + 1, end)) : -1;
    }

    /**
     * The hook source, inline so the probe stays one file with nothing to install first.
     *
     * <p>Read-only: it prints the registry and exits, registering no tool and calling none.
     */
    private static final String HOOK_SOURCE = """
            import { writeFileSync } from "node:fs";
            interface ToolInfo { readonly name: string; readonly mcpServerName?: string | null; }
            interface ToolTableApi {
              registerCommand(name: string, spec: { description: string; handler(): Promise<void> }): void;
              getAllTools(): readonly ToolInfo[];
              getActiveTools?(): readonly string[];
            }
            export default function toolTable(pi: unknown): void {
              const api = pi as ToolTableApi;
              api.registerCommand("tooltable", {
                description: "Dump the live tool registry as JSON and exit",
                async handler() {
                  const active = new Set<string>(api.getActiveTools?.() ?? []);
                  const rows = (api.getAllTools() ?? []).map((t) => ({
                    name: t.name,
                    active: active.has(t.name),
                    mcp: t.name.startsWith("mcp__") || (t.mcpServerName ?? "") !== "",
                  }));
                  const out = process.env.TOOLTABLE_OUT;
                  if (out) {
                    writeFileSync(out, JSON.stringify({
                      total: rows.length,
                      mcpTotal: rows.filter((r) => r.mcp).length,
                      mcpActive: rows.filter((r) => r.mcp && r.active).length,
                      names: rows.map((r) => r.name).sort(),
                      mcpNames: rows.filter((r) => r.mcp).map((r) => r.name).sort(),
                      activeNames: rows.filter((r) => r.active).map((r) => r.name).sort(),
                    }), "utf8");
                  }
                  process.exit(0);
                },
              });
            }
            """;
}