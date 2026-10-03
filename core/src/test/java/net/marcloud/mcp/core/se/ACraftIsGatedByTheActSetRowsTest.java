package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.action.ActTools;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.ob.ObManager;

import org.junit.Test;

/**
 * The craft capability must be behind all five gates -- and the honest shape of that claim is the
 * whole point of this file, because the craft path adds NO tool name.
 *
 * <p><b>Why there is no row to add.</b> The design put craft on {@code act_set} as
 * {@code interact kind='craft'} rather than on a tool of its own (the argument is in
 * {@code CraftWire}'s javadoc). Every gate table in this repository keys on the tool NAME, so a new
 * tool would have meant five new rows -- and W6 already shipped four HIGH writers with no L4 row at
 * all, leaving {@code disable_privilege(SE_NET_RAW)} with no kill switch for them. As a kind on
 * {@code act_set}, craft inherits rows that are already declared and already tested.
 *
 * <p><b>So "is there a row for it" is the wrong question and "does the craft path stop when this
 * layer says so" is the right one.</b> A row nothing consults is the {@code dev_probe} shape at L2,
 * and a row for a name nothing registers is the same defect pointing the other way. Each test below
 * therefore pins the DECLARATION and, where the gate can be driven, the ENFORCEMENT too; they fail
 * independently and the mutation note on each says which row to delete to watch it go red.
 *
 * <p>Every enforcement test is non-vacuous the same way: the call must first be shown to RUN with
 * the gate satisfied, so a later denial is evidence about the gate rather than about a broken tool.
 */
public final class ACraftIsGatedByTheActSetRowsTest {

    /** The tool the craft capability lives under. One name, so one set of rows. */
    private static final String CRAFT_UNDER = "act_set";

    // ===== L2: ring =====

    /**
     * L2. {@code act_set} is declared R1 and craft changes shared, server-visible state, so R1 is
     * the honest ring -- the same one a dig, a drop and a placement already carry.
     *
     * <p>Deliberately not R2: R2 is what {@code craft_plan} holds, and craft_plan genuinely only
     * reads. The two halves of this capability sit on opposite sides of that line, which is the
     * whole reason they are two verbs rather than one with a flag.
     *
     * <p><b>Mutation:</b> delete the {@code Map.entry("act_set", R1)} row from
     * {@code Ring.BUILTIN_RINGS}. {@link Ring#forBuiltin} then returns the {@code null} this test
     * requires to be absent, and {@code RegisteredBuiltinGateCoverageTest} goes red as well --
     * a registered name carrying no ring row is exactly the {@code dev_probe} defect.
     */
    @Test
    public void l2_theCraftCapabilityIsInheritedAtRingR1() {
        assertNotNull(CRAFT_UNDER + " must declare an L2 ring row; a craft spends ingredients and "
                + "changes server-visible state", Ring.forBuiltin(CRAFT_UNDER, null));
        assertEquals("craft changes shared state, so it inherits act_set's R1 -- not the R2 that "
                        + "the read-only craft_plan holds",
                Ring.R1, Ring.forBuiltin(CRAFT_UNDER, Ring.R3));
    }

    // ===== L3: integrity =====

    /**
     * L3. {@code act_set} writes at HIGH integrity. A craft writes the container and the bag, so
     * HIGH is the correct floor for the craft path.
     *
     * <p><b>Mutation:</b> delete the {@code act_set} entry from
     * {@code SeToolRequirement.L3_WRITES}; {@link SeToolRequirement#forTool} then reports a null
     * write level, so an unintegrified caller could craft with no integrity floor at all.
     */
    @Test
    public void l3_theCraftCapabilityIsInheritedAtIntegrityHigh() {
        assertEquals("a craft writes the container and the bag, so it inherits act_set's HIGH "
                        + "integrity floor",
                IntegrityLevel.HIGH, SeToolRequirement.forTool(CRAFT_UNDER, true).writesResourceAt());
    }

    // ===== L4: privilege -- the one W6 taught the hard way =====

    /**
     * L4, the declaration. THE ONE W6 TAUGHT THE HARD WAY.
     *
     * <p>W6 shipped four typed {@code send_*} tools as HIGH writers with no L4 privilege row, so
     * {@code disable_privilege(SE_NET_RAW)} had nothing to switch off and they kept running. The
     * craft path rides on {@code act_set}, whose L4 row was added as that lesson's fix, so this is
     * the assertion that says the fix held for the capability that arrived afterwards.
     *
     * <p>{@code SE_GUI_INTERACT} would be the plausible wrong answer and is named here because it is
     * wrong for a specific reason: that privilege gates the GUI-widget surface, and a craft is a
     * container click, not a widget. No privilege at all is the W6 defect.
     *
     * <p><b>Mutation:</b> delete {@code Map.entry("act_set", Privilege.SE_WORLD_WRITE)} from
     * {@code SeToolRequirement.L4_PRIVILEGE}. The row test below goes red, the enforcement test
     * below goes red on its second half, and {@code PolicySideTableDriftTest} reports a HIGH
     * writer carrying no L4 privilege -- which is W6, reproduced.
     */
    @Test
    public void l4_theCraftCapabilityIsInheritedTheWorldWritePrivilege() {
        assertEquals("a craft spends the player's bag and writes a container, which is what "
                        + "SE_WORLD_WRITE is for. SE_GUI_INTERACT gates GUI widgets and is wrong "
                        + "here; no privilege at all is the W6 defect",
                Privilege.SE_WORLD_WRITE,
                SeToolRequirement.forTool(CRAFT_UNDER, true).requiredPrivilege());
    }

    /**
     * L4, the enforcement: revoking the privilege must actually stop the call.
     *
     * <p>The row test proves a declaration exists; this proves the gate READS it. They fail
     * independently -- a row nothing consults is the {@code dev_probe} shape at L2 -- so both are
     * here rather than either.
     *
     * <p>Registered through the real {@link ActTools}, not a stub, so the name under test is the
     * one production registers and the row lookup is the production one.
     *
     * <p><b>Mutation:</b> as above. The inverse also fails this test: pointing the row at a
     * privilege the test never revokes leaves the first half green and the second half red.
     */
    @Test
    public void l4_revojingTheWorldWritePrivilegeStopsTheCraftCall() {
        IoSupervisor exec = new IoSupervisor(2, 2000L);
        try {
            SeLocalMonitor engine = new SeLocalMonitor(
                    new SeClearancePolicy(Ring.R1, "tok"), SeToken.wideOpen());
            IoManager reg = new IoManager(exec, engine);
            new ActTools(new ActRuntime()).registerAll(reg);

            Map<String, Object> call = craftCall();

            CallToolResult ran = reg.invoke(CRAFT_UNDER, call);
            assertFalse("with every gate satisfied the craft call must RUN; a denial here would "
                    + "mean the rest of this test proves nothing about the gate: "
                    + ran.content(), Boolean.TRUE.equals(ran.isError()));

            assertTrue("SE_WORLD_WRITE must be revocable so the kill switch can be shown to work; "
                    + "a subject that never held it cannot demonstrate one",
                    engine.disablePrivilege(Privilege.SE_WORLD_WRITE));

            CallToolResult denied = reg.invoke(CRAFT_UNDER, call);
            assertTrue("a craft MUST be denied once SE_WORLD_WRITE is revoked -- it is a world "
                    + "write wearing act_set's name, and this is the assertion W6's four tools "
                    + "would have failed", Boolean.TRUE.equals(denied.isError()));
            assertTrue("the refusal must come from L4 and name the privilege: " + denied.content(),
                    denied.content().toString().contains("SE_WORLD_WRITE"));
        } finally {
            exec.shutdown();
        }
    }

    // ===== L5: capability =====

    /**
     * L5. {@code act_set} requires {@code CAP_WORLD_WRITE}, so the capability spend a craft makes is
     * refused to a subject whose capability was revoked.
     *
     * <p><b>Mutation:</b> delete the {@code act_set} entry from {@code CapabilityCatalog.REQUIRED}
     * and {@link CapabilityCatalog#requiredFor} returns the empty set for a built-in, so the
     * revoke stops mattering for this tool.
     */
    @Test
    public void l5_theCraftCapabilityIsInheritedTheWorldWriteCapability() {
        assertEquals("a craft writes live game state, so it requires CAP_WORLD_WRITE -- "
                        + "CAP_WORLD_READ is craft_plan's, and the read half must not inherit it",
                java.util.Set.of(CapabilitySid.CAP_WORLD_WRITE),
                CapabilityCatalog.requiredFor(CRAFT_UNDER, true));
    }

    // ===== L6: handles =====

    /**
     * L6. The craft path must NOT be a handle operation, and the observable proof is that a
     * handle-less craft call passes.
     *
     * <p>Negative on purpose, and that IS the registration. {@link ObManager#checkRequest} grants a
     * handle-less call to any tool absent from {@code HANDLE_OPS}; a craft reaches live state
     * through the player's own open container, not through a frozen object handle, so it has
     * nothing to freeze and must not borrow the debugger's L6 machinery. Listing it there would
     * make {@code strictHandles} refuse every craft as a handle-op missing its handle -- the exact
     * mistake {@code L6DebugGateThroughRegistryTest} records, where the folded debug tools fell
     * through to a READ default.
     *
     * <p><b>Mutation:</b> add {@code Map.entry("act_set", ObAccessMask.WRITE.bit())} to
     * {@code ObManager.HANDLE_OPS}; this test goes red, because a handle-less craft is then
     * routed into the handle table instead of being waved through.
     */
    @Test
    public void l6_aCraftCallSuppliesNoHandleAndIsNotTreatedAsAHandleOperation() {
        ObManager objects = new ObManager(null, 8, 60_000L);
        SeAccessCheck check = objects.checkRequest(SeToken.wideOpen(),
                new IoRequestPacket(CRAFT_UNDER, craftCall(), true));
        assertTrue("a handle-less craft must pass L6 -- it is not a handle-op, and turning it into "
                + "one would make every craft uncallable under a strict-handle posture. Denied: "
                + check.reason(), check.allow());
    }

    /**
     * L6, under the posture where a false handle-op is actually fatal.
     *
     * <p>The default posture waves a handle-less call through whatever the table says, so the test
     * above alone cannot tell "not a handle-op" from "listed but not enforced". Under
     * {@code -Dmcp.core.hardened=true} a tool listed in {@code HANDLE_OPS} and called without a
     * handle is DENIED -- which is what makes a stray listing fatal rather than cosmetic, and it is
     * why the same mistake L6DebugGateThroughRegistryTest records turned the folded debug tools
     * from restricted into unusable.
     *
     * <p><b>Mutation:</b> add {@code Map.entry("act_set", ObAccessMask.WRITE.bit())} to
     * {@code ObManager.HANDLE_OPS} and this goes red on its second half: every craft becomes
     * uncallable under a hardened posture.
     */
    @Test
    public void aCraftCallStillPassesUnderTheStrictHandlePosture() {
        ObManager strict = new ObManager(null, 8, 60_000L, true);
        SeAccessCheck lenient = new ObManager(null, 8, 60_000L)
                .checkRequest(SeToken.wideOpen(),
                        new IoRequestPacket(CRAFT_UNDER, craftCall(), true));
        assertTrue("the lenient posture must wave a handle-less craft through: " + lenient.reason(),
                lenient.allow());

        SeAccessCheck hardened = strict.checkRequest(SeToken.wideOpen(),
                new IoRequestPacket(CRAFT_UNDER, craftCall(), true));
        assertTrue("a craft must ALSO pass under the strict-handle posture, which denies every "
                        + "handle-less call to a tool listed as a handle-op. Denied: "
                        + hardened.reason(), hardened.allow());
    }

    // ===== reachability through production wiring =====

    /**
     * The craft verb must be reachable on the surface {@link McpCore#registerBuiltins} actually
     * builds.
     *
     * <p>Not a string search over the source, and not a hand-copied provider list. The registry is
     * built by driving the one production registration method, so this fails when the tool is not
     * registered -- the "provider exists but is never wired" shape {@code chat_read} sat in, and
     * the reason {@code RegisteredBuiltinGateCoverageTest} deleted its own copy of the list.
     *
     * <p>The kind must be in the SCHEMA the tool publishes, not merely in the parser: the boundary
     * validates against the declared enum before the handler runs, which is precisely where the
     * DROP verb was once refused while its parser and controller were both green.
     *
     * <p><b>Mutation:</b> drop {@code new ActTools().registerAll(registry)} from
     * {@code McpCore.registerBuiltins} and this goes red on the name assertion.
     */
    @Test
    public void theCraftVerbIsReachableOnTheRegisteredModelSurface() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            IoManager reg = registerEveryBuiltin(exec);
            TreeSet<String> names = new TreeSet<>(reg.names());
            assertTrue("act_set must be on the registered surface or the craft verb is unreachable "
                    + "by a model; registered: " + names, names.contains(CRAFT_UNDER));

            var cap = reg.get(CRAFT_UNDER);
            assertNotNull("act_set must be registered as a capability", cap);

            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) cap.spec().tool().inputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> interact = (Map<String, Object>) props.get("interact");
            @SuppressWarnings("unchecked")
            Map<String, Object> interactProps = (Map<String, Object>) interact.get("properties");

            @SuppressWarnings("unchecked")
            List<String> kinds =
                    (List<String>) ((Map<String, Object>) interactProps.get("kind")).get("enum");
            assertTrue("the published interact.kind enum must carry 'craft'; it publishes "
                    + kinds, kinds.contains("craft"));
            assertTrue("'item' must be declared on the published schema, or the parser knows a "
                            + "key the boundary has never heard of",
                    interactProps.containsKey("item"));
        } finally {
            exec.shutdown();
        }
    }

    /** The registry {@link McpCore#registerBuiltins} builds -- production's one registration site. */
    private static IoManager registerEveryBuiltin(IoSupervisor exec) {
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
        IoManager reg = new IoManager(exec, engine);
        EventBus bus = new EventBus();
        McpCore core = new McpCore();
        core.registerBuiltins(reg, engine,
                new ObManager(null, 8, 60_000L),
                new ToolContext(null, null, null, null, null),
                new LdrEngine(ACraftIsGatedByTheActSetRowsTest.class.getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return reg;
    }

    /** The arguments a model sends to craft one {@code stick}. */
    private static Map<String, Object> craftCall() {
        return Map.of("interact", Map.of("kind", "craft", "item", "stick"));
    }
}