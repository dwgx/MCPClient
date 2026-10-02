package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
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
import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.io.IoProbe;
import net.marcloud.mcp.core.ke.GameClock;

import org.junit.Before;
import org.junit.Test;

/**
 * Three defects with one shape -- the tool's own DECLARED shape was not what its handler enforced
 * -- and one of them is the recovery tool's documented form being unsendable.
 *
 * <ol>
 *   <li><b>{@code walk_straight} accepted a short coordinate and filled the missing axes with
 *       0</b>, while {@code go_to} refused the identical input. {@code [100]} walked toward
 *       {@code (100, 0, 0)}: y=0 is 64 blocks under the surface and neither target steers y at
 *       all, so the walk could not arrive where it aimed. The schema declares {@code minItems: 3}
 * * for both* coordinates, so the handler was contradicting its own declaration on one of them --
 *       and this is the tool whose description records that it walked a live player off a
 *       30-block cliff.</li>
 *   <li><b>{@code act_cancel}'s {@code "all"} could not be sent.</b> The schema declared
 *       {@code type: array}; the description, the handler and the handler's own error text all
 *       said the bare string was valid. {@code IoProbe} enforces {@code type} before dispatch, so
 *       a model sending exactly what the tool documented was rejected before reaching the code
 *       that would have honoured it -- on the tool an agent reaches for when something has gone
 *       wrong.</li>
 *   <li><b>{@code act_set}'s reply could not distinguish success from "will never run".</b>
 *       {@code perSlot} is read at SUBMIT time so it is IDLE whether the seam is alive or dead,
 *       and {@code accepted} is true in both cases. The one fact submit can observe -- the game
 *       clock -- was in the reply as {@code tickNow}, but reading it meant comparing two calls,
 *       which is the step a weak model skips.</li>
 * </ol>
 *
 * <p><b>Mutation, both directions, and the red sets are disjoint.</b> For each fix: removing the
 * guard reddens the tests that assert the malformed input is REFUSED; making the guard blanket
 * (refusing every coordinate / every cancel form / reporting every submit as unrunnable) reddens
 * the tests that assert the WELL-FORMED call still works. Nothing appears in both sets.
 */
public class TheMalformedDestinationAndTheUnsendableCancelAreBothClosedTest {

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

    // ===== 1. the coordinate arity =====

    /**
     * Both keys, every wrong arity, including four.
     *
     * <p>{@code go_to} is included even though it already refused: the point of this file is that
     * the two keys agreed, and a guard that fixed only {@code walk_straight} would leave the
     * asymmetry in place for the next key somebody adds.
     */
    @Test
    public void everyWrongCoordinateArityIsRefusedOnBothMoveTargets() {
        List<List<Object>> wrong = List.of(
                List.of(),
                List.of(100),
                List.of(100, 64),
                List.of(1, 2, 3, 4));
        for (String key : List.of("walk_straight", "go_to")) {
            for (List<Object> coord : wrong) {
                Map<String, Object> move = new LinkedHashMap<>();
                move.put(key, coord);
                CallToolResult r = call(tools.actSet(), Map.of("move", move));
                String msg = text(r);
                assertTrue(key + " with " + coord.size() + " coordinates must be refused rather "
                        + "than filled with zeros: " + msg, Boolean.TRUE.equals(r.isError()));
                assertTrue("the refusal must name the key, or the caller does not know which "
                        + "argument to fix: " + msg, msg.contains("'" + key + "'"));
                assertTrue("and must say it needs three: " + msg,
                        msg.contains("three block coordinates"));
            }
        }
    }

    /**
     * The refusal has to SHOW the destination it would otherwise have walked to.
     *
     * <p>Asserted on the exact numbers the un-guarded code computed, not on a shape: a message that
     * said "not enough coordinates" would pass a weaker version of this and still leave the caller
     * unable to tell which end was wrong.
     */
    @Test
    public void theRefusalShowsThePaddedDestinationItRefusedToWalkTo() {
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("walk_straight", List.of(100));
        String msg = text(call(tools.actSet(), Map.of("move", move)));

        assertTrue("the refusal must print the (x, 0, 0) it declined to walk to, because that is "
                + "the sentence that tells the caller the tool understood the mistake rather than "
                + "rejecting the shape blindly: " + msg, msg.contains("100.0"));
        assertTrue("and must say y would have been zero: " + msg, msg.contains("0.0"));
        assertEquals("nothing may reach the MOVE slot", null,
                runtime.record(ActSlot.MOVE).intent());
    }

    /**
     * The control for the arity guard: a real triple still reaches the slot, and the y is the
     * caller's rather than a zero.
     *
     * <p>This is what reddens under a blanket refusal of every coordinate. Both forms and both
     * keys, because {@code coord()} accepts an array or an object and both must survive.
     */
    @Test
    public void everyWellFormedCoordinateStillReachesTheSlotInBothShapes() {
        Object[] triples = {List.of(100, 64, -3), Map.of("x", 100, "y", 64, "z", -3)};
        for (String key : List.of("walk_straight", "go_to")) {
            for (Object coord : triples) {
                Map<String, Object> move = new LinkedHashMap<>();
                move.put(key, coord);
                CallToolResult r = call(tools.actSet(), Map.of("move", move));
                assertFalse(key + " with a complete coordinate must be accepted: " + text(r),
                        Boolean.TRUE.equals(r.isError()));
                var intent = runtime.record(ActSlot.MOVE).intent();
                assertNotNull("the intent must reach the slot", intent);
                if (key.equals("walk_straight")) {
                    assertEquals("walk_straight's y must be the caller's 64, never a substituted 0",
                            64.0, ((NavIntent) intent).targetY(), 1e-9);
                } else {
                    assertEquals(64, ((RouteIntent) intent).targetY());
                }
            }
        }
    }

    // ===== 2. act_cancel's "all" =====

    /**
     * The recovery tool must accept the recovery it documents.
     *
     * <p>Driven through {@link IoProbe} first, because that is where the rejection happened: the
     * handler always honoured {@code "all"} and the schema never let it through. Asserting only
     * the handler would pass on the broken code -- the handler is unchanged by this fix.
     */
    @Test
    public void theDocumentedAllIsAcceptedByTheSchemaAndHonouredByTheHandler() {
        clock.advance();
        CallToolResult r = call(tools.actSet(),
                Map.of("move", Map.of("walk_straight", List.of(100, 64, 100))));
        assertFalse("setup: something must be live to cancel", Boolean.TRUE.equals(r.isError()));

        @SuppressWarnings("unchecked")
        Map<String, Object> schema =
                (Map<String, Object>) tools.actCancel().tool().inputSchema();
        IoProbe.Result probe = IoProbe.validate(schema, Map.of("slots", "all"));
        assertTrue("the documented \"all\" must pass schema validation -- this is the exact "
                + "rejection that made the recovery tool's own documented form unsendable: "
                + probe.message(), probe.ok());

        CallToolResult cancelled = call(tools.actCancel(), Map.of("slots", "all"));
        assertFalse("and the handler must honour it: " + text(cancelled),
                Boolean.TRUE.equals(cancelled.isError()));
        assertTrue("the live MOVE slot must have been flagged: " + text(cancelled),
                text(cancelled).contains("\"move\""));
    }

    /** The array form, and an omitted argument, must keep working. This is the control. */
    @Test
    public void theArrayFormAndAnOmittedSlotsBothStillCancelAll() {
        clock.advance();
        call(tools.actSet(), Map.of("move", Map.of("walk_straight", List.of(100, 64, 100))));

        for (Map<String, Object> args : List.of(
                Map.<String, Object>of("slots", List.of("move")),
                Map.<String, Object>of())) {
            call(tools.actSet(), Map.of("move", Map.of("walk_straight", List.of(100, 64, 100))));
            CallToolResult r = call(tools.actCancel(), args);
            assertFalse("cancelling via " + args + " must be accepted: " + text(r),
                    Boolean.TRUE.equals(r.isError()));
            assertTrue("and must flag the live MOVE slot: " + text(r), text(r).contains("\"move\""));
        }
    }

    /** An unknown slot is still a refusal, and it now says which shapes exist. */
    @Test
    public void anUnknownSlotIsStillRefusedAndNamesBothShapes() {
        CallToolResult r = call(tools.actCancel(), Map.of("slots", List.of("nope")));
        assertTrue("an unknown slot must still be refused: " + text(r),
                Boolean.TRUE.equals(r.isError()));
        assertTrue("and the refusal must point at the bare \"all\" form, since a caller who wrote "
                + "[\"all\"] was told the word was valid: " + text(r), text(r).contains("all"));
    }

    // ===== 3. act_set's reply =====

    /**
     * A dead seam is stated in the reply, not inferred by the caller from two calls.
     *
     * <p>Driven through a runtime whose clock has never advanced, which is the real condition: not
     * a mocked flag but the value {@code GameClock} actually holds.
     */
    @Test
    public void aSubmitIntoADeadSeamSaysSoInTheSameReply() throws Exception {
        Map<String, Object> out = replyJson(call(tools.actSet(),
                Map.of("move", Map.of("walk_straight", List.of(100, 64, 100)))));

        assertTrue("the reply must carry 'seamArmed' so a model can branch on the seam without "
                + "comparing two calls: " + out, out.containsKey("seamArmed"));
        assertEquals("and with no tick ever completed it must be false", Boolean.FALSE,
                out.get("seamArmed"));
        assertEquals("the per-slot prediction must agree: nothing will be stepped",
                Boolean.FALSE, ((Map<?, ?>) out.get("runnable")).get("move"));
    }

    /**
     * And the other direction: a live seam says so. This is what reddens under a blanket
     * "always report unrunnable", which is the mutant that would pass a test that only ever
     * submits into a dead one.
     */
    @Test
    public void aSubmitIntoALiveSeamSaysItWillRun() throws Exception {
        clock.advance();
        Map<String, Object> out = replyJson(call(tools.actSet(),
                Map.of("move", Map.of("walk_straight", List.of(100, 64, 100)))));

        assertEquals("with the clock advanced the seam is armed and must say so",
                Boolean.TRUE, out.get("seamArmed"));
        assertEquals("and the slot is predicted runnable",
                Boolean.TRUE, ((Map<?, ?>) out.get("runnable")).get("move"));
        assertEquals("while accepted still means what it says -- the intent is in the slot",
                Boolean.TRUE, out.get("accepted"));
        assertEquals("and perSlot is unchanged, because existing readers parse it",
                "IDLE", ((Map<?, ?>) out.get("perSlot")).get("move"));
    }

    /**
     * The fields the old reply carried must survive with the same names and types.
     *
     * <p>This is the compatibility statement made executable: {@code perSlot} is read by
     * {@code ActToolsTest} and {@code ActSetValidatesEveryChannelBeforeSubmittingAnyTest}, and
     * anything reading a model prompt reads the prose that quotes these names. The fix is
     * ADDITIVE, so nothing that parsed the old reply can break.
     */
    @Test
    public void everyFieldTheOldReplyCarriedIsStillThereWithTheSameShape() throws Exception {
        Map<String, Object> out = replyJson(call(tools.actSet(),
                Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3))));

        for (String key : Arrays.asList("accepted", "tickNow", "effectiveTick", "perSlot")) {
            assertTrue("the pre-existing field '" + key + "' must survive: " + out, out.containsKey(key));
        }
        assertNotNull("and perSlot must still be a map of slot -> phase string", out.get("perSlot"));
        assertTrue("effectiveTick must still be per slot", out.get("effectiveTick") instanceof Map);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replyJson(CallToolResult r) throws Exception {
        assertFalse("setup: this call must succeed: " + text(r), Boolean.TRUE.equals(r.isError()));
        String body = text(r);
        int open = body.indexOf('{');
        int close = body.lastIndexOf('}');
        return (Map<String, Object>) net.marcloud.mcp.core.io.http.Json
                .readObject(body.substring(open, close + 1));
    }
}