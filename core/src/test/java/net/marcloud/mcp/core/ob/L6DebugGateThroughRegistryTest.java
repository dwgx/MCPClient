package net.marcloud.mcp.core.ob;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;
import org.junit.Test;

/**
 * End-to-end proof that the L6 object-handle layer actually GATES a real C6 tool
 * once wired — the payoff of the L6-activation work. Everything here runs headless
 * (no native agent, no game): we exercise the reference-monitor decision path, not
 * the JVMTI operation. The op itself still returns an honest "agent absent" error,
 * but the L6 verdict happens BEFORE that, which is what we assert.
 *
 * <p>Before this work the L6 branch was dead (ObManager never wired into any
 * engine), so none of these deny paths could fire through the registry.
 */
public class L6DebugGateThroughRegistryTest {

    /** A registry whose engine has L6 wired, with DebugTools handle-aware. */
    private static IoManager wired(IoSupervisor exec, ObManager om) {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen(), om);
        IoManager reg = new IoManager(exec, engine);
        new DebugTools(new AllowAllGate(), om, engine::currentSubject).registerAll(reg);
        return reg;
    }

    @Test
    public void openThreadIsRegisteredOnlyWhenL6Wired() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            ObManager om = new ObManager(ref -> new Object(), 8, 60_000L);
            IoManager withL6 = wired(exec, om);
            assertNotNull("debug_open_thread present when L6 wired", withL6.get("debug_open_thread"));
            assertNotNull("debug_close_handle present when L6 wired", withL6.get("debug_close_handle"));

            // Without L6 the lifecycle tools must NOT exist (surface unchanged).
            IoManager noL6 = new IoManager(exec,
                    new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
            new DebugTools(new AllowAllGate()).registerAll(noL6);
            org.junit.Assert.assertNull("no lifecycle tool without L6", noL6.get("debug_open_thread"));
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void readOnlyHandleAllowsReadButDeniesSuspendAtL6() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            // Freeze a THREAD handle READ-only (below the RWE scheme ceiling).
            Object fakeThreadTarget = Thread.currentThread();
            ObManager om = new ObManager(ref -> fakeThreadTarget, 8, 60_000L);
            IoManager reg = wired(exec, om);

            SeToken s = SeToken.wideOpen();
            ObHandle h = om.open(s, ObRef.parse("thread:probe"), ObAccessMask.READ.bit());
            String hid = Long.toString(h.id());

            // debug_read_local (needs READ) with the handle: L6 ALLOWS, so the call
            // proceeds to the handler, which returns the honest "agent absent" error
            // (NOT an L6 deny). That proves L6 passed a READ op on a READ handle.
            var read = reg.invoke("debug_read_local", Map.of("handle", hid, "slot", 0));
            assertNotNull(read);
            assertTrue("read op should reach handler (agent-absent), not be L6-denied",
                    read.content().toString().contains("-agentpath:core-jvmti.dll"));

            // debug_suspend_thread (needs EXECUTE) with the same READ-only handle:
            // L6 must DENY at the "L6 handle" layer (no escalation), and the deny
            // message must NOT be the agent-absent one.
            var suspend = reg.invoke("debug_suspend_thread", Map.of("handle", hid));
            assertNotNull(suspend);
            String msg = suspend.content().toString();
            assertTrue("suspend on a READ-only handle must be L6-denied", msg.contains("L6 handle"));
            assertFalse("L6 deny must fire BEFORE the handler (not agent-absent)",
                    msg.contains("-agentpath:core-jvmti.dll"));
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void unknownHandleIsDeniedAtL6ThroughRegistry() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            ObManager om = new ObManager(ref -> new Object(), 8, 60_000L);
            IoManager reg = wired(exec, om);
            var r = reg.invoke("debug_read_local", Map.of("handle", "999999", "slot", 0));
            assertNotNull(r);
            assertTrue("unknown handle denied at L6", r.content().toString().contains("L6 handle"));
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void handleLessDebugCallIsUnaffectedByL6() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            ObManager om = new ObManager(ref -> new Object(), 8, 60_000L);
            IoManager reg = wired(exec, om);
            // No handle arg → L6 is a pure no-op; the call reaches the handler and
            // returns the honest agent-absent error, exactly as without L6.
            var r = reg.invoke("debug_suspend_thread", Map.of("threadName", "main"));
            assertNotNull(r);
            assertTrue("handle-less call unaffected by L6 (reaches handler)",
                    r.content().toString().contains("-agentpath:core-jvmti.dll"));
        } finally {
            exec.shutdown();
        }
    }

    /** The six HANDLE_OPS tools: each MUST advertise an optional 'handle' property when L6 is wired. */
    private static final List<String> HANDLE_OP_TOOLS = List.of(
            "debug_read_local", "debug_write_local", "debug_force_return",
            "debug_suspend_thread", "debug_pop_frame", "debug_single_step");

    /** The 'handle' property declared in a wired tool's inputSchema, or null if absent. */
    @SuppressWarnings("unchecked")
    private static Object handlePropOf(IoManager reg, String tool) {
        SyncToolSpecification spec = reg.get(tool).spec();
        Object schema = spec.tool().inputSchema();
        if (!(schema instanceof Map)) {
            return null;
        }
        Object props = ((Map<String, Object>) schema).get("properties");
        return (props instanceof Map) ? ((Map<String, Object>) props).get("handle") : null;
    }

    /**
     * H3/H4 regression: every one of the six handle-op tools must EXPOSE the optional
     * {@code handle} property in its advertised inputSchema once L6 is wired. On the
     * pre-fix code four of them (pop_frame / force_return / single_step / write_local)
     * declared no handle property, so an LLM following the schema could never supply
     * one — this loop fails for those four before the fix.
     */
    @Test
    public void allSixHandleOpToolsAdvertiseOptionalHandleProperty() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            ObManager om = new ObManager(ref -> new Object(), 8, 60_000L);
            IoManager reg = wired(exec, om);
            for (String tool : HANDLE_OP_TOOLS) {
                assertNotNull(tool + " must declare an optional 'handle' schema property",
                        handlePropOf(reg, tool));
            }
        } finally {
            exec.shutdown();
        }
    }

    /** As {@link #wired} but with the strict-handle (hardened) posture on the ObManager. */
    private static IoManager wiredStrict(IoSupervisor exec, ObManager om) {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen(), om);
        IoManager reg = new IoManager(exec, engine);
        new DebugTools(new AllowAllGate(), om, engine::currentSubject).registerAll(reg);
        return reg;
    }

    /**
     * H4 regression: under strictHandles the four previously-broken handle-ops
     * (pop_frame / force_return / single_step / write_local) are in HANDLE_OPS, so a
     * handle-LESS call is denied — but they must be SATISFIABLE when a handle is
     * supplied. Pre-fix these four re-resolved by name and (worse) advertised no
     * handle property; here we prove that supplying a frozen RWE handle now reaches
     * the handler (honest agent-absent error), i.e. the op is no longer deny-always.
     */
    @Test
    public void previouslyBrokenOpsAreSatisfiableWithHandleUnderStrictHandles() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            Object frozen = Thread.currentThread();
            ObManager om = new ObManager(ref -> frozen, 8, 60_000L, true); // strictHandles
            IoManager reg = wiredStrict(exec, om);

            SeToken s = SeToken.wideOpen();
            int rwe = ObAccessMask.mask(
                    ObAccessMask.READ, ObAccessMask.WRITE, ObAccessMask.EXECUTE);
            ObHandle h = om.open(s, ObRef.parse("thread:probe"), rwe);
            String hid = Long.toString(h.id());

            for (String tool : List.of(
                    "debug_pop_frame", "debug_force_return",
                    "debug_single_step", "debug_write_local")) {
                // Handle-less: strict posture DENIES at the L6 handle layer.
                var denied = reg.invoke(tool, Map.of("threadName", "probe",
                        "enabled", true, "slot", 0, "intValue", 0));
                assertTrue(tool + " handle-less must be L6-denied under strictHandles: "
                        + denied.content(), denied.content().toString().contains("L6 handle"));

                // With the frozen RWE handle: L6 allows, the call reaches the handler
                // (agent-absent), proving the op is satisfiable per its advertised schema.
                var withHandle = reg.invoke(tool, Map.of("handle", hid,
                        "enabled", true, "slot", 0, "intValue", 0));
                assertNotNull(withHandle);
                assertFalse(tool + " with a handle must NOT be L6-denied: " + withHandle.content(),
                        withHandle.content().toString().contains("L6 handle"));
                assertTrue(tool + " with a handle must reach the handler (agent-absent): "
                        + withHandle.content(),
                        withHandle.content().toString().contains("-agentpath:core-jvmti.dll"));
            }
        } finally {
            exec.shutdown();
        }
    }
}
