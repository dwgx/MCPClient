package net.marcloud.mcp.core.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;

/**
 * Teeth for {@link KernelStatePort}: the snapshot carries the expected ordered rows, it
 * is LIVE (a runtime disablePrivilege / revokeCapability shows up on the next snapshot,
 * not frozen at construction), and a null/faulty engine degrades to "n/a" rather than
 * throwing — the render thread must never break on a posture-source hiccup.
 */
public class KernelStatePortTest {

    private static SeLocalMonitor engine() {
        return new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"));
    }

    @Test
    public void snapshotCarriesTheExpectedRows() {
        Map<String, String> snap = new KernelStatePort(engine()).snapshot();
        assertTrue("has Clearance", snap.containsKey("Clearance"));
        assertTrue("has Integrity", snap.containsKey("Integrity"));
        assertTrue("has Disabled privileges", snap.containsKey("Disabled privileges"));
        assertTrue("has Revoked caps", snap.containsKey("Revoked caps"));
        assertTrue("has Armed patches", snap.containsKey("Armed patches"));
        assertTrue("has MCP facade", snap.containsKey("MCP facade"));
        // wide-open dev default: nothing disabled, nothing revoked.
        assertEquals("none", snap.get("Disabled privileges"));
        assertEquals("none", snap.get("Revoked caps"));
    }

    @Test
    public void snapshotIsLive_reflectsRuntimeDisablePrivilege() {
        SeLocalMonitor e = engine();
        KernelStatePort port = new KernelStatePort(e);

        assertEquals("baseline: no disabled privileges", "none",
                port.snapshot().get("Disabled privileges"));

        // A runtime disable_privilege must show up on the NEXT snapshot (the port re-reads
        // currentSubject() each call — the whole point of "live, not a startup snapshot").
        assertTrue(e.disablePrivilege(Privilege.SE_NET_RAW));
        String disabled = port.snapshot().get("Disabled privileges");
        assertTrue("SE_NET_RAW now listed as disabled", disabled.contains("SE_NET_RAW"));
    }

    @Test
    public void snapshotIsLive_reflectsRuntimeRevokeCapability() {
        SeLocalMonitor e = engine();
        KernelStatePort port = new KernelStatePort(e);

        assertEquals("baseline: wildcard subject revokes nothing", "none",
                port.snapshot().get("Revoked caps"));

        // Revoking one SID from the wildcard subject materializes the full set minus that
        // SID, so exactly the revoked one should surface as not-held.
        assertTrue(e.revokeCapability(CapabilitySid.CAP_NETWORK_SEND));
        String revoked = port.snapshot().get("Revoked caps");
        assertTrue("CAP_NETWORK_SEND now listed as revoked", revoked.contains("CAP_NETWORK_SEND"));
    }

    @Test
    public void nullEngineDegradesToNaWithoutThrowing() {
        // A null monitor (e.g. wiring order edge) must not fault the snapshot.
        Map<String, String> snap = new KernelStatePort(null).snapshot();
        assertNotNull(snap);
        assertEquals("n/a", snap.get("Clearance"));
        assertEquals("n/a", snap.get("Integrity"));
        assertEquals("n/a", snap.get("Disabled privileges"));
        // Armed patches + facade read from static sources, still resolve without an engine.
        assertTrue("armed-patch row present", snap.containsKey("Armed patches"));
        assertTrue("facade row present", snap.containsKey("MCP facade"));
    }
}
