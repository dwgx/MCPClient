package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;

import org.junit.Before;
import org.junit.Test;

/**
 * Teeth for the PHASE A.7 MCP surface: drive the three tool handlers directly over
 * a real headless {@link ActRuntime} (own clock, recording no-op appliers) and
 * assert the observable effect on {@code runtime.status()} — a move submitted with
 * effectiveTick = clock+1, a look_at-block submitted, per-slot and "all" cancels,
 * a reference-free status snapshot, and that a bad enum is an honest error result
 * (isError), not a thrown exception.
 */
public class ActToolsTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTools tools;

    /** A recording no-op applier: proves the runtime saw a submit without touching the game. */
    private final AtomicInteger applyCalls = new AtomicInteger();

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        // No-op appliers so the runtime is fully wired but nothing touches a live game.
        runtime.registerApplier(ActSlot.MOVE, r -> {
            applyCalls.incrementAndGet();
            return r;
        });
        runtime.registerApplier(ActSlot.LOOK, r -> {
            applyCalls.incrementAndGet();
            return r;
        });
        runtime.registerApplier(ActSlot.INTERACT, r -> {
            applyCalls.incrementAndGet();
            return r;
        });
        tools = new ActTools(runtime);
    }

    // ===== helpers: drive a handler directly =====

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

    // ===== act_set =====

    @Test
    public void actSetMoveSubmitsWithEffectiveTickClockPlusOne() {
        clock.advance(); // now = 1, so a submit must be effective at tick 2
        CallToolResult r = call(tools.actSet(),
                Map.of("move", Map.of("forward", 1.0, "sprint", true, "durationTicks", 5)));
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));

        Map<String, Object> out = parseJson(text(r));
        assertEquals(Boolean.TRUE, out.get("accepted"));
        Map<String, Object> eff = (Map<String, Object>) out.get("effectiveTick");
        assertEquals("move effectiveTick is clock+1", 2L, ((Number) eff.get("move")).longValue());
        Map<String, Object> perSlot = (Map<String, Object>) out.get("perSlot");
        assertEquals("IDLE", perSlot.get("move"));

        // The runtime actually holds the submitted MOVE intent, IDLE, not yet applied.
        var st = runtime.status().slots().get(ActSlot.MOVE.ordinal());
        assertTrue("MOVE slot now holds an intent", st.hasIntent());
        assertEquals(ActPhase.IDLE, st.phase());
        assertEquals("MOVE", st.intentKind());
        assertEquals("no applier ran yet (submit only)", 0, applyCalls.get());
    }

    @Test
    public void actSetLookAtBlockSubmitsLookSlot() {
        clock.advance(); // now = 1
        CallToolResult r = call(tools.actSet(),
                Map.of("look", Map.of("mode", "look_at", "block", List.of(10, 64, -3),
                        "slewDegPerTick", 12)));
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));

        var st = runtime.status().slots().get(ActSlot.LOOK.ordinal());
        assertTrue("LOOK slot now holds an intent", st.hasIntent());
        assertEquals("LOOK:LOOK_AT", st.intentKind());

        Map<String, Object> out = parseJson(text(r));
        Map<String, Object> eff = (Map<String, Object>) out.get("effectiveTick");
        assertEquals(2L, ((Number) eff.get("look")).longValue());
    }

    @Test
    public void actSetInteractDigSubmitsInteractSlot() {
        clock.advance();
        CallToolResult r = call(tools.actSet(),
                Map.of("interact", Map.of("kind", "dig", "block", List.of(1, 2, 3), "face", 1)));
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));
        var st = runtime.status().slots().get(ActSlot.INTERACT.ordinal());
        assertTrue(st.hasIntent());
        assertEquals("INTERACT:DIG", st.intentKind());
    }

    @Test
    public void actSetWithNoSlotsIsAnHonestError() {
        CallToolResult r = call(tools.actSet(), Map.of());
        assertTrue("empty act_set is an error", Boolean.TRUE.equals(r.isError()));
        assertFalse("nothing was submitted", runtime.status().slots().get(0).hasIntent());
    }

    @Test
    public void actSetBadInteractKindIsErrorResultNotException() {
        CallToolResult r;
        try {
            r = call(tools.actSet(),
                    Map.of("interact", Map.of("kind", "teleport", "block", List.of(0, 0, 0))));
        } catch (RuntimeException e) {
            fail("bad enum must be an isError result, not a thrown exception: " + e);
            return;
        }
        assertTrue("bad kind is an error result", Boolean.TRUE.equals(r.isError()));
        assertTrue("message names the bad kind", text(r).contains("teleport"));
        // and it must NOT have partially submitted anything to the INTERACT slot
        assertFalse(runtime.status().slots().get(ActSlot.INTERACT.ordinal()).hasIntent());
    }

    @Test
    public void actSetBadLookModeIsErrorResult() {
        CallToolResult r = call(tools.actSet(), Map.of("look", Map.of("mode", "spin")));
        assertTrue(Boolean.TRUE.equals(r.isError()));
        assertTrue(text(r).contains("spin"));
    }

    // ===== act_cancel =====

    @Test
    public void actCancelNamedSlotCancelsOnlyThatSlot() {
        clock.advance();
        runtime.submitMove(new net.marcloud.mcp.core.drivers.act.MoveIntent(1f, 0f, false, false, false, 0));
        runtime.submitLook(net.marcloud.mcp.core.drivers.act.LookIntent.set(0f, 0f, 0f));

        CallToolResult r = call(tools.actCancel(), Map.of("slots", List.of("move")));
        assertFalse(Boolean.TRUE.equals(r.isError()));

        Map<String, Object> out = parseJson(text(r));
        List<Object> cancelled = (List<Object>) out.get("cancelled");
        assertEquals(List.of("move"), cancelled);

        // MOVE was flagged for cancel; LOOK still live.
        assertTrue("MOVE cancel-requested",
                runtime.status().slots().get(ActSlot.MOVE.ordinal()).message().contains("cancel"));
        assertTrue("LOOK still holds its intent",
                runtime.status().slots().get(ActSlot.LOOK.ordinal()).hasIntent());
    }

    @Test
    public void actCancelAllCancelsEverySlot() {
        clock.advance();
        runtime.submitMove(new net.marcloud.mcp.core.drivers.act.MoveIntent(1f, 0f, false, false, false, 0));
        runtime.submitLook(net.marcloud.mcp.core.drivers.act.LookIntent.set(0f, 0f, 0f));
        runtime.submitInteract(net.marcloud.mcp.core.drivers.act.InteractIntent.dig(1, 2, 3, 1));

        CallToolResult r = call(tools.actCancel(), Map.of("slots", "all"));
        assertFalse(Boolean.TRUE.equals(r.isError()));
        Map<String, Object> out = parseJson(text(r));
        List<Object> cancelled = (List<Object>) out.get("cancelled");
        assertEquals("all three live slots were flagged", 3, cancelled.size());
        assertTrue(cancelled.contains("move"));
        assertTrue(cancelled.contains("look"));
        assertTrue(cancelled.contains("interact"));
    }

    @Test
    public void actCancelOmittedSlotsCancelsAll() {
        clock.advance();
        runtime.submitMove(new net.marcloud.mcp.core.drivers.act.MoveIntent(1f, 0f, false, false, false, 0));
        CallToolResult r = call(tools.actCancel(), Map.of());
        assertFalse(Boolean.TRUE.equals(r.isError()));
        Map<String, Object> out = parseJson(text(r));
        List<Object> cancelled = (List<Object>) out.get("cancelled");
        assertEquals(List.of("move"), cancelled);
    }

    @Test
    public void actCancelUnknownSlotIsErrorResult() {
        CallToolResult r = call(tools.actCancel(), Map.of("slots", List.of("fly")));
        assertTrue(Boolean.TRUE.equals(r.isError()));
        assertTrue(text(r).contains("fly"));
    }

    // ===== act_status =====

    @Test
    public void actStatusReturnsPhasesForEverySlot() {
        clock.advance();
        runtime.submitInteract(net.marcloud.mcp.core.drivers.act.InteractIntent.hotbar(3));

        CallToolResult r = call(tools.actStatus(), Map.of());
        assertFalse(Boolean.TRUE.equals(r.isError()));
        Map<String, Object> out = parseJson(text(r));
        assertEquals("tickNow reflects the clock", 1L, ((Number) out.get("tickNow")).longValue());

        List<Object> slots = (List<Object>) out.get("slots");
        assertEquals("one row per ActSlot", ActSlot.values().length, slots.size());

        Map<String, Object> interact = (Map<String, Object>) slots.get(ActSlot.INTERACT.ordinal());
        assertEquals("interact", interact.get("slot"));
        assertEquals("INTERACT:HOTBAR", interact.get("intentKind"));
        assertEquals(Boolean.TRUE, interact.get("hasIntent"));
        assertEquals(ActPhase.IDLE.name(), interact.get("phase"));

        // MOVE untouched → idle, no intent.
        Map<String, Object> move = (Map<String, Object>) slots.get(ActSlot.MOVE.ordinal());
        assertEquals(Boolean.FALSE, move.get("hasIntent"));
        assertEquals(ActPhase.IDLE.name(), move.get("phase"));
    }

    /**
     * {@code move:{to:[x,y,z]}} carries a y that {@code NavController} records and never steers
     * toward -- deliberately, since this walks rather than flies. That fact reached the model
     * only through {@link net.marcloud.mcp.core.drivers.act.NavIntent}'s javadoc, so a caller
     * feeding a find_block position and reading back "arrived" had no way to know it says
     * nothing about height. Same defect shape as the undocumented grid keys; see
     * {@code GridSemanticsAreDocumentedTest}.
     */
    @Test
    public void theActSetDescriptionStatesThatYIsNotSteeredToward() {
        String desc = tools.actSet().tool().description();
        assertTrue("act_set takes to:[x,y,z] but never steers toward y; unstated, the model "
                + "reads 'arrived' as being at that y", desc.contains("NEVER STEERED TOWARD"));
        assertTrue("the description must say what 'arrived' actually measures",
                desc.contains("HORIZONTAL"));
    }

    @Test
    public void registerAllRegistersThreeToolsAsBuiltins() {
        var exec = new net.marcloud.mcp.core.io.IoSupervisor(2, 2000L);
        var registry = new net.marcloud.mcp.core.io.IoManager(exec,
                new net.marcloud.mcp.core.se.SeLocalMonitor(
                        new net.marcloud.mcp.core.se.SeClearancePolicy(
                                net.marcloud.mcp.core.se.Ring.R_MINUS_1, "tok")));
        tools.registerAll(registry);
        assertTrue("act_set registered", registry.isBuiltin("act_set"));
        assertTrue("act_cancel registered", registry.isBuiltin("act_cancel"));
        assertTrue("act_status registered", registry.isBuiltin("act_status"));
    }
}
