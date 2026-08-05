package net.marcloud.mcp.core.drivers.observe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.drivers.world.ObserveProfile;
import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.PacketJournal;
import net.marcloud.mcp.core.ke.event.EventBus;

/**
 * The observation token budget, pinned at the values that make the tool's own promises true.
 *
 * <p><b>Why this file exists.</b> Six one-token edits to the budget were compiled and run against
 * the whole suite on 2026-08-05 and every one stayed GREEN: {@code SPARSE}'s grid radius widened
 * from 4 to the clamp ceiling 16, {@code COMBAT}'s {@code emitProfile} flipped to true, the entity
 * cap at the {@code WorldViewCapture} call site replaced by {@code Integer.MAX_VALUE}, the
 * {@code sections} gate short-circuited to "sample everything", the {@code world_view} handler's
 * radius default pinned to 16 instead of read from the profile, and the packet window's default
 * noise drop turned off. Every one of them costs the caller context window on a poll it asked to be
 * cheap, and each one is stated to the model in a tool description, so a drifted flag does not merely
 * cost tokens -- it makes the description a lie.
 *
 * <p>What let them survive was hollow shape rather than missing files. {@code WorldViewJsonTest}
 * reads {@code maxEntities} through a {@code >} comparison and {@code emitProfile} for two of the
 * three profiles; four of the seven knobs ({@code gridRadius}, {@code vBelow}, {@code vAbove},
 * {@code entityRangeMul}) were referenced by no test at all. So the flags are asserted here at their
 * SPECIFIC VALUES, one profile at a time, because an assertion written as a comparison between two
 * profiles agrees with both of them moving.
 *
 * <p><b>Why three of these are asserted on the compiled bytecode.</b> The three wiring mutations
 * live at call sites inside {@code WorldViewCapture.capture} and the {@code world_view} handler
 * lambda, and both need a live {@code EntityPlayerSP} + {@code WorldClient} that nothing in
 * {@code core/src/test} can construct -- which is exactly how they survived. {@code capture}
 * returns {@code WorldView.absent()} before reaching any of them without one. Reading the argument a
 * call site actually passes is the same technique {@code LocalGridColumnsArePinnedAtTheirBoundaryTest}
 * uses for the three private, world-bound methods one class over: it fails on the mutation instead of
 * agreeing with it. The bytecode is required, not assumed present -- skipping would let the mutation
 * live.
 *
 * <p><b>The known ceiling of those three, stated because this repo just paid for the confusion.</b>
 * Asserting the instruction a call site emits is a SHAPE assertion, and shape assertions have a
 * failure mode that behavioural ones do not: a body with the right shape and the wrong behaviour
 * satisfies them. That is not hypothetical here -- {@code TrustAnchorRevocationTest}'s source-text
 * guard on {@code Compat.defaultTrustAnchors} was defeated on 2026-08-05 by a body containing every
 * literal it demanded while restoring the defect it existed to forbid
 * ({@link net.marcloud.mcp.core.compat.ABrokenRootChainDisarmsTheProductionCompositionTest}).
 * These three read a field rather than grep prose, so the ceiling is lower, but it is the same
 * ceiling: an implementation that reads {@code maxEntities} and then ignores the value passes.
 * They are the strongest assertion available headless, not the strongest assertion possible. The
 * upgrade is a live probe that asks the running client what the cap actually did; until one exists,
 * treat a green here as "the wiring is present", not as "the budget is honoured".
 *
 * <p>The clamp ceiling is RECOVERED from the compiled handler rather than hand-copied, then the
 * schema is required to quote it. A hand-copied threshold stays consistent with a description
 * forever, including after the code stops honouring it (handoff-2026-08-05 section 5(3)).
 */
public class ObserveProfilesArePinnedAtTheirBudgetTest {

    /** Internal name of the enum whose fields the call sites below must be reading. */
    private static final String OBSERVE_PROFILE = "net/marcloud/mcp/core/drivers/world/ObserveProfile";
    private static final String WORLD_VIEW_CAPTURE =
            "net/marcloud/mcp/core/drivers/world/WorldViewCapture";
    private static final String TOOL_REGISTRY = "net/marcloud/mcp/core/io/transport/ToolRegistry";
    private static final String LOCAL_GRID = "net/marcloud/mcp/core/drivers/world/LocalGrid";

    // Real 1.8.9 FQCNs: PacketFilter matches its NOISE patterns against the fully-qualified name,
    // so an approximation here would exercise a path production never takes.
    private static final String KEEPALIVE = "net.minecraft.network.play.server.S00PacketKeepAlive";
    private static final String TIME = "net.minecraft.network.play.server.S03PacketTimeUpdate";
    private static final String VELOCITY = "net.minecraft.network.play.server.S12PacketEntityVelocity";
    private static final String SIGNAL = "net.minecraft.network.play.server.S06PacketUpdateHealth";

    private EventBus bus;
    private PacketJournal journal;
    private ObserveTools tools;

    @Before
    public void setUp() {
        GameClock.INSTANCE.reset();
        bus = new EventBus();
        journal = new PacketJournal(64);
        journal.attach(bus);
        // seams == null -> tapInstalled() is false; the journal is non-empty in every test below,
        // so the honest tap guard never fires and the filter decides the payload alone.
        tools = new ObserveTools(GameClock.INSTANCE, null, journal, null);
    }

    // ===== the seven budget knobs, per profile, at their specific values =====

    @Test
    public void sparseIsPinnedAtTheCheapHeartbeatItIsAdvertisedAs() {
        ObserveProfile p = ObserveProfile.SPARSE;
        assertEquals("SPARSE's grid radius is the dominant term of its cost: 4 is "
                + columns(4) + " columns, and the clamp ceiling 16 would be " + columns(16)
                + " -- a " + (columns(16) / columns(4)) + "x payload for the one profile whose only "
                + "reason to exist is being the cheap heartbeat a caller can poll without spending "
                + "its context window", 4, p.gridRadius);
        assertEquals("the vertical window below the feet is 1 layer on SPARSE; widening it multiplies "
                + "the per-column work at every one of those columns", 1, p.vBelow);
        assertEquals("and 1 above, for the same reason", 1, p.vAbove);
        assertFalse("SPARSE must NOT emit the run-length vertical profile: that is the difference "
                + "between a heartbeat and full geometry, and the grid would also start advertising "
                + "mode \"column\" to a caller told it asked for the cheapest view", p.emitProfile);
        assertEquals("the entity cap the description quotes to the model as \"sparse 5\"; a wider cap "
                + "ships entities the caller was promised it would not pay for", 5, p.maxEntities);
        assertEquals("the entity scan range is exactly the grid radius on SPARSE (x1.0): a larger "
                + "multiplier scans further than the grid the caller can even see",
                1.0, p.entityRangeMul, 0.0);
        assertFalse("SPARSE must not carry hp/name per entity -- that is what COMBAT is for, and "
                + "paying for it here is cost with no question behind it", p.entityHp);
    }

    @Test
    public void exploreIsPinnedAtTheFullGeometryProfile() {
        ObserveProfile p = ObserveProfile.EXPLORE;
        assertEquals("EXPLORE is the default profile, so its radius is what an unqualified "
                + "world_view costs: " + columns(8) + " columns", 8, p.gridRadius);
        assertEquals("vBelow 3 is quoted in the drop legend and in ColumnRevealsDropDepthTest; "
                + "changing it changes what a caller is told about a fall", 3, p.vBelow);
        assertEquals("and 4 above, the window a caller reads head clearance out of", 4, p.vAbove);
        assertTrue("EXPLORE is the profile that DOES emit the run-length vertical profile -- the "
                + "positive counterpart to the two assertions that it stays off elsewhere, without "
                + "which \"full geometry\" is a promise nothing keeps", p.emitProfile);
        assertEquals("the cap the description quotes as \"explore 12\"", 12, p.maxEntities);
        assertEquals("EXPLORE scans entities to twice its grid radius", 2.0, p.entityRangeMul, 0.0);
        assertTrue("EXPLORE carries hp: the positive counterpart to SPARSE not carrying it",
                p.entityHp);
    }

    @Test
    public void combatIsPinnedAtTheWideEntityNetWithAFeetLayerGridOnly() {
        ObserveProfile p = ObserveProfile.COMBAT;
        assertEquals("COMBAT trades grid for entities, so its radius sits between the other two: "
                + columns(6) + " columns", 6, p.gridRadius);
        assertEquals("two layers below the feet", 2, p.vBelow);
        assertEquals("and two above", 2, p.vAbove);
        assertFalse("COMBAT is documented as \"wide entity net with hp, feet-layer grid only\". With "
                + "emitProfile true the run-length vertical profile is emitted at every one of its "
                + columns(6) + " columns and the grid's advertised mode flips from \"surface\" to "
                + "\"column\" -- so a combat poll, the one a caller repeats while something is "
                + "hitting it, silently starts carrying full geometry and its own description stops "
                + "being true of it", p.emitProfile);
        assertEquals("the cap the description quotes as \"combat 24\"", 24, p.maxEntities);
        assertEquals("COMBAT's whole point is the wide net: three times the grid radius",
                3.0, p.entityRangeMul, 0.0);
        assertTrue("and hp per living entity, which is the reason to spend the wider net at all",
                p.entityHp);
    }

    /**
     * The mode label only separates the profiles while exactly one of them emits the vertical
     * profile. Guards the three assertions above from going hollow the way a cross-profile
     * comparison does: if every profile emitted it, "feet-layer grid only" would be unrepresentable.
     */
    @Test
    public void exactlyOneProfileEmitsTheVerticalProfileSoTheModeLabelStillSeparatesThem() {
        long emitting = java.util.Arrays.stream(ObserveProfile.values())
                .filter(p -> p.emitProfile).count();
        assertEquals("exactly one profile may emit the run-length vertical profile -- EXPLORE, the "
                + "one sold as full geometry. A second one makes \"column\" the mode of a poll the "
                + "caller chose for being cheap, and the two cheap profiles then cost what the "
                + "expensive one costs: " + java.util.Arrays.toString(ObserveProfile.values()),
                1, emitting);
        assertTrue("and it must be EXPLORE that does, not whichever one drifted",
                ObserveProfile.EXPLORE.emitProfile);
    }

    /** (2r+1)^2 -- the column count a radius actually costs, so the messages above quote work. */
    private static int columns(int radius) {
        return (2 * radius + 1) * (2 * radius + 1);
    }

    // ===== the wiring: the budget only binds while these call sites read the profile =====

    /**
     * The ceiling is recovered from the handler's own clamp and then required of the schema, rather
     * than hand-copied into both. A copied threshold agrees with the description forever -- including
     * after the code stops honouring it -- so recovering it catches a changed constant AND a changed
     * comparison, and it is what makes "SPARSE is below the ceiling" a statement about behaviour.
     */
    @Test
    public void theRadiusCeilingTheSchemaQuotesIsTheOneTheHandlerEnforces() {
        int ceiling = clampCeiling();
        assertEquals("the world_view handler clamps a caller's radius to 16. The number is the top of "
                + "the cost curve the profiles are spread across, so recovering it from the code is "
                + "what lets the profile assertions be about payload rather than about symbols",
                16, ceiling);
        assertTrue("the schema must quote the ceiling the handler actually enforces: a model told "
                + "\"1-16\" while the clamp stopped at something else either has requests silently "
                + "shrunk or is refused a radius it was invited to ask for. Schema said: "
                + radiusSchemaDescription(),
                radiusSchemaDescription().contains("1-" + ceiling));

        // The point of the recovery: SPARSE has to sit STRICTLY below the ceiling, or the cheap
        // profile is the maximum grid and the 2 KB / 38 KB spread the profiles exist to offer is
        // gone. Asserted against the recovered number, not against a literal beside it.
        assertTrue("SPARSE's default radius (" + ObserveProfile.SPARSE.gridRadius + ") must stay "
                + "strictly below the ceiling (" + ceiling + "): at the ceiling the heartbeat costs "
                + columns(ceiling) + " columns, the same as the widest view a caller can request, so "
                + "choosing sparse would buy nothing and cost everything",
                ObserveProfile.SPARSE.gridRadius < ceiling);
        assertTrue("every profile's default must be requestable through the documented range too, or "
                + "the default is a radius the schema tells the model it cannot ask for",
                java.util.Arrays.stream(ObserveProfile.values())
                        .allMatch(p -> p.gridRadius >= 1 && p.gridRadius <= ceiling));
    }

    /**
     * The one line that IS the budget wiring: {@code int radius = prof.gridRadius}. It is the only
     * read of {@code gridRadius} anywhere, so pinning it to a literal collapses all three profiles
     * onto one grid while 'profile' still parses, still labels the payload and still caps entities.
     */
    @Test
    public void theWorldViewHandlerDefaultsTheRadiusFromTheProfileItsSchemaPromises() {
        MethodNode handler = methodReading(TOOL_REGISTRY, OBSERVE_PROFILE, "gridRadius");
        assertTrue("the method that reads gridRadius must be the world_view handler itself -- the "
                + "same body that parsed the caller's 'profile'. Read anywhere else and the profile "
                + "the caller chose is not the one sizing the grid",
                callsMethod(handler, "parse"));
        assertEquals("gridRadius must be read exactly where the default is taken, once. Replace that "
                + "read with a literal and sparse's " + columns(ObserveProfile.SPARSE.gridRadius)
                + " columns, combat's " + columns(ObserveProfile.COMBAT.gridRadius) + " and "
                + "explore's " + columns(ObserveProfile.EXPLORE.gridRadius) + " all become "
                + columns(16) + " -- every profile pays the widest grid while the schema keeps "
                + "telling the model the radius defaults \"from profile\"",
                1, fieldReads(TOOL_REGISTRY, OBSERVE_PROFILE, "gridRadius"));
        assertTrue("and the schema must keep making that promise, since it is the only place a model "
                + "learns the profile sizes the grid: " + radiusSchemaDescription(),
                radiusSchemaDescription().contains("from profile"));
    }

    /**
     * The entity cap and the flag that describes it must come from the SAME profile field.
     * {@code WorldViewCapture}'s javadoc claims the flag "cannot disagree with the list it
     * describes"; that holds only while both call sites read {@code prof.maxEntities}. Uncap the list
     * alone and the payload ships every entity in range WITH {@code entitiesCapped:true} beside it.
     */
    @Test
    public void theEntityCapAndItsFlagBothComeFromTheProfileTheCallerAsked() {
        MethodNode capture = method(WORLD_VIEW_CAPTURE, "capture");
        List<MethodInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode n = capture.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call
                    && (call.name.equals("entitiesSection") || call.name.equals("capTruncated"))) {
                sites.add(call);
            }
        }
        assertEquals("capture must build the entities section and its cap flag from one scan, at two "
                + "call sites -- entitiesSection and capTruncated. Neither exists to be optional: "
                + "without both there is nothing for this test to compare", 2, sites.size());
        for (MethodInsnNode call : sites) {
            FieldInsnNode cap = fieldPushedInto(call);
            assertNotNull("the cap handed to " + call.name + " must be a FIELD READ, not a literal. A "
                    + "literal cap is a cap the caller's chosen profile cannot set: with an "
                    + "effectively infinite one, sparse ships every entity in range instead of the 5 "
                    + "its description promises, and the caller pays that in context window on the "
                    + "poll it picked for being cheap", cap);
            assertEquals("and it must be ObserveProfile.maxEntities specifically -- both the list and "
                    + "the flag. Read different sources and the payload can ship an uncapped list "
                    + "labelled 'capped', which is the one disagreement WorldViewCapture's javadoc "
                    + "claims is impossible by construction",
                    OBSERVE_PROFILE + ".maxEntities", cap.owner + "." + cap.name);
        }
    }

    /**
     * {@code sections} is the caller's structural budget lever. With the gate short-circuited to
     * "sample everything", {@code craft_plan}'s {@code sections=["inventory"]} starts paying for a
     * full grid, and {@code entities}/{@code effects} can no longer be null -- so the whole
     * 'unsampled' honesty path becomes dead code while the description keeps promising
     * {@code 'entities':{'unsampled':true}}.
     */
    @Test
    public void captureConsultsTheRequestedSectionsInsteadOfSamplingEverything() {
        MethodNode capture = method(WORLD_VIEW_CAPTURE, "capture");
        assertTrue("capture must ask the section list whether it is EMPTY. That test is the whole "
                + "difference between \"the caller wants the default\" and \"the caller named its "
                + "budget\": lose it and a request for inventory alone silently samples the block "
                + "grid too, which at explore radius is tens of thousands of characters the answer "
                + "never uses",
                callsMethod(capture, "isEmpty"));
        assertTrue("and it must still consult the list per section, or 'sections' narrows nothing "
                + "even while the emptiness test survives -- the positive half of the same lever",
                callsMethod(method(WORLD_VIEW_CAPTURE, "want"), "contains"));
        assertEquals("every one of the six sections must go through that gate; a section that skips "
                + "it is sampled unconditionally and cannot be budgeted away",
                6, countCallsTo(capture, "want"));
    }

    /**
     * The mode label and the per-column cost are one flag, so COMBAT's {@code emitProfile} decides
     * both. Asserted structurally because {@code sampleColumnar} needs a live {@code WorldClient}:
     * the tests that touch the labels hand-build {@code LocalGrid} with "column"/"surface" literals
     * rather than deriving them from a profile, so nothing tied the label to the flag.
     */
    @Test
    public void theEmitProfileFlagDrivesBothThePerColumnCostAndTheAdvertisedModeLabel() {
        MethodNode sample = method(LOCAL_GRID, "sampleColumnar");
        assertTrue("the sampler must read emitProfile off the profile it was handed. That one flag is "
                + "both the per-column cost and the label the payload advertises, so a profile the "
                + "flag no longer reaches pays for geometry under a mode string that denies it",
                readsField(sample, OBSERVE_PROFILE, "emitProfile"));
        assertTrue("the flag must gate the per-column run-length work, which is the cost itself",
                callsMethod(sample, "runs"));
        assertTrue("and it must choose between exactly the two labels the caller branches on",
                loadsConstant(sample, "column") && loadsConstant(sample, "surface"));
    }

    // ===== the packet window's own budget: the default noise drop =====

    /**
     * The default drop is the token budget of the packet window, and {@code packets_tail} states it
     * to the model verbatim. Driven through the real handler, because the flag is computed in a
     * private helper whose only callers are the two packet tools.
     */
    @Test
    public void theDefaultPacketWindowDropsTheHighNoiseFloodItPromisesToDrop() {
        publish(SIGNAL, Map.of("hp", 20.0));
        publish(KEEPALIVE, Map.of("id", 1234));
        publish(TIME, Map.of("worldTime", 6000));
        publish(VELOCITY, Map.of("entityId", 7, "motionX", 12));

        List<String> kept = simpleNames(call(packetsTail(), Map.of()));
        assertEquals("an unqualified packets_tail must return ONLY the signal packet. The three noise "
                + "families are the highest-frequency traffic on the wire, so on a live server they "
                + "fill the default 50-entry window and EVICT the packet the caller was looking for -- "
                + "the caller then pays a full window of keepalives to be told nothing. Kept: " + kept,
                List.of("S06PacketUpdateHealth"), kept);
    }

    /**
     * The positive counterpart: the drop is a default a caller can turn off, not an amputation. Uses
     * an explicit include, which the description names as one of the two ways off ("or an explicit
     * include") and which {@code PacketFilter} honours by skipping the noise step entirely.
     */
    @Test
    public void anExplicitIncludeReachesTheNoisePacketsTheDefaultWindowHides() {
        publish(SIGNAL, Map.of("hp", 20.0));
        publish(KEEPALIVE, Map.of("id", 1234));

        List<String> kept = simpleNames(call(packetsTail(), Map.of("include", List.of(KEEPALIVE))));
        assertEquals("asking for a noise class by name must reach it. Otherwise the drop is not a "
                + "budget but a blind spot: a caller debugging a keepalive timeout has no way to see "
                + "the packet at all, and the description's \"or an explicit include\" is false. "
                + "Kept: " + kept,
                List.of("S00PacketKeepAlive"), kept);
    }

    /**
     * The flag the description actually documents, in both positions.
     *
     * <p>These two were the audit's find and could not be asserted when this file was written: the
     * comparison was {@code !Boolean.FALSE.equals(includeNoise)}, which inverted BOTH answers, so
     * either assertion would have shipped red or blessed the defect. The production side is fixed
     * (TRUE-equals), so they belong here now -- and they are the only assertions in the suite that
     * drive this flag at all, which is how it stayed inverted with the whole suite green.
     */
    @Test
    public void includeNoiseTrueReachesTheNoiseTheDefaultDrops() {
        publish(SIGNAL, Map.of("hp", 20.0));
        publish(KEEPALIVE, Map.of("id", 1234));

        List<String> kept = simpleNames(call(packetsTail(), Map.of("includeNoise", true)));
        assertEquals("the description says noise is \"dropped unless you set includeNoise=true\", so "
                + "true must KEEP it. Dropping here makes the documented escape hatch do nothing: a "
                + "caller debugging a keepalive timeout sets the one flag the schema offers, gets a "
                + "window with no keepalives in it, and concludes the packets are not arriving. "
                + "Kept: " + kept,
                List.of("S06PacketUpdateHealth", "S00PacketKeepAlive"), kept);
    }

    @Test
    public void includeNoiseFalseStillDropsTheNoise() {
        publish(SIGNAL, Map.of("hp", 20.0));
        publish(KEEPALIVE, Map.of("id", 1234));

        List<String> kept = simpleNames(call(packetsTail(), Map.of("includeNoise", false)));
        assertEquals("explicitly declining noise must behave like the default, not like the escape "
                + "hatch. This is the half that proves the fix is TRUE-equals rather than a flipped "
                + "sign: a plain negation would keep noise here, which is the caller being handed "
                + "exactly what it asked not to receive. Kept: " + kept,
                List.of("S06PacketUpdateHealth"), kept);
    }

    /**
     * The description must name the classes the filter actually drops, derived one family at a time.
     * A caller cannot reason about a window that silently omits traffic it was never told about.
     */
    @Test
    public void theDescriptionNamesTheTrafficTheWindowActuallyDrops() {
        String desc = packetsTail().tool().description();
        assertTrue("the drop must be stated, since a filtered window looks exactly like a quiet "
                + "server: " + desc, desc.contains("are dropped"));
        assertTrue("and the families must be enumerated as one legend, so a family name cannot be "
                + "satisfied by unrelated prose elsewhere in the description: " + desc,
                desc.contains("(keepalive/time/velocity)"));

        // Each documented family, proven dropped by the handler rather than merely mentioned.
        publish(SIGNAL, Map.of("hp", 20.0));
        for (Map.Entry<String, String> family : Map.of(
                "keepalive", KEEPALIVE, "time", TIME, "velocity", VELOCITY).entrySet()) {
            publish(family.getValue(), Map.of("n", 1));
            List<String> kept = simpleNames(call(packetsTail(), Map.of()));
            assertFalse("the description lists \"" + family.getKey() + "\" among the traffic it drops, "
                    + "so " + family.getValue() + " must actually be absent from a default window -- "
                    + "a documented drop the code does not perform is a description the caller budgets "
                    + "against and a window that fills with the packets it was told were removed. "
                    + "Kept: " + kept,
                    kept.contains(simpleName(family.getValue())));
        }
        assertTrue("while the signal packet stays -- the drop must be selective, not a mute button",
                simpleNames(call(packetsTail(), Map.of())).contains("S06PacketUpdateHealth"));
    }

    // ===== harness =====

    private void publish(String packetClass, Map<String, Object> fields) {
        bus.publish(new SeamPacketInboundEvent(
                new NettyTap.PacketTapHandler.MessageSnapshot(packetClass, "", fields)));
    }

    /**
     * The {@code packets_tail} spec. Reached reflectively because the factory is private and this is
     * the tool that CARRIES the noise-drop promise -- {@code packet_view} shares the filter but
     * documents none of it, so testing only the reachable one would leave the promise unguarded.
     */
    private SyncToolSpecification packetsTail() {
        try {
            Method m = ObserveTools.class.getDeclaredMethod("packetsTail");
            m.setAccessible(true);
            return (SyncToolSpecification) m.invoke(tools);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("packets_tail is gone, and the noise-drop promise with it", e);
        }
    }

    private static CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), args));
    }

    private static String simpleName(String packetClass) {
        return packetClass.substring(packetClass.lastIndexOf('.') + 1);
    }

    /** The simple names the handler actually shipped, oldest first. */
    private static List<String> simpleNames(CallToolResult r) {
        assertFalse("packets_tail must not error on a non-empty journal",
                Boolean.TRUE.equals(r.isError()));
        String json = null;
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                json = t.text();
            }
        }
        if (json == null) {
            fail("no text content in the packets_tail result");
        }
        Map<String, Object> out = Json.readObject(json);
        assertNotNull("packets_tail must return a JSON object", out);
        List<String> names = new ArrayList<>();
        for (Object entry : (List<?>) out.get("entries")) {
            names.add(String.valueOf(((Map<?, ?>) entry).get("simpleName")));
        }
        return names;
    }

    /** The radius knob's own schema text -- what the model is told about the default and the range. */
    private static String radiusSchemaDescription() {
        Object schema = worldViewTool().inputSchema();
        assertTrue("world_view's input schema must be a map to derive from", schema instanceof Map);
        Object props = ((Map<?, ?>) schema).get("properties");
        assertTrue("with a properties map", props instanceof Map);
        Object radius = ((Map<?, ?>) props).get("radius");
        assertNotNull("world_view must still accept 'radius', or the clamp guards nothing", radius);
        Object desc = ((Map<?, ?>) radius).get("description");
        assertNotNull("and 'radius' must be documented: the range and the default are the only place "
                + "a model learns what it may ask for", desc);
        return String.valueOf(desc);
    }

    private static io.modelcontextprotocol.spec.McpSchema.Tool worldViewTool() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool();
            }
        }
        throw new AssertionError("world_view is missing from the registry");
    }

    // ===== reading the call sites of the three world-bound bodies =====
    // The technique, and the helpers, follow LocalGridColumnsArePinnedAtTheirBoundaryTest: these
    // arguments are unobservable any other way, since capture() and the world_view handler both
    // return early without a live EntityPlayerSP + WorldClient that no test can build.

    /**
     * The clamp ceiling the handler enforces, read off the {@code Math.min} in the same body that
     * takes the radius default. Recovered rather than copied so a changed constant fails here.
     */
    private static int clampCeiling() {
        MethodNode handler = methodReading(TOOL_REGISTRY, OBSERVE_PROFILE, "gridRadius");
        AbstractInsnNode min = null;
        for (AbstractInsnNode n = handler.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call
                    && call.owner.equals("java/lang/Math") && call.name.equals("min")) {
                min = call;
            }
        }
        assertNotNull("the handler must clamp the caller's radius with Math.min, or the schema's upper "
                + "bound is advice rather than a limit", min);
        // Java evaluates arguments left to right, so the ceiling is the last constant pushed before
        // the call; the other operand (the caller's own number) is computed, not constant.
        for (AbstractInsnNode n = min.getPrevious(); n != null; n = n.getPrevious()) {
            Integer v = intConstant(n);
            if (v != null) {
                return v;
            }
        }
        throw new AssertionError("the clamp's ceiling is not a compile-time constant, so nothing can "
                + "pin the range the schema quotes to the model");
    }

    /** The field read whose value is pushed directly into {@code call}, or null if it is not one. */
    private static FieldInsnNode fieldPushedInto(AbstractInsnNode call) {
        AbstractInsnNode prev = call.getPrevious();
        while (prev != null && prev.getOpcode() < 0) {
            prev = prev.getPrevious();
        }
        return prev instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD ? f : null;
    }

    /** One method of a compiled class, asserted present: a missing body is a guard that is gone. */
    private static MethodNode method(String internalName, String methodName) {
        for (MethodNode m : classNode(internalName).methods) {
            if (m.name.equals(methodName)) {
                return m;
            }
        }
        throw new AssertionError(internalName + "." + methodName + " is gone, so the budget it wired "
                + "is gone with it");
    }

    /**
     * The single method of a class that reads {@code owner.field}, including a lambda body (javac
     * compiles those into synthetic methods of the same class).
     */
    private static MethodNode methodReading(String internalName, String owner, String field) {
        MethodNode found = null;
        for (MethodNode m : classNode(internalName).methods) {
            if (readsField(m, owner, field)) {
                assertTrue("only one body may read " + field + ", or the default it sets is not the "
                        + "one the caller receives", found == null);
                found = m;
            }
        }
        assertNotNull("nothing in " + internalName + " reads ObserveProfile." + field + " any more. "
                + "That read IS the wiring: without it the profile the caller chose no longer sets "
                + "what it is documented to set, and every profile pays the same cost", found);
        return found;
    }

    /** How many times a class reads {@code owner.field}, across every method including lambdas. */
    private static int fieldReads(String internalName, String owner, String field) {
        int n = 0;
        for (MethodNode m : classNode(internalName).methods) {
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (isRead(i, owner, field)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean readsField(MethodNode m, String owner, String field) {
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (isRead(i, owner, field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRead(AbstractInsnNode i, String owner, String field) {
        return i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                && f.owner.equals(owner) && f.name.equals(field);
    }

    private static boolean callsMethod(MethodNode m, String calleeName) {
        return countCallsTo(m, calleeName) > 0;
    }

    private static int countCallsTo(MethodNode m, String calleeName) {
        int n = 0;
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof MethodInsnNode call && call.name.equals(calleeName)) {
                n++;
            }
        }
        return n;
    }

    private static boolean loadsConstant(MethodNode m, String literal) {
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof LdcInsnNode l && literal.equals(l.cst)) {
                return true;
            }
        }
        return false;
    }

    /** The int a single instruction pushes, in any of the forms javac emits, else null. */
    private static Integer intConstant(AbstractInsnNode n) {
        int op = n.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op - Opcodes.ICONST_0;
        }
        if (n instanceof IntInsnNode i && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
            return i.operand;
        }
        if (n instanceof LdcInsnNode l && l.cst instanceof Integer v) {
            return v;
        }
        return null;
    }

    private static ClassNode classNode(String internalName) {
        byte[] bytes = classBytes(internalName);
        assertNotNull(internalName + ".class must be readable from the test classpath: the arguments "
                + "its world-bound call sites pass are unobservable any other way", bytes);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cn;
    }

    /** Raw bytes of a class from the test classpath, or null when absent. */
    private static byte[] classBytes(String internalName) {
        try (InputStream in = ObserveProfilesArePinnedAtTheirBudgetTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
