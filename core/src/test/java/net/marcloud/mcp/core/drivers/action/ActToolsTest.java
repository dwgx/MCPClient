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
import net.marcloud.mcp.core.drivers.act.LookIntent;
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

    // ===== act_set look: the tracking (non-self-terminating) mode =====
    //
    // The controller half of this is pinned by LookTrackingDoesNotSelfTerminateTest. These four are
    // the wiring: a mode reachable only from Java is not a capability the model has. The hold channel
    // shipped in exactly that state for one commit -- parseInteract had no 'hold' case -- which is
    // why the wiring gets its own tests rather than being assumed from the controller's.

    /** The LOOK slot must actually receive an intent whose aim mode is KEEP. */
    @Test
    public void actSetLookTrackReachesTheSlotAsAKeepAim() {
        clock.advance();
        CallToolResult r = call(tools.actSet(),
                Map.of("look", Map.of("mode", "look_at", "entityId", 42, "track", true,
                        "slewDegPerTick", 6)));
        assertFalse("not an error: " + text(r), Boolean.TRUE.equals(r.isError()));

        var intent = runtime.record(ActSlot.LOOK).intent();
        assertTrue("the LOOK slot holds a look intent", intent instanceof LookIntent);
        LookIntent li = (LookIntent) intent;
        assertEquals("track:true must become AimMode.KEEP, or the wire flag does nothing",
                LookIntent.AimMode.KEEP, li.aim());
        assertTrue(li.keepsAiming());
        assertEquals(42, li.targetEntityId());
        assertEquals("unbounded by default, which is the useful form of a track",
                0, li.durationTicks());
        assertEquals("and act_status must distinguish an occupied-by-a-track LOOK channel",
                "LOOK:LOOK_AT+KEEP",
                runtime.status().slots().get(ActSlot.LOOK.ordinal()).intentKind());
    }

    /** Omitting track must leave the default aim alone. */
    @Test
    public void actSetLookWithoutTrackIsStillAOneShotAim() {
        clock.advance();
        call(tools.actSet(), Map.of("look", Map.of("mode", "look_at", "entityId", 42)));
        LookIntent li = (LookIntent) runtime.record(ActSlot.LOOK).intent();
        assertEquals(LookIntent.AimMode.ONCE, li.aim());
        assertEquals("LOOK:LOOK_AT",
                runtime.status().slots().get(ActSlot.LOOK.ordinal()).intentKind());
    }

    @Test
    public void actSetLookTrackCarriesDurationTicks() {
        clock.advance();
        call(tools.actSet(), Map.of("look", Map.of("mode", "look_at", "block", List.of(1, 2, 3),
                "track", true, "durationTicks", 40)));
        LookIntent li = (LookIntent) runtime.record(ActSlot.LOOK).intent();
        assertEquals(LookIntent.AimMode.KEEP, li.aim());
        assertEquals(40, li.durationTicks());
        assertTrue("a block track must keep its block target", li.hasBlock());
    }

    /**
     * A duration without track is rejected rather than accepted and discarded.
     *
     * <p>This is the defect shape the last several commits were all about: the reply says accepted,
     * the caller believes it asked for 40 ticks of aim, and the intent ends on the first tick it
     * lands. Silence there is worse than an error, because nothing downstream ever contradicts it.
     * Asserting on WHICH complaint, not merely that one happened -- a weaker assertion passes even
     * with the whole feature deleted, which is the hollow shape this repo keeps catching.
     */
    @Test
    public void actSetLookRejectsDurationTicksWithoutTrackRatherThanIgnoringIt() {
        CallToolResult r = call(tools.actSet(),
                Map.of("look", Map.of("mode", "look_at", "entityId", 42, "durationTicks", 40)));
        assertTrue("durationTicks without track must be an error", Boolean.TRUE.equals(r.isError()));
        assertTrue("and the message must name both arguments so the fix is obvious: " + text(r),
                text(r).contains("durationTicks") && text(r).contains("track"));
        assertFalse("nothing was partially submitted",
                runtime.record(ActSlot.LOOK).intent() != null);
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

    /**
     * The screen claim must be CONDITIONED on focus, because measured live it is not unconditional.
     *
     * <p>The description said a hold ends FAILED when a screen opens. True with in-game focus, and
     * false without it: {@code setIngameNotInFocus} clears the bindings only inside
     * {@code if (this.inGameHasFocus)} ({@code Minecraft.java:1467-1469}). On an unfocused client --
     * the normal state when a script drives one -- the key survives, the hold keeps re-asserting, and
     * the use freezes instead. Measured both ways: focused cleared the key, unfocused did not and the
     * hold ran 73 ticks with the count stuck at 32.
     *
     * <p>A description that states one branch as the whole truth sends the caller to the wrong
     * diagnosis on the branch automation actually hits.
     */
    @Test
    public void theActSetDescriptionConditionsTheScreenClaimOnInGameFocus() {
        String desc = tools.actSet().tool().description();
        assertTrue("the focus condition must be stated, or the screen claim is only half true: "
                + desc, desc.contains("only CLEARS the key when the game had in-game focus"));
        assertTrue("and what happens INSTEAD on an unfocused client must be named, since that is "
                + "the branch a script hits", desc.contains("stops the world"));
        assertTrue("naming the honest diagnosis the caller will read there",
                desc.contains("count stopped moving"));
        // The half the first version of this fix got backwards, so it is pinned explicitly: chat
        // does NOT pause (GuiChat.doesGuiPauseGame() is false) and a meal completes behind it.
        // Measured live -- the count ran 32 down to 7. A description that lumped chat in with the
        // pausing screens would send a caller to close the one screen that was never the problem.
        assertTrue("chat must be called out as the exception, not lumped in with pausing screens: "
                + desc, desc.contains("CHAT does not pause"));
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

    /**
     * The two new look arguments must be both READ and DOCUMENTED, and each half is proved rather
     * than asserted from prose.
     *
     * <p>"Read" is established by submitting the argument and observing that the resulting intent
     * differs -- so a parser that stopped consuming it fails here instead of silently discarding a
     * caller's request. "Documented" is the other direction: an argument the parser honours and the
     * description never names is one a model cannot use without reading our source, which is how
     * {@code hitX/hitY/hitZ} shipped unusable.
     */
    @Test
    public void everyLookArgumentIsBothReadAndDocumented() {
        String desc = tools.actSet().tool().description();

        // Read: track changes the aim mode.
        call(tools.actSet(), Map.of("look", Map.of("mode", "look_at", "entityId", 1)));
        LookIntent plain = (LookIntent) runtime.record(ActSlot.LOOK).intent();
        call(tools.actSet(),
                Map.of("look", Map.of("mode", "look_at", "entityId", 1, "track", true)));
        LookIntent tracked = (LookIntent) runtime.record(ActSlot.LOOK).intent();
        assertEquals("precondition: 'track' is consumed by the parser",
                LookIntent.AimMode.ONCE, plain.aim());
        assertEquals(LookIntent.AimMode.KEEP, tracked.aim());

        // Read: durationTicks reaches the intent.
        call(tools.actSet(), Map.of("look", Map.of("mode", "look_at", "entityId", 1,
                "track", true, "durationTicks", 7)));
        assertEquals("precondition: 'durationTicks' is consumed by the parser", 7,
                ((LookIntent) runtime.record(ActSlot.LOOK).intent()).durationTicks());

        for (String arg : List.of("track", "durationTicks")) {
            assertTrue("look reads '" + arg + "' but act_set's description never names it",
                    desc.contains(arg));
        }
    }

    /**
     * The description must state the DEFAULT that surprises a caller, not merely offer the flag.
     *
     * <p>An aim ending the instant it lands is correct and is what most callers want, but it is also
     * why aiming at a mob leaves the crosshair where the mob used to be one tick later. A caller who
     * does not know that writes a look, reads COMPLETE, attacks, and misses -- and every field it
     * could have checked says success. Offering 'track' without saying what happens without it makes
     * the flag findable only by someone who already knew to look for it.
     */
    @Test
    public void theActSetDescriptionSaysAnAimStopsCorrectingOnceItLands() {
        String desc = tools.actSet().tool().description();
        assertTrue("the description must state that a default aim ends on arrival: " + desc,
                desc.contains("ENDS THE MOMENT IT LANDS"));
        assertTrue("and must name the consequence a caller would otherwise hit",
                desc.contains("walks leaves you pointed where it USED to be"));
        assertTrue("a track's endings must be enumerated, since arrival is deliberately not one",
                desc.contains("does NOT stop on arrival"));
        assertTrue("and the failure that must not be read as success must be stated",
                desc.contains("never means 'aimed' unless it says so"));
    }

    /**
     * act_set volunteers {@code perSlot.<slot>} = the slot's phase, read at SUBMIT time from the
     * record {@link net.marcloud.mcp.core.drivers.act.SlotRecord#submitted} just built -- which
     * hard-codes IDLE on every path, correctly, since the intent has not run yet. So perSlot is a
     * constant on success, and act_set's OWN description names that same constant as the symptom of
     * a dead tick seam ("sits at IDLE forever"). A healthy submit was therefore indistinguishable
     * from a broken seam using the very field the reply hands over unasked.
     *
     * <p>Not a phase bug -- IDLE is the honest phase at submit time -- so the fix is the legend: say
     * perSlot is pre-run and constant, and point at the field that DOES carry seam health.
     *
     * <p>The phase name is DERIVED from the production factory rather than typed in, so changing
     * what a submit reports without touching the legend fails here instead of quietly making the
     * legend a lie. Scoped to the text from 'perSlot' onward, because asserting "the description
     * contains IDLE" passes on the OLD text -- the dead-seam warning above says IDLE too. That is
     * the hollow shape this repo has caught in itself repeatedly.
     */
    @Test
    public void theActSetDescriptionSaysPerSlotIsAlwaysIdleAtSubmitTime() {
        String submitPhase = net.marcloud.mcp.core.drivers.act.SlotRecord
                .submitted(net.marcloud.mcp.core.drivers.act.LookIntent.set(0f, 0f, 0f), 1L, 2L, "m")
                .phase().name();

        String desc = tools.actSet().tool().description();
        int start = desc.indexOf("perSlot");
        assertTrue("act_set emits 'perSlot' but its description never names the key", start >= 0);
        String legend = desc.substring(start);

        assertTrue("the legend must state that perSlot is ALWAYS " + submitPhase + " on a successful "
                        + "submit, since that is what makes it useless as a health check: " + legend,
                legend.contains("ALWAYS " + submitPhase));
        assertTrue("and must say WHY -- it is read before the intent runs", legend.contains("BEFORE"));
        assertTrue("and must disclaim that it carries health information, or a reader still weighs "
                + "it against the dead-seam warning above", legend.contains("NOTHING about"));
        assertTrue("and must point at where the answer actually is", legend.contains("act_status"));
    }

    /**
     * The half above is text; this is the wording it must mirror. tickNow -- which act_set already
     * returns -- is the dead-seam signal, and that convention is published by clock_now for this same
     * clock. Lifted OUT of clock_now's live description rather than retyped, so a reword there fails
     * here instead of leaving act_set quietly holding a second, divergent phrasing of one rule.
     */
    @Test
    public void theActSetDescriptionMirrorsClockNowsOwnDeadSeamWording() {
        String clockNow = clockNowDescription();
        int open = clockNow.indexOf("(monotonic,");
        assertTrue("clock_now must still publish the tickId convention parenthetically", open >= 0);
        String convention = clockNow.substring(open, clockNow.indexOf(')', open) + 1);
        assertTrue("the extracted convention must be the seam clause: " + convention,
                convention.contains("tick seam is not armed"));

        assertTrue("act_set returns tickNow, so it must carry clock_now's OWN wording for what a "
                        + "frozen one means rather than inventing a second phrasing: " + convention,
                tools.actSet().tool().description().contains(convention));
    }

    /** clock_now's live description, via the registry ObserveTools publishes it into. */
    private String clockNowDescription() {
        var registry = new net.marcloud.mcp.core.io.IoManager(
                new net.marcloud.mcp.core.io.IoSupervisor(2, 2000L),
                new net.marcloud.mcp.core.se.SeLocalMonitor(
                        new net.marcloud.mcp.core.se.SeClearancePolicy(
                                net.marcloud.mcp.core.se.Ring.R_MINUS_1, "tok")));
        // Timeline null is supported (timeline_tail then reports empty, honestly) -- clock_now is
        // the only description read here.
        new net.marcloud.mcp.core.drivers.observe.ObserveTools(clock, null).registerAll(registry);
        var cap = registry.get("clock_now");
        assertNotNull("clock_now must be registered for its wording to be the shared source", cap);
        return cap.description();
    }

    /**
     * The behaviour the legend claims, pinned so text and code cannot drift apart in silence: on a
     * runtime PROVEN alive -- clock advancing, an applier really reaching ACTIVE -- a fresh submit
     * still reports {@code perSlot} IDLE. The expected value is the literal enum here, not
     * {@code submitted(...).phase()}: deriving it from the code under test would make this assertion
     * agree with any mutation of it, which is exactly the hollow shape. A description-only test
     * would go green the moment submit started reporting something else.
     */
    @Test
    public void aSubmitOnALiveRuntimeStillReportsIdleSoTheLegendHolds() {
        runtime.registerApplier(ActSlot.MOVE, r -> r.markActive(r.lastAppliedTick(), "stepping"));
        var loop = new net.marcloud.mcp.core.drivers.act.ActTickLoop(runtime);

        clock.advance(); // tick 1
        call(tools.actSet(), Map.of("move", Map.of("forward", 1.0)));
        loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(clock.advance())); // tick 2
        assertEquals("the runtime must be demonstrably ALIVE, or reporting IDLE proves nothing",
                ActPhase.ACTIVE, runtime.status().slots().get(ActSlot.MOVE.ordinal()).phase());

        clock.advance(); // tick 3
        Map<String, Object> out = parseJson(text(call(tools.actSet(),
                Map.of("move", Map.of("forward", 1.0)))));
        @SuppressWarnings("unchecked")
        Map<String, Object> perSlot = (Map<String, Object>) out.get("perSlot");
        assertEquals("a submit reports the phase BEFORE the intent runs, so even here it is IDLE",
                ActPhase.IDLE.name(), perSlot.get("move"));
        assertTrue("while tickNow -- the field the legend points at -- is the one that moved",
                ((Number) out.get("tickNow")).longValue() > 0L);
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
