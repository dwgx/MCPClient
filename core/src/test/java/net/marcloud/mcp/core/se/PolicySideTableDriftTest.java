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
