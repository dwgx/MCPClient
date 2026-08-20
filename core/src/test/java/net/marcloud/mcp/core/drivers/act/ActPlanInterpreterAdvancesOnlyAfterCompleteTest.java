package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.ke.GameClock;
import org.junit.Before;
import org.junit.Test;

/**
 * A two-step plan must not occupy the next channel until the previous step's slots are
 * COMPLETE, and that advance is {@link ActRuntime#stepPlan(long)}'s job — not the slot
 * appliers'.
 *
 * <p>The shape this pins: look, then hotbar. LOOK landing is not permission to apply
 * INTERACT on the same tick; INTERACT is submitted only after {@code stepPlan} sees
 * COMPLETE, and it becomes eligible on the next tick the way every other submit does.
 * Calling the slot loop without {@code stepPlan} must leave INTERACT empty, so forgetting
 * the hook is a test failure rather than a silent stall.
 */
public class ActPlanInterpreterAdvancesOnlyAfterCompleteTest {

    private GameClock clock;
    private ActRuntime runtime;
    private FakeActuator act;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        act = new FakeActuator();
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(act));
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));
    }

    @Test
    public void lookThenHotbarSubmitsInteractOnlyAfterLookCompletesAndStepPlanRuns() {
        ActPlan plan = ActPlan.parse(List.of(
                Map.of("look", Map.of("mode", "set", "yaw", 45.0, "pitch", 0.0)),
                Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3))));
        runtime.submitPlan(plan);

        assertSame("LOOK holds the first step immediately", ActSlot.LOOK,
                runtime.record(ActSlot.LOOK).intent().slot());
        assertNull("INTERACT must stay empty while LOOK has not completed",
                runtime.record(ActSlot.INTERACT).intent());

        long tick = clock.advance();
        stepSlots(tick);
        assertEquals("instant SET look completes on its first eligible tick",
                ActPhase.COMPLETE, runtime.record(ActSlot.LOOK).phase());
        assertNull("the slot loop completing LOOK is not enough — without stepPlan the next "
                        + "step is never submitted",
                runtime.record(ActSlot.INTERACT).intent());

        runtime.stepPlan(tick);
        ActIntent interact = runtime.record(ActSlot.INTERACT).intent();
        assertEquals("stepPlan after COMPLETE submits INTERACT", InteractIntent.class,
                interact.getClass());
        assertEquals(InteractIntent.Kind.HOTBAR, ((InteractIntent) interact).kind());
        assertEquals("the new submit is eligible next tick, not this one",
                ActPhase.IDLE, runtime.record(ActSlot.INTERACT).phase());

        tick = clock.advance();
        stepSlots(tick);
        runtime.stepPlan(tick);
        assertEquals(ActPhase.COMPLETE, runtime.record(ActSlot.INTERACT).phase());
        assertEquals(3, act.heldSlot());
        assertEquals(ActPlanStatus.Phase.COMPLETE, runtime.planStatus().phase());
    }

    /**
     * Drive each slot's applier the way {@link ActTickLoop} does, without calling
     * {@code stepPlan}. The missing hook is the whole assertion.
     */
    private void stepSlots(long tick) {
        for (ActSlot slot : ActSlot.values()) {
            SlotRecord rec = runtime.record(slot);
            if (rec.intent() == null || rec.phase().isTerminal()) {
                continue;
            }
            if (tick < rec.effectiveTick()) {
                continue;
            }
            ActApplier applier = runtime.applier(slot);
            runtime.store(slot, applier.apply(rec.stampTick(tick)));
        }
    }
}
