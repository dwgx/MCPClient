import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.debug.DebugTools;
import net.marcloud.mcp.core.debug.DebuggerBridge;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.registry.SafeToolExecutor;
import net.marcloud.mcp.core.security.AllowAllGate;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.ToolPolicy;
import org.junit.Test;

/**
 * C6 tools are NOT dead: all 9 register, are callable, and (without the native
 * DLL) return an honest isError naming the missing agent — never silent success,
 * never a crash. Also verifies the gate tables gate them at R-1 + SE_DEBUG_CONTROL.
 */
public class DebugToolsTest {

    private CapabilityRegistry register() {
        SafeToolExecutor exec = new SafeToolExecutor(4, 2000L);
        CapabilityRegistry reg = new CapabilityRegistry(exec,
                new InProcessPolicyEngine(new PermissionPolicy(Ring.R_MINUS_1, "tok")));
        new DebugTools(new AllowAllGate()).registerAll(reg);
        return reg;
    }

    @Test
    public void allNineToolsRegisterAndAreNotDead() {
        CapabilityRegistry reg = register();
        for (String name : DebugTools.TOOL_NAMES) {
            assertNotNull("tool " + name + " must be registered", reg.get(name));
        }
        assertEquals(9, DebugTools.TOOL_NAMES.size());
    }

    @Test
    public void withoutNativeAgentEveryToolReturnsHonestError() {
        // Precondition: no DLL in the headless suite.
        assertFalse(DebuggerBridge.isAvailable());
        CapabilityRegistry reg = register();
        String reason = DebuggerBridge.unavailableReason();
        for (String name : DebugTools.TOOL_NAMES) {
            var r = reg.invoke(name, Map.of("threadName", "main", "slot", 0, "intValue", 0,
                    "className", "java.lang.String", "method", "length",
                    "signature", "()I", "field", "value", "enabled", true));
            assertNotNull(r);
            assertTrue(name + " must report isError when the agent is absent",
                    Boolean.TRUE.equals(r.isError()));
            assertTrue(name + " error must name the missing agent",
                    r.content().toString().contains("-agentpath:core-jvmti.dll"));
        }
    }

    @Test
    public void debugToolsAreFullyGatedAtHypervisor() {
        for (String name : DebugTools.TOOL_NAMES) {
            assertEquals(name + " must be R-1", Ring.R_MINUS_1, Ring.forBuiltin(name, Ring.R3));
            ToolPolicy tp = ToolPolicy.forTool(name, true);
            assertNotNull(name + " must declare L4 privilege", tp.requiredPrivilege());
            assertFalse(name + " must declare L5 caps", tp.requiredCaps().isEmpty());
            assertNotNull(name + " must declare L3 write integrity", tp.writesResourceAt());
        }
    }
}
