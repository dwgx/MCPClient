import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.PrivilegeControlTools;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.io.IoRequestPacket;
import org.junit.Test;

/**
 * GAP-2 regression: L4 privilege and L5 capability changes must PERSIST across
 * evaluations. Before the fix the in-process engine rebuilt a fresh wide-open
 * subject on every call, so a disable/revoke never bit — these tests fail against
 * that old behavior.
 */
public class PrivilegeControlTest {

    private static SeLocalMonitor engine() {
        return new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"));
    }

    private static IoRequestPacket req(String name) {
        return new IoRequestPacket(name, Map.of(), true);
    }

    // ---- L4: disable a privilege → L4 deny → re-enable → allow (through engine) ----

    @Test
    public void disablePrivilegePersistsAndDeniesAtL4() {
        SeLocalMonitor e = engine();
        // Baseline: wide-open, send_chat (needs SE_NET_RAW enabled) is allowed. (Uses
        // send_chat, not send_raw_packet: the latter is now code-exec-gated on
        // SE_CREATE_TOOL, so send_chat is the pure SE_NET_RAW vehicle for this test.)
        assertTrue("wide-open allows send_chat",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());

        assertTrue("disable succeeds on granted privilege",
                e.disablePrivilege(Privilege.SE_NET_RAW));

        // The disable must PERSIST across a fresh currentSubject() (this is the GAP-2
        // fix — the old always-fresh-wide-open subject would re-allow here).
        SeAccessCheck denied = e.evaluate(e.currentSubject(), req("send_chat"));
        assertFalse("send_chat denied after disable", denied.allow());
        assertEquals("L4 privilege", denied.layer());

        // Another unrelated tool is unaffected.
        assertTrue("redefine_class (SE_DEBUG_CLASS) still allowed",
                e.evaluate(e.currentSubject(), req("redefine_class")).allow());

        // Re-enable restores it.
        assertTrue(e.enablePrivilege(Privilege.SE_NET_RAW));
        assertTrue("re-enable re-allows send_chat",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());
    }

    // ---- L5: revoke a capability → L5 deny → grant → allow (through engine) ----

    @Test
    public void revokeCapabilityMaterializesWildcardAndDeniesAtL5() {
        SeLocalMonitor e = engine();
        // Baseline: wildcard caps, send_chat (needs CAP_NETWORK_SEND) allowed.
        assertTrue("wildcard holds CAP_NETWORK_SEND",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());

        assertTrue("revoke from wildcard succeeds",
                e.revokeCapability(CapabilitySid.CAP_NETWORK_SEND));

        // Revoke must bite even though the subject was wildcard (materialized set
        // minus the SID). Old wildcard-forever behavior would still allow.
        SeAccessCheck denied = e.evaluate(e.currentSubject(), req("send_chat"));
        assertFalse("send_chat denied after revoke", denied.allow());
        assertEquals("L5 capability", denied.layer());

        // A tool needing a DIFFERENT cap is unaffected by the materialization.
        assertTrue("capture_screen (CAP_SCREEN_CAP) still allowed",
                e.evaluate(e.currentSubject(), req("capture_screen")).allow());

        // Grant re-adds it.
        assertTrue(e.grantCapability(CapabilitySid.CAP_NETWORK_SEND));
        assertTrue("grant re-allows send_chat",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());
    }

    // ---- L4 and L5 changes do not clobber each other ----

    @Test
    public void privilegeAndCapabilityChangesAreIndependent() {
        SeLocalMonitor e = engine();
        e.disablePrivilege(Privilege.SE_DEBUG_CLASS);   // L4 mutation (token in place)
        e.revokeCapability(CapabilitySid.CAP_NETWORK_SEND); // L5 mutation (set swap)
        // The token change must survive the capability-set swap: SE_DEBUG_CLASS
        // still disabled after the withCapabilities copy (same token instance).
        assertFalse("redefine_class still denied at L4 after a cap revoke",
                e.evaluate(e.currentSubject(), req("redefine_class")).allow());
        assertEquals("L4 privilege",
                e.evaluate(e.currentSubject(), req("redefine_class")).layer());
        // And the capability change is still in force too.
        assertFalse("send_chat still denied at L5",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());
    }

    @Test
    public void cannotEnableUngrantedPrivilege() {
        // Subject with NO privileges granted: enable must fail (no self-escalation).
        net.marcloud.mcp.core.se.SeToken noPrivs =
                new net.marcloud.mcp.core.se.SeToken("t", Ring.R_MINUS_1,
                        net.marcloud.mcp.core.se.IntegrityLevel.SYSTEM,
                        net.marcloud.mcp.core.se.PrivilegeToken.none(), null);
        SeLocalMonitor e = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), noPrivs);
        assertFalse("cannot enable a never-granted privilege",
                e.enablePrivilege(Privilege.SE_NET_RAW));
    }

    // ---- End-to-end through the real supervised registry + MCP tools ----

    @Test
    public void toolsDriveTheLiveGateEndToEnd() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        SeLocalMonitor e = engine();
        IoManager reg = new IoManager(exec, e);
        new PrivilegeControlTools(e).registerAll(reg);

        // A stand-in gated tool: send_chat needs SE_NET_RAW (L4) + CAP_NETWORK_SEND (L5).
        // We invoke the control tools, then evaluate send_chat through the same engine
        // the registry's supervise() gate reads.
        assertTrue("baseline allows send_chat",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());

        CallToolResult disable = reg.invoke("disable_privilege",
                Map.of("privilege", "SE_NET_RAW"));
        assertFalse("disable_privilege succeeded", Boolean.TRUE.equals(disable.isError()));
        SeAccessCheck afterDisable = e.evaluate(e.currentSubject(), req("send_chat"));
        assertFalse("send_chat denied after disable_privilege tool", afterDisable.allow());
        assertEquals("L4 privilege", afterDisable.layer());

        CallToolResult enable = reg.invoke("enable_privilege",
                Map.of("privilege", "NET_RAW")); // bare form must parse too
        assertFalse("enable_privilege succeeded", Boolean.TRUE.equals(enable.isError()));
        assertTrue("send_chat allowed after enable_privilege tool",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());

        CallToolResult revoke = reg.invoke("revoke_capability",
                Map.of("capability", "CAP_NETWORK_SEND"));
        assertFalse("revoke_capability succeeded", Boolean.TRUE.equals(revoke.isError()));
        SeAccessCheck afterRevoke = e.evaluate(e.currentSubject(), req("send_chat"));
        assertFalse("send_chat denied after revoke_capability tool", afterRevoke.allow());
        assertEquals("L5 capability", afterRevoke.layer());

        CallToolResult grant = reg.invoke("grant_capability",
                Map.of("capability", "CAP_NETWORK_SEND"));
        assertFalse("grant_capability succeeded", Boolean.TRUE.equals(grant.isError()));
        assertTrue("send_chat allowed after grant_capability tool",
                e.evaluate(e.currentSubject(), req("send_chat")).allow());

        // Unknown arg is a domain error, not a crash.
        CallToolResult bad = reg.invoke("disable_privilege", Map.of("privilege", "NOPE"));
        assertTrue("unknown privilege is an error result", Boolean.TRUE.equals(bad.isError()));

        exec.shutdown();
    }

    @Test
    public void remoteEngineDefaultsAreNoOps() {
        // The widened interface's default methods must not accidentally allow a
        // remote (P-SECURE) engine to mutate an authority-owned subject.
        net.marcloud.mcp.core.se.SeReferenceMonitor remote =
                new net.marcloud.mcp.core.se.SeRemoteMonitor("127.0.0.1", 1, "t", 50);
        assertFalse(remote.enablePrivilege(Privilege.SE_NET_RAW));
        assertFalse(remote.disablePrivilege(Privilege.SE_NET_RAW));
        assertFalse(remote.grantCapability(CapabilitySid.CAP_NETWORK_SEND));
        assertFalse(remote.revokeCapability(CapabilitySid.CAP_NETWORK_SEND));
    }
}
