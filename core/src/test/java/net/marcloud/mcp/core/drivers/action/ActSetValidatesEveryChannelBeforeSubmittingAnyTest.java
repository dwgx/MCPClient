package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.LookIntent;
import net.marcloud.mcp.core.drivers.act.MoveIntent;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;

import org.junit.Before;
import org.junit.Test;

/**
 * {@code act_set} takes up to three channels at once, so validation has to happen for all of them
 * before any of them is submitted. The handler used to submit each channel as it parsed it, which made
 * a later channel's validation error a lie about the world: {@code {"move":{...},"look":{"mode":"spin"}}}
 * started the player WALKING and then returned a clean error saying nothing about it, and only
 * {@code act_status} could ever reveal the contradiction. ActPlanStep.parse has always validated a whole
 * step before the interpreter submits it; these tests pin the same contract on act_set.
 *
 * <p>Each refusal is asserted to leave EVERY slot empty, not just the failing one — "nothing was
 * submitted" is the promise an error reply makes, and a partial submit is exactly what breaks it.
 */
public class ActSetValidatesEveryChannelBeforeSubmittingAnyTest {

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

    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> m = Json.readObject(json);
        assertNotNull("top-level JSON is an object", m);
        return m;
    }

    private void assertEverySlotEmpty(String because) {
        for (ActSlot slot : ActSlot.values()) {
            assertFalse("an error reply promises nothing happened, so " + slot + " must be empty; it "
                            + "held " + runtime.record(slot).intent() + " (" + because + ")",
                    runtime.record(slot).intent() != null);
        }
    }

    /**
     * The shipped shape: a valid {@code move} parsed and submitted first, then a {@code look} that
     * fails. The walk must not have started.
     */
    @Test
    public void aValidMoveWithAnInvalidLookSubmitsNothing() {
        CallToolResult r = call(tools.actSet(), Map.of(
                "move", Map.of("walk_straight", List.of(100, 64, 100)),
                "look", Map.of("mode", "spin")));

        assertTrue("the look must be refused", Boolean.TRUE.equals(r.isError()));
        assertTrue("and the complaint must name the bad value: " + text(r), text(r).contains("spin"));
        assertEverySlotEmpty("move was validated but must not have been submitted");
    }

    /**
     * The worst ordering: two channels that are perfectly valid, submitted in the old code before the
     * third is rejected. Both must stay out of the runtime.
     */
    @Test
    public void twoValidChannelsWithAnInvalidInteractSubmitNothing() {
        CallToolResult r = call(tools.actSet(), Map.of(
                "move", Map.of("walk_straight", List.of(1, 2, 3)),
                "look", Map.of("mode", "set", "yaw", 90.0),
                "interact", Map.of("kind", "teleport")));

        assertTrue("the interact must be refused", Boolean.TRUE.equals(r.isError()));
        assertTrue("and the complaint must name the bad kind: " + text(r), text(r).contains("teleport"));
        assertEverySlotEmpty("move and look were valid, which is what used to get them running");
    }

    /**
     * The other half of the contract: validating first must not cost the success path. Three valid
     * channels still submit, and each keeps its own {@code effectiveTick = lastCompleted + 1}.
     */
    @Test
    public void threeValidChannelsStillSubmitAllThreeAtLastCompletedPlusOne() {
        clock.advance(); // now = 1
        clock.advance(); // now = 2, so every channel must be effective at 3

        CallToolResult r = call(tools.actSet(), Map.of(
                "move", Map.of("forward", 1.0, "durationTicks", 5),
                "look", Map.of("mode", "set", "yaw", 45.0),
                "interact", Map.of("kind", "hotbar", "hotbarSlot", 3)));

        assertFalse("three valid channels is not an error: " + text(r),
                Boolean.TRUE.equals(r.isError()));
        Map<String, Object> out = parseJson(text(r));
        assertEquals(Boolean.TRUE, out.get("accepted"));

        @SuppressWarnings("unchecked")
        Map<String, Object> effectiveTick = (Map<String, Object>) out.get("effectiveTick");
        for (String channel : List.of("move", "look", "interact")) {
            assertEquals("effectiveTick for " + channel + " must be lastCompleted + 1",
                    3L, ((Number) effectiveTick.get(channel)).longValue());
        }

        assertTrue("MOVE really holds the move intent",
                runtime.record(ActSlot.MOVE).intent() instanceof MoveIntent);
        assertTrue("LOOK really holds the look intent",
                runtime.record(ActSlot.LOOK).intent() instanceof LookIntent);
        assertTrue("INTERACT really holds the interact intent",
                runtime.record(ActSlot.INTERACT).intent() instanceof InteractIntent);

        @SuppressWarnings("unchecked")
        Map<String, Object> perSlot = (Map<String, Object>) out.get("perSlot");
        for (ActSlot slot : ActSlot.values()) {
            assertEquals("each channel is reported at submit time, before anything runs",
                    ActPhase.IDLE.name(), perSlot.get(slot.name().toLowerCase(Locale.ROOT)));
        }
    }
}
