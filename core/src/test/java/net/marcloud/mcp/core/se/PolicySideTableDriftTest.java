package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Drift guard for the by-name policy side tables (repo-gap-survey finding #1,
 * second half). A built-in tool's gate is composed by name from THREE independent
 * maps: {@link Ring} BUILTIN_RINGS, {@link SeToolRequirement} L3_WRITES + L4_PRIVILEGE
 * (and {@link CapabilityCatalog}). An unlisted built-in silently falls back to R3
 * (USER, safest ring) with no L3/L4 gate — so a maintainer who adds a DANGEROUS
 * built-in and forgets its ring gets a silently under-gated tool runnable at the
 * lowest clearance.
 *
 * <p>This pins the load-bearing invariant: <b>any tool that writes something (L3)
 * or requires a privilege (L4) MUST also declare a ring</b>. Those tables list the
 * dangerous verbs; if one appears there but not in BUILTIN_RINGS it would fall
 * back to R3, collapsing the ring gate for a tool the other tables just declared
 * dangerous. Adding such a tool without a ring entry fails this test.
 */
public class PolicySideTableDriftTest {

    @Test
    public void everyL3WritingToolDeclaresARing() {
        Set<String> rings = Ring.declaredBuiltinNames();
        Set<String> missing = new TreeSet<>();
        for (String name : SeToolRequirement.l3WriteNames()) {
            if (!rings.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue("L3-writing built-ins missing an explicit ring (would fall back to R3): "
                + missing, missing.isEmpty());
    }

    @Test
    public void everyL4PrivilegedToolDeclaresARing() {
        Set<String> rings = Ring.declaredBuiltinNames();
        Set<String> missing = new TreeSet<>();
        for (String name : SeToolRequirement.l4PrivilegeNames()) {
            if (!rings.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue("L4-privileged built-ins missing an explicit ring (would fall back to R3): "
                + missing, missing.isEmpty());
    }

    /**
     * The REVERSE invariant, and the one the other three miss: they all run
     * L3/L4 → Ring, so a tool that IS in L3_WRITES but was never added to
     * L4_PRIVILEGE is invisible to them. That is exactly how the W6 typed
     * {@code send_*} tools shipped able to bypass {@code disable_privilege(SE_NET_RAW)}
     * — the purpose-built kill switch for the send surface — while three green
     * assertions reported "no gate drift".
     *
     * <p>Pinned against the EXPLICIT {@link SeToolRequirement#NETWORK_SEND_TOOLS} set,
     * not a name prefix. The prefix heuristic ({@code startsWith("send_")}) was itself
     * a latent hole: renaming the typed tools to the {@code do_} family silently
     * stopped it matching them, so the guard would go green while covering nothing.
     * A packet-sending tool is now a declared member of the set, whatever it is named.
     *
     * <p><b>Bidirectional</b>, so neither drift direction can hide:
     * <ul>
     *   <li>forward — every member of the send set writes at HIGH and holds SE_NET_RAW
     *       (so {@code disable_privilege(SE_NET_RAW)} is a real kill switch);</li>
     *   <li>reverse — every SE_NET_RAW tool in the L4 table is listed in the send set
     *       (so a new sender can't get the privilege without being declared a sender).</li>
     * </ul>
     *
     * <p>The former {@code act_set}/{@code act_cancel} exemption is now closed: they
     * were HIGH writers with no L4 privilege (the same drift the typed send tools had),
     * and now carry {@link Privilege#SE_WORLD_WRITE}. The general "HIGH+ writer needs an
     * L4 privilege" rule is asserted by {@link #everyHighIntegrityWriterDeclaresAnL4Privilege}.
     */
    @Test
    public void networkSendToolsHoldTheNetPrivilegeBothWays() {
        // forward: each declared sender writes HIGH + holds SE_NET_RAW
        Set<String> forwardOffenders = new TreeSet<>();
        for (String name : SeToolRequirement.networkSendTools()) {
            SeToolRequirement req = SeToolRequirement.forTool(name, true);
            if (req.writesResourceAt() != IntegrityLevel.HIGH
                    || req.requiredPrivilege() != Privilege.SE_NET_RAW) {
                forwardOffenders.add(name + "=(" + req.writesResourceAt()
                        + "," + req.requiredPrivilege() + ")");
            }
        }
        assertTrue("declared network-send tools not gated HIGH+SE_NET_RAW — disable_privilege"
                + "(SE_NET_RAW) would NOT stop them putting packets on the wire: "
                + forwardOffenders, forwardOffenders.isEmpty());

        // reverse: every SE_NET_RAW tool is a declared sender (can't get the privilege
        // for the send surface without being listed as one)
        Set<String> reverseOffenders = new TreeSet<>();
        for (String name : SeToolRequirement.l4PrivilegeNames()) {
            if (SeToolRequirement.forTool(name, true).requiredPrivilege() == Privilege.SE_NET_RAW
                    && !SeToolRequirement.networkSendTools().contains(name)) {
                reverseOffenders.add(name);
            }
        }
        assertTrue("tools hold SE_NET_RAW but are not in NETWORK_SEND_TOOLS — declare them "
                + "senders so the send-surface invariants cover them: " + reverseOffenders,
                reverseOffenders.isEmpty());
    }

    /**
     * The general form of the reverse invariant, now that the {@code act_*} exemption
     * is closed: <b>any tool that writes a resource at HIGH integrity or above MUST
     * carry an L4 privilege</b>. A HIGH+ write is a dangerous verb against the game
     * classes, the network connection, or the JVM itself; the L4 privilege is the
     * per-verb kill switch {@code disable_privilege} flips. A HIGH+ writer with a null
     * privilege has no such switch — exactly the hole the W6 {@code send_*} tools and
     * the PHASE-A {@code act_*} tools both shipped with.
     *
     * <p>LOW/MEDIUM writers (memory, narrative, tool self-modification) are correctly
     * excluded: they gate on ring + integrity + capability, not a dangerous verb.
     * Reverting the {@code act_set}/{@code act_cancel} L4 entries fails this test.
     */
    @Test
    public void everyHighIntegrityWriterDeclaresAnL4Privilege() {
        Set<String> offenders = new TreeSet<>();
        for (String name : SeToolRequirement.l3WriteNames()) {
            SeToolRequirement req = SeToolRequirement.forTool(name, true);
            IntegrityLevel writes = req.writesResourceAt();
            if (writes == null || writes.rank() < IntegrityLevel.HIGH.rank()) {
                continue;
            }
            if (req.requiredPrivilege() == null) {
                offenders.add(name + "=" + writes.label());
            }
        }
        assertTrue("HIGH+ integrity writers with no L4 privilege — disable_privilege has "
                + "no kill switch for these dangerous verbs: " + offenders, offenders.isEmpty());
    }

    /**
     * Any built-in that requires a privilege (L4) is by definition a dangerous
     * verb; it must not sit at R3 USER (the safest ring, reachable at any
     * clearance). It should be R2 or more privileged (lower number).
     */
    @Test
    public void everyL4PrivilegedToolIsMorePrivilegedThanUserRing() {
        Set<String> offenders = new TreeSet<>();
        for (String name : SeToolRequirement.l4PrivilegeNames()) {
            Ring r = Ring.forBuiltin(name, Ring.R3);
            if (r.level() >= Ring.R3.level()) {
                offenders.add(name + "=" + r.tag());
            }
        }
        assertTrue("L4-privileged built-ins sitting at R3 USER (dangerous verb at safest ring): "
                + offenders, offenders.isEmpty());
    }
}
