package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.mm.MmAccess;

import org.junit.Test;

/**
 * The production {@link AccessGate} is live. This file exists because it was not:
 * {@code McpCore.registerBuiltins} handed {@link MmAccess}, {@code HookTools} and
 * {@code DebugTools} a {@code new AllowAllGate()} — a class whose
 * {@code require} body is empty — while {@code HookTools}' own javadoc claimed
 * those tools were gated by "the ring AND an AccessGate defense-in-depth check
 * (CAP_CLASS_RETRANSFORM), following the L1-L7 AND-composition rule". The second
 * term of that AND did nothing. A documented defense that is not there is the
 * same shape as a patch that arms and does nothing.
 *
 * <p><b>FAILS ON THE PRE-FIX WIRING, by construction.</b> Every assertion drives
 * the registry {@link McpCore#registerBuiltins} builds — the same call
 * {@code start()} makes — and then reaches the {@link MmAccess} instance that
 * call constructed. Reaching it in-JVM is the point: the by-name tool table
 * ({@link SeToolRequirement#forTool}) only sees calls that arrive as a tool
 * name, so a caller holding an {@code MmAccess} directly crosses no L4 and no
 * L5 through it. That gap is exactly what the gate is for, and it is why these
 * tests do not go through {@code IoManager.invoke} — going through the registry
 * would let the tool table answer in the gate's place, and the assertions would
 * pass no matter which gate was wired.
 *
 * <p>Both terms are pinned, each with a counterweight so a blanket refusal
 * cannot pass: the L5 capability term and the L4 privilege term. A further pair
 * pins that the subject is re-read per call, which is what separates a live gate
 * from one built from a snapshot at wiring time.
 */
public final class MonitorAccessGateIsLiveTest {

    /**
     * The read/write target.
     *
     * <p>Deliberately NOT a nested class of this test. This file lives in
     * {@code net.marcloud.mcp.core.se}, and {@link SeProtectedObjects} protects
     * that whole package by prefix — a probe declared here would be refused by
     * {@code MmAccess}'s own protected-object guard before the gate was ever
     * consulted, and every assertion below would be testing the wrong refusal
     * (it did exactly that on the first run of this file: five errors, all
     * "refusing to mutate protected class ...$Probe"). {@link GameClock} sits in
     * the unprotected {@code ke} package, is publicly constructible for isolated
     * tests, and has both a writable instance field and a readable static field.
     */
    private static final GameClock PROBE = new GameClock();

    /** {@code lastTickMonoNs} is a plain {@code volatile long} — writable, readable. */
    private static final String FIELD = "lastTickMonoNs";

    // ---- production handles ------------------------------------------------

    /** The production registry, built exactly the way {@code start()} builds it. */
    private static IoManager productionRegistry(IoSupervisor exec, SeReferenceMonitor engine) {
        IoManager reg = new IoManager(exec, engine);
        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(reg, engine, null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(MonitorAccessGateIsLiveTest.class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return reg;
    }

    /**
     * The {@link MmAccess} that {@link McpCore#registerBuiltins} built, reached
     * through the production {@code MutateStateTools} it registered. Reflection
     * is the only handle: the local is not returned and production exposes no
     * accessor for it. Walking captured fields by TYPE rather than by name means
     * a recompile that renames a synthetic field fails loudly here instead of
     * silently skipping the check.
     */
    private static MmAccess productionMmAccess(IoManager registry) {
        var cap = registry.get("read_field");
        if (cap == null) {
            fail("read_field is not registered — registerBuiltins did not run");
        }
        for (Object o : flatten(cap.spec(), 0)) {
            if (o instanceof MmAccess mm) {
                return mm;
            }
        }
        fail("could not reach the MmAccess behind the registered read_field spec");
        return null;
    }

    /** The {@link AccessGate} instance the production {@link MmAccess} holds. */
    private static AccessGate productionGateOf(MmAccess mm) {
        try {
            Field f = MmAccess.class.getDeclaredField("gate");
            f.setAccessible(true);
            return (AccessGate) f.get(mm);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read MmAccess.gate", e);
        }
    }

    /** Breadth-first over own declared fields, skipping statics and JDK types. */
    private static List<Object> flatten(Object root, int depth) {
        List<Object> out = new ArrayList<>();
        if (root == null || depth > 5) {
            return out;
        }
        out.add(root);
        Class<?> c = root.getClass();
        if (c.getName().startsWith("java.")) {
            return out;
        }
        for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                out.addAll(flatten(f.get(root), depth + 1));
            } catch (RuntimeException | ReflectiveOperationException ignored) {
                // An inaccessible synthetic field is no reason to skip the rest.
            }
        }
        return out;
    }

    // ---- subjects ----------------------------------------------------------

    private static SeLocalMonitor engineWith(SeToken subject) {
        return new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok"), subject);
    }

    /** SYSTEM integrity, wide-open privileges, and NO capability at all. */
    private static SeToken noCapabilities() {
        return SeLocalMonitor.strictSubject(Set.of());
    }

    /** Wildcard capabilities (so L5 passes) but SE_DEBUG_CLASS granted-and-disabled. */
    private static SeToken withoutDebugClassPrivilege() {
        EnumMap<Privilege, Boolean> grants = new EnumMap<>(Privilege.class);
        for (Privilege p : Privilege.values()) {
            grants.put(p, true);
        }
        grants.put(Privilege.SE_DEBUG_CLASS, false);
        return new SeToken("t", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                new PrivilegeToken(grants), null);
    }

    private static long readTick(MmAccess mm) {
        return ((Number) mm.getField(PROBE, FIELD)).longValue();
    }

    // ---- L5: the capability term -------------------------------------------

    /**
     * The second term of the AND denies a memory read when the subject holds no
     * capability. On the pre-fix wiring {@code AllowAllGate.require} returned
     * without reading anything, so this read SUCCEEDED and returned the field's
     * value — which is this assertion failing, not passing.
     */
    @Test
    public void aMemoryReadIsDeniedWhenTheSubjectHoldsNoCapability() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            MmAccess mm = productionMmAccess(productionRegistry(exec, engineWith(noCapabilities())));
            try {
                Object v = mm.getField(PROBE, FIELD);
                fail("MmAccess.getField returned " + v + " for a subject holding NO capability: "
                        + "the wired AccessGate allowed a read it must refuse, so the "
                        + "CAP_MEMORY_READ term of the AND is not enforced");
            } catch (SecurityException e) {
                assertTrue("the deny must name the capability layer: " + e.getMessage(),
                        e.getMessage().contains("L5 capability"));
                assertTrue("and name the capability that was missing: " + e.getMessage(),
                        e.getMessage().contains("CAP_MEMORY_READ"));
            }
        } finally {
            exec.shutdown();
        }
    }

    /**
     * {@code getStaticField} is the same L5 term through a different door. It is
     * asserted separately because it is a distinct {@code require} site
     * ({@code MmAccess}:103) with its own code path, and a gap at one site is
     * exactly the shape of defect under repair.
     */
    @Test
    public void aStaticReadIsDeniedWhenTheSubjectHoldsNoCapability() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            MmAccess mm = productionMmAccess(productionRegistry(exec, engineWith(noCapabilities())));
            try {
                Object v = mm.getStaticField(GameClock.class, "INSTANCE");
                fail("MmAccess.getStaticField returned " + v + " for a subject holding NO "
                        + "capability: the static read path does not consult the gate");
            } catch (SecurityException e) {
                assertTrue("the deny must name the capability layer: " + e.getMessage(),
                        e.getMessage().contains("L5 capability"));
            }
        } finally {
            exec.shutdown();
        }
    }

    // ---- L4: the privilege term -------------------------------------------

    /**
     * Wildcard capabilities mean L5 cannot be what denies, so only the privilege
     * term can. {@code SE_DEBUG_CLASS} is granted but DISABLED — the exact
     * two-state shape the privilege model exists for — and the deny must say so,
     * because "granted but disabled" and "never granted" call for different
     * operator actions.
     */
    @Test
    public void aMemoryWriteIsDeniedWhenItsPrivilegeIsGrantedButDisabled() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            MmAccess mm = productionMmAccess(
                    productionRegistry(exec, engineWith(withoutDebugClassPrivilege())));
            long before = readTick(mm);
            try {
                mm.setField(PROBE, FIELD, 123L, null);
                fail("MmAccess.setField wrote " + readTick(mm) + " with SE_DEBUG_CLASS "
                        + "granted-but-disabled: the wired AccessGate allowed a write it must "
                        + "refuse, so the L4 term of the AND is not enforced");
            } catch (SecurityException e) {
                assertTrue("the deny must name the privilege layer: " + e.getMessage(),
                        e.getMessage().contains("L4 privilege"));
                assertTrue("and distinguish disabled from never-granted: " + e.getMessage(),
                        e.getMessage().contains("granted but disabled"));
                assertEquals("and nothing may have been written", before, readTick(mm));
            }
        } finally {
            exec.shutdown();
        }
    }

    // ---- counterweights: a blanket refusal must not pass --------------------

    /**
     * The wide-open posture is the shipped default and must still be able to
     * read. Without this a gate that refused everything would satisfy both
     * denials above — the failure mode of "fixing" a gate by making it a blanket
     * denial, which is the same defect in a new costume.
     */
    @Test
    public void theWideOpenSubjectStillReads() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            MmAccess mm = productionMmAccess(
                    productionRegistry(exec, engineWith(SeToken.wideOpen())));
            assertEquals("the shipped wide-open posture must not be denied by the gate",
                    PROBE.lastTickMonoNs(), readTick(mm));
        } finally {
            exec.shutdown();
        }
    }

    /** The static read's counterweight. */
    @Test
    public void theWideOpenSubjectStillReadsAStatic() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            MmAccess mm = productionMmAccess(
                    productionRegistry(exec, engineWith(SeToken.wideOpen())));
            assertNotNull("the shipped wide-open posture must still read statics",
                    mm.getStaticField(GameClock.class, "INSTANCE"));
        } finally {
            exec.shutdown();
        }
    }

    /** And the write path, so the L4 counterweight is real too. */
    @Test
    public void theWideOpenSubjectStillWrites() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            MmAccess mm = productionMmAccess(
                    productionRegistry(exec, engineWith(SeToken.wideOpen())));
            long before = readTick(mm);
            mm.setField(PROBE, FIELD, before + 1, null);
            assertEquals("a write must land under the shipped posture",
                    before + 1, readTick(mm));
        } finally {
            exec.shutdown();
        }
    }

    // ---- the subject is LIVE, not snapshotted at wiring time ---------------

    /**
     * A revocation that lands AFTER {@code registerBuiltins} must bite at the
     * implementation boundary, exactly as it does at the dispatch boundary. A
     * gate that captured its subject in the constructor would pass every test
     * above and fail this one: it would stop biting the moment the operator
     * tightens the posture, which is the whole purpose of the in-session levers.
     */
    @Test
    public void aCapabilityRevokedAfterWiringBitesImmediately() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            SeLocalMonitor engine = engineWith(SeToken.wideOpen());
            MmAccess mm = productionMmAccess(productionRegistry(exec, engine));

            readTick(mm); // precondition: a wide-open subject reads before the revoke
            assertTrue("revoke_capability must report that it narrowed the subject",
                    engine.revokeCapability(CapabilitySid.CAP_MEMORY_READ));

            try {
                Object v = mm.getField(PROBE, FIELD);
                fail("a read returned " + v + " AFTER revoke_capability(CAP_MEMORY_READ): the "
                        + "gate snapshotted its subject at wiring time, so that in-session "
                        + "kill switch does not reach the implementation boundary");
            } catch (SecurityException e) {
                assertTrue("the revoke must deny at the capability layer: " + e.getMessage(),
                        e.getMessage().contains("L5 capability"));
            }
        } finally {
            exec.shutdown();
        }
    }

    /** The same live-subject property on the L4 axis: the other kill switch. */
    @Test
    public void aPrivilegeDisabledAfterWiringBitesImmediately() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            SeLocalMonitor engine = engineWith(SeToken.wideOpen());
            MmAccess mm = productionMmAccess(productionRegistry(exec, engine));

            long before = readTick(mm);
            mm.setField(PROBE, FIELD, before + 1, null);
            assertEquals("precondition: the write lands before the disable",
                    before + 1, readTick(mm));
            assertTrue("disable_privilege must report that it narrowed the subject",
                    engine.disablePrivilege(Privilege.SE_DEBUG_CLASS));

            long mark = readTick(mm);
            try {
                mm.setField(PROBE, FIELD, mark + 100, null);
                fail("a write landed after disable_privilege(SE_DEBUG_CLASS): the gate "
                        + "snapshotted its subject, so that kill switch does not reach the "
                        + "implementation boundary");
            } catch (SecurityException e) {
                assertTrue("the disable must deny at the privilege layer: " + e.getMessage(),
                        e.getMessage().contains("L4 privilege"));
                assertEquals("and nothing may have been written", mark, readTick(mm));
            }
        } finally {
            exec.shutdown();
        }
    }

    // ---- the capability the docs actually name -----------------------------

    /**
     * The capability {@code HookTools}' javadoc names for its two gate sites:
     * {@code CAP_CLASS_RETRANSFORM}. Asserted against the gate object
     * PRODUCTION wired, not against a gate built here — the wiring is the thing
     * that was broken.
     *
     * <p><b>Why this asks the gate directly instead of invoking the tool.</b>
     * Reaching {@code HookTools} through {@code IoManager.invoke} would prove
     * nothing about the gate: the by-name L5 row in {@link CapabilityCatalog}
     * already requires {@code CAP_CLASS_RETRANSFORM} for {@code install_hook},
     * so that table refuses first and the gate's own call site never answers.
     * Driving the production gate instance is the only way to pin that second
     * term, and it is honest about the limit — it shows the gate
     * {@code HookTools} holds enforces the capability its documentation claims.
     */
    @Test
    public void theDocumentedRetransformTermIsEnforcedByTheProductionGate() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            AccessGate productionGate = productionGateOf(productionMmAccess(
                    productionRegistry(exec, engineWith(noCapabilities()))));
            try {
                productionGate.require(CapabilitySid.CAP_CLASS_RETRANSFORM);
                fail("the production gate allowed CAP_CLASS_RETRANSFORM for a subject holding "
                        + "no capability: the documented second term of the AND is not real");
            } catch (SecurityException e) {
                assertTrue("the deny must name the capability the docs name: " + e.getMessage(),
                        e.getMessage().contains("CAP_CLASS_RETRANSFORM"));
                assertTrue("and attribute it to the capability layer: " + e.getMessage(),
                        e.getMessage().contains("L5 capability"));
            }
        } finally {
            exec.shutdown();
        }
    }

    /** That test's counterweight: a subject holding the capability is admitted. */
    @Test
    public void theRetransformTermAdmitsASubjectThatHoldsIt() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            AccessGate productionGate = productionGateOf(productionMmAccess(
                    productionRegistry(exec, engineWith(SeToken.wideOpen()))));
            productionGate.require(CapabilitySid.CAP_CLASS_RETRANSFORM);
        } finally {
            exec.shutdown();
        }
    }

    // ---- the gate's own contract, independent of the wiring ---------------

    /**
     * The wired gate is not the no-op. Asserted on the CLASS because a purely
     * behavioural proxy cannot separate "wired to a real gate" from "wired to a
     * gate that happens to deny in these particular cases" — but class identity
     * is not the interesting claim on its own either, which is why it sits
     * alongside the behavioural tests rather than replacing them.
     */
    @Test
    public void theWiredGateIsNotTheNoOp() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            AccessGate gate = productionGateOf(productionMmAccess(
                    productionRegistry(exec, engineWith(SeToken.wideOpen()))));
            assertFalse("production still wires the no-op gate: " + gate.getClass().getName(),
                    gate instanceof AllowAllGate);
            assertTrue("production must wire a gate backed by the live monitor, got "
                    + gate.getClass().getName(), gate instanceof MonitorAccessGate);
        } finally {
            exec.shutdown();
        }
    }

    /**
     * A null subject fails CLOSED. With nothing to evaluate against the safe
     * answer is refusal — a gate that allowed here would be failing open on the
     * one condition it cannot reason about.
     */
    @Test
    public void theGateFailsClosedOnAnAbsentSubject() {
        MonitorAccessGate gate = MonitorAccessGate.overSubject(() -> null);
        try {
            gate.require(CapabilitySid.CAP_MEMORY_READ);
            fail("a null subject must deny, not allow: with nothing to evaluate against the "
                    + "safe answer is refusal");
        } catch (SecurityException e) {
            assertTrue("the deny must say it failed closed: " + e.getMessage(),
                    e.getMessage().contains("failing closed"));
        }
    }

    /**
     * A null requirement is a caller bug, and must be rejected rather than read
     * as "no requirement" — treating it as absent is failing open on a bug, the
     * same defect one level down.
     */
    @Test
    public void aNullCapabilityIsARejectedCallNotAPermission() {
        MonitorAccessGate gate = MonitorAccessGate.overSubject(SeToken::wideOpen);
        try {
            gate.require(null);
            fail("a null capability SID must be rejected, not treated as an absent requirement");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("capability SID is required"));
        }
    }

    /**
     * Both terms AND-composed, and the capability is matched by identity rather
     * than by "some capability": holding {@code CAP_MEMORY_WRITE} must not admit
     * a {@code CAP_MEMORY_READ} operation, and an empty set must deny by default.
     */
    @Test
    public void theCapabilityAndPrivilegeTermsAreAndComposed() {
        MonitorAccessGate writer = MonitorAccessGate.overSubject(() ->
                new SeToken("t", Ring.R_MINUS_1, IntegrityLevel.SYSTEM,
                        PrivilegeToken.wideOpen(), Set.of(CapabilitySid.CAP_MEMORY_WRITE)));

        // Holds CAP_MEMORY_WRITE and asks for it: allowed.
        writer.require(CapabilitySid.CAP_MEMORY_WRITE, Privilege.SE_DEBUG_CLASS);

        try {
            writer.require(CapabilitySid.CAP_MEMORY_READ, Privilege.SE_DEBUG_CLASS);
            fail("holding CAP_MEMORY_WRITE must not admit a CAP_MEMORY_READ operation");
        } catch (SecurityException e) {
            assertTrue(e.getMessage().contains("L5 capability"));
        }

        MonitorAccessGate strict =
                MonitorAccessGate.overSubject(() -> SeLocalMonitor.strictSubject(Set.of()));
        try {
            strict.require(CapabilitySid.CAP_MEMORY_READ);
            fail("an empty capability set must deny by default");
        } catch (SecurityException e) {
            assertTrue(e.getMessage().contains("L5 capability"));
        }
    }
}
