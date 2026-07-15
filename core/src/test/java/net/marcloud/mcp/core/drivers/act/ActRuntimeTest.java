package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * Teeth for the lock-free runtime + tick loop: effectiveTick gating (an intent
 * never runs on the tick it was submitted), lifecycle transitions IDLE→ACTIVE→
 * terminal, cancel semantics, and per-slot applier fault isolation.
 */
public class ActRuntimeTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
    }

    private void tick() {
        long id = clock.advance();
        loop.onTick(new TickEvent(id));
    }

    @Test
    public void submitStampsEffectiveTickOnePastNow() {
        clock.advance(); // now = 1
        SlotRecord rec = runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        assertEquals("submittedTick is 'now'", 1L, rec.submittedTick());
        assertEquals("effectiveTick is now+1", 2L, rec.effectiveTick());
        assertEquals(ActPhase.IDLE, rec.phase());
    }

    @Test
    public void intentDoesNotApplyOnTheTickItWasSubmitted() {
        // Register a move applier so the slot can progress.
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());

        clock.advance(); // now = 1, submit becomes effective at tick 2
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));

        // Tick 2: effectiveTick reached — first application.
        long t2 = clock.advance();
        assertEquals(2L, t2);
        loop.onTick(new TickEvent(t2));
        SlotRecord after = runtime.record(ActSlot.MOVE);
        assertEquals("first eligible tick makes it ACTIVE", ActPhase.ACTIVE, after.phase());
        assertEquals(1, after.ticksActive());
    }

    @Test
    public void moveWithDurationCompletesAfterExactlyThatManyTicks() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance(); // now=1, effective=2
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 3));

        tick(); // tick 2: active 1
        tick(); // tick 3: active 2
        assertEquals(ActPhase.ACTIVE, runtime.record(ActSlot.MOVE).phase());
        tick(); // tick 4: active 3 -> complete
        SlotRecord r = runtime.record(ActSlot.MOVE);
        assertEquals(ActPhase.COMPLETE, r.phase());
        assertEquals(3, r.ticksActive());
    }

    @Test
    public void cancelBeforeStartEndsCancelledWithNoApplierRun() {
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance(); // now=1, effective=2
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        assertTrue(runtime.cancel(ActSlot.MOVE));
        tick(); // tick 2: eligible, but cancel pending and never started
        assertEquals(ActPhase.CANCELLED, runtime.record(ActSlot.MOVE).phase());
    }

    @Test
    public void liveIntentWithNoApplierFailsHonestly() {
        clock.advance(); // now=1, effective=2
        runtime.submitLook(LookIntent.set(10f, 0f, 0f));
        tick(); // tick 2: eligible, no applier registered
        assertEquals(ActPhase.FAILED, runtime.record(ActSlot.LOOK).phase());
    }

    @Test
    public void applierThrowIsIsolatedToItsSlotAndFailsIt() {
        // MOVE applier throws; LOOK applier works. One tick must fail MOVE but
        // still apply LOOK — the throw cannot break the tick.
        runtime.registerApplier(ActSlot.MOVE, r -> {
            throw new RuntimeException("boom");
        });
        runtime.registerApplier(ActSlot.LOOK, r -> r.markActive(r.lastAppliedTick(), "ok"));

        clock.advance(); // now=1, effective=2
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 0));
        runtime.submitLook(LookIntent.set(0f, 0f, 5f));

        tick(); // tick 2
        assertEquals("faulting slot is FAILED", ActPhase.FAILED, runtime.record(ActSlot.MOVE).phase());
        assertTrue(runtime.record(ActSlot.MOVE).message().contains("threw"));
        assertEquals("other slot still applied", ActPhase.ACTIVE, runtime.record(ActSlot.LOOK).phase());
    }

    @Test
    public void submitReplacesWhateverTheSlotHeld() {
        MoveIntent first = new MoveIntent(1f, 0f, false, false, false, 0);
        MoveIntent second = new MoveIntent(-1f, 0f, false, false, false, 0);
        runtime.submitMove(first);
        runtime.submitMove(second);
        assertEquals(second, runtime.record(ActSlot.MOVE).intent());
    }

    @Test
    public void statusReflectsEverySlotInEnumOrder() {
        ActStatus st = runtime.status();
        assertEquals(ActSlot.values().length, st.slots().size());
        assertEquals(ActSlot.MOVE, st.slots().get(0).slot());
        assertFalse(st.slots().get(0).hasIntent());

        runtime.submitInteract(InteractIntent.dig(1, 2, 3, 1));
        ActStatus st2 = runtime.status();
        ActStatus.SlotStatus interact = st2.slots().get(ActSlot.INTERACT.ordinal());
        assertTrue(interact.hasIntent());
        assertEquals("INTERACT:DIG", interact.intentKind());
    }

    @Test
    public void moveIntentViewGatesOnActivePhase() {
        // Fresh submit is IDLE, so the view must NOT be active yet.
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier());
        clock.advance();
        runtime.submitMove(new MoveIntent(0.5f, -0.5f, true, false, false, 0));
        assertFalse("IDLE intent must not drive movement", runtime.moveActive());

        tick(); // becomes ACTIVE
        assertTrue(runtime.moveActive());
        assertEquals(0.5f, runtime.moveForward(), 1e-6);
        assertEquals(-0.5f, runtime.moveStrafe(), 1e-6);
        assertTrue(runtime.jump());
    }

    @Test
    public void effectiveTickAdvancesWithTheClock() {
        clock.advance();
        clock.advance(); // now = 2
        SlotRecord rec = runtime.submitLook(LookIntent.set(0f, 0f, 0f));
        assertEquals(3L, rec.effectiveTick());
        assertNotEquals(rec.submittedTick(), rec.effectiveTick());
    }
}
