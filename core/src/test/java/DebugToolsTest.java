import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.kd.KdBridge;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToolRequirement;
import org.junit.Test;

/**
 * The C6 debug surface after the ADR-0004 fold: two manifest entries, eleven actions.
 *
 * <p>The previous version of this file asserted that nine separately-REGISTERED tools existed,
 * and iterated {@code TOOL_NAMES} calling each by its own name. Both assertions were about the
 * SHAPE of the surface, not its behaviour, and the fold exists precisely to change the shape --
 * so they were rewritten rather than re-pinned. What survives is the contract that made the
 * tools worth having: every action reports the missing agent honestly instead of succeeding
 * silently, an unusable call says WHICH action was wrong, and folding did not quietly lower a
 * gate.
 */
public class DebugToolsTest {

    private IoManager register() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
        new DebugTools(new AllowAllGate()).registerAll(reg);
        return reg;
    }

    /** Enough arguments to satisfy whichever per-action schema the handler checks first. */
    private Map<String, Object> argsFor(String action) {
        Map<String, Object> a = new HashMap<>();
        a.put("action", action);
        a.put("threadName", "main");
        a.put("slot", 0);
        a.put("intValue", 0);
        a.put("className", "java.lang.String");
        a.put("method", "length");
        a.put("signature", "()I");
        a.put("field", "value");
        a.put("enabled", true);
        return a;
    }

    @Test
    public void theFoldedToolsAreWhatGetsRegistered() {
        IoManager reg = register();
        assertNotNull("debug_manage must be registered", reg.get("debug_manage"));
        // Without the L6 handle layer the two handle actions are absent, so debug_handle is not
        // registered either -- and must NOT be, because offering an action-less tool is worse
        // than not offering the tool.
        assertEquals(null, reg.get("debug_handle"));
        // The unfolded names must NOT be manifest entries any more: that is the entire point.
        for (String concrete : DebugTools.MANAGE_ACTIONS) {
            assertEquals("unfolded " + concrete + " must not be a manifest entry",
                    null, reg.get(concrete));
        }
    }

    @Test
    public void everyManageActionReturnsAnHonestErrorWithoutTheNativeAgent() {
        // Precondition: no DLL in the headless suite.
        assertFalse(KdBridge.isAvailable());
        IoManager reg = register();
        for (String action : DebugTools.MANAGE_ACTIONS) {
            var r = reg.invoke("debug_manage", argsFor(action));
            assertNotNull(action + " must produce a result", r);
            assertTrue("action " + action + " must report isError when the agent is absent",
                    Boolean.TRUE.equals(r.isError()));
            assertTrue("action " + action + " error must name the missing agent",
                    r.content().toString().contains("-agentpath:core-jvmti.dll"));
        }
    }

    @Test
    public void aMissingActionIsRefusedByTheSchemaBeforeTheHandlerRuns() {
        IoManager reg = register();
        Map<String, Object> a = argsFor("debug_read_local");
        a.remove("action");
        var r = reg.invoke("debug_manage", a);
        assertNotNull(r);
        assertTrue("a call with no action must be refused",
                Boolean.TRUE.equals(r.isError()));
        // The SDK validates `required` from the input schema before the handler is entered, so
        // this never reaches the fold's own guard. That is the better outcome -- the refusal
        // names the missing parameter and the tool -- and the fold's guard stays as the
        // backstop for a server that does not validate.
        String text = r.content().toString();
        assertTrue("the refusal must name the missing parameter: " + text,
                text.contains("action"));
        assertTrue("the refusal must name the tool: " + text,
                text.contains("debug_manage"));
    }

    @Test
    public void anUnknownActionIsRefusedRatherThanGuessedAt() {
        IoManager reg = register();
        var r = reg.invoke("debug_manage", argsFor("debug_reformat_everything"));
        assertNotNull(r);
        assertTrue("an unknown action must be refused", Boolean.TRUE.equals(r.isError()));
        String text = r.content().toString();
        assertTrue("the refusal must quote the bad action: " + text,
                text.contains("debug_reformat_everything"));
        assertTrue("the refusal must list the valid ones: " + text,
                text.contains("debug_read_local") && text.contains("debug_suspend_thread"));
    }

    /**
     * The security regression this fold could most plausibly introduce.
     *
     * <p>Folding eleven names into two means eleven names stop appearing in the manifest. The
     * side tables are keyed BY NAME, and every one of them falls back to the permissive default
     * when a name is absent -- so deleting the concrete entries would have handed nine R-1 tools
     * to user clearance and stripped the two handles of their integrity level, with no test
     * failing. The gate has to be asked about the concrete names too.
     */
    @Test
    public void foldingDidNotLowerAnyGateOnTheConcreteNames() {
        List<String> handles = DebugTools.HANDLE_ACTIONS;
        for (String name : DebugTools.TOOL_NAMES) {
            assertEquals(name + " must stay R-1", Ring.R_MINUS_1, Ring.forBuiltin(name, Ring.R3));
            assertGated(name);
        }
        for (String name : handles) {
            // The handle family is R0, not R-1: it mints no thread control by itself.
            assertEquals(name + " must stay R0", Ring.R0, Ring.forBuiltin(name, Ring.R3));
            assertGated(name);
        }
    }

    /** Each folded entry must carry the strongest requirement of its cluster, no weaker. */
    @Test
    public void eachFoldedEntryCarriesItsWholeClustersGate() {
        SeToolRequirement manage = SeToolRequirement.forTool("debug_manage", true);
        assertEquals("debug_manage must declare the cluster's R-1",
                Ring.R_MINUS_1, manage.requiredRing());
        SeToolRequirement handle = SeToolRequirement.forTool("debug_handle", true);
        assertEquals("debug_handle must declare the cluster's R0", Ring.R0, handle.requiredRing());

        // And the four dimensions must agree across the cluster -- this is the invariant that
        // makes folding legal at all, since SeLocalMonitor never sees `arguments`.
        for (String name : DebugTools.MANAGE_ACTIONS) {
            SeToolRequirement concrete = SeToolRequirement.forTool(name, true);
            assertEquals(name + " L3 must match the cluster",
                    manage.writesResourceAt(), concrete.writesResourceAt());
            assertEquals(name + " L4 must match the cluster",
                    manage.requiredPrivilege(), concrete.requiredPrivilege());
            assertEquals(name + " L5 must match the cluster",
                    manage.requiredCaps(), concrete.requiredCaps());
        }
        for (String name : DebugTools.HANDLE_ACTIONS) {
            SeToolRequirement concrete = SeToolRequirement.forTool(name, true);
            assertEquals(name + " L3 must match the cluster",
                    handle.writesResourceAt(), concrete.writesResourceAt());
            assertEquals(name + " L4 must match the cluster",
                    handle.requiredPrivilege(), concrete.requiredPrivilege());
            assertEquals(name + " L5 must match the cluster",
                    handle.requiredCaps(), concrete.requiredCaps());
        }
    }

    private void assertGated(String name) {
        SeToolRequirement tp = SeToolRequirement.forTool(name, true);
        assertNotNull(name + " must declare L4 privilege", tp.requiredPrivilege());
        assertFalse(name + " must declare L5 caps", tp.requiredCaps().isEmpty());
        assertNotNull(name + " must declare L3 write integrity", tp.writesResourceAt());
    }
}