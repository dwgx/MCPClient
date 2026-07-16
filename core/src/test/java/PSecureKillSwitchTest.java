import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeRemoteMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.After;
import org.junit.Test;

/**
 * HEADLESS (loopback sockets only). Proves the cross-wall <b>kill switch</b> works:
 * a game JVM (SeRemoteMonitor) can DISABLE an L4 privilege / REVOKE an L5 capability
 * in the authority over the wall so a dangerous verb it was previously permitted is
 * afterwards DENIED — and, crucially, that the switch only TIGHTENS: there is no
 * enable/grant RPC, so a compromised game process cannot re-open what the authority
 * shut. Mirrors {@link PSecureRpcTest}'s server setup (ephemeral port, @After close).
 *
 * <p>Non-vacuous + FAILS ON OLD CODE: before this fix {@code SeRemoteMonitor} did
 * not override {@code disablePrivilege}/{@code revokeCapability}, so they inherited
 * the interface's {@code false} no-op — the disable claim below would fail (returns
 * false) and the subsequent send_chat would still be ALLOWED, blowing the L4-deny
 * assertion. There was also no {@code M_DISABLE_PRIV}/{@code M_REVOKE_CAP} RPC on
 * the server, so the authority never narrowed. This is the P-SECURE posture-split
 * high-severity fix (game hardened + authority wide-open ⇒ kill switch could not
 * bite across the wall).
 */
public class PSecureKillSwitchTest {

    private AlpcServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private SeRemoteMonitor connect(String token) {
        return new SeRemoteMonitor("127.0.0.1", server.boundPort(), token, 2000);
    }

    private void startWideOpenAuthority(String token) throws Exception {
        // Wide-open authority: R-1 clearance, all L4 privileges enabled, wildcard L5.
        // This is exactly the shipped default the vulnerability report flags — the
        // authority permits everything until the game JVM narrows it over the wall.
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        server = new AlpcServer(authority, 0, token);
        server.start();
        assertTrue("bound to an ephemeral port", server.boundPort() > 0);
    }

    /**
     * The core regression: send_chat (R1 / SE_NET_RAW at L4) is ALLOWED at the
     * wide-open authority; the game JVM disables SE_NET_RAW across the wall; the
     * SAME send_chat is then DENIED at "L4 privilege". Proves the kill switch
     * crosses the wall and actually bites.
     */
    @Test
    public void disablePrivilegeCrossesTheWallAndDeniesTheSendSurface() throws Exception {
        startWideOpenAuthority("secret");
        SeRemoteMonitor client = connect("secret");

        // Precondition: the send surface is open at the wide-open authority.
        SeAccessCheck before = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("send_chat", Map.of(), true));
        assertTrue("send_chat ALLOWED before the kill switch: " + before.reason(),
                before.allow());

        // Kill switch across the wall: this is the no-op on OLD code.
        assertTrue("authority reports SE_NET_RAW disabled across the wall",
                client.disablePrivilege(Privilege.SE_NET_RAW));

        // Now the whole SE_NET_RAW send surface is shut at L4.
        SeAccessCheck after = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("send_chat", Map.of(), true));
        assertFalse("send_chat DENIED after disable_privilege(SE_NET_RAW)", after.allow());
        assertEquals("denied at the privilege layer", "L4 privilege", after.layer());

        client.close();
    }

    /**
     * revoke_capability likewise crosses the wall: recent_packets (CAP_NETWORK_RECV_TAP
     * at L5) is allowed at the wildcard authority, then denied at "L5 capability" once
     * the tap capability is revoked over the wall.
     */
    @Test
    public void revokeCapabilityCrossesTheWallAndDeniesAtL5() throws Exception {
        startWideOpenAuthority("secret");
        SeRemoteMonitor client = connect("secret");

        SeAccessCheck before = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("recent_packets", Map.of(), true));
        assertTrue("recent_packets ALLOWED before revoke: " + before.reason(), before.allow());

        assertTrue("authority reports CAP_NETWORK_RECV_TAP revoked across the wall",
                client.revokeCapability(CapabilitySid.CAP_NETWORK_RECV_TAP));

        SeAccessCheck after = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("recent_packets", Map.of(), true));
        assertFalse("recent_packets DENIED after revoke_capability", after.allow());
        assertEquals("denied at the capability layer", "L5 capability", after.layer());

        client.close();
    }

    /**
     * Tighten-only invariant: enablePrivilege / grantCapability keep the inherited
     * false no-op on the remote monitor (there is deliberately no enable/grant RPC),
     * so a game process cannot re-open a verb. Disabling first, then trying to
     * re-enable across the wall, leaves send_chat DENIED.
     */
    @Test
    public void noReEnableAcrossTheWall() throws Exception {
        startWideOpenAuthority("secret");
        SeRemoteMonitor client = connect("secret");

        assertTrue(client.disablePrivilege(Privilege.SE_NET_RAW));
        // enablePrivilege is the inherited false no-op — never sends an RPC.
        assertFalse("no self-escalation across the wall",
                client.enablePrivilege(Privilege.SE_NET_RAW));

        SeAccessCheck still = client.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("send_chat", Map.of(), true));
        assertFalse("send_chat STAYS denied — the game process cannot re-open it",
                still.allow());
        assertEquals("L4 privilege", still.layer());

        client.close();
    }

    /**
     * Fail-closed: after the authority dies, disable/revoke return false (they did
     * not narrow anything — no phantom success), and posture() returns null rather
     * than a stale string.
     */
    @Test
    public void killSwitchFailsClosedWhenAuthorityGone() throws Exception {
        startWideOpenAuthority("secret");
        SeRemoteMonitor client = connect("secret");
        // wide-open authority reports its posture over the wall
        assertEquals("wide-open", client.posture());

        server.close();
        assertFalse("disable fails closed once authority is gone",
                client.disablePrivilege(Privilege.SE_NET_RAW));
        assertFalse("revoke fails closed once authority is gone",
                client.revokeCapability(CapabilitySid.CAP_NETWORK_RECV_TAP));
        assertEquals("posture null when unreachable", null, client.posture());

        client.close();
    }
}
