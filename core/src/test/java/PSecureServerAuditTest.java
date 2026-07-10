import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.marcloud.mcp.core.http.Json;
import net.marcloud.mcp.core.secure.PSecureProtocol;
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

public class PSecureServerAuditTest {

    private PSecureServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void silentHandshakeCannotMonopolizeAuthority() throws Exception {
        startServer();
        try (Socket silent = connect()) {
            RemotePolicyEngine client = remote();
            try {
                AccessDecision decision = client.evaluate(SecurityContext.wideOpen(),
                        new ToolRequest("eval_java", Map.of(), true));
                assertTrue("new authenticated client replaces silent client", decision.allow());
                assertEquals("silent socket is actively closed", -1, silent.getInputStream().read());
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void oversizedFrameIsClosedBeforeNewline() throws Exception {
        startServer();
        try (Socket client = connect()) {
            BufferedReader in = reader(client);
            BufferedWriter out = writer(client);
            authenticate(in, out);
            out.write("x".repeat(70_000));
            out.flush();
            assertClosed(in);
        }
    }

    @Test
    public void partialFrameTimesOutWithoutKillingAuthority() throws Exception {
        startServer();
        try (Socket client = connect()) {
            BufferedReader in = reader(client);
            BufferedWriter out = writer(client);
            authenticate(in, out);
            out.write("{\"method\"");
            out.flush();
            assertClosed(in);
        }

        RemotePolicyEngine replacement = remote();
        try {
            assertEquals(Ring.R_MINUS_1, replacement.clearance());
        } finally {
            replacement.close();
        }
    }

    @Test
    public void closeDisconnectsAuthenticatedActiveClient() throws Exception {
        startServer();
        RemotePolicyEngine client = remote();
        try {
            assertTrue(client.evaluate(SecurityContext.wideOpen(),
                    new ToolRequest("eval_java", Map.of(), true)).allow());
            server.close();
            AccessDecision afterClose = client.evaluate(SecurityContext.wideOpen(),
                    new ToolRequest("eval_java", Map.of(), true));
            assertFalse("active client fails closed after server close", afterClose.allow());
        } finally {
            client.close();
        }
    }

    private void startServer() throws Exception {
        server = new PSecureServer(new InProcessPolicyEngine(
                new PermissionPolicy(Ring.R_MINUS_1, "restore")), 0, "secret");
        server.start();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.boundPort());
        socket.setSoTimeout(2500);
        return socket;
    }

    private RemotePolicyEngine remote() {
        return new RemotePolicyEngine("127.0.0.1", server.boundPort(), "secret", 2000);
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(Socket socket) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static void authenticate(BufferedReader in, BufferedWriter out) throws IOException {
        out.write(Json.write(Map.of(PSecureProtocol.K_AUTH, "secret")));
        out.newLine();
        out.flush();
        assertTrue(Boolean.TRUE.equals(
                Json.readObject(in.readLine()).get(PSecureProtocol.K_AUTHED)));
    }

    private static void assertClosed(BufferedReader in) throws IOException {
        try {
            assertEquals("server closes invalid client", -1, in.read());
        } catch (SocketException expected) {
            // A TCP reset is also an active close.
        }
    }
}
