/**
 * Read-only dump of omp's LIVE tool registry.
 *
 * Shape copied from `core/src/test/java/net/marcloud/mcp/core/eval/SingleCallProbe.HOOK_SOURCE`
 * (that class's lines 147-181). It registers ONE command, prints the registry, and exits: it
 * registers no tool, calls no tool, and makes no provider call.
 *
 * Why a registry dump and not a question: a model asked to list its tools under `--no-tools`
 * was MEASURED inventing ten of them (bash/read/write/edit/...) for a run that had none. So
 * every "what tools does it have" check reads THIS file instead.
 *
 * Usage (from the repo root):
 *   set TOOLTABLE_OUT=D:\Project\MCPClient\.ai-notes\docs\audits\tooltable.json
 *   omp -p --mode json --no-session --profile modelbridge-iso --hook scripts\tooltable.ts --max-time 150s /tooltable
 *
 * The `mcpActive` count is the acceptance number. Read TOOLTABLE_OUT, not the console.
 */
import { writeFileSync } from "node:fs";

interface ToolInfo {
  readonly name: string;
  readonly mcpServerName?: string | null;
}

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
        }, null, 2), "utf8");
      }
      process.exit(0);
    },
  });
}
