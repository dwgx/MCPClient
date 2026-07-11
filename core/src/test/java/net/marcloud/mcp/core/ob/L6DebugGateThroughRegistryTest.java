package net.marcloud.mcp.core.ob;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
}
