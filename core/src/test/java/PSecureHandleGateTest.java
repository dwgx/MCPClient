import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.ob.ObAccessMask;
import net.marcloud.mcp.core.ob.ObHandle;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.ob.ObRef;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.PrivilegeToken;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeHandleGatedMonitor;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.SeRemoteMonitor;
import net.marcloud.mcp.core.se.SeToken;
import org.junit.After;
import org.junit.Test;

/**
 * KI-8 CROSS_PROCESS regression: the LOCAL L6 handle gate spliced in front of the
 * P-SECURE remote authority.
 *
 * <p>Wiring under test = {@code McpCore.buildEngine}'s psecure branch when the
 * object manager is present: a real {@link SeRemoteMonitor} client talking to a
 * real {@link AlpcServer} on an ephemeral loopback port (the same harness as
 * {@link PSecureRpcTest}), wrapped by {@link SeHandleGatedMonitor} with a real
 * {@link ObManager} in STRICT-handle mode.
 *
 * <p><b>Teeth.</b> KI-8 was: under {@code -Dmcp.core.psecure} the engine returned
 * the BARE {@link SeRemoteMonitor}, dropping the ObManager, so L6 strict-handle
 * TOCTOU protection silently became a no-op. {@link #preFixBareRemoteSilentlyAllowsHandlelessHandleOp}
 * reproduces the pre-fix path (bare remote, no L6) and shows the handle-less
 * handle-op is silently ALLOWED. The fixed path
 * ({@link #localL6GateBitesHandlelessHandleOpUnderPSecure}) DENIES it at L6 while
 * L1-L5 still go to the remote authority — so the pair fails on pre-fix code and
 * passes only with the gate wired.
 */
public class PSecureHandleGateTest {

    private AlpcServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** A wide-open R-1 authority (allows the debug handle-ops at L1-L5). */
    private AlpcServer startAuthority(String token) throws Exception {
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        AlpcServer s = new AlpcServer(authority, 0, token);
        s.start();
        assertTrue("bound to an ephemeral port", s.boundPort() > 0);
        return s;
    }

    private static SeToken subject(String id) {
        return new SeToken(id, Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                PrivilegeToken.wideOpen(), (java.util.Set<CapabilitySid>) null);
    }

    // ---- FIXED path: local L6 gate in front of the remote authority ----

    @Test
    public void localL6GateBitesHandlelessHandleOpUnderPSecure() throws Exception {
        server = startAuthority("secret");
        SeRemoteMonitor remote = new SeRemoteMonitor(
                "127.0.0.1", server.boundPort(), "secret", 2000);
        // STRICT-handle ObManager, exactly as McpCore wires it under the hardened posture.
        ObManager objects = new ObManager(ref -> new Object(), 8, 60_000L, true);
        SeReferenceMonitor engine = new SeHandleGatedMonitor(remote, objects);

        SeToken s = subject("alice");

        // 1. A HANDLE_OPS tool invoked WITHOUT a handle: L1-L5 pass at the remote
        //    authority (R-1 wide open), but the LOCAL L6 gate DENIES it (strict mode
        //    refuses the name-based TOCTOU fallback). This is the layer KI-8 dropped.
        SeAccessCheck handleless = engine.evaluate(s,
                new IoRequestPacket("debug_suspend_thread", Map.of(), true));
        assertFalse("strict L6 denies a handle-less handle-op under P-SECURE",
                handleless.allow());
        assertEquals("deny attributed to the LOCAL L6 layer", "L6 handle", handleless.layer());

        // 2. Same tool WITH a valid handle frozen for EXECUTE: L1-L5 pass at the
        //    remote authority AND the local L6 subset check allows it.
        ObHandle h = objects.open(s, ObRef.parse("thread:Server thread"),
                ObAccessMask.mask(ObAccessMask.READ, ObAccessMask.EXECUTE));
        SeAccessCheck withHandle = engine.evaluate(s,
                new IoRequestPacket("debug_suspend_thread",
                        Map.of("handle", Long.toString(h.id())), true));
        assertTrue("handle-op WITH a valid EXECUTE handle is allowed end-to-end: "
                + withHandle.reason(), withHandle.allow());

        remote.close();
    }

    @Test
    public void l1ToL5StillDecidedByRemoteAuthority() throws Exception {
        // Authority at R2: an R-1 tool must be denied at the REMOTE authority, and
        // the local L6 gate must not mask that remote layer name.
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R2, "restore"));
        server = new AlpcServer(authority, 0, "secret");
        server.start();

        SeRemoteMonitor remote = new SeRemoteMonitor(
                "127.0.0.1", server.boundPort(), "secret", 2000);
        ObManager objects = new ObManager(ref -> new Object(), 8, 60_000L, true);
        SeReferenceMonitor engine = new SeHandleGatedMonitor(remote, objects);

        SeToken s = subject("alice");
        // eval_java is R-1; an R2 authority denies it at L2 across the wall. The L6
        // gate must NOT run (short-circuit on the remote deny) and the deny keeps the
        // remote layer name, not "L6 handle".
        SeAccessCheck denied = engine.evaluate(s,
                new IoRequestPacket("eval_java", Map.of(), true));
        assertFalse("R-1 tool denied by the R2 remote authority", denied.allow());
        assertFalse("remote deny is not masked by the local L6 layer",
                "L6 handle".equals(denied.layer()));

        // An observe-tier tool (no handle) still passes both the remote authority and
        // the L6 no-op.
        assertTrue("benign tool allowed through the remote authority + L6 no-op",
                engine.evaluate(s,
                        new IoRequestPacket("scan_surroundings", Map.of(), true)).allow());

        // clearance / restorable delegate straight to the remote authority.
        assertEquals("clearance read from the remote authority", Ring.R2, engine.clearance());
        assertTrue("restorable delegates to the remote authority", engine.restorable());

        remote.close();
    }

    // ---- TEETH: pre-fix path (bare remote, L6 dropped) silently allows ----

    @Test
    public void preFixBareRemoteSilentlyAllowsHandlelessHandleOp() throws Exception {
        // This reproduces KI-8: buildEngine returned the BARE SeRemoteMonitor under
        // -Dmcp.core.psecure, dropping the ObManager. The remote authority never sees
        // a handle (evaluate() sends only the tool identity), so a handle-op invoked
        // without a handle passes L1-L5 and there is NO local L6 gate to catch it.
        server = startAuthority("secret");
        SeReferenceMonitor bareRemote = new SeRemoteMonitor(
                "127.0.0.1", server.boundPort(), "secret", 2000);

        SeToken s = subject("alice");
        SeAccessCheck d = bareRemote.evaluate(s,
                new IoRequestPacket("debug_suspend_thread", Map.of(), true));
        assertTrue("PRE-FIX: bare remote silently ALLOWS the handle-less handle-op "
                + "(L6 dropped) — this is exactly the KI-8 hole the local gate closes",
                d.allow());

        ((SeRemoteMonitor) bareRemote).close();
    }
}
