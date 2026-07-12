import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.PrivilegeToken;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToken;
import net.marcloud.mcp.core.io.IoRequestPacket;
import org.junit.Test;

/**
 * Honesty: the L4 "granted but disabled" deny message must point the operator at
 * the REAL in-session lever. {@code enable_privilege} is a live R0 built-in
 * (see {@link net.marcloud.mcp.core.se.PrivilegeControlTools#enablePrivilege})
 * that flips a granted-but-disabled privilege back on. An earlier message
 * misdirected the operator by claiming "no in-session tool can re-enable it;
 * relaunch..." — false remediation advice. The message must name
 * {@code enable_privilege}, must NOT deny that an in-session tool exists, and
 * must stay informative (name the privilege, say it is granted-but-disabled).
 *
 * <p>This is a message-content regression: it FAILS against the old text, which
 * read "(granted but disabled — no in-session tool can re-enable it; relaunch
 * with the subject configured to enable this privilege).".
 */
public class L4DenyMessageHonestyTest {

    /** A subject at full clearance/integrity whose SE_DEBUG_CLASS is granted but disabled. */
    private static SeAccessCheck l4Denial() {
        SeClearancePolicy p = new SeClearancePolicy(Ring.R_MINUS_1, "tok");
        // Granted (present in the token) but disabled (false) → forces the L4
        // "granted but disabled" branch specifically. SE_NET_RAW enabled so the
        // token is not empty.
        PrivilegeToken tok = new PrivilegeToken(Map.of(
                Privilege.SE_DEBUG_CLASS, false, Privilege.SE_NET_RAW, true));
        SeToken subj = new SeToken("t", Ring.R_MINUS_1,
                IntegrityLevel.SYSTEM, tok, null);
        SeLocalMonitor e = new SeLocalMonitor(p, subj);
        // redefine_class requires SE_DEBUG_CLASS enabled (L4); L2/L3/L5 all pass
        // for this SYSTEM/R-1/wildcard-cap subject, so L4 is the deciding layer.
        SeAccessCheck d = e.evaluate(e.currentSubject(), new IoRequestPacket("redefine_class", Map.of(), true));
        assertFalse("must be a denial", d.allow());
        assertTrue("must be denied at L4, got: " + d.layer(), d.layer().contains("L4"));
        return d;
    }

    @Test
    public void l4DisabledDenyPointsAtRealEnableTool() {
        String msg = l4Denial().reason();
        assertTrue("L4 deny must name the real in-session lever enable_privilege: " + msg,
                msg.contains("enable_privilege"));
        assertFalse("L4 deny must NOT claim no in-session tool can re-enable it: " + msg,
                msg.contains("no in-session tool can re-enable"));
    }

    @Test
    public void l4DisabledDenyStaysInformative() {
        String msg = l4Denial().reason();
        assertTrue("must name the privilege: " + msg, msg.contains("SE_DEBUG_CLASS"));
        assertTrue("must state granted-but-disabled: " + msg, msg.contains("granted but disabled"));
    }
}
