import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;

import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.CapabilityCatalog;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.PrivilegeToken;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToken;
import net.marcloud.mcp.core.se.SeToolRequirement;
import net.marcloud.mcp.core.io.IoRequestPacket;
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

    // ---- SeToolRequirement safe defaults ----

    @Test
    public void toolPolicyUnlistedEnforcesOnlyRing() {
        SeToolRequirement tp = SeToolRequirement.forTool("recent_packets", true);
        assertEquals(Ring.R3, tp.requiredRing());
        assertEquals("no integrity write gate", null, tp.writesResourceAt());
        assertEquals("no privilege gate", null, tp.requiredPrivilege());
    }

    @Test
    public void toolPolicyRedefineIsFullyGated() {
        SeToolRequirement tp = SeToolRequirement.forTool("redefine_class", true);
        assertEquals(Ring.R_MINUS_1, tp.requiredRing());
        assertEquals(IntegrityLevel.HIGH, tp.writesResourceAt());
        assertEquals(Privilege.SE_DEBUG_CLASS, tp.requiredPrivilege());
        assertTrue(tp.requiredCaps().contains(CapabilitySid.CAP_CLASS_REDEFINE));
    }

    // ---- Composition: the in-process engine ----

    private static SeLocalMonitor engine(Ring clearance) {
        return new SeLocalMonitor(new SeClearancePolicy(clearance, "tok"));
    }

    private static IoRequestPacket req(String name) {
        return new IoRequestPacket(name, Map.of(), true);
    }

    @Test
    public void wideOpenAllowsEverything() {
        SeLocalMonitor e = engine(Ring.R_MINUS_1);
        for (String tool : new String[]{"redefine_class", "eval_java", "create_tool",
                "send_raw_packet", "capture_screen", "recent_packets"}) {
            SeAccessCheck d = e.evaluate(e.currentSubject(), req(tool));
            assertTrue(tool + " should be allowed at R-1 wide open: " + d.reason(), d.allow());
        }
    }

    @Test
    public void loweredClearanceDeniesAtL2() {
        SeLocalMonitor e = engine(Ring.R_MINUS_1);
        e.dropTo(Ring.R2); // self-sandbox to observe-tier
        SeAccessCheck eval = e.evaluate(e.currentSubject(), req("eval_java"));
        assertFalse("eval_java denied at R2", eval.allow());
        assertEquals("L2 ring", eval.layer());
        SeAccessCheck scan = e.evaluate(e.currentSubject(), req("scan_surroundings"));
        assertTrue("scan still allowed at R2: " + scan.reason(), scan.allow());
    }

    @Test
    public void integrityDeniesWhenSubjectTooLow() {
        // A subject at MEDIUM integrity may not run redefine_class (writes HIGH),
        // even at R-1 clearance with all privileges/caps — L3 catches it.
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        SeToken lowIntegrity = new SeToken("t", Ring.R_MINUS_1,
                IntegrityLevel.MEDIUM, PrivilegeToken.wideOpen(), null);
        SeLocalMonitor e = new SeLocalMonitor(p, lowIntegrity);
        SeAccessCheck d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("L3 integrity", d.layer());
    }

    @Test
    public void privilegeDeniesWhenDisabled() {
        // SE_DEBUG_CLASS granted but disabled → redefine_class denied at L4.
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        PrivilegeToken tok = new PrivilegeToken(Map.of(
                Privilege.SE_DEBUG_CLASS, false, Privilege.SE_NET_RAW, true));
        SeToken subj = new SeToken("t", Ring.R_MINUS_1,
                IntegrityLevel.SYSTEM, tok, null);
        SeLocalMonitor e = new SeLocalMonitor(p, subj);
        SeAccessCheck d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("L4 privilege", d.layer());
    }

    @Test
    public void capabilityDeniesUnderStrictSet() {
        // Strict subject holding only CAP_WORLD_READ: capture_screen (needs
        // CAP_SCREEN_CAP) is denied at L5 even at R-1.
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        SeToken strict = SeLocalMonitor.strictSubject(
                Set.of(CapabilitySid.CAP_WORLD_READ));
        SeLocalMonitor e = new SeLocalMonitor(p, strict);
        SeAccessCheck denied = e.evaluate(e.currentSubject(), req("capture_screen"));
        assertFalse(denied.allow());
        assertEquals("L5 capability", denied.layer());
        SeAccessCheck allowed = e.evaluate(e.currentSubject(), req("scan_surroundings"));
        assertTrue("world-read tool allowed under the held cap: " + allowed.reason(),
                allowed.allow());
    }

    @Test
    public void mutatingSeamToolsAreFullyGated() {
        // Regression for the audit finding: seam_netty_uninstall / seam_tick_disable
        // were missing from SeToolRequirement L3/L4, so removing a pipeline handler or a
        // tick hook (which mutate a HIGH-integrity resource) skipped the integrity
        // and privilege gates. Every mutating seam tool must declare BOTH.
        for (String tool : new String[]{
                "seam_netty_install", "seam_netty_uninstall",
                "seam_glfw_key_hook", "seam_glfw_mouse_hook",
                "seam_tick_enable", "seam_tick_disable",
                "install_hook", "uninstall_hook",
                "write_field", "invoke_method", "open_module"}) {
            SeToolRequirement tp = SeToolRequirement.forTool(tool, true);
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
        SeLocalMonitor e = engine(Ring.R2);
        SeAccessCheck d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("first failing layer is L2", "L2 ring", d.layer());
    }
}
