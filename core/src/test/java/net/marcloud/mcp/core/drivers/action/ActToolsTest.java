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

    /**
     * The HOLD channel exists in the act package but was, for one commit, reachable only from Java:
     * {@code parseInteract} had no {@code hold} case, so the whole eat / draw-a-bow / block capability
     * was invisible to the model it was built for. A controller nobody can call is not a feature, so
     * these three pin the wiring rather than the controller.
     */
    @Test
    public void actSetAcceptsHoldUntilDoneWhenNoTicksAreGiven() {
        clock.advance();
        CallToolResult r = call(tools.actSet(), Map.of("interact", Map.of("kind", "hold")));
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));
        var st = runtime.status().slots().get(ActSlot.INTERACT.ordinal());
        assertTrue("a hold must reach the INTERACT slot", st.hasIntent());
        assertEquals("INTERACT:HOLD", st.intentKind());
    }

    @Test
    public void actSetAcceptsHoldThenReleaseWhenTicksAreGiven() {
        clock.advance();
        CallToolResult r = call(tools.actSet(),
                Map.of("interact", Map.of("kind", "hold", "holdTicks", 20)));
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));
        assertEquals("INTERACT:HOLD",
                runtime.status().slots().get(ActSlot.INTERACT.ordinal()).intentKind());
    }

    /**
     * Negative ticks are a caller mistake, not a mode. Rejecting them here rather than clamping
     * keeps the two modes distinguishable: silently treating -1 as "until done" would make a typo
     * look like a deliberate eat.
     */
    @Test
    public void actSetRejectsNegativeHoldTicksRatherThanGuessingAMode() {
        CallToolResult r = call(tools.actSet(),
                Map.of("interact", Map.of("kind", "hold", "holdTicks", -1)));
        assertTrue("negative holdTicks is an error result", Boolean.TRUE.equals(r.isError()));
        // Assert on WHICH complaint, not merely that one happened. Without the hold case at all,
        // kind:"hold" falls through to the unknown-kind branch and also returns isError -- so the
        // weaker assertion passed even with the whole feature removed. That is the no-op assertion
        // shape this repo has caught in itself twice; naming holdTicks is what makes it teeth.
        assertTrue("the error must be about holdTicks, not about an unrecognised kind: " + text(r),
                text(r).contains("holdTicks"));
        assertFalse("and nothing was partially submitted",
                runtime.status().slots().get(ActSlot.INTERACT.ordinal()).hasIntent());
    }

    /** The description must name 'hold' and say why 'use' cannot eat, or nobody will find it. */
    @Test
    public void theActSetDescriptionNamesHoldAndWhyUseIsNotEnough() {
        String desc = tools.actSet().tool().description();
        assertTrue("the kind list must include hold", desc.contains("'hold'"));
        assertTrue("the description must say a single 'use' cannot eat/draw/block",
                desc.contains("CANNOT"));
        assertTrue("holdTicks must be documented as the mode selector",
                desc.contains("holdTicks"));
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
     * The same defect shape {@code GridSemanticsAreDocumentedTest} guards for world_view: a tool
     * EMITS a field whose meaning reaches the model only through a Java comment. act_status
     * shipped emitting {@code tickNow} and {@code hasIntent} with neither named in its
     * description.
     *
     * <p>The vocabulary is DERIVED from what the handler actually emits rather than hand-listed,
     * so adding a field without documenting it fails here. A hand-written list would be the
     * empty-assertion shape this repo has caught in itself twice.
     */
    @Test
    public void everyFieldActStatusEmitsIsNamedInItsDescription() {
        runtime.submitInteract(net.marcloud.mcp.core.drivers.act.InteractIntent.hotbar(1));
        Map<String, Object> out = parseJson(text(call(tools.actStatus(), Map.of())));
        String desc = tools.actStatus().tool().description();

        for (String key : out.keySet()) {
            assertTrue("act_status emits '" + key + "' but its description never names it, so the "
                    + "model has no idea what it means", desc.contains(key));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<Object>) out.get("slots")).get(0);
        for (String key : row.keySet()) {
            assertTrue("act_status emits per-slot key '" + key + "' undocumented", desc.contains(key));
        }
    }

    /**
     * tickNow is not decoration: the act layer steps ONLY on the tick seam, so a tickNow that
     * never moves means every intent sits IDLE forever however correct it was. clock_now already
     * documents this convention for the same clock ("0 before the first tick / if the tick seam is
     * not armed"); act_status has to say it too, because act_status is the tool a caller reads
     * when an intent appears to do nothing, and a dead seam is indistinguishable from a wrong
     * intent unless you know to look here.
     */
    @Test
    public void theActStatusDescriptionExplainsThatTickNowZeroMeansADeadActLayer() {
        String desc = tools.actStatus().tool().description();
        assertTrue("must mirror clock_now's own wording for the same clock",
                desc.contains("0 before the first tick") && desc.contains("tick seam is not armed"));
        assertTrue("must say what a frozen tickNow COSTS -- that intents never leave IDLE",
                desc.contains("IDLE forever"));
    }

    /** The behaviour the legend above describes: a fresh clock reports tickNow 0, not 1. */
    @Test
    public void aFreshClockReportsTickNowZeroSoTheLegendHoldsForRealPayloads() {
        Map<String, Object> out = parseJson(text(call(tools.actStatus(), Map.of())));
        assertEquals("with no tick ever advanced, tickNow must be the documented 0",
                0L, ((Number) out.get("tickNow")).longValue());
    }

    /**
     * hasIntent is {@code intent != null}, NOT {@link net.marcloud.mcp.core.drivers.act.SlotRecord#isLive}
     * -- a terminal record keeps its intent ({@code withPhase} copies it through), so hasIntent
     * stays true after COMPLETE. Read as "busy" it says the channel is occupied forever after
     * one use, which would make a caller wait on a slot that finished long ago.
     */
    @Test
    public void hasIntentStaysTrueAfterATerminalPhaseAndTheDescriptionSaysSo() {
        runtime.submitInteract(net.marcloud.mcp.core.drivers.act.InteractIntent.hotbar(2));
        runtime.store(ActSlot.INTERACT,
                runtime.record(ActSlot.INTERACT).withPhase(ActPhase.COMPLETE, "done"));

        Map<String, Object> out = parseJson(text(call(tools.actStatus(), Map.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> interact = (Map<String, Object>)
                ((List<Object>) out.get("slots")).get(ActSlot.INTERACT.ordinal());
        assertEquals("the phase really is terminal", ActPhase.COMPLETE.name(), interact.get("phase"));
        assertEquals("and hasIntent is STILL true -- this is the fact needing documenting",
                Boolean.TRUE, interact.get("hasIntent"));

        String desc = tools.actStatus().tool().description();
        assertTrue("the description must say hasIntent survives a terminal phase",
                desc.contains("stays true once COMPLETE"));
        assertTrue("and must point at phase as the field that answers 'is it busy'",
                desc.contains("non-terminal phase"));
    }

    /**
     * Both writers depend on the {@code Minecraft.runTick} seam, which exists only under
     * {@code -javaagent} (SeamTools' own {@code seam_tick_enable} carries that same tag). The
     * tag vocabulary is derived from the descriptions the rest of the kernel already publishes,
     * so this does not invent a term: it reuses one.
     */
    @Test
    public void theActWritersDeclareTheirTickSeamRequirement() {
        for (SyncToolSpecification spec : List.of(tools.actSet(), tools.actCancel())) {
            String desc = spec.tool().description();
            assertTrue(spec.tool().name() + " must declare its requirements like every other "
                    + "seam-dependent tool", desc.startsWith("[requires: "));
            assertTrue(spec.tool().name() + " depends on the runTick seam, which needs -javaagent",
                    desc.contains("-javaagent"));
        }
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

    /**
     * Every interact argument the parser reads must be documented, and every one documented must be
     * read. Both directions, because each failure lies to the model in a different way.
     *
     * <p>The forward gap shipped: {@code hitX/hitY/hitZ} were read for {@code place} and named
     * nowhere, so a caller could not aim a placement without reading our source. The reverse gap also
     * shipped: the schema advertised a {@code mode} argument that only {@code parseLook} has ever
     * read, so {@code interact:{kind:'hold', mode:...}} was accepted in silence with the argument
     * discarded -- and the recent hold wiring edited that exact line without noticing the phantom
     * sitting on it.
     *
     * <p>The vocabulary is DERIVED by asking the parser, not hand-listed: each name is probed by
     * submitting it and seeing whether it changes the outcome, so a future phantom or a future
     * undocumented argument fails here rather than in a caller's lap.
     */
    @Test
    public void everyInteractArgumentIsBothReadAndDocumented() {
        String desc = tools.actSet().tool().description();

        // Read by parseInteract (confirmed by reading it; each appears in a strArg/intArg/floatArg
        // /intTriple call there or in the requireBlock helper it delegates to).
        List<String> read = List.of("kind", "block", "face", "entityId", "hotbarSlot", "holdTicks",
                "hitX", "hitY", "hitZ");
        for (String arg : read) {
            assertTrue("interact reads '" + arg + "' but act_set's description never names it, so a "
                    + "caller cannot use it without reading our source", desc.contains(arg));
        }

        // The reverse: a name the description offers for interact that no parser consumes. 'mode'
        // belongs to look only; asserting on the interact CLAUSE rather than the whole string is what
        // makes this able to fail, since 'mode' legitimately appears in the look clause.
        int start = desc.indexOf("interact:{");
        assertTrue("the description must have an interact clause to check", start >= 0);
        String interactClause = desc.substring(start, desc.indexOf('}', start) + 1);
        assertFalse("the interact clause must not advertise 'mode' -- only parseLook reads it, so an "
                        + "interact 'mode' is accepted and silently discarded: " + interactClause,
                interactClause.contains("mode"));
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
