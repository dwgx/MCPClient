import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.security.AccessDecision;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.IntegrityLevel;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.Privilege;
import net.marcloud.mcp.core.security.PrivilegeToken;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.SecurityContext;
import net.marcloud.mcp.core.security.ToolRequest;
import org.junit.Test;

/**
 * GAP-5 (honesty): the L4 "granted but disabled" deny message must NOT tell an
 * operator to run {@code enable_privilege} — no such tool is wired anywhere in
 * the project (only drop_privilege / restore_privilege exist, and those drive L2
 * clearance, not L4). Pointing at a nonexistent tool is impossible remediation
 * advice. The message must still be informative (name the privilege, say it is
 * granted-but-disabled, and give a real remediation).
 *
 * <p>This is a message-content regression: it FAILS against the old text, which
 * literally read "(granted but disabled — enable_privilege first).".
 */
public class L4DenyMessageHonestyTest {

    /** A subject at full clearance/integrity whose SE_DEBUG_CLASS is granted but disabled. */
    private static AccessDecision l4Denial() {
        PermissionPolicy p = new PermissionPolicy(Ring.R_MINUS_1, "tok");
        // Granted (present in the token) but disabled (false) → forces the L4
        // "granted but disabled" branch specifically. SE_NET_RAW enabled so the
        // token is not empty.
        PrivilegeToken tok = new PrivilegeToken(Map.of(
                Privilege.SE_DEBUG_CLASS, false, Privilege.SE_NET_RAW, true));
        SecurityContext subj = new SecurityContext("t", Ring.R_MINUS_1,
                IntegrityLevel.SYSTEM, tok, null);
        InProcessPolicyEngine e = new InProcessPolicyEngine(p, subj);
        // redefine_class requires SE_DEBUG_CLASS enabled (L4); L2/L3/L5 all pass
        // for this SYSTEM/R-1/wildcard-cap subject, so L4 is the deciding layer.
        AccessDecision d = e.evaluate(e.currentSubject(), new ToolRequest("redefine_class", Map.of(), true));
        assertFalse("must be a denial", d.allow());
        assertTrue("must be denied at L4, got: " + d.layer(), d.layer().contains("L4"));
        return d;
    }

    @Test
    public void l4DisabledDenyDoesNotCiteNonexistentEnableTool() {
        String msg = l4Denial().reason();
        assertFalse("L4 deny must not point at a nonexistent enable_privilege tool: " + msg,
                msg.contains("enable_privilege"));
        assertFalse("L4 deny must not point at a nonexistent disable_privilege tool: " + msg,
                msg.contains("disable_privilege"));
    }

    @Test
    public void l4DisabledDenyStaysInformative() {
        String msg = l4Denial().reason();
        assertTrue("must name the privilege: " + msg, msg.contains("SE_DEBUG_CLASS"));
        assertTrue("must state granted-but-disabled: " + msg, msg.contains("granted but disabled"));
    }
}
