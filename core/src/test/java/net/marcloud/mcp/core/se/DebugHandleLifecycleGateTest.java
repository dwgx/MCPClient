package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.ob.ObManager;

import org.junit.Test;

/**
 * Audit H9 — the L6 handle-lifecycle tools must carry the same gate as the nine debug ops they
 * feed.
 *
 * <p><b>The defect.</b> {@code debug_open_thread} and {@code debug_close_handle} were declared only
 * in {@link Ring} (R0): they had no L3 {@code IntegrityLevel}, no L4 {@code Privilege} and no L5
 * capability, so a caller at R0 could mint a frozen {@code READ|WRITE|EXECUTE} handle over any live
 * thread while {@code revoke_capability(CAP_DEBUG_CONTROL)} and
 * {@code disable_privilege(SE_DEBUG_CONTROL)} — the two kill switches the other nine debug tools
 * answer to — did nothing. The handler was also the only one of the ten that never ran the shared
 * {@code guard()} preamble, so even the in-handler defense-in-depth check was absent.
 *
 * <p><b>Driven, not read.</b> Every assertion goes through the real supervised registry with the
 * REAL {@link DebugTools} registered, so the deny is the production decision for the production
 * tool name. The handler's own return value is the proof: on the pre-fix code the L4/L5 layers had
 * nothing to deny and the in-handler gate was never consulted, so the calls ran.
 */
public final class DebugHandleLifecycleGateTest {

    /**
     * The ADR-0004 fold means nothing is registered under its own name any more. What these
     * tests check -- "revoking CAP_DEBUG_CONTROL closes the handle surface", "minting needs the
     * privilege" -- still has to hold; it is just reached through {@code debug_handle} with
     * the operation named in {@code action}.
     */
    private static Map<String, Object> via(String concrete, Map<String, Object> args) {
        Map<String, Object> m = new HashMap<>(args);
        m.put("action", concrete);
        return m;
    }

    /** A registry with the L6-wired debug tools registered, gated by {@code engine}. */
    private static IoManager registry(IoSupervisor exec, SeReferenceMonitor engine, AccessGate gate) {
        IoManager reg = new IoManager(exec, engine);
        ObManager objects = new ObManager(ref -> Thread.currentThread(), 8, 60_000L);
        new DebugTools(gate, objects, SeToken::wideOpen).registerAll(reg);
        return reg;
    }

    private static Map<String, Object> openArgs() {
        return Map.of("threadName", Thread.currentThread().getName());
    }

    /**
     * L4: every privilege GRANTED but {@code SE_DEBUG_CONTROL} DISABLED — so the ring (R0) and the
     * capability layer (wildcard caps) both pass and only the privilege layer can deny. Before the
     * fix there was no L4 row for either tool, so both calls ran.
     */
    @Test
    public void withoutTheDebugPrivilegeTheHandleToolsAreDenied() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            Map<Privilege, Boolean> grants = new EnumMap<>(Privilege.class);
            for (Privilege p : Privilege.values()) {
                grants.put(p, true); // granted AND enabled...
            }
            grants.put(Privilege.SE_DEBUG_CONTROL, false); // ...except the one under test
            SeToken subject = new SeToken("t", Ring.R0, IntegrityLevel.SYSTEM,
                    new PrivilegeToken(grants), null); // null caps = wildcard, so L5 passes
            IoManager reg = registry(exec,
                    new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"), subject),
                    new AllowAllGate());

            CallToolResult open = reg.invoke("debug_handle", via("debug_open_thread", openArgs()));
            assertTrue("debug_open_thread must require SE_DEBUG_CONTROL, or the debugger's kill "
                            + "switch does not cover handle minting: " + open.content(),
                    Boolean.TRUE.equals(open.isError()));
            assertTrue("the deny must come from the privilege layer: " + open.content(),
                    open.content().toString().contains("L4 privilege"));

            CallToolResult close = reg.invoke("debug_handle", via("debug_close_handle", Map.of("handle", "1")));
            assertTrue("debug_close_handle is part of the same surface: " + close.content(),
                    Boolean.TRUE.equals(close.isError()));
            assertTrue("the deny must come from the privilege layer: " + close.content(),
                    close.content().toString().contains("L4 privilege"));
        } finally {
            exec.shutdown();
        }
    }

    /**
     * L5: a strict subject holding only {@code CAP_WORLD_READ}, with every privilege enabled and
     * SYSTEM integrity — so only the capability layer can deny.
     */
    @Test
    public void withoutTheDebugCapabilityTheHandleToolsAreDenied() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            SeToken strict = SeLocalMonitor.strictSubject(java.util.Set.of(CapabilitySid.CAP_WORLD_READ));
            IoManager reg = registry(exec,
                    new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"), strict),
                    new AllowAllGate());

            CallToolResult open = reg.invoke("debug_handle", via("debug_open_thread", openArgs()));
            assertTrue("debug_open_thread must require CAP_DEBUG_CONTROL, or revoking the "
                            + "capability does not close handle minting: " + open.content(),
                    Boolean.TRUE.equals(open.isError()));
            assertTrue("the deny must come from the capability layer: " + open.content(),
                    open.content().toString().contains("L5 capability"));

            CallToolResult close = reg.invoke("debug_handle", via("debug_close_handle", Map.of("handle", "1")));
            assertTrue("debug_close_handle is part of the same surface: " + close.content(),
                    Boolean.TRUE.equals(close.isError()));
            assertTrue("the deny must come from the capability layer: " + close.content(),
                    close.content().toString().contains("L5 capability"));
        } finally {
            exec.shutdown();
        }
    }

    /**
     * The in-handler half: the two handle handlers now run the shared {@code guard()} preamble
     * like the other eight.
     *
     * <p>The preamble's FIRST check is the native agent's presence, and no JVMTI agent exists in a
     * headless run — so the observable proof that the preamble runs is the honest "agent absent"
     * error naming {@code -agentpath:core-jvmti.dll}. Pre-fix, {@code openThread()} had no preamble
     * at all: it found this test's own thread, minted the handle and returned SUCCESS, which is
     * exactly the assertion below failing. ({@code debug_close_handle} likewise reported a close it
     * had not performed, or failed for its own unrelated reason — either way not with this error.)
     *
     * <p>The L4/L5 half of the preamble is pinned by the two side-table tests above, which the
     * registry enforces BEFORE the handler is reached; the native check short-circuits it here, so
     * asserting a gate message in this test would be asserting something the run cannot produce.
     */
    @Test
    public void theHandleHandlersRunTheSharedGuard() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            IoManager reg = registry(exec,
                    new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"),
                            SeToken.wideOpen()),
                    new AllowAllGate());

            CallToolResult open = reg.invoke("debug_handle", via("debug_open_thread", openArgs()));
            assertTrue("debug_open_thread must run the shared guard() preamble: " + open.content(),
                    Boolean.TRUE.equals(open.isError()));
            assertTrue("the preamble's agent check must be why: " + open.content(),
                    open.content().toString().contains("-agentpath:core-jvmti.dll"));
            assertFalse("and it must NOT report a minted handle: " + open.content(),
                    open.content().toString().contains("opened handle #"));

            CallToolResult close = reg.invoke("debug_handle", via("debug_close_handle", Map.of("handle", "1")));
            assertTrue("debug_close_handle must run the shared guard() preamble: " + close.content(),
                    Boolean.TRUE.equals(close.isError()));
            assertTrue("the preamble's agent check must be why: " + close.content(),
                    close.content().toString().contains("-agentpath:core-jvmti.dll"));
            assertFalse("and it must NOT claim to have closed anything: " + close.content(),
                    close.content().toString().contains("closed handle #"));
        } finally {
            exec.shutdown();
        }
    }

    /**
     * The counterweight: at R0 with the privilege enabled and the capability held, the call
     * PROCEEDS — so the denials above are the gate biting, not a blanket refusal.
     *
     * <p>Driven with a stub handler rather than the real one because the real handler now runs the
     * shared {@code guard()} preamble, whose first check is the native agent's presence — absent in
     * any headless run. That is the same shape {@code SupervisedGateL4L5DenyTest} uses for its
     * allowed half, and it is what makes "the call proceeds" observable without a JVMTI agent.
     */
    @Test
    public void withItsPrivilegesAndCapabilityTheCallProceeds() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            IoManager reg = new IoManager(exec, new SeLocalMonitor(
                    new SeClearancePolicy(Ring.R0, "tok"), SeToken.wideOpen()));
            // The stub is registered under the FOLDED name, because that is the name the gate
            // sees and the name the agent calls. A stub parked under "debug_open_thread" would
            // never be reached, and the assertion below would pass or fail for reasons that
            // have nothing to do with the gate.
            Tool tool = Tool.builder().name("debug_handle").description("stub")
                    .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
            reg.register("debug_handle",
                    new SyncToolSpecification(tool, (ex, req) -> CallToolResult.builder()
                            .addTextContent("ran").isError(false).build()),
                    null, "stub", true, Ring.forBuiltin("debug_handle", Ring.R0));

            CallToolResult allowed = reg.invoke("debug_handle", openArgs());
            assertFalse("an R0 subject holding SE_DEBUG_CONTROL and CAP_DEBUG_CONTROL must be "
                            + "able to call the handle tools, or the rows above are a blanket "
                            + "refusal rather than a gate: " + allowed.content(),
                    Boolean.TRUE.equals(allowed.isError()));
            assertTrue("the allowed handler actually ran",
                    allowed.content().toString().contains("ran"));
        } finally {
            exec.shutdown();
        }
    }
}
