import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.alpc.AlpcMain;
import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeRemoteMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * HEADLESS (loopback sockets only). Proves the opt-in hardened posture BITES in
 * the L1-VTL P-SECURE authority: a dangerous verb is denied at L4/L5 IN THE
 * AUTHORITY (both directly and across the RPC wall), a benign R3/no-capability
 * tool is allowed, and the wall still fails closed ("L1 VTL") when the authority
 * dies. Mirrors {@link PSecureRpcTest}'s structure (ephemeral port, @After close).
 *
 * <p>Non-vacuous + FAILS ON OLD CODE: the shipped {@code AlpcMain} always built a
 * wide-open authority ({@code new SeLocalMonitor(new SeClearancePolicy(...))}), so
 * eval_java would be ALLOWED both directly and across the wall — failing every
 * deny assertion here. {@code AlpcMain.buildAuthority} is the new seam this test
 * drives (it does not exist on the old code, so this also will not compile against
 * it — the stronger regression signal).
 */
public class PSecureHardenedDenyTest {

    private static final String HARDENED = "mcp.core.hardened";

    private AlpcServer server;
    private String savedHardened;
    private String savedCaps;

    @Before
    public void saveProps() {
        savedHardened = System.getProperty(HARDENED);
        savedCaps = System.getProperty("mcp.core.caps");
        System.clearProperty("mcp.core.caps");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
        restore(HARDENED, savedHardened);
        restore("mcp.core.caps", savedCaps);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Build a hardened authority via the production seam (flag on only during the build). */
    private SeReferenceMonitor buildHardenedAuthority() {
        System.setProperty(HARDENED, "true");
        try {
            return AlpcMain.buildAuthority(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        } finally {
            System.clearProperty(HARDENED);
        }
    }

    /**
     * Direct: the hardened authority denies eval_java at L4 (privilege
     * granted-but-disabled) and allows a benign R3/no-capability tool
     * (list_permissions). Evaluated against the authority's OWN subject, exactly as
     * the P-SECURE server does.
     */
    @Test
    public void hardenedAuthorityDeniesEvalDirectlyAllowsBenign() {
        SeReferenceMonitor authority = buildHardenedAuthority();

        SeAccessCheck evalDecision = authority.evaluate(authority.currentSubject(),
                new IoRequestPacket("eval_java", Map.of(), true));
        assertFalse("eval_java denied by hardened authority: " + evalDecision.reason(),
                evalDecision.allow());
        assertEquals("denied at the privilege layer", "L4 privilege", evalDecision.layer());

        SeAccessCheck benign = authority.evaluate(authority.currentSubject(),
                new IoRequestPacket("list_permissions", Map.of(), true));
        assertTrue("benign R3 no-capability tool allowed: " + benign.reason(), benign.allow());
    }

    /**
     * Across the wall: a game JVM (SeRemoteMonitor) never states a subject — the
     * hardened authority in the separate process decides. eval_java is denied at
     * L4, recent_packets at L5, list_permissions is allowed, and after the
     * authority dies every decision fails closed at "L1 VTL".
     */
    @Test
    public void hardenedDenyAndFailClosedCrossTheWall() throws Exception {
        SeReferenceMonitor authority = buildHardenedAuthority();
        server = new AlpcServer(authority, 0, "secret");
        server.start();
        assertTrue("bound to an ephemeral port", server.boundPort() > 0);

        SeRemoteMonitor client = new SeRemoteMonitor(
                "127.0.0.1", server.boundPort(), "secret", 2000);

        SeAccessCheck evalDecision = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("eval_java", Map.of(), true));
        assertFalse("eval_java denied across the wall", evalDecision.allow());
        assertEquals("L4 privilege", evalDecision.layer());

        SeAccessCheck packets = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("recent_packets", Map.of(), true));
        assertFalse("recent_packets denied across the wall", packets.allow());
        assertEquals("L5 capability", packets.layer());

        SeAccessCheck benign = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("list_permissions", Map.of(), true));
        assertTrue("benign R3 no-capability tool allowed across the wall: " + benign.reason(),
                benign.allow());

        // Authority dies → the wall must fail closed (deny, layer L1 VTL), never
        // fall back to a phantom allow.
        server.close();
        SeAccessCheck afterDeath = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("list_permissions", Map.of(), true));
        assertFalse("fail-closed deny once the authority is gone", afterDeath.allow());
        assertEquals("L1 VTL", afterDeath.layer());
        client.close();
    }
}
