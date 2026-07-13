package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.List;
import java.util.Set;

import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.alpc.CompatCandidate;
import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.alpc.TicketCompatAuthority;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import org.junit.After;
import org.junit.Test;

/**
 * Headless socket tests for the compat ALPC channel (v2 short-TTL Ed25519 tickets).
 * Pattern matches {@code PSecureRpcTest}: ephemeral AlpcServer + real client.
 */
public class CompatAuthorityRpcTest {

    private AlpcServer server;
    private TicketCompatAuthority tickets;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private CompatAuthorityClient startPair(String token) throws Exception {
        KeyPair id = CompatCrypto.generateEd25519();
        tickets = new TicketCompatAuthority(id);
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        server = new AlpcServer(authority, tickets, 0, token);
        server.start();
        assertTrue(server.boundPort() > 0);
        return new CompatAuthorityClient(
                "127.0.0.1", server.boundPort(), token, 2000, tickets.identityPublicKey());
    }

    @Test
    public void handshakeAndTicketRoundTrip() throws Exception {
        CompatAuthorityClient client = startPair("secret");
        tickets.allow("cp-A", "hash-A");
        assertTrue(client.handshake());
        Set<String> ok = client.authorize(List.of(new CompatCandidate("cp-A", "hash-A")));
        assertEquals(Set.of("cp-A"), ok);
        client.close();
    }

    @Test
    public void deListMeansNoTicket() throws Exception {
        CompatAuthorityClient client = startPair("secret");
        tickets.allow("cp-A", "hash-A");
        tickets.allow("cp-B", "hash-B");
        assertTrue(client.handshake());
        assertEquals(
                Set.of("cp-A", "cp-B"),
                client.authorize(List.of(
                        new CompatCandidate("cp-A", "hash-A"),
                        new CompatCandidate("cp-B", "hash-B"))));
        tickets.deList("cp-B", "hash-B");
        // fresh session for a second authorize
        client.close();
        client = new CompatAuthorityClient(
                "127.0.0.1", server.boundPort(), "secret", 2000, tickets.identityPublicKey());
        assertTrue(client.handshake());
        assertEquals(
                Set.of("cp-A"),
                client.authorize(List.of(
                        new CompatCandidate("cp-A", "hash-A"),
                        new CompatCandidate("cp-B", "hash-B"))));
        client.close();
    }

    @Test
    public void unreachableFailsClosedFast() {
        KeyPair id = CompatCrypto.generateEd25519();
        CompatAuthorityClient client =
                new CompatAuthorityClient("127.0.0.1", 65533, "secret", 800, id.getPublic());
        long start = System.nanoTime();
        assertFalse(client.handshake());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("elapsed " + elapsedMs, elapsedMs < 2500);
        assertTrue(client.authorize(List.of(new CompatCandidate("cp-X", "h"))).isEmpty());
        client.close();
    }

    @Test
    public void wrongAuthTokenFailsClosed() throws Exception {
        CompatAuthorityClient client = startPair("the-real-secret");
        CompatAuthorityClient wrong = new CompatAuthorityClient(
                "127.0.0.1", server.boundPort(), "WRONG", 1500, tickets.identityPublicKey());
        assertFalse(wrong.handshake());
        wrong.close();
        client.close();
    }

    @Test
    public void contentHashMismatchNotAuthorized() throws Exception {
        CompatAuthorityClient client = startPair("secret");
        tickets.allow("cp-A", "hash-A");
        assertTrue(client.handshake());
        // Client asks for same id but different hash — authority allowlist is bound to hash.
        assertTrue(client.authorize(List.of(new CompatCandidate("cp-A", "hash-TAMPERED"))).isEmpty());
        client.close();
    }

    @Test
    public void staleSessionRejected() throws Exception {
        CompatAuthorityClient client = startPair("secret");
        tickets.allow("cp-A", "hash-A");
        assertTrue(client.handshake());
        // Reap by using a fabricated session after close + new connection without hello path:
        // authorize without handshake leaves empty.
        CompatAuthorityClient cold = new CompatAuthorityClient(
                "127.0.0.1", server.boundPort(), "secret", 2000, tickets.identityPublicKey());
        assertTrue(cold.authorize(List.of(new CompatCandidate("cp-A", "hash-A"))).isEmpty());
        cold.close();
        client.close();
    }

    @Test
    public void denyAllAuthorityFailsClosed() throws Exception {
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        // Old single-arg ctor → DenyAllCompatAuthority
        server = new AlpcServer(authority, 0, "secret");
        server.start();
        KeyPair id = CompatCrypto.generateEd25519();
        CompatAuthorityClient client = new CompatAuthorityClient(
                "127.0.0.1", server.boundPort(), "secret", 2000, id.getPublic());
        // Hello returns no K_COMPAT_* (allow:false) → handshake false
        assertFalse(client.handshake());
        client.close();
    }

    @Test
    public void wrongPinnedPublicKeyRejectsTranscript() throws Exception {
        CompatAuthorityClient client = startPair("secret");
        KeyPair wrong = CompatCrypto.generateEd25519();
        CompatAuthorityClient badPin = new CompatAuthorityClient(
                "127.0.0.1", server.boundPort(), "secret", 2000, wrong.getPublic());
        assertFalse(badPin.handshake());
        badPin.close();
        client.close();
    }
}
