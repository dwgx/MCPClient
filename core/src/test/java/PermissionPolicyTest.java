import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Ring;
import org.junit.Test;

/** Tests the CPU-ring privilege model: gating, self-drop, token-gated restore. */
public class PermissionPolicyTest {

    @Test
    public void clearanceGatesByRingLevel() {
        SeClearancePolicy p = new SeClearancePolicy(Ring.R2, "tok");
        // R2 clearance permits R2 and R3, denies the more-privileged rings.
        assertFalse("R-1 denied at R2 clearance", p.allows(Ring.R_MINUS_1));
        assertFalse(p.allows(Ring.R0));
        assertFalse(p.allows(Ring.R1));
        assertTrue(p.allows(Ring.R2));
        assertTrue(p.allows(Ring.R3));
    }

    @Test
    public void wideOpenPermitsEverything() {
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        for (Ring r : Ring.values()) {
            assertTrue(r.tag() + " allowed at R-1", p.allows(r));
        }
    }

    @Test
    public void dropOnlyLowersNeverRaises() {
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        assertEquals(Ring.R2, p.dropTo(Ring.R2));
        assertFalse(p.allows(Ring.R1));
        // A "drop" to a MORE privileged ring is ignored.
        assertEquals("drop cannot raise", Ring.R2, p.dropTo(Ring.R_MINUS_1));
        assertFalse(p.allows(Ring.R1));
    }

    @Test
    public void restoreRequiresCorrectToken() {
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "secret");
        p.dropTo(Ring.R3);
        assertFalse("wrong token denied", p.tryRestore(Ring.R_MINUS_1, "nope"));
        assertEquals(Ring.R3, p.clearance());
        assertTrue("correct token restores", p.tryRestore(Ring.R_MINUS_1, "secret"));
        assertEquals(Ring.R_MINUS_1, p.clearance());
    }

    @Test
    public void restoreDisabledWhenNoToken() {
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, null);
        p.dropTo(Ring.R3);
        assertFalse(p.restorable());
        assertFalse(p.tryRestore(Ring.R_MINUS_1, "anything"));
        assertEquals("permanent drop", Ring.R3, p.clearance());
    }

    @Test
    public void builtinRingTableMapsDangerousToolsLow() {
        assertEquals(Ring.R_MINUS_1, Ring.forBuiltin("eval_java", Ring.R3));
        assertEquals(Ring.R_MINUS_1, Ring.forBuiltin("redefine_class", Ring.R3));
        assertEquals(Ring.R0, Ring.forBuiltin("create_tool", Ring.R3));
        assertEquals(Ring.R1, Ring.forBuiltin("send_raw_packet", Ring.R3));
        assertEquals(Ring.R2, Ring.forBuiltin("capture_screen", Ring.R3));
        assertEquals(Ring.R3, Ring.forBuiltin("memory_write", Ring.R3));
        assertEquals("unknown falls back", Ring.R3, Ring.forBuiltin("nope_tool", Ring.R3));
    }
}
