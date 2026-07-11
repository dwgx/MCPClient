import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeRemoteMonitor;
import net.marcloud.mcp.core.se.Ring;
import org.junit.After;
import org.junit.Test;

/**
 * GAP-1 regression: in L1 VTL mode a {@code drop_privilege} issued while the
 * P-SECURE authority is briefly unreachable must FAIL CLOSED — it must never
 * report a phantom lowered clearance.
 *
 * <p><b>Why this fails against the OLD behavior.</b> The previous {@code dropTo}
 * treated "remote down" as "safe to apply locally": it lowered {@code
 * cachedClearance} and RETURNED the lowered ring, so {@link
 * net.marcloud.mcp.core.se.PermissionTools} rendered "clearance is now R2".
 * But {@code evaluate()} in remote mode always asks the authority and never
 * consults that cache, so the authority stayed at R-1 — when connectivity
 * returned, R-1 tools were allowed again and the kill-switch had silently done
 * nothing. This test primes the client cache to R-1, kills the authority, and
 * calls {@code dropTo(R2)}: the old code returned {@code R2} with no error
 * (phantom success) and would fall through to {@code fail(...)} here; the fixed
 * code throws {@link SeRemoteMonitor.AuthorityUnreachableException}.
 */
public class RemotePolicyEngineDropFailClosedTest {

    private AlpcServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private AlpcServer startAuthority(Ring clearance, String token) throws Exception {
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(clearance, "restore"));
        AlpcServer s = new AlpcServer(authority, 0, token);
        s.start();
        assertTrue("bound to an ephemeral port", s.boundPort() > 0);
        return s;
    }

    @Test
    public void dropWhileAuthorityUnreachableFailsClosedNotPhantomSuccess() throws Exception {
        // 1. Live authority at R-1 (wide open); prime the client cache to R-1 so a
        //    later drop to R2 would look like a genuine reduction on the OLD path.
        server = startAuthority(Ring.R_MINUS_1, "secret");
        SeRemoteMonitor client = new SeRemoteMonitor(
                "127.0.0.1", server.boundPort(), "secret", 2000);
        assertEquals("cache primed from the authority", Ring.R_MINUS_1, client.clearance());

        // 2. P-SECURE process blips out (authority unreachable).
        server.close();
        server = null;

        // 3. Operator hits the kill-switch: drop to R2. It CANNOT reach the
        //    authority, so it must NOT report a successful lowered clearance.
        try {
            Ring reported = client.dropTo(Ring.R2);
            fail("phantom success: dropTo reported clearance " + reported.tag()
                    + " while the authority was unreachable and unchanged");
        } catch (SeRemoteMonitor.AuthorityUnreachableException expected) {
            assertTrue("failure message must say the drop did not take effect",
                    expected.getMessage().contains("FAILED"));
        }

        // 4. The failed drop must not have leaked a lowered value into the cache:
        //    the fail-closed clearance readback is still the primed R-1, NOT R2.
        assertEquals("drop must not silently lower the cached clearance",
                Ring.R_MINUS_1, client.clearance());
        assertFalse("no phantom drop to R2", client.clearance() == Ring.R2);

        client.close();
    }
}
