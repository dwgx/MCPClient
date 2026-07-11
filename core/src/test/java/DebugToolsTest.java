import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
 * C6 tools are NOT dead: all 9 register, are callable, and (without the native
 * DLL) return an honest isError naming the missing agent — never silent success,
 * never a crash. Also verifies the gate tables gate them at R-1 + SE_DEBUG_CONTROL.
 */
public class DebugToolsTest {

    private IoManager register() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
        new DebugTools(new AllowAllGate()).registerAll(reg);
        return reg;
    }

    @Test
    public void allNineToolsRegisterAndAreNotDead() {
        IoManager reg = register();
        for (String name : DebugTools.TOOL_NAMES) {
            assertNotNull("tool " + name + " must be registered", reg.get(name));
        }
        assertEquals(9, DebugTools.TOOL_NAMES.size());
    }

    @Test
    public void withoutNativeAgentEveryToolReturnsHonestError() {
        // Precondition: no DLL in the headless suite.
        assertFalse(KdBridge.isAvailable());
        IoManager reg = register();
        String reason = KdBridge.unavailableReason();
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
            SeToolRequirement tp = SeToolRequirement.forTool(name, true);
            assertNotNull(name + " must declare L4 privilege", tp.requiredPrivilege());
            assertFalse(name + " must declare L5 caps", tp.requiredCaps().isEmpty());
            assertNotNull(name + " must declare L3 write integrity", tp.writesResourceAt());
        }
    }
}
