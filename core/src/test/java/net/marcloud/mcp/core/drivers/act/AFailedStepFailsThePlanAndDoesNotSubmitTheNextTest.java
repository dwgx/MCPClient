package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Before;
import org.junit.Test;

/**
 * A step that ends FAILED is the end of the plan. The next step must not be submitted,
 * because submitting it would keep acting after the caller already has a honest failure.
 */
public class AFailedStepFailsThePlanAndDoesNotSubmitTheNextTest {

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
        FakeActuator act = new FakeActuator();
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(act));
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));
    }

    @Test
    public void aFailedLookDoesNotSubmitTheFollowingHotbar() {
        ActPlan plan = ActPlan.parse(List.of(
                Map.of("look", Map.of("mode", "look_at", "entityId", 99)),
                Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3))));
        runtime.submitPlan(plan);

        loop.onTick(new TickEvent(clock.advance()));

        assertEquals("a LOOK_AT with no entity fails rather than hanging",
                ActPhase.FAILED, runtime.record(ActSlot.LOOK).phase());
        assertNull("FAILED must not submit the next step",
                runtime.record(ActSlot.INTERACT).intent());
        assertEquals(ActPlanStatus.Phase.FAILED, runtime.planStatus().phase());
        assertTrue("the plan names the failed step, not a later one: "
                        + runtime.planStatus().message(),
                runtime.planStatus().message().toLowerCase().contains("gone")
                        || runtime.planStatus().message().toLowerCase().contains("fail"));
        assertEquals("stuck on the failed index, not advanced", 0, runtime.planStatus().index());
    }
}
