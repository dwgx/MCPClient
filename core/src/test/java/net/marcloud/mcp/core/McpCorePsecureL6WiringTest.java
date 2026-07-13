package net.marcloud.mcp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.alpc.AlpcProtocol;
import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeHandleGatedMonitor;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeRemoteMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * KI-8 WIRING regression — drives the actual defect location: {@link
 * McpCore#buildEngine}'s {@code -Dmcp.core.psecure} branch. Package-private access
 * lets this call {@code buildEngine} exactly as production does.
 *
 * <p>KI-8: buildEngine returned a BARE {@link SeRemoteMonitor} under psecure,
 * DROPPING the {@link ObManager} argument, so with psecure + handles + hardened all
 * on, L6 strict-handle TOCTOU protection silently became a no-op.
 *
 * <p><b>FAILS ON PRE-FIX CODE.</b> Before the fix, {@code buildEngine(policy,
 * objects)} ignored {@code objects} in the psecure branch and returned the bare
 * remote monitor — a handle-less handle-op was silently ALLOWED (the remote
 * authority is R-1 wide open and never sees a handle), so
 * {@link #buildEnginePsecureWithObjectsWiresLocalL6Gate} would fail both the
 * {@code instanceof SeHandleGatedMonitor} check and the L6-deny assertion. The
 * {@link #buildEnginePsecureWithoutObjectsStaysBareRemote} case pins the null-objects
 * path (L6 off) to the bare remote so the wrapper is added only when L6 is live.
 */
public class McpCorePsecureL6WiringTest {

    private String savedPsecure;
    private String savedHost;
    private String savedPort;
    private String savedToken;
    private AlpcServer server;

    @Before
    public void setUp() {
        savedPsecure = System.getProperty(AlpcProtocol.ENABLE_PROPERTY);
        savedHost = System.getProperty("mcp.core.psecureHost");
        savedPort = System.getProperty("mcp.core.psecurePort");
        savedToken = System.getProperty(AlpcProtocol.TOKEN_PROPERTY);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
        restore(AlpcProtocol.ENABLE_PROPERTY, savedPsecure);
        restore("mcp.core.psecureHost", savedHost);
        restore("mcp.core.psecurePort", savedPort);
        restore(AlpcProtocol.TOKEN_PROPERTY, savedToken);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Point buildEngine's psecure client at a live R-1 authority on an ephemeral port. */
    private void startAuthorityAndPointClientAtIt(String token) throws Exception {
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        server = new AlpcServer(authority, 0, token);
        server.start();
        assertTrue("bound to an ephemeral port", server.boundPort() > 0);
        System.setProperty(AlpcProtocol.ENABLE_PROPERTY, "true");
        System.setProperty("mcp.core.psecureHost", "127.0.0.1");
        System.setProperty("mcp.core.psecurePort", Integer.toString(server.boundPort()));
        System.setProperty(AlpcProtocol.TOKEN_PROPERTY, token);
    }

    @Test
    public void buildEnginePsecureWithObjectsWiresLocalL6Gate() throws Exception {
        startAuthorityAndPointClientAtIt("secret");
        // STRICT-handle ObManager, as McpCore#buildObjectManager wires under hardened.
        ObManager objects = new ObManager(ref -> new Object(), 8, 60_000L, true);

        SeReferenceMonitor engine = McpCore.buildEngine(
                new SeClearancePolicy(Ring.R_MINUS_1, "t"), objects);

        assertTrue("psecure + objects wraps the remote monitor in the local L6 gate "
                + "(pre-fix returned the bare SeRemoteMonitor, dropping objects)",
                engine instanceof SeHandleGatedMonitor);

        // A HANDLE_OPS tool without a handle: L1-L5 pass at the remote R-1 authority,
        // but the LOCAL L6 gate denies it (strict mode). Pre-fix this was allowed.
        SeAccessCheck d = engine.evaluate(SeToken.wideOpen(),
                new IoRequestPacket("debug_suspend_thread", Map.of(), true));
        assertFalse("handle-less handle-op denied by the local L6 gate under psecure",
                d.allow());
        assertEquals("deny attributed to the LOCAL L6 layer", "L6 handle", d.layer());
    }

    @Test
    public void buildEnginePsecureWithoutObjectsStaysBareRemote() throws Exception {
        startAuthorityAndPointClientAtIt("secret");

        SeReferenceMonitor engine = McpCore.buildEngine(
                new SeClearancePolicy(Ring.R_MINUS_1, "t"), null);

        assertTrue("L6 off (null objects) => bare remote monitor, not wrapped",
                engine instanceof SeRemoteMonitor);
        assertFalse("not the L6 wrapper when L6 is off",
                engine instanceof SeHandleGatedMonitor);
    }
}
