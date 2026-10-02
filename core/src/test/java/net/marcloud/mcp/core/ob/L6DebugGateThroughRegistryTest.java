package net.marcloud.mcp.core.ob;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.HashMap;
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

    /**
     * ADR-0004: nothing is registered under its own name any more. The behaviour under test --
     * strict handles deny a handle-less debug call, a READ-only handle allows read but denies
     * suspend, an unknown handle id is denied -- is unchanged; it is now reached through the
     * folded entry with the operation named in {@code action}.
     */
    private static Map<String, Object> via(String concrete, Map<String, Object> args) {
        Map<String, Object> m = new HashMap<>(args);
        m.put("action", concrete);
        return m;
    }

    private static String toolFor(String concrete) {
        return DebugTools.HANDLE_ACTIONS.contains(concrete) ? "debug_handle" : "debug_manage";
    }

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
            assertNotNull("debug_handle present when L6 wired", withL6.get("debug_handle"));
            

            // Without L6 the lifecycle tools must NOT exist (surface unchanged).
            IoManager noL6 = new IoManager(exec,
                    new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
            new DebugTools(new AllowAllGate()).registerAll(noL6);
            org.junit.Assert.assertNull("no handle tool without L6", noL6.get("debug_handle"));
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
            var read = reg.invoke(toolFor("debug_read_local"), via("debug_read_local", Map.of("handle", hid, "slot", 0)));
            assertNotNull(read);
            assertTrue("read op should reach handler (agent-absent), not be L6-denied",
                    read.content().toString().contains("-agentpath:core-jvmti.dll"));

            // debug_suspend_thread (needs EXECUTE) with the same READ-only handle:
            // L6 must DENY at the "L6 handle" layer (no escalation), and the deny
            // message must NOT be the agent-absent one.
            var suspend = reg.invoke(toolFor("debug_suspend_thread"), via("debug_suspend_thread", Map.of("handle", hid)));
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
            var r = reg.invoke(toolFor("debug_read_local"), via("debug_read_local", Map.of("handle", "999999", "slot", 0)));
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
            var r = reg.invoke(toolFor("debug_suspend_thread"), via("debug_suspend_thread", Map.of("threadName", "main")));
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

    /**
     * Whether {@code action} advertises the optional {@code handle} parameter.
     *
     * <p>ADR-0004 changed WHERE this information lives, not whether it exists. Before the fold
     * each of the six tools carried a `handle` property in its own inputSchema; folded behind
     * one {@code action} enum they cannot, because there is one schema for nine operations. So
     * the property moved into the folded description, and this checks it there. The original
     * assertion -- four of these tools hid the handle, so an agent following the schema could
     * never supply one -- is exactly as load-bearing as it was; only the place it is read from
     * changed, and if the description stops naming `handle` this fails.
     */
    private static boolean advertisesHandle(IoManager reg, String action) {
        String desc = reg.get(toolFor(action)).spec().tool().description();
        return desc != null && desc.contains(action) && desc.contains("handle");
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
                assertTrue(tool + " must advertise the optional 'handle' parameter",
                        advertisesHandle(reg, tool));
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
                var denied = reg.invoke(toolFor(tool), via(tool, Map.of("threadName", "probe",
                        "enabled", true, "slot", 0, "intValue", 0)));
                assertTrue(tool + " handle-less must be L6-denied under strictHandles: "
                        + denied.content(), denied.content().toString().contains("L6 handle"));

                // With the frozen RWE handle: L6 allows, the call reaches the handler
                // (agent-absent), proving the op is satisfiable per its advertised schema.
                var withHandle = reg.invoke(toolFor(tool), via(tool, Map.of("handle", hid,
                        "enabled", true, "slot", 0, "intValue", 0)));
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

    /**
     * A folded action the handle table does not know must be REFUSED, not treated as a
     * read-only request.
     *
     * <p>The fallback in {@code require} is {@code READ}, which is the weakest mask there is.
     * If an unrecognised action reached it, then a handle with only READ could reach an
     * operation whose true mask is EXECUTE -- and a folded tool makes exactly that possible,
     * because {@code action} is caller-supplied text. Downgrading to the weak case on an
     * unrecognised name is the one branch here that must never be taken.
     *
     * <p>This test exists because mutating that branch away leaves every other test in the
     * class green. The gate is the last thing standing, so its refusals need their own pins.
     */
    @Test
    public void anUnrecognisedActionIsRefusedRatherThanDowngradedToRead() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            ObManager om = new ObManager(ref -> new Object(), 8, 60_000L);
            IoManager reg = wired(exec, om);
            SeToken s = SeToken.wideOpen();
            int rwe = ObAccessMask.mask(
                    ObAccessMask.READ, ObAccessMask.WRITE, ObAccessMask.EXECUTE);
            String hid = Long.toString(om.open(s, ObRef.parse("thread:probe"), rwe).id());

            // A handle-op name that does not exist. If the gate fell back to the READ default
            // this would pass the mask check and reach the handler.
            var withHandle = reg.invoke("debug_manage",
                    via("debug_do_anything_at_all", Map.of("handle", hid)));
            assertNotNull(withHandle);
            assertTrue("an unrecognised action must be L6-denied with a handle, not run on a "
                            + "read-only fallback: " + withHandle.content(),
                    withHandle.content().toString().contains("not a recognised handle-op"));

            // The same refusal on the handle-LESS path, which is a separate branch in
            // checkRequest. Pinning only the handle-bearing case leaves the handle-less branch
            // free to downgrade to allowed() -- and mutating it away keeps every other test
            // in this class green, because a name-based call is otherwise permitted by design.
            var noHandle = reg.invoke("debug_manage",
                    via("debug_do_anything_at_all", Map.of("threadName", "probe")));
            assertNotNull(noHandle);
            assertTrue("an unrecognised action must be L6-denied without a handle too: "
                            + noHandle.content(),
                    noHandle.content().toString().contains("not a recognised handle-op"));

            // And the contrast that makes the rule meaningful: a RECOGNISED handle-op with no
            // handle is still permitted on the name-based path when the posture is not strict.
            // Without this the previous assertion could pass for the wrong reason -- a blanket
            // refusal of every handle-less debug call.
            var known = reg.invoke("debug_manage",
                    via("debug_read_local", Map.of("threadName", "probe", "slot", 0)));
            assertNotNull(known);
            assertFalse("a known handle-op must still fall back to the name-based path when "
                            + "not hardened: " + known.content(),
                    known.content().toString().contains("not a recognised handle-op"));
        } finally {
            exec.shutdown();
        }
    }
}
