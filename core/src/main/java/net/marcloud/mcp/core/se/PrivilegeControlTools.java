package net.marcloud.mcp.core.se;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import net.marcloud.mcp.core.io.IoManager;

/**
 * MCP tools for L4/L5 self management on the live subject the reference monitor
 * evaluates against. These make the two-state privilege token (L4) and the
 * capability SID set (L5) actually adjustable at runtime, closing GAP-2: without
 * them a disabled privilege or revoked capability could never take hold because
 * the base subject was a fresh wide-open template on every call.
 *
 * <ul>
 *   <li>{@code enable_privilege} / {@code disable_privilege} — flip a granted
 *       {@link Privilege}'s enabled state. Disabling makes any tool requiring that
 *       privilege deny at L4 until re-enabled (least privilege in time). Neither
 *       can grant a privilege that was never held (no self-escalation).</li>
 *   <li>{@code grant_capability} / {@code revoke_capability} — add/remove a
 *       {@link CapabilitySid} from the subject's granted set. Revoking a SID makes
 *       any tool requiring it deny at L5; when the subject is wildcard, the first
 *       revoke materializes the full set minus that SID so the revoke bites.</li>
 * </ul>
 *
 * <p>All four are R0 (KERNEL) — self privilege management — and reserved (they
 * register as built-ins, so a generated tool can never squat these names). They
 * carry no L3/L4/L5 requirement of their own: gating the privilege editor behind
 * the privilege it edits would be a chicken-and-egg lockout. The ring gate alone
 * (R0) is the guard, so a clearance dropped below R0 locks the editor out.
 */
public final class PrivilegeControlTools {

    private final SeReferenceMonitor engine;

    public PrivilegeControlTools(SeReferenceMonitor engine) {
        this.engine = engine;
    }

    public void registerAll(IoManager reg) {
        for (SyncToolSpecification spec : List.of(
                enablePrivilege(), disablePrivilege(), grantCapability(), revokeCapability())) {
            Tool t = spec.tool();
            reg.register(t.name(), spec, null, t.description(), true,
                    Ring.forBuiltin(t.name(), Ring.R0));
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

    private SyncToolSpecification enablePrivilege() {
        Tool tool = Tool.builder()
                .name("enable_privilege")
                .title("Enable L4 privilege")
                .description("Enable a granted L4 privilege on the current subject so tools "
                        + "requiring it are permitted again (e.g. SE_NET_RAW re-enables "
                        + "send_chat / send_raw_packet). Cannot enable a privilege that was "
                        + "never granted — no self-escalation.")
                .inputSchema(schema(Map.of("privilege",
                        Map.of("type", "string", "description",
                                "privilege name, e.g. SE_NET_RAW or bare NET_RAW")),
                        List.of("privilege")))
                .annotations(ToolAnnotations.builder()
                        .title("Enable L4 privilege")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Privilege p = Privilege.parse(arg(request.arguments(), "privilege"));
            if (p == null) {
                return err("unknown privilege; see list_permissions / Privilege names");
            }
            return engine.enablePrivilege(p)
                    ? ok("privilege " + p.name() + " enabled")
                    : err("cannot enable " + p.name()
                            + " (not granted to this subject, or subject not locally owned)");
        });
    }

    private SyncToolSpecification disablePrivilege() {
        Tool tool = Tool.builder()
                .name("disable_privilege")
                .title("Disable L4 privilege")
                .description("Disable a granted L4 privilege on the current subject (least "
                        + "privilege in time). Any tool requiring it will then be DENIED at L4 "
                        + "until re-enabled with enable_privilege — the grant is kept, only the "
                        + "enabled state flips.")
                .inputSchema(schema(Map.of("privilege",
                        Map.of("type", "string", "description",
                                "privilege name, e.g. SE_NET_RAW or bare NET_RAW")),
                        List.of("privilege")))
                .annotations(ToolAnnotations.builder()
                        .title("Disable L4 privilege")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Privilege p = Privilege.parse(arg(request.arguments(), "privilege"));
            if (p == null) {
                return err("unknown privilege; see list_permissions / Privilege names");
            }
            return engine.disablePrivilege(p)
                    ? ok("privilege " + p.name() + " disabled — tools requiring it now deny at L4")
                    : err("cannot disable " + p.name()
                            + " (not granted to this subject, or subject not locally owned)");
        });
    }

    private SyncToolSpecification grantCapability() {
        Tool tool = Tool.builder()
                .name("grant_capability")
                .title("Grant L5 capability")
                .description("Grant an L5 capability SID to the current subject so tools "
                        + "requiring it are permitted (e.g. CAP_NETWORK_SEND re-allows send_chat). "
                        + "Re-adds a previously revoked SID.")
                .inputSchema(schema(Map.of("capability",
                        Map.of("type", "string", "description",
                                "capability SID, e.g. CAP_NETWORK_SEND or bare NETWORK_SEND")),
                        List.of("capability")))
                .annotations(ToolAnnotations.builder()
                        .title("Grant L5 capability")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            CapabilitySid sid = CapabilitySid.parse(arg(request.arguments(), "capability"));
            if (sid == null) {
                return err("unknown capability SID; see list_capabilities / CapabilitySid names");
            }
            return engine.grantCapability(sid)
                    ? ok("capability " + sid.name() + " granted")
                    : err("cannot grant " + sid.name() + " (subject not locally owned)");
        });
    }

    private SyncToolSpecification revokeCapability() {
        Tool tool = Tool.builder()
                .name("revoke_capability")
                .title("Revoke L5 capability")
                .description("Revoke an L5 capability SID from the current subject. Any tool "
                        + "requiring it will then be DENIED at L5 until re-granted. If the "
                        + "subject currently holds the wildcard set, the first revoke materializes "
                        + "the full set minus this SID so the revoke actually takes effect.")
                .inputSchema(schema(Map.of("capability",
                        Map.of("type", "string", "description",
                                "capability SID, e.g. CAP_NETWORK_SEND or bare NETWORK_SEND")),
                        List.of("capability")))
                .annotations(ToolAnnotations.builder()
                        .title("Revoke L5 capability")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            CapabilitySid sid = CapabilitySid.parse(arg(request.arguments(), "capability"));
            if (sid == null) {
                return err("unknown capability SID; see list_capabilities / CapabilitySid names");
            }
            return engine.revokeCapability(sid)
                    ? ok("capability " + sid.name() + " revoked — tools requiring it now deny at L5")
                    : err("cannot revoke " + sid.name() + " (subject not locally owned)");
        });
    }
}
