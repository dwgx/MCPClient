package net.marcloud.mcp.core.security;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.registry.Capability;
import net.marcloud.mcp.core.registry.CapabilityRegistry;

/**
 * MCP tools for the privilege system (CPU-ring model). All three are themselves
 * R3 (always reachable), so the agent can always inspect and voluntarily lower
 * its own clearance — but raising it back is token-gated.
 *
 * <ul>
 *   <li>{@code list_permissions} — show current clearance + every tool's ring and
 *       whether it's currently allowed.</li>
 *   <li>{@code drop_privilege} — lower clearance to a less-privileged ring
 *       (self-sandbox). Cannot raise. Always allowed.</li>
 *   <li>{@code restore_privilege} — raise clearance, gated by the restore token
 *       configured at startup (prevents self-escalation).</li>
 * </ul>
 */
public final class PermissionTools {

    private final PolicyEngine engine;
    private final CapabilityRegistry registry;

    public PermissionTools(PolicyEngine engine, CapabilityRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    public void registerAll(CapabilityRegistry reg) {
        for (SyncToolSpecification spec : List.of(listPermissions(), dropPrivilege(), restorePrivilege())) {
            var t = spec.tool();
            reg.register(t.name(), spec, null, t.description(), true,
                    Ring.forBuiltin(t.name(), Ring.R3));
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

    /** Parse a ring from "R-1".."R3" or a label like "OBSERVE" (case-insensitive). */
    private static Ring parseRing(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase();
        for (Ring r : Ring.values()) {
            if (v.equals("R" + r.level()) || v.equals(r.label()) || v.equals(r.name())) {
                return r;
            }
        }
        return null;
    }

    private SyncToolSpecification listPermissions() {
        Tool tool = Tool.builder()
                .name("list_permissions")
                .description("Show the privilege system: current clearance ring, whether "
                        + "privilege can be restored, and every tool's ring + whether it is "
                        + "currently permitted. Rings: R-1 HYPERVISOR (arbitrary code / redefine) "
                        + "> R0 KERNEL (self-modify tools) > R1 SYSTEM (game/network effects) > "
                        + "R2 OBSERVE (live game reads) > R3 USER (local read-only).")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            StringBuilder sb = new StringBuilder();
            sb.append("clearance: ").append(engine.clearance().tag())
                    .append("  (restorable: ").append(engine.restorable()).append(")")
                    .append(System.lineSeparator());
            for (Capability c : registry.capabilities()) {
                sb.append(registry.isAllowed(c) ? "  [ALLOW] " : "  [DENY ] ")
                        .append(c.name()).append("  ").append(c.ring().tag())
                        .append(System.lineSeparator());
            }
            return ok(sb.toString().stripTrailing());
        });
    }

    private SyncToolSpecification dropPrivilege() {
        Tool tool = Tool.builder()
                .name("drop_privilege")
                .description("Voluntarily LOWER clearance to a less-privileged ring "
                        + "(self-sandbox). e.g. target 'R2' locks out send/create/eval/redefine, "
                        + "leaving only observation + local tools. Cannot raise privilege. "
                        + "Restoring later requires the restore token (if configured).")
                .inputSchema(schema(Map.of("target",
                        Map.of("type", "string", "description",
                                "target ring: R-1, R0, R1, R2, or R3 (or label like OBSERVE)")),
                        List.of("target")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Ring target = parseRing(arg(request.arguments(), "target"));
            if (target == null) {
                return err("unknown ring; use R-1, R0, R1, R2, or R3");
            }
            // GAP-1: in P-SECURE (L1) mode a drop that can't reach the authority
            // did NOT take effect (evaluate() always asks the authority, never the
            // local cache). RemotePolicyEngine fails closed by throwing; render that
            // as an honest tool error, never a phantom "clearance is now Rn".
            final Ring now;
            try {
                now = engine.dropTo(target);
            } catch (net.marcloud.mcp.core.security.RemotePolicyEngine.AuthorityUnreachableException e) {
                return err(e.getMessage());
            }
            return ok("clearance is now " + now.tag()
                    + (now == target ? "" : " (already at or below " + target.tag() + ")"));
        });
    }

    private SyncToolSpecification restorePrivilege() {
        Tool tool = Tool.builder()
                .name("restore_privilege")
                .description("RAISE clearance back up, gated by the restore token set at startup "
                        + "(prevents a sandboxed agent from re-escalating itself). Supply the "
                        + "token and the target ring.")
                .inputSchema(schema(Map.of(
                        "token", Map.of("type", "string", "description", "the restore token"),
                        "target", Map.of("type", "string", "description",
                                "target ring to restore to (default R-1)")),
                        List.of("token")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            if (!engine.restorable()) {
                return err("privilege restoration is disabled this session (no token configured)");
            }
            String token = arg(request.arguments(), "token");
            Ring target = parseRing(arg(request.arguments(), "target"));
            if (target == null) {
                target = Ring.R_MINUS_1;
            }
            boolean restored = engine.tryRestore(target, token);
            return restored ? ok("clearance restored to " + engine.clearance().tag())
                            : err("restore denied: wrong or missing token");
        });
    }
}
