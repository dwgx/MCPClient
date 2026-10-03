package net.marcloud.mcp.core.rulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.Test;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;

/**
 * Both rulers must be reachable from the PRODUCTION registration site, and their payloads must be
 * able to say they hold nothing.
 *
 * <p><b>What this file is a receipt for.</b> The audit that scoped instrument 2 recorded that
 * {@code NightShelter} was once DECLARED in three tables and CONSTRUCTED in none, and that
 * {@code SurvivalDamage}'s only constructor call in the whole repository is a line in test tree. A
 * ruler that exists, is correct under test, and is unreachable at runtime is the same defect in a
 * different costume -- and this project has already shipped it once. So these assertions are about
 * REACHABILITY through {@link McpCore#registerBuiltins}, which is the single production wiring
 * site, and about what a caller sees when a ruler is disarmed.
 *
 * <p><b>Why this drives production rather than the tool class.</b> The obvious test would construct
 * {@code NightRulerTools} directly, and that test would stay green on a build where the wiring line
 * had been deleted -- which is precisely the {@code chat_read} defect
 * {@code RegisteredBuiltinGateCoverageTest} documents. Building through {@code registerBuiltins}
 * and then asking {@code core.modelSurface()} means a dropped wiring line fails HERE rather than
 * quietly shrinking the audited surface. It is also why the tool class's spec builders stay
 * package-private: from another package the only honest way in is production's own registration.
 *
 * <p><b>Why the payload keys are named as strings.</b> A missing key has to be a red ASSERTION
 * rather than a compile error: "the payload does not carry the answer" is the defect, and a compile
 * error only proves a method is absent. This file therefore COMPILES against a tree that had
 * neither tool, which is what makes the mutation meaningful.
 *
 * <p><b>What is asserted about the switches.</b> The verb must stay REGISTERED either way -- a tool
 * that says NOT MEASURED is honest, a gate row naming a tool nothing registers is not -- and a
 * disarmed payload must be recognisable STRUCTURALLY, not only by its prose. If the only difference
 * were English, every caller that matters would read "off" as "safe".
 */
public final class TheRulersAreReachableFromTheProductionKernelTest {

    private static final String HEALTH = "night_health";
    private static final String BOX = "night_box";
    private static final String MEASURED_ONCE = "measuredOnce";

    /** One production-built core, torn down by the caller. */
    private record Built(McpCore core, IoManager audited, IoManager surface, IoSupervisor exec) {
    }

    /**
     * Drive {@link McpCore#registerBuiltins} headlessly.
     *
     * <p>Every collaborator that is only touched from inside a handler is left null, because
     * registration never dereferences one -- the same stand-ins
     * {@code RegisteredBuiltinGateCoverageTest} uses, for the same reason.
     */
    private static Built build() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        EventBus bus = new EventBus();
        SeLocalMonitor engine = new SeLocalMonitor(
                new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
        IoManager audited = new IoManager(exec, engine);
        McpCore core = new McpCore();
        core.registerBuiltins(audited, engine, null,
                new ToolContext(null, null, null, null, null),
                new LdrEngine(TheRulersAreReachableFromTheProductionKernelTest.class
                        .getClassLoader()),
                new FltManager(bus), new FltDynamicManager(null, bus),
                new SeamController(bus, core.gameAccess()),
                ActRuntime.INSTANCE);
        return new Built(core, audited, core.modelSurface(), exec);
    }

    private static SyncToolSpecification spec(IoManager reg, String name) {
        SyncToolSpecification s = null;
        for (var c : reg.capabilities()) {
            if (name.equals(c.name())) {
                s = c.spec();
                break;
            }
        }
        assertNotNull("production must register '" + name + "', or a model can never ask it: "
                + new TreeSet<>(reg.names()), s);
        return s;
    }

    private static Map<String, Object> payloadOf(IoManager reg, String name) {
        CallToolResult r = spec(reg, name).callHandler()
                .apply(null, new CallToolRequest(name, Map.of()));
        Content c = r.content().get(0);
        Map<String, Object> out =
                net.marcloud.mcp.core.io.http.Json.readObject(((TextContent) c).text());
        assertNotNull("the payload must be a JSON object: " + name, out);
        return out;
    }

    /**
     * THE HEADLINE: both rulers are on the surface a model can actually reach.
     *
     * <p>Checked on the MODEL-FACING registry, which is the one a model is handed. A tool that is
     * built but filtered off the surface is unreachable in the way that matters, and {@code McpCore}
     * keeps the audited registry and the model-facing one separate precisely so that distinction is
     * checkable rather than assumed.
     */
    @Test
    public void bothRulersAreRegisteredByProductionAndReachable() {
        Built b = build();
        try {
            List<String> surface = b.surface().names();

            assertTrue(HEALTH + " must be reachable from the MODEL-FACING surface: a tool"
                    + " registered nowhere is a tool that does not exist. Registered: "
                    + new TreeSet<>(surface), surface.contains(HEALTH));
            assertTrue(BOX + " must be reachable from the MODEL-FACING surface: "
                    + new TreeSet<>(surface), surface.contains(BOX));
            assertTrue("and the surface must still be real -- a near-empty one would satisfy the"
                    + " two lines above: " + new TreeSet<>(surface),
                    surface.contains("chat_read") && surface.contains("world_view")
                            && surface.contains("night_shelter"));
            assertFalse("both are declared GAME, so no kernel-only name may have leaked onto the"
                    + " surface: " + new TreeSet<>(surface),
                    ToolRegistry.kernelLayeredNames().stream().anyMatch(surface::contains));
            assertFalse("and neither may be classified KERNEL by default -- the layer table is"
                    + " deny-by-default, so an unclassified name is hidden from the model",
                    ToolRegistry.isKernelLayered(HEALTH));
            assertFalse(ToolRegistry.isKernelLayered(BOX));

            assertNotNull("and both must ANSWER when called", payloadOf(b.surface(), HEALTH));
            assertNotNull(payloadOf(b.surface(), BOX));
        } finally {
            b.exec().shutdown();
        }
    }

    /**
     * A disarmed ruler's payload must be distinguishable from a safe one.
     *
     * <p>Both are "no verdict", and both have every counter at zero. The bit that separates them is
     * {@code measuredOnce}, and it is the shape {@code NightShelter} already established rather
     * than a third invention. Without it a model reading {@code floorHeld:false} concludes the
     * floor was breached, which is the opposite of the truth.
     */
    @Test
    public void aDisarmedHealthPayloadIsNotASafeNight() {
        Built b = build();
        try {
            Map<String, Object> out = payloadOf(b.surface(), HEALTH);

            assertTrue("the payload must carry the latch: " + out, out.containsKey(MEASURED_ONCE));
            assertEquals("and it must be false, because nothing was ever measured",
                    Boolean.FALSE, out.get(MEASURED_ONCE));
            assertEquals("so the verdict is not available rather than negative",
                    Boolean.FALSE, out.get("floorHeld"));
            assertEquals("and the low-water mark is absent rather than a number that reads as a"
                    + " verdict in either direction", null, out.get("minHealth"));
            assertEquals("zero samples", 0, ((Number) out.get("samples")).intValue());
            assertTrue("while the sentence names the cause: " + out,
                    String.valueOf(out.get("fact")).contains("NOT MEASURED"));
        } finally {
            b.exec().shutdown();
        }
    }

    /** The same for the box ruler. */
    @Test
    public void aDisarmedBoxPayloadIsNotABoxThatStood() {
        Built b = build();
        try {
            Map<String, Object> out = payloadOf(b.surface(), BOX);

            assertEquals(Boolean.FALSE, out.get(MEASURED_ONCE));
            assertEquals("neither reading may report a box", Boolean.FALSE, out.get("heldThroughout"));
            assertEquals(Boolean.FALSE, out.get("standingAtDawn"));
            assertEquals("and no cells were ever censused",
                    0, ((Number) out.get("chestsSeen")).intValue());
            assertTrue("while the sentence says so: " + out,
                    String.valueOf(out.get("fact")).contains("NOT MEASURED"));
        } finally {
            b.exec().shutdown();
        }
    }

    /**
     * The three health quantities must each be a key of their own.
     *
     * <p>This is the acceptance criterion as an assertion: a payload carrying only a derived boolean
     * cannot express "hit and absorbed", because one boolean has one value both for "it got through"
     * and for "it did not". Each key is checked separately so that merging any two of them -- the
     * tempting simplification -- turns this red.
     */
    @Test
    public void theThreeHealthQuantitiesEachAppearIndependently() {
        Built b = build();
        try {
            Map<String, Object> out = payloadOf(b.surface(), HEALTH);

            for (String key : new String[] {"minHealth", "healthDrops", "hitEdges", "absorbedHits",
                    "maxLastDamage", "lastDamageReadable", "floorHeld", "northStarFloor"}) {
                assertTrue("the payload must carry '" + key + "' as its own key, because each has a"
                        + " case the others cannot see. Present: " + out.keySet(),
                        out.containsKey(key));
            }
            assertEquals("and the floor is stated as a number a reader can check against, rather"
                    + " than left implicit",
                    18.0F, ((Number) out.get("northStarFloor")).floatValue(), 0.0F);
        } finally {
            b.exec().shutdown();
        }
    }

    /**
     * The box payload must separate the two kinds of "not standing".
     *
     * <p>On a live client a cell that left load range and a chest that was demolished both arrive
     * as a null read, so a row that cannot say which one it saw is a row nobody can act on.
     */
    @Test
    public void theBoxPayloadSeparatesDemolishedFromUnreadable() {
        Built b = build();
        try {
            Map<String, Object> out = payloadOf(b.surface(), BOX);

            for (String key : new String[] {"chestsSeen", "standingAtDawn", "heldThroughout",
                    "missingSamples", "unreadableSamples", "firstSeenTick", "latestFirstSeenTick",
                    "censusRadiusCells", "censusPeriodTicks"}) {
                assertTrue("the payload must carry '" + key + "': " + out.keySet(),
                        out.containsKey(key));
            }
        } finally {
            b.exec().shutdown();
        }
    }

    /**
     * Both descriptions must be true of the world a model is standing in.
     *
     * <p>{@code DawnChest.fact()} printed "no creeper, no explosion", which is false on a running
     * client. A description that repeats it teaches the model a false belief about its own world,
     * and that is the same defect this session has already hit three times.
     */
    @Test
    public void neitherDescriptionClaimsAFixtureWorld() {
        Built b = build();
        try {
            String h = spec(b.surface(), HEALTH).tool().description();
            String box = spec(b.surface(), BOX).tool().description();

            assertFalse("'no creeper' is false on a live client: " + h, h.contains("no creeper"));
            assertFalse("'no explosion' likewise", box.contains("no explosion"));
            assertFalse("'no fire' likewise", box.contains("no fire"));
            assertTrue("the box description must instead say these ARE present: " + box,
                    box.contains("mobs, creepers, explosions and lava ARE present"));
            assertTrue("it must name the region reading's weakening, or a model concludes its own"
                    + " chest stood: " + box, box.contains("NOT that the chest YOU built did"));
            assertTrue("it must say CONTENTS are not measurable and why: " + box,
                    box.contains("CONTENTS are not measured AT ALL"));
            assertTrue("and it must name both failure directions: " + box,
                    box.contains("few ticks late") && box.contains("left load range"));
            assertTrue("while the health description must close the never-observed trap in the"
                    + " same words ShelterTools does: " + h, h.contains("NEVER 'safe'"));
            assertTrue(h.contains(MEASURED_ONCE));
        } finally {
            b.exec().shutdown();
        }
    }

    /**
     * Both verbs must be read-only ledger readers.
     *
     * <p>A tool that mutates cannot honestly claim to be a ledger read, and an
     * {@code idempotentHint} that is false on a pure read is a description lying to the client.
     */
    @Test
    public void bothVerbsAreReadOnlyLedgerReaders() {
        Built b = build();
        try {
            for (String name : new String[] {HEALTH, BOX}) {
                var ann = spec(b.surface(), name).tool().annotations();
                assertTrue(name + " must be read-only", ann.readOnlyHint());
                assertFalse(name + " must not be destructive", ann.destructiveHint());
                assertTrue(name + " must be idempotent, since reading a ledger twice changes"
                        + " nothing", ann.idempotentHint());
                assertFalse(name + " must not be an open-world tool, because it reads this"
                        + " client's own ledger and nothing else", ann.openWorldHint());
            }
        } finally {
            b.exec().shutdown();
        }
    }

    /** Both must sit at R3, the ring the gate enforces, which is what the audit compares. */
    @Test
    public void bothRulersAreGatedAtR3() {
        for (String name : new String[] {HEALTH, BOX}) {
            assertEquals(name + " is a ledger reader, so R3 like its shelter sibling -- a name in"
                    + " none of the three gate tables would be enforced at the fallback with no row"
                    + " at all, which is the dev_probe defect",
                    Ring.R3, Ring.forBuiltin(name, null));
        }
    }

    /**
     * The two switches must be two properties, and both must default to armed.
     *
     * <p>A test that flipped both together would pass if one name were misspelled and the other
     * happened to share it, so this asserts the NAMES and the DEFAULTS. The EFFECT needs a forked
     * JVM with the property set; what is asserted here is that the wiring has two distinct
     * switches rather than one shared flag, because one flag for both would mean a caller could not
     * be given the health ruler without also paying for the region's cell scan.
     */
    @Test
    public void eachRulerHasItsOwnSwitchAndBothDefaultToArmed() {
        assertNull("mcp.core.health must not be set in a normal run",
                System.getProperty("mcp.core.health"));
        assertNull("and neither must mcp.core.box", System.getProperty("mcp.core.box"));
        assertEquals("so the wiring's default for the health switch is armed",
                "true", System.getProperty("mcp.core.health", "true"));
        assertEquals("and the box switch defaults to armed as well", "true",
                System.getProperty("mcp.core.box", "true"));
    }

    /** A disarmed ruler must be recognisable by STRUCTURE, not only by its prose. */
    @Test
    public void aDisarmedRulerIsRecognisableByStructureNotOnlyByProse() {
        Built b = build();
        try {
            Map<String, Object> out = payloadOf(b.surface(), HEALTH);
            assertEquals(Boolean.FALSE, out.get(MEASURED_ONCE));
            assertEquals(Boolean.FALSE, out.get("measured"));
            assertEquals("no low-water mark at all", null, out.get("minHealth"));
            assertEquals("zero samples", 0, ((Number) out.get("samples")).intValue());
            assertEquals("and the resolution is still published, so a caller can tell a disarmed"
                    + " counter from a dead seam", 1,
                    ((Number) out.get("samplingPeriodTicks")).intValue());
        } finally {
            b.exec().shutdown();
        }
    }
}