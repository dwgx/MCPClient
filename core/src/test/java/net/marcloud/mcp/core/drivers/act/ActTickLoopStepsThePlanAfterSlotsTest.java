package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link ActTickLoop#onTick} must call {@code runtime.stepPlan(tick)} AFTER the
 * existing slot loop. Same-tick COMPLETE → next-step submit is only possible in
 * that order; calling {@code stepPlan} first would see LOOK still IDLE/ACTIVE and
 * leave INTERACT empty until a later tick.
 *
 * <p>The LOOK applier itself is the witness: while it is completing, INTERACT
 * must still be empty. After {@code onTick} returns, INTERACT must already hold
 * the next step.
 */
public class ActTickLoopStepsThePlanAfterSlotsTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;
    private FakeActuator act;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
        act = new FakeActuator();
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));
    }

    @Test
    public void onTickSubmitsTheNextStepAfterTheCompletingSlotApply() {
        AtomicBoolean sawComplete = new AtomicBoolean();
        AtomicReference<ActIntent> interactDuringLook = new AtomicReference<>();
        LookApplier real = new LookApplier(act);
        runtime.registerApplier(ActSlot.LOOK, rec -> {
            SlotRecord next = real.apply(rec);
            if (next.phase() == ActPhase.COMPLETE) {
                sawComplete.set(true);
                interactDuringLook.set(runtime.record(ActSlot.INTERACT).intent());
            }
            return next;
        });

        runtime.submitPlan(ActPlan.parse(List.of(
                Map.of("look", Map.of("mode", "set", "yaw", 10.0, "pitch", 0.0)),
                Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3)))));

        loop.onTick(new TickEvent(clock.advance()));

        assertTrue("LOOK must have completed inside this onTick, or the order is untested",
                sawComplete.get());
        assertNull("INTERACT must still be empty WHILE LOOK is completing — that is the "
                        + "proof stepPlan has not run yet",
                interactDuringLook.get());
        ActIntent after = runtime.record(ActSlot.INTERACT).intent();
        assertTrue("after onTick returns, INTERACT holds the next step",
                after instanceof InteractIntent);
        assertEquals(InteractIntent.Kind.HOTBAR, ((InteractIntent) after).kind());
        assertEquals("submitted this tick, eligible next",
                ActPhase.IDLE, runtime.record(ActSlot.INTERACT).phase());
    }
}
