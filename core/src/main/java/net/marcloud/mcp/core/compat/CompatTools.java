package net.marcloud.mcp.core.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.se.Ring;

/**
 * The only upper-layer window onto the compat engine: a read-only MCP tool,
 * {@code list_compat_patches}, that reports which patches the kernel knows about,
 * what each fixes, its target class, build date, signature status, and whether the
 * engine actually armed it. Patch <i>application</i> is kernel-automatic at premain
 * (it does not pass through the ring gate); patch <i>state</i> is transparent and
 * auditable through this tool — mirroring Windows' "which compatibility fixes were
 * applied" view (07-COMPAT-SHIM §可观察).
 *
 * <p>Registration follows the same supervised pattern as every other tool group
 * (see {@code NarrativeTools} / {@code ToolRegistry}): the tool is a built-in gated
 * at {@link Ring#forBuiltin} (R3, read-only).
 */
public final class CompatTools {

    private final CompatDatabase db;
    private final CompatEngine engine;

    /**
     * @param db     the catalog of all known patches (may be empty)
     * @param engine the built engine, to report which patches were actually armed
     *               (may be null if compat never installed — everything reports as
     *               not-armed)
     */
    public CompatTools(CompatDatabase db, CompatEngine engine) {
        this.db = db;
        this.engine = engine;
    }

    /** Register {@code list_compat_patches} into the supervised registry. */
    public void registerAll(IoManager registry) {
        SyncToolSpecification spec = listCompatPatches();
        Tool t = spec.tool();
        registry.register(t.name(), spec, null, t.description(), true,
                Ring.forBuiltin(t.name(), Ring.R3));
    }

    private SyncToolSpecification listCompatPatches() {
        Tool tool = Tool.builder()
                .name("list_compat_patches")
                .title("List compat patches")
                .description("Read-only: list the startup compatibility patches the kernel knows about "
                        + "(NT AppCompat analogue). For each: code (MCP-KIxxxx), the KI it fixes, target "
                        + "vanilla class, platform condition, build date, signature status, whether it was "
                        + "armed by the engine, and supersede/status. Patches are applied automatically at "
                        + "startup by kernel code, not through the ring gate; this tool makes their state "
                        + "auditable. Each row also reports what the patch actually did at runtime: "
                        + "transformRuns (times its transform ran), targetsChanged (of those, times it "
                        + "returned different bytes), applyFailures (times it threw) and lastApplyError. "
                        + "armed means AUTHORISED, not applied: a patch can be armed and still change "
                        + "nothing, and these counters are how you tell. Totals changedCount, inertCount "
                        + "and failedCount summarise that. The counters are per-process measurements, "
                        + "not proof that a rewrite is correct.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("List compat patches")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) ->
                CallToolResult.builder().addTextContent(Json.write(reportFor(db, engine))).isError(false).build());
    }

    /**
     * The exact object {@code list_compat_patches} serialises. Package-visible so the report is
     * unit-testable without standing up an {@link IoManager}.
     *
     * <p>Two independent axes per row, and they must not be collapsed:
     * <ul>
     *   <li>{@code armed} — <b>authorisation</b>, decided at build time by the gauntlet in
     *       {@link CompatEngine#build}. Unchanged meaning.</li>
     *   <li>{@code transformRuns} / {@code targetsChanged} / {@code applyFailures} /
     *       {@code unauthorizedAtUse} / {@code lastApplyError} — <b>measurement</b> of what
     *       the transform then did. Per-process; resets on restart.</li>
     * </ul>
     * A patch is named {@code armed}, never {@code applied}: in this package's own vocabulary
     * a patch is already "applied" merely by being installed to run at class load, so an
     * {@code applied} field would read as a synonym for {@code armed} and reinstate exactly
     * the confusion this row exists to remove.
     */
    static Map<String, Object> reportFor(CompatDatabase db, CompatEngine engine) {
        // Armed-ness is per-PATCH (by content-addressed id), not per-target: two patches can
        // share a target class where one is armed and the other skipped, so matching by target
        // would falsely mark the skipped one armed.
        java.util.Set<String> armedIds = engine == null
                ? java.util.Set.of()
                : engine.armedPatchIds();
        java.util.Map<String, CompatEngine.ApplyRecord> records =
                engine == null ? java.util.Map.of() : engine.applyRecords();

        List<Object> patches = new ArrayList<>();
        int changed = 0;
        int inert = 0;
        int failed = 0;
        for (CompatPatch p : db.all()) {
            PatchManifest m = p.manifest();
            CompatEngine.ApplyRecord r = records.get(m.patchId());
            if (r == null) {
                r = CompatEngine.ApplyRecord.none(m.patchId());
            }
            Map<String, Object> row = new LinkedHashMap<>(m.toDisplayMap());
            row.put("appliesToRuntime", p.appliesToRuntime());
            row.put("armed", armedIds.contains(m.patchId()));
            row.put("transformRuns", r.transformRuns());
            row.put("targetsChanged", r.targetsChanged());
            row.put("unauthorizedAtUse", r.unauthorizedAtUse());
            row.put("applyFailures", r.applyFailures());
            row.put("lastApplyError", r.lastApplyError());
            patches.add(row);
            if (r.targetsChanged() > 0) {
                changed++;
            }
            // INERT: the patch ran and every run changed nothing. This is the state that
            // shipped in GlClampToEdgePatch and that "armed" alone could not express. A
            // patch with no runs at all is NOT inert — it simply has not been reached yet.
            if (r.transformRuns() > 0 && r.targetsChanged() == 0) {
                inert++;
            }
            if (r.applyFailures() > 0) {
                failed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", patches.size());
        out.put("armedCount", armedIds.size());
        out.put("changedCount", changed);
        out.put("inertCount", inert);
        out.put("failedCount", failed);
        out.put("patches", patches);
        return out;
    }
}
