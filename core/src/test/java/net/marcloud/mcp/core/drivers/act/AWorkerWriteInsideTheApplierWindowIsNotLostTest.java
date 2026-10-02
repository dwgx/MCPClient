package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * Teeth for the half of the lock-free model the effective-tick gate does not cover.
 *
 * <p>The gate keeps an intent from being HALF-applied on the tick it arrived; it does nothing about
 * the tick loop's write. The loop reads a slot's record, runs the applier for a whole tick of real
 * work, and stores its continuation — and that store used to be unconditional, so a worker submit or
 * cancel landing anywhere inside the window was written away by a record derived from the OLD intent.
 * Both ends of the contract broke: {@code act_set} answered {@code accepted:true} for an intent the
 * slot no longer held, and {@code act_cancel} reported a slot cancelled that kept running.
 *
 * <p>The concurrent write is performed from INSIDE {@code apply} by the fake applier rather than by a
 * second thread with a sleep. The window is a sequence of operations, not a duration: a timing-based
 * test would prove nothing on a fast machine and fail on a slow one, and would also be testing the
 * scheduler rather than the runtime.
 */
public class AWorkerWriteInsideTheApplierWindowIsNotLostTest {

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

    private static MoveIntent move(float forward) {
        return new MoveIntent(forward, 0f, false, false, false, 0);
    }

    /**
     * A submit landing between the loop's read and its store must be the record that survives, and the
     * loop's own result — a continuation of the record the submit replaced — must be DROPPED.
     */
    @Test
    public void aSubmitInsideApplySurvivesAndTheSupersededRecordIsDropped() {
        MoveIntent first = move(1f);
        MoveIntent second = move(-1f);
        runtime.submitMove(first); // tick 0, eligible at tick 1

        AtomicInteger applies = new AtomicInteger();
        runtime.registerApplier(ActSlot.MOVE, rec -> {
            applies.incrementAndGet();
            runtime.submitMove(second); // the worker write, landing mid-tick
            return rec.withPhase(ActPhase.COMPLETE, "finished the intent that was just replaced");
        });

        loop.onTick(new TickEvent(clock.advance())); // tick 1: eligible, the applier runs

        SlotRecord after = runtime.record(ActSlot.MOVE);
        assertSame("the submit that landed inside apply() must be the record that survives: "
                + after.message(), second, after.intent());
        assertEquals("and it must be the fresh submit, not the applier's COMPLETE continuation of the "
                + "record it replaced", ActPhase.IDLE, after.phase());
        assertEquals("with its own gating, which is what says it has not run yet: the submit landed "
                + "while the clock read 1", 2L, after.effectiveTick());
        assertEquals("the superseded result is dropped, not retried: a retrying loop would call the "
                + "applier again inside this same tick", 1, applies.get());
    }

    /**
     * A cancel landing in the same window must still be visible afterwards, and must still reach the
     * applier's teardown tick — that flag is the only reason a hold releases the use key and a dig
     * aborts its break.
     */
    @Test
    public void aCancelInsideApplySurvivesIntoTheTeardownTick() {
        runtime.submitMove(move(1f)); // tick 0, eligible at tick 1

        AtomicBoolean cancelAccepted = new AtomicBoolean();
        AtomicInteger applies = new AtomicInteger();
        runtime.registerApplier(ActSlot.MOVE, rec -> {
            applies.incrementAndGet();
            if (rec.cancelRequested()) {
                return rec.withPhase(ActPhase.CANCELLED, "torn down on the tick that saw the flag");
            }
            cancelAccepted.set(runtime.cancel(ActSlot.MOVE)); // the worker write, landing mid-tick
            return rec.markActive(rec.lastAppliedTick(), "driving");
        });

        loop.onTick(new TickEvent(clock.advance())); // tick 1: the applier runs and cancels
        assertTrue("premise: the cancel was accepted", cancelAccepted.get());
        assertTrue("the cancel must survive the loop's continuation of the record it was applied to, "
                + "or act_cancel reports a slot cancelled while it keeps running",
                runtime.record(ActSlot.MOVE).cancelRequested());

        loop.onTick(new TickEvent(clock.advance())); // tick 2: the teardown tick
        assertEquals("and the surviving flag must be what the applier sees there", ActPhase.CANCELLED,
                runtime.record(ActSlot.MOVE).phase());
        assertEquals("the cancel costs exactly one teardown tick", 2, applies.get());
    }

    /**
     * Fault isolation is not weakened by the guard: a throwing applier still fails only its own slot,
     * and that FAILED record is itself a continuation of the record the tick read — so it must not roll
     * back a submit that landed inside the same window either.
     */
    @Test
    public void aThrowStillFailsItsOwnSlotAndDoesNotRollBackASubmit() {
        MoveIntent replacement = move(-1f);
        runtime.submitMove(move(1f)); // tick 0, eligible at tick 1
        runtime.submitLook(LookIntent.set(0f, 0f, 0f));

        runtime.registerApplier(ActSlot.MOVE, rec -> {
            runtime.submitMove(replacement);
            throw new IllegalStateException("boom");
        });
        runtime.registerApplier(ActSlot.LOOK, rec -> {
            throw new IllegalStateException("look boom");
        });

        loop.onTick(new TickEvent(clock.advance())); // tick 1: both appliers run

        SlotRecord failed = runtime.record(ActSlot.LOOK);
        assertEquals("a throwing applier still fails its own slot, and only that slot",
                ActPhase.FAILED, failed.phase());
        assertTrue("with the throw named in its message: " + failed.message(),
                failed.message().contains("threw"));
        assertSame("and the MOVE submit that landed inside the throwing window still stands: "
                + runtime.record(ActSlot.MOVE).message(),
                replacement, runtime.record(ActSlot.MOVE).intent());
        assertEquals("so the FAILED record of the replaced intent was dropped rather than stored",
                ActPhase.IDLE, runtime.record(ActSlot.MOVE).phase());
    }
}
