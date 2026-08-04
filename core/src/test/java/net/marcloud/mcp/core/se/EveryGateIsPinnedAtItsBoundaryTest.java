package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.Test;

import net.marcloud.mcp.core.io.IoRequestPacket;

/**
 * The kernel's gates, asserted where they can actually be off by one.
 *
 * <p><b>Why this file exists.</b> Every existing end-to-end denial in this kernel uses a ring or rank
 * gap of two or more -- R2 clearance against an R-1 tool, MEDIUM integrity against a HIGH writer --
 * so a threshold with one unit of slack denies exactly the same set and no test says a word. Measured,
 * not argued: three mutations that widen a gate by one, and two that re-point a side-table row to a
 * wrong-but-present value, each survived all 957 tests.
 *
 * <table>
 *   <tr><th>mutation</th><th>before</th></tr>
 *   <tr><td>{@code SeLocalMonitor} L2: {@code clearance > ring} becomes {@code clearance > ring + 1}
 *       </td><td>SURVIVED</td></tr>
 *   <tr><td>{@code IntegrityLevel.canWriteTo}: {@code rank >= resource} becomes
 *       {@code rank + 1 >= resource}</td><td>SURVIVED</td></tr>
 *   <tr><td>{@code PrivilegeToken.isGranted} returns true unconditionally</td><td>SURVIVED</td></tr>
 *   <tr><td>{@code debug_suspend_thread} downgraded from a SYSTEM writer to LOW</td>
 *       <td>SURVIVED</td></tr>
 *   <tr><td>{@code debug_write_local} re-keyed from CAP_DEBUG_CONTROL to CAP_CLASS_INTROSPECT</td>
 *       <td>SURVIVED</td></tr>
 * </table>
 *
 * <p><b>The L2 case has a second, sharper reason.</b> The adjacent ring pairs ARE asserted -- against
 * {@link SeClearancePolicy#allows}, which no production code calls. {@code SeLocalMonitor:126} names
 * it in a comment ("identical to the old policy.allows(ring) check") and then inlines the comparison.
 * So the tests pin a dead copy of the rule while the live copy is unpinned at its boundary: the same
 * one-side-of-the-seam shape as a differ tested without its capture.
 *
 * <p><b>And the side-table cases defeat the drift test by construction.</b>
 * {@code PolicySideTableDriftTest.everyHighIntegrityWriterDeclaresAnL4Privilege} keys its own
 * precondition off the value being mutated -- {@code if (writes.rank() < HIGH.rank()) continue} --
 * so downgrading a tool's integrity moves it OUT of the invariant's scope instead of violating it. A
 * guard that skips what it is meant to catch is exactly the {@code isFullCube()} shape: it cannot
 * fail. The assertions here are on the VALUES, which is the part existence checks cannot reach.
 */
public class EveryGateIsPinnedAtItsBoundaryTest {

    private static IoRequestPacket req(String name) {
        return new IoRequestPacket(name, Map.of(), true);
    }

    /**
     * L2 must deny a subject exactly ONE ring less privileged than the tool.
     *
     * <p>This is the case that decides whether {@code drop_privilege(R0)} is a real lockout. With one
     * ring of slack an R0 subject runs the R-1 set -- {@code eval_java}, {@code redefine_class},
     * {@code write_field}, every {@code debug_*} -- which is arbitrary code execution inside the JVM,
     * the thing the ring numbering exists to separate.
     */
    @Test
    public void ringClearanceDeniesTheAdjacentRingNotOnlyADistantOne() {
        SeLocalMonitor e = new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"));
        e.dropTo(Ring.R0);

        SeAccessCheck evalJava = e.evaluate(e.currentSubject(), req("eval_java"));
        assertFalse("R0 is ONE ring less privileged than eval_java's R-1, and that is the whole "
                + "point of dropping to R0 -- if the gate has a ring of slack, drop_privilege(R0) "
                + "stops being a hypervisor lockout while every existing test (which uses a gap of "
                + "two or more) still passes", evalJava.allow());
        assertEquals("and the denial must be attributed to L2, not to a later layer that happens "
                + "to also refuse", "L2 ring", evalJava.layer());

        SeAccessCheck redefine = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse("same for redefine_class, the other R-1 code-execution door",
                redefine.allow());
        assertEquals("L2 ring", redefine.layer());

        // The other half of the boundary: equal rings must still pass, or the fix above would be a
        // ring too strict and this test would pass for the wrong reason.
        SeAccessCheck createTool = e.evaluate(e.currentSubject(), req("create_tool"));
        assertTrue("an R0 subject must still run its OWN ring's tools: a gate asserted only in the "
                + "deny direction is satisfied by one that denies everything. " + createTool.reason(),
                createTool.allow());
    }

    /** The same boundary one layer down, for every adjacent pair in the lattice. */
    @Test
    public void integrityNoWriteUpDeniesTheAdjacentRankNotOnlyADistantOne() {
        IntegrityLevel[] ladder = {IntegrityLevel.UNTRUSTED, IntegrityLevel.LOW,
                IntegrityLevel.MEDIUM, IntegrityLevel.MEDIUM_PLUS, IntegrityLevel.HIGH,
                IntegrityLevel.SYSTEM, IntegrityLevel.PROTECTED};
        for (int i = 0; i < ladder.length - 1; i++) {
            IntegrityLevel lower = ladder[i];
            IntegrityLevel justAbove = ladder[i + 1];
            assertFalse(lower.label() + " must not write " + justAbove.label()
                    + ": no-write-up means rank >= rank, and every existing assertion here uses a "
                    + "gap of two or more (MEDIUM->HIGH, LOW->SYSTEM), so one rank of slack denies "
                    + "the same set and nothing notices. The pair that matters most is "
                    + "HIGH->SYSTEM: HIGH is where net.minecraft bytecode sits and SYSTEM is the "
                    + "agent and Instrumentation.", lower.canWriteTo(justAbove));
            assertTrue("while writing its own level must stay allowed",
                    lower.canWriteTo(lower));
        }
    }

    /**
     * The two-state privilege model, asserted on the state no test drove.
     *
     * <p>{@code isGranted} exists only to separate "disabled, and you may enable it in-session" from
     * "never granted, and you cannot" -- and the L4 deny message picks its remediation advice from it
     * ({@code SeLocalMonitor:148-151}). A version that always answers true tells the operator to run
     * {@code enable_privilege(X)} for a privilege where {@code enable} returns false forever.
     * {@code L4DenyMessageHonestyTest} was written to stop exactly that class of bogus advice, but it
     * only ever builds the granted-but-disabled subject.
     */
    @Test
    public void anUngrantedPrivilegeIsNotReportedAsMerelyDisabled() {
        PrivilegeToken t = new PrivilegeToken(Map.of(Privilege.SE_DEBUG_CLASS, false));
        assertTrue("the granted-but-disabled case", t.isGranted(Privilege.SE_DEBUG_CLASS));
        assertFalse("an absent key means never granted, which is not the same state and carries "
                + "different advice: enable_privilege cannot help here",
                t.isGranted(Privilege.SE_NET_RAW));
        assertFalse("and the two states must agree with enable(), which is what the advice "
                + "promises will work", t.enable(Privilege.SE_NET_RAW));
    }

    /** And the deny message must actually reach the branch that says so. */
    @Test
    public void theL4DenialForAnUngrantedPrivilegeSaysSo() {
        SeToken subject = new SeToken("t", Ring.R_MINUS_1, IntegrityLevel.PROTECTED,
                new PrivilegeToken(Map.of()), java.util.EnumSet.allOf(CapabilitySid.class));
        SeLocalMonitor e = new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"), subject);
        SeAccessCheck d = e.evaluate(e.currentSubject(), req("redefine_class"));
        assertFalse(d.allow());
        assertEquals("L4 privilege", d.layer());
        assertTrue("the ungranted branch of the message was never driven by any test; its text is "
                + "the operator's only signal that enable_privilege will NOT work here. Got: "
                + d.reason(), d.reason().contains("not granted"));
        assertFalse("and it must not offer the remediation that belongs to the other state",
                d.reason().contains("granted but disabled"));
    }

    /**
     * The most invasive write class must be declared at the integrity it claims.
     *
     * <p>Asserted by VALUE because existence is what the subsystem's own test checks
     * ({@code DebugToolsTest}: {@code assertNotNull(tp.writesResourceAt())}), and a downgrade is
     * non-null. Suspending a live game thread through JVMTI at LOW integrity would let a
     * MEDIUM-integrity subject freeze the game.
     */
    @Test
    public void theDebuggerWriteSurfaceIsDeclaredAtSystemIntegrity() {
        assertEquals("debug_suspend_thread pauses a live game thread via JVMTI; its own comment "
                + "calls it the most invasive write class. A downgrade passes every existing "
                + "assertion here, and it also moves the tool OUT of the drift test's scope, "
                + "because that invariant skips anything below HIGH.",
                IntegrityLevel.SYSTEM,
                SeToolRequirement.forTool("debug_suspend_thread", true).writesResourceAt());
        assertTrue("and it stays at or above HIGH, which is the condition that keeps it inside "
                + "the drift test's own precondition rather than exempt from it",
                SeToolRequirement.forTool("debug_suspend_thread", true)
                        .writesResourceAt().rank() >= IntegrityLevel.HIGH.rank());
    }

    /**
     * A debugger WRITE must be gated by the debugger-control capability, not by a read-only one.
     *
     * <p>{@code revoke_capability(CAP_DEBUG_CONTROL)} is the lever that shuts this surface. Keyed to
     * {@code CAP_CLASS_INTROSPECT} instead, revoking DEBUG_CONTROL stops nothing and a subject
     * holding only read-only introspection can rewrite a local in a live stack frame. No assertion in
     * the repo named this capability before -- it appeared only in a javadoc line.
     */
    @Test
    public void writingALiveStackFrameRequiresDebugControlNotIntrospection() {
        Set<CapabilitySid> caps = CapabilityCatalog.requiredFor("debug_write_local", true);
        assertTrue("debug_write_local must require CAP_DEBUG_CONTROL, or revoking it does not "
                + "close the debugger write surface. Declared: " + caps,
                caps.contains(CapabilitySid.CAP_DEBUG_CONTROL));
        assertFalse("and read-only introspection must not be sufficient for a write",
                caps.equals(Set.of(CapabilitySid.CAP_CLASS_INTROSPECT)));
    }

    /**
     * The rule the tests pinned and the rule the kernel runs must be the same rule.
     *
     * <p>{@code SeClearancePolicy.allows} has no production caller: {@code SeLocalMonitor} inlines the
     * comparison and mentions {@code allows} only in a comment. That is how the adjacent-ring
     * assertions in {@code PermissionPolicyTest} came to guard nothing. Rather than delete a method
     * whose removal is an owner's call, this pins the two to agree -- so the live gate cannot drift
     * from the copy the other tests exercise.
     */
    @Test
    public void theLiveRingGateAgreesWithThePolicyObjectTheOtherTestsAssertOn() {
        for (Ring clearance : Ring.values()) {
            for (Ring toolRing : Ring.values()) {
                SeClearancePolicy policy = new SeClearancePolicy(clearance, "tok");
                boolean policySays = policy.allows(toolRing);
                boolean liveSays = liveGateAllows(clearance, toolRing);
                assertEquals("clearance " + clearance.tag() + " vs tool ring " + toolRing.tag()
                        + ": SeClearancePolicy.allows and the inlined L2 comparison in "
                        + "SeLocalMonitor must not disagree. PermissionPolicyTest asserts the "
                        + "adjacent pairs against allows(), which nothing in production calls, so "
                        + "a divergence would leave the real gate covered only at gaps of two.",
                        policySays, liveSays);
            }
        }
    }

    /** Drives the real monitor and reports whether L2 specifically let the request through. */
    private static boolean liveGateAllows(Ring clearance, Ring toolRing) {
        String tool = toolAtRing(toolRing);
        SeToken subject = new SeToken("t", clearance, IntegrityLevel.PROTECTED,
                PrivilegeToken.wideOpen(), java.util.EnumSet.allOf(CapabilitySid.class));
        SeLocalMonitor e = new SeLocalMonitor(new SeClearancePolicy(clearance, "tok"), subject);
        SeAccessCheck d = e.evaluate(e.currentSubject(), req(tool));
        // Only L2's verdict is in question here; a later layer refusing is a different claim.
        return d.allow() || !"L2 ring".equals(d.layer());
    }

    /** A real builtin declared at each ring, so the comparison runs on production side-table rows. */
    private static String toolAtRing(Ring r) {
        String tool = switch (r) {
            case R_MINUS_1 -> "eval_java";
            case R0 -> "create_tool";
            // send_chat, NOT send_raw_packet: the latter reads like the obvious R1 example and the
            // Ring javadoc listed it as one, but the table gates it at R-1 because it compiles and
            // reflectively runs caller-supplied Java. The premise guard below caught that on the
            // first run of this test, which is the reason the guard is here.
            case R1 -> "send_chat";
            case R2 -> "scan_surroundings";
            case R3 -> "recent_packets";
        };
        assertEquals("this test's premise is that " + tool + " sits at " + r.tag()
                + "; if the side table moved it, the loop above would be comparing the wrong pair "
                + "and would keep passing", r,
                SeToolRequirement.forTool(tool, true).requiredRing());
        return tool;
    }
}
