import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;

import net.marcloud.mcp.core.security.AccessDecision;
import net.marcloud.mcp.core.security.CapabilityCatalog;
import net.marcloud.mcp.core.security.CapabilitySid;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.IntegrityLevel;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.Privilege;
import net.marcloud.mcp.core.security.PrivilegeToken;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.SecurityContext;
import net.marcloud.mcp.core.security.ToolPolicy;
import net.marcloud.mcp.core.security.ToolRequest;
import org.junit.Test;

/**
 * The 7-layer kernel data model + the in-process composition. All headless,
 * pure logic. Layers: L2 ring, L3 integrity (MIC no-write-up), L4 privilege
 * (granted vs enabled), L5 capability SIDs (default-deny), AND-composed.
 */
public class SecurityKernelTest {

    // ---- L3 integrity ----

    @Test
    public void integrityNoWriteUp() {
        assertTrue("System writes High", IntegrityLevel.SYSTEM.canWriteTo(IntegrityLevel.HIGH));
        assertTrue("equal writes equal", IntegrityLevel.MEDIUM.canWriteTo(IntegrityLevel.MEDIUM));
        assertFalse("Medium cannot write High", IntegrityLevel.MEDIUM.canWriteTo(IntegrityLevel.HIGH));
        assertFalse("Low cannot write System", IntegrityLevel.LOW.canWriteTo(IntegrityLevel.SYSTEM));
        assertTrue("null target = no L3 gate", IntegrityLevel.LOW.canWriteTo(null));
    }

    // ---- L4 privilege two-state ----

    @Test
    public void privilegeGrantedVsEnabled() {
        PrivilegeToken t = new PrivilegeToken(Map.of(Privilege.SE_DEBUG_CLASS, false));
        assertTrue("granted", t.isGranted(Privilege.SE_DEBUG_CLASS));
        assertFalse("granted but not enabled", t.isEnabled(Privilege.SE_DEBUG_CLASS));
        assertTrue(t.enable(Privilege.SE_DEBUG_CLASS));
        assertTrue("now enabled", t.isEnabled(Privilege.SE_DEBUG_CLASS));
        assertFalse("cannot enable an ungranted privilege", t.enable(Privilege.SE_NET_RAW));
        assertFalse(t.isEnabled(Privilege.SE_NET_RAW));
    }

    @Test
    public void wideOpenTokenEnablesAll() {
        PrivilegeToken t = PrivilegeToken.wideOpen();
        for (Privilege p : Privilege.values()) {
            assertTrue(p.name(), t.isEnabled(p));
        }
    }

    // ---- L5 capability catalog ----

    @Test
    public void capabilityCatalogRequirements() {
        assertEquals(Set.of(CapabilitySid.CAP_CLASS_REDEFINE),
                CapabilityCatalog.requiredFor("redefine_class", true));
        assertEquals(Set.of(CapabilitySid.CAP_SCREEN_CAP),
                CapabilityCatalog.requiredFor("capture_screen", true));
        assertTrue("unlisted built-in requires nothing",
                CapabilityCatalog.requiredFor("list_capabilities", true).isEmpty());
        assertEquals("unlisted AI tool defaults to observe-tier",
                CapabilityCatalog.DEFAULT_GENERATED,
                CapabilityCatalog.requiredFor("some_ai_tool", false));
    }

    // ---- ToolPolicy safe defaults ----

    @Test
    public void toolPolicyUnlistedEnforcesOnlyRing() {
        ToolPolicy tp = ToolPolicy.forTool("recent_packets", true);
        assertEquals(Ring.R3, tp.requiredRing());
        assertEquals("no integrity write gate", null, tp.writesResourceAt());
        assertEquals("no privilege gate", null, tp.requiredPrivilege());
    }

    @Test
    public void toolPolicyRedefineIsFullyGated() {
        ToolPolicy tp = ToolPolicy.forTool("redefine_class", true);
        assertEquals(Ring.R_MINUS_1, tp.requiredRing());
        assertEquals(IntegrityLevel.HIGH, tp.writesResourceAt());
        assertEquals(Privilege.SE_DEBUG_CLASS, tp.requiredPrivilege());
        assertTrue(tp.requiredCaps().contains(CapabilitySid.CAP_CLASS_REDEFINE));
    }

    // ---- Composition: the in-process engine ----

    private static InProcessPolicyEngine engine(Ring clearance) {
        return new InProcessPolicyEngine(new PermissionPolicy(clearance, "tok"));
    }

    private static ToolRequest req(String name) {
        return new ToolRequest(name, Map.of(), true);
    }

    @Test
    public void wideOpenAllowsEverything() {
        InProcessPolicyEngine e = engine(Ring.R_MINUS_1);
        for (String tool : new String[]{"redefine_class", "eval_java", "create_tool",
                "send_raw_packet", "capture_screen", "recent_packets"}) {
            AccessDecision d = e.evaluate(e.currentSubject(), req(tool));
            assertTrue(tool + " should be allowed at R-1 wide open: " + d.reason(), d.allow());
        }
    }

    @Test
    public void loweredClearanceDeniesAtL2() {
        InProcessPolicyEngine e = engine(Ring.R_MINUS_1);
        e.dropTo(Ring.R2); // self-sandbox to observe-tier
        AccessDecision eval = e.evaluate(e.currentSubject(), req("eval_java"));
        assertFalse("eval_java denied at R2", eval.allow());
        assertEquals("L2 ring", eval.layer());
        AccessDecision scan = e.evaluate(e.currentSubject(), req("scan_surroundings"));
        assertTrue("scan still allowed at R2: " + scan.reason(), scan.allow());
    }

    @Test
    public void integrityDeniesWhenSubjectTooLow() {
        // A subject at MEDIUM integrity may not run redefine_class (writes HIGH),
        // even at R-1 clearance with all privileges/caps — L3 catches it.
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "tok");
        SecurityContext lowIntegrity = new SecurityContext("t", Ring.R_MINUS_1,
                IntegrityLevel.MEDIUM, PrivilegeToken.wideOpen(), null);
        InProcessPolicyEngine e = new InProcessPolicyEngine(p, lowIntegrity);
        AccessDecision d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("L3 integrity", d.layer());
    }

    @Test
    public void privilegeDeniesWhenDisabled() {
        // SE_DEBUG_CLASS granted but disabled → redefine_class denied at L4.
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "tok");
        PrivilegeToken tok = new PrivilegeToken(Map.of(
                Privilege.SE_DEBUG_CLASS, false, Privilege.SE_NET_RAW, true));
        SecurityContext subj = new SecurityContext("t", Ring.R_MINUS_1,
                IntegrityLevel.SYSTEM, tok, null);
        InProcessPolicyEngine e = new InProcessPolicyEngine(p, subj);
        AccessDecision d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("L4 privilege", d.layer());
    }

    @Test
    public void capabilityDeniesUnderStrictSet() {
        // Strict subject holding only CAP_WORLD_READ: capture_screen (needs
        // CAP_SCREEN_CAP) is denied at L5 even at R-1.
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "tok");
        SecurityContext strict = InProcessPolicyEngine.strictSubject(
                Set.of(CapabilitySid.CAP_WORLD_READ));
        InProcessPolicyEngine e = new InProcessPolicyEngine(p, strict);
        AccessDecision denied = e.evaluate(e.currentSubject(), req("capture_screen"));
        assertFalse(denied.allow());
        assertEquals("L5 capability", denied.layer());
        AccessDecision allowed = e.evaluate(e.currentSubject(), req("scan_surroundings"));
        assertTrue("world-read tool allowed under the held cap: " + allowed.reason(),
                allowed.allow());
    }

    @Test
    public void mutatingSeamToolsAreFullyGated() {
        // Regression for the audit finding: seam_netty_uninstall / seam_tick_disable
        // were missing from ToolPolicy L3/L4, so removing a pipeline handler or a
        // tick hook (which mutate a HIGH-integrity resource) skipped the integrity
        // and privilege gates. Every mutating seam tool must declare BOTH.
        for (String tool : new String[]{
                "seam_netty_install", "seam_netty_uninstall",
                "seam_glfw_key_hook", "seam_glfw_mouse_hook",
                "seam_tick_enable", "seam_tick_disable",
                "install_hook", "uninstall_hook",
                "write_field", "invoke_method", "open_module"}) {
            ToolPolicy tp = ToolPolicy.forTool(tool, true);
            assertTrue(tool + " must declare an L3 write integrity",
                    tp.writesResourceAt() != null);
            assertTrue(tool + " must declare an L4 privilege",
                    tp.requiredPrivilege() != null);
        }
    }

    @Test
    public void layersAreAndComposedShortCircuitInOrder() {
        // At R2 clearance, redefine_class fails L2 first (ring), not L3/L4 —
        // confirms ordering + short-circuit.
        InProcessPolicyEngine e = engine(Ring.R2);
        AccessDecision d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("first failing layer is L2", "L2 ring", d.layer());
    }
}
