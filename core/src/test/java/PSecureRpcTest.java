import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.secure.PSecureServer;
import net.marcloud.mcp.core.security.AccessDecision;
import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.RemotePolicyEngine;
import net.marcloud.mcp.core.security.Ring;
import net.marcloud.mcp.core.security.SecurityContext;
import net.marcloud.mcp.core.security.ToolRequest;
import org.junit.After;
import org.junit.Test;

/**
 * L1 VTL: the P-SECURE separate-process decision authority. Spins the server on
 * an ephemeral loopback port in-test and drives the real RemotePolicyEngine
 * client. Confirms round-trip allow/deny, the auth handshake, and — critically —
 * fail-closed behavior (unreachable → deny, never allow, never hang).
 */
public class PSecureRpcTest {

    private PSecureServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private PSecureServer startAuthority(Ring clearance, String token) throws Exception {
        InProcessPolicyEngine authority =
                new InProcessPolicyEngine(new PermissionPolicy(clearance, "restore"));
        PSecureServer s = new PSecureServer(authority, 0, token);
        s.start();
        assertTrue("bound to an ephemeral port", s.boundPort() > 0);
        return s;
    }

    @Test
    public void roundTripAllowAtWideOpen() throws Exception {
        server = startAuthority(Ring.R_MINUS_1, "secret");
        RemotePolicyEngine client = new RemotePolicyEngine(
                "127.0.0.1", server.boundPort(), "secret", 2000);
        AccessDecision d = client.evaluate(SecurityContext.wideOpen(),
                new ToolRequest("eval_java", Map.of(), true));
        assertTrue("R-1 authority allows eval_java: " + d.reason(), d.allow());
        client.close();
    }

    @Test
    public void roundTripDenyWhenAuthorityClearanceLow() throws Exception {
        // Authority sits at R2: an R-1 tool must be denied at the authority, and
        // the game JVM (client) cannot override that — it never states a subject.
        server = startAuthority(Ring.R2, "secret");
        RemotePolicyEngine client = new RemotePolicyEngine(
                "127.0.0.1", server.boundPort(), "secret", 2000);
        AccessDecision d = client.evaluate(SecurityContext.wideOpen(),
                new ToolRequest("eval_java", Map.of(), true));
        assertFalse("R-1 tool denied by an R2 authority", d.allow());
        // an observe-tier tool is still allowed
        assertTrue(client.evaluate(SecurityContext.wideOpen(),
                new ToolRequest("scan_surroundings", Map.of(), true)).allow());
        client.close();
    }

    @Test
    public void clearanceAndDropCrossTheWall() throws Exception {
        server = startAuthority(Ring.R_MINUS_1, "secret");
        RemotePolicyEngine client = new RemotePolicyEngine(
                "127.0.0.1", server.boundPort(), "secret", 2000);
        assertEquals(Ring.R_MINUS_1, client.clearance());
        client.dropTo(Ring.R2);
        assertEquals("drop reflected by the authority", Ring.R2, client.clearance());
        // after drop, an R-1 tool is denied through the wall
        assertFalse(client.evaluate(SecurityContext.wideOpen(),
                new ToolRequest("eval_java", Map.of(), true)).allow());
        client.close();
    }

    @Test
    public void wrongAuthTokenFailsClosed() throws Exception {
        server = startAuthority(Ring.R_MINUS_1, "the-real-secret");
        RemotePolicyEngine client = new RemotePolicyEngine(
                "127.0.0.1", server.boundPort(), "WRONG", 1500);
        // Handshake is rejected → no decision channel → fail-closed deny.
        AccessDecision d = client.evaluate(SecurityContext.wideOpen(),
                new ToolRequest("eval_java", Map.of(), true));
        assertFalse("bad auth → deny", d.allow());
        assertEquals("L1 VTL", d.layer());
        client.close();
    }

    @Test
    public void unreachableProcessFailsClosedFast() {
        // No server on this port. Client must DENY within ~timeout, never allow,
        // never hang.
        RemotePolicyEngine client = new RemotePolicyEngine(
                "127.0.0.1", 65533, "secret", 1000);
        long start = System.nanoTime();
        AccessDecision d = client.evaluate(SecurityContext.wideOpen(),
                new ToolRequest("eval_java", Map.of(), true));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse("unreachable → deny", d.allow());
        assertEquals("L1 VTL", d.layer());
        assertTrue("failed closed within ~2x timeout (was " + elapsedMs + "ms)", elapsedMs < 2500);
        // restorable also fails closed
        assertFalse(client.restorable());
        client.close();
    }
}
