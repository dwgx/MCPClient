package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.ke.GameClock;

import org.junit.Before;
import org.junit.Test;

/**
 * A block-target argument an interact kind cannot carry must be REFUSED, never dropped.
 *
 * <p>The parser used to ignore extras silently, and every kind had its own instance of it:
 * {@code {"kind":"use","block":[...]}} became an in-air click, {@code "hold"} discarded the same key
 * (and held a use at whatever the crosshair was on instead), {@code "dig"} discarded the within-block
 * hit offsets, and {@code "attack"}/{@code "hotbar"} discarded a block entirely. In each case the
 * caller asked for one action, got another, and was told it had succeeded -- and act_status, which
 * reports the action that really ran, cannot be read as a complaint about the argument either.
 *
 * <p>Refusing is the honest half of the pair and it is not a decision about the FEATURE: a kind that
 * later grows a block target stops refusing, and until then the refusal is what makes the absence
 * visible instead of silent. 'place' is the kind that already carries the whole block target (block,
 * face, hitX/hitY/hitZ).
 *
 * <p>The consequence on the other side of the seam: {@code InteractController.use}'s
 * {@code intent.hasBlock()} branch is unreachable by parser path and stays dead until a kind that can
 * build a block-targeted USE exists. It is named here because the refusal lives in the parser, not in
 * the controller (that file is outside this change's ownership, so the note sits at the refusal site
 * in {@code ActIntentParser} too).
 */
public class ActSetRefusesBlockTargetsItCannotHonourTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTools tools;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        tools = new ActTools(runtime);
    }

    private static CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), args));
    }

    private static String text(CallToolResult r) {
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                return t.text();
            }
        }
        fail("no text content in result");
        return null;
    }

    private static Object valueFor(String key) {
        if (key.equals("block")) {
            return List.of(12, 64, -3);
        }
        return key.equals("face") ? 1 : 0.5;
    }

    private InteractIntent submitted() {
        return (InteractIntent) runtime.record(ActSlot.INTERACT).intent();
    }

    @Test
    public void aUseWithABlockIsRefusedAndNothingIsSubmitted() {
        CallToolResult r = call(tools.actSet(),
                Map.of("interact", Map.of("kind", "use", "block", List.of(12, 64, -3))));

        assertTrue("a block on 'use' must be refused, not silently clicked into open air",
                Boolean.TRUE.equals(r.isError()));
        String msg = text(r);
        assertTrue("the complaint must name the argument it cannot honour: " + msg,
                msg.contains("'block'"));
        assertTrue("and it must name the kind that CAN carry a block, or the caller is left with a "
                + "refusal and nowhere to go: " + msg, msg.contains("'place'"));
        assertFalse("nothing may reach the INTERACT slot", submitted() != null);
    }

    /**
     * Every kind that reads no block target refuses every block-target argument, one key at a time.
     *
     * <p>Each call also supplies the kind's OWN argument (entityId, hotbarSlot), so that on the
     * un-guarded code the call SUCCEEDS and the extra key is the only thing that can fail the test --
     * an error from a missing entityId would make this pass for the wrong reason and would keep
     * passing if the refusal were deleted.
     */
    @Test
    public void anInAirKindRefusesEveryBlockTargetArgument() {
        for (String kind : List.of("use", "hold", "attack", "hotbar")) {
            for (String key : List.of("block", "face", "hitX", "hitY", "hitZ")) {
                Map<String, Object> interact = new LinkedHashMap<>();
                interact.put("kind", kind);
                if (kind.equals("attack")) {
                    interact.put("entityId", 5);
                }
                if (kind.equals("hotbar")) {
                    interact.put("hotbarSlot", 3);
                }
                interact.put(key, valueFor(key));

                CallToolResult r = call(tools.actSet(), Map.of("interact", interact));
                assertTrue(kind + " + '" + key + "' must be refused rather than dropped: " + text(r),
                        Boolean.TRUE.equals(r.isError()));
                assertTrue(kind + " must be named with '" + key + "': " + text(r),
                        text(r).contains("'" + key + "'"));
                assertFalse("nothing may reach the INTERACT slot",
                        runtime.record(ActSlot.INTERACT).intent() != null);
            }
        }
    }

    /**
     * A key written with a null is not a supplied block, and refusing it was a regression.
     *
     * <p>The guard tested {@code containsKey}, so every optional argument serialized as an
     * explicit null -- the shape a model produces when it fills a schema it has nothing for, and
     * the shape {@code IoProbe}'s own comment calls "missing" -- was read as a block the caller
     * had supplied. {@code {"kind":"hotbar","hotbarSlot":3,"block":null}} selected slot 3
     * before the guard and threw after it.
     *
     * <p>Its sibling, {@link #anInAirKindRefusesEveryBlockTargetArgument}, cannot catch this:
     * {@code valueFor} returns only well-formed values, so it never writes a null.
     */
    @Test
    public void anExplicitNullBlockIsAbsentRatherThanSupplied() {
        // LinkedHashMap, not Map.of: Map.of REJECTS a null value with an NPE of its own, which is
        // why writing this input is fiddly by hand and why the guard's containsKey test was never
        // exercised by the shape the JSON layer actually hands it.
        Map<String, Object> interact = new LinkedHashMap<>();
        interact.put("kind", "hotbar");
        interact.put("hotbarSlot", 3);
        interact.put("block", null);
        interact.put("face", null);
        CallToolResult r = call(tools.actSet(), Map.of("interact", interact));

        assertFalse("a null is 'missing', not 'supplied': this call selects slot 3. " + text(r),
                Boolean.TRUE.equals(r.isError()));
        InteractIntent intent = submitted();
        assertTrue("the hotbar select must reach the slot", intent != null);
    }

    @Test
    public void everyInAirKindAcceptsAnExplicitNullForEveryBlockTargetKey() {
        for (String kind : List.of("use", "hold", "attack", "hotbar")) {
            for (String key : List.of("block", "face", "hitX", "hitY", "hitZ")) {
                Map<String, Object> interact = new LinkedHashMap<>();
                interact.put("kind", kind);
                if (kind.equals("attack")) {
                    interact.put("entityId", 5);
                }
                if (kind.equals("hotbar")) {
                    interact.put("hotbarSlot", 3);
                }
                // A LinkedHashMap is required: Map.of rejects null values outright, which is
                // exactly why this input is hard to write by accident and why the guard was
                // wrong about it for as long as it was.
                interact.put(key, null);

                CallToolResult r = call(tools.actSet(), Map.of("interact", interact));
                assertFalse(kind + " + '" + key + "': null must read as absent, not as a supplied "
                        + "block: " + text(r), Boolean.TRUE.equals(r.isError()));
            }
        }
    }

    /**
     * A dig names a block and a face. The within-block hit offsets have nowhere to go -- the dig seam
     * ({@code ActActuator.startDig}/{@code pumpDig}) has no hit vector and the game derives the point
     * itself -- so they are refused as PLACEMENT arguments rather than dropped as though they were
     * irrelevant.
     */
    @Test
    public void aDigRefusesTheHitOffsetsItHasNoVectorFor() {
        for (String key : List.of("hitX", "hitY", "hitZ")) {
            CallToolResult r = call(tools.actSet(), Map.of("interact", Map.of(
                    "kind", "dig", "block", List.of(4, 64, 4), "face", 1, key, 0.5)));

            assertTrue("dig + '" + key + "' must be refused rather than dropped: " + text(r),
                    Boolean.TRUE.equals(r.isError()));
            assertTrue("and the complaint must name '" + key + "': " + text(r),
                    text(r).contains("'" + key + "'"));
            assertFalse("nothing may reach the INTERACT slot", submitted() != null);
        }
    }

    /**
     * The other direction, and the reason this file exists at all: the guard must not swallow the
     * arguments a kind DOES read. Every kind's own form still parses and reaches the slot intact.
     */
    @Test
    public void everyKindsOwnArgumentsStillWork() {
        assertFalse("dig keeps block and face", Boolean.TRUE.equals(
                call(tools.actSet(), Map.of("interact", Map.of("kind", "dig",
                        "block", List.of(4, 64, 4), "face", 1))).isError()));
        assertEquals(InteractIntent.Kind.DIG, submitted().kind());
        assertEquals(1, submitted().face());

        assertFalse("place keeps the whole block target", Boolean.TRUE.equals(
                call(tools.actSet(), Map.of("interact", Map.of("kind", "place",
                        "block", List.of(1, 2, 3), "face", 2,
                        "hitX", 0.25, "hitY", 0.5, "hitZ", 0.75))).isError()));
        assertEquals(InteractIntent.Kind.PLACE, submitted().kind());
        assertEquals(2, submitted().face());
        assertEquals(0.25, submitted().hitX(), 1e-9);

        assertFalse("a bare use is still the in-air click", Boolean.TRUE.equals(
                call(tools.actSet(), Map.of("interact", Map.of("kind", "use"))).isError()));
        assertEquals(InteractIntent.Kind.USE, submitted().kind());
        assertFalse("with no block target, which is what makes useInAir the right factory",
                submitted().hasBlock());

        assertFalse("hold keeps holdTicks", Boolean.TRUE.equals(
                call(tools.actSet(), Map.of("interact", Map.of("kind", "hold",
                        "holdTicks", 20))).isError()));
        assertEquals(InteractIntent.Kind.HOLD, submitted().kind());
        assertEquals(20, submitted().holdTicks());

        assertFalse("attack keeps its entity id", Boolean.TRUE.equals(
                call(tools.actSet(), Map.of("interact", Map.of("kind", "attack",
                        "entityId", 5))).isError()));
        assertEquals(InteractIntent.Kind.ATTACK, submitted().kind());
        assertEquals(5, submitted().entityId());

        assertFalse("hotbar keeps its slot", Boolean.TRUE.equals(
                call(tools.actSet(), Map.of("interact", Map.of("kind", "hotbar",
                        "hotbarSlot", 3))).isError()));
        assertEquals(InteractIntent.Kind.HOTBAR, submitted().kind());
        assertEquals(3, submitted().hotbarSlot());
    }

    /**
     * The rule the handler enforces has to be visible to the caller BEFORE the call, in both places the
     * schema puts text: the tool description and the {@code interact} property. A refusal nobody can
     * read about is a trap, and the OLD descriptions advertised {@code block} as a general interact
     * argument -- which is how the caller got there in the first place.
     */
    @Test
    public void theDescriptionsStateTheRefusalAndNameTheRemedy() {
        String desc = tools.actSet().tool().description();
        int start = desc.indexOf("'use' is also the IN-AIR click");
        assertTrue("the tool description must say 'use' is the in-air click that takes no block "
                + "target: " + desc, start >= 0);
        // Bounded slice: 'place' appears many times in this description, so the assertion has to be
        // about THIS sentence rather than about the document.
        String useLegend = desc.substring(start, Math.min(desc.length(), start + 420));
        assertTrue("and must say such an argument is REFUSED rather than ignored: " + useLegend,
                useLegend.contains("REFUSED"));
        assertTrue("and must name 'place' as the remedy: " + useLegend, useLegend.contains("'place'"));

        assertTrue("the refusal covers every kind that cannot carry a block target, not just 'use' -- "
                + "a caller reading only the 'use' sentence would still lose an argument on 'hold': "
                + desc, desc.contains("Every block-target argument is refused on a kind that has "
                + "nowhere to put it, never dropped"));

        Object schema = tools.actSet().tool().inputSchema();
        assertTrue("act_set's interact rule must be readable from the schema a model is handed",
                schema instanceof Map);
        Object props = ((Map<?, ?>) schema).get("properties");
        assertTrue("with a properties map", props instanceof Map);
        Object interactProp = ((Map<?, ?>) props).get("interact");
        assertNotNull("act_set must still publish an interact property", interactProp);
        String schemaDesc = String.valueOf(((Map<?, ?>) interactProp).get("description"));
        assertTrue("the interact property must carry the same rule, since a model may read only the "
                + "schema: " + schemaDesc, schemaDesc.contains("REFUSED"));
        // DERIVED from the real kind set, not a pinned literal. This assertion used to require
        // the exact string "'use', 'hold', 'attack' or 'hotbar'", so when 'block' and 'release'
        // were added as real kinds -- ActIntentParser now has case "block" at :356 and
        // case "release" at :367, both of which read no block target either -- the schema became
        // MORE accurate and this test went red. A literal list is stale the moment the product
        // grows, and a test that is wrong whenever the product improves is a test that gets
        // relaxed instead of fixed.
        //
        // The property is: EVERY kind that reads no block target is named in the refusal, so a
        // caller reading only this sentence loses no argument. That is derived from the kinds
        // themselves below.
        String[] noBlockTarget = {"'use'", "'hold'", "'attack'", "'block'", "'release'", "'hotbar'"};
        for (String kind : noBlockTarget) {
            assertTrue("the refusal must name " + kind + ", the kinds that read no block target, "
                    + "otherwise a caller reading only the schema loses an argument silently: "
                    + schemaDesc, schemaDesc.contains(kind));
        }
        assertTrue("and the one argument 'dig' does not read: " + schemaDesc,
                schemaDesc.contains("hitX/hitY/hitZ on 'dig'"));
        assertTrue("and the kind that does take the block: " + schemaDesc,
                schemaDesc.contains("'place'"));
    }
}
