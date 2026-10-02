package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.LookIntent;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;

import org.junit.Before;
import org.junit.Test;

/**
 * The lease is enforced in the runtime and would be invisible at the tool boundary without this row
 * field — and an enforcement mechanism a caller cannot read is the "capability that exists but does
 * nothing" shape this project has already paid for more than once.
 *
 * <p>The second half of this file is the honest part: it pins that {@code heldBy} DISCRIMINATES, and
 * it pins what the field means when it does not. On the live tool path {@code act_set} submits as
 * {@code act_set/DIRECT}, which outranks {@code act_plan/REPLAY} and is same-owner with itself, so
 * {@code act_set} is structurally unrefusable. The test that proves a plan step is refused goes
 * through the plan interpreter's own lease ask, not through {@code act_set}, because there is no
 * {@code act_set} call in existence that can be refused.
 */
public class ActStatusExposesTheLeaseHolderTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTools tools;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        runtime.registerApplier(ActSlot.MOVE, r -> r);
        runtime.registerApplier(ActSlot.LOOK, r -> r);
        runtime.registerApplier(ActSlot.INTERACT, r -> r);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slot(CallToolResult r, ActSlot slot) {
        Map<String, Object> out = Json.readObject(text(r));
        List<Object> slots = (List<Object>) out.get("slots");
        Map<String, Object> row = (Map<String, Object>) slots.get(slot.ordinal());
        assertNotNull("the row for " + slot + " must exist", row);
        return row;
    }

    // ===== the field exists and is correct in both states =====

    @Test
    public void everySlotRowCarvesHeldByAndItIsNullOnAFreeChannel() {
        CallToolResult r = call(tools.actStatus(), Map.of());
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));
        for (ActSlot s : ActSlot.values()) {
            Map<String, Object> row = slot(r, s);
            assertTrue("the row for " + s + " must carry the key even when the value is null, or a "
                    + "caller cannot tell 'free' from 'this build has no such field': " + row,
                    row.containsKey("heldBy"));
            assertNull("nothing has been submitted, so " + s + " is free: " + row,
                    row.get("heldBy"));
        }
    }

    @Test
    public void heldByNamesTheDirectOwnerAfterAnActSet() {
        clock.advance();
        CallToolResult set = call(tools.actSet(),
                Map.of("move", Map.of("forward", 1.0, "durationTicks", 5)));
        assertFalse("act_set must still be accepted: nothing can refuse a DIRECT submit",
                Boolean.TRUE.equals(set.isError()));

        Map<String, Object> row = slot(call(tools.actStatus(), Map.of()), ActSlot.MOVE);
        assertEquals("the lease must name its owner AND its priority, so a reader can tell a live "
                        + "command from a queued one without a second lookup",
                "act_set/DIRECT", row.get("heldBy"));
        assertEquals("and the channels nothing was submitted on must still read free",
                null, slot(call(tools.actStatus(), Map.of()), ActSlot.LOOK).get("heldBy"));
    }

    @Test
    public void heldByDistinguishesTheTwoRealOwnersRatherThanBeingOneValue() {
        // The point of the field. If this ever reads one value everywhere, the row's own documented
        // promise ("exactly two possible values") has become a lie and the field is decoration.
        clock.advance();
        runtime.trySubmit(LookIntent.set(0f, 0f, 0f), ActRuntime.replayLease(LookIntent.set(0f, 0f, 0f)));
        assertEquals("a replayed plan step's lease must be visible as REPLAY",
                "act_plan/REPLAY", runtime.leaseHolder(ActSlot.LOOK));

        clock.advance();
        runtime.submitMove(new net.marcloud.mcp.core.drivers.act.MoveIntent(
                1f, 0f, false, false, false, 20));
        assertEquals("and a live command's as DIRECT, on the same snapshot",
                "act_set/DIRECT", runtime.leaseHolder(ActSlot.MOVE));

        CallToolResult r = call(tools.actStatus(), Map.of());
        assertEquals("act_plan/REPLAY", slot(r, ActSlot.LOOK).get("heldBy"));
        assertEquals("act_set/DIRECT", slot(r, ActSlot.MOVE).get("heldBy"));
    }

    @Test
    public void heldByGoesBackToNullWhenTheChannelIsCancelled() {
        clock.advance();
        runtime.submitMove(new net.marcloud.mcp.core.drivers.act.MoveIntent(
                1f, 0f, false, false, false, 20));
        assertNotNull("premise: held",
                slot(call(tools.actStatus(), Map.of()), ActSlot.MOVE).get("heldBy"));

        runtime.cancel(ActSlot.MOVE);
        runtime.tickLeases(clock.lastCompletedTick());

        assertNull("a cancelled channel must read free immediately: a cancel is the owner saying "
                        + "it is done, and a heldBy that still names a holder would have a model "
                        + "reasoning from a lie about the one field built so it need not",
                slot(call(tools.actStatus(), Map.of()), ActSlot.MOVE).get("heldBy"));
    }

    @Test
    public void heldByGoesBackToNullOnAnExplicitRelease() {
        clock.advance();
        LookIntent aim = LookIntent.set(0f, 0f, 0f);
        runtime.trySubmit(aim, ActRuntime.replayLease(aim));
        assertEquals("premise: held as a plan step",
                "act_plan/REPLAY", slot(call(tools.actStatus(), Map.of()), ActSlot.LOOK).get("heldBy"));

        assertFalse("a stranger cannot release a lease it does not hold -- one owner handing back "
                        + "another owner's channel is the same defect wearing a different hat",
                runtime.release(ActSlot.LOOK, "someone_else"));
        assertEquals("and the refusal changed nothing",
                "act_plan/REPLAY", slot(call(tools.actStatus(), Map.of()), ActSlot.LOOK).get("heldBy"));

        assertTrue("the holder can release its own lease", runtime.release(ActSlot.LOOK, ActRuntime.PLAN_OWNER));
        assertNull("and then the channel reads free",
                slot(call(tools.actStatus(), Map.of()), ActSlot.LOOK).get("heldBy"));
        assertFalse("releasing twice is a no-op, not an error and not a second release of someone "
                + "else's channel", runtime.release(ActSlot.LOOK, ActRuntime.PLAN_OWNER));
    }

    @Test
    public void heldByGoesBackToNullAfterTheAutoExpiryAndWithoutAnyExplicitRelease() {
        // The path with the longest fuse: nobody calls cancel, nobody calls release, the owner just
        // stops. If this one leaks, heldBy lies forever, which is worse than a lease that is merely
        // never granted.
        clock.advance();
        runtime.submitMove(new net.marcloud.mcp.core.drivers.act.MoveIntent(
                1f, 0f, false, false, false, 20));
        assertNotNull("premise: held",
                slot(call(tools.actStatus(), Map.of()), ActSlot.MOVE).get("heldBy"));

        // Detach the tick loop's effect entirely: the clock advances and tickLeases runs, but
        // nothing ever advances the intent, which is exactly the "owner died mid-step" shape.
        for (int i = 0; i <= ActRuntime.DEFAULT_MAX_IDLE_TICKS; i++) {
            runtime.tickLeases(clock.advance());
        }

        assertNull("a lease whose owner never releases and never advances must free itself after "
                        + "maxIdleTicks; a heldBy stuck here is a permanent lie",
                slot(call(tools.actStatus(), Map.of()), ActSlot.MOVE).get("heldBy"));
    }

    // ===== the refusal a caller can actually reach, and its honest limit =====

    @Test
    public void actSetIsNeverRefusedAndThatIsAStructuralFactNotALuckyOne() {
        // Stated as a construction, not as an observation that no test failed. DIRECT outranks
        // REPLAY, and a second DIRECT submit is a same-owner re-acquire, so there is no owner in the
        // shipped system that can refuse act_set. This test pins the two halves of that sentence.
        assertTrue("DIRECT must outrank REPLAY or the above is not true",
                ActRuntime.Priority.DIRECT.outranks(ActRuntime.Priority.REPLAY));
        assertFalse("REPLAY must not outrank DIRECT",
                ActRuntime.Priority.REPLAY.outranks(ActRuntime.Priority.DIRECT));

        clock.advance();
        for (int i = 0; i < 3; i++) {
            CallToolResult r = call(tools.actSet(),
                    Map.of("move", Map.of("forward", 1.0, "durationTicks", 20)));
            assertFalse("act_set call " + i + " must be accepted", Boolean.TRUE.equals(r.isError()));
            Map<String, Object> out = Json.readObject(text(r));
            assertEquals("act_set call " + i + " must report accepted:true", Boolean.TRUE,
                    out.get("accepted"));
            clock.advance();
        }
    }

    @Test
    public void aPlanStepBlockedByALiveActSetSaysWhoHoldsTheChannelInsteadOfOverwritingIt() {
        // The refusal that IS reachable, and the only one a caller can hit today: the interpreter
        // asking for a slot a live command holds.
        clock.advance();
        call(tools.actSet(), Map.of("move", Map.of("walk_straight",
                Map.of("x", 40.0, "y", 64.0, "z", 0.0))));

        clock.advance();
        CallToolResult plan = call(tools.actPlan(), java.util.List.of(Map.of(
                "steps", List.of(
                        Map.of("move", Map.of("walk_straight",
                                Map.of("x", -40.0, "y", 64.0, "z", 0.0))),
                        Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3)))))
                .isEmpty() ? Map.of() : Map.of("steps", List.of(
                        Map.of("move", Map.of("walk_straight",
                                Map.of("x", -40.0, "y", 64.0, "z", 0.0))))));
        assertFalse("act_plan itself is accepted; the step is blocked, not the call",
                Boolean.TRUE.equals(plan.isError()));

        Map<String, Object> status = Json.readObject(text(call(tools.actStatus(), Map.of())));
        Map<String, Object> planRow = (Map<String, Object>) status.get("plan");
        assertEquals("a blocked step keeps the plan RUNNING and on the same index",
                "RUNNING", planRow.get("phase"));
        assertEquals("the index does not advance past a step that was never submitted",
                0, ((Number) planRow.get("index")).intValue());
        assertTrue("and waitingOn must list the slot it could not get: " + planRow.get("waitingOn"),
                ((List<?>) planRow.get("waitingOn")).contains("move"));
        assertTrue("the message must name the holder, so the block is diagnosable without guessing: "
                        + planRow.get("message"),
                String.valueOf(planRow.get("message")).contains("act_set"));

        assertEquals("and the live act_set keeps the channel it was granted",
                "act_set/DIRECT", slot(call(tools.actStatus(), Map.of()), ActSlot.MOVE).get("heldBy"));
    }
}