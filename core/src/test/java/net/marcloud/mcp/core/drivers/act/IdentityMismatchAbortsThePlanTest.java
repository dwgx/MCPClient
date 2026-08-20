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
 * The wait policy is COMPLETE <i>of the same intent identity</i>. A racing
 * {@code act_set} that replaces the slot is supersession, not success: the plan
 * aborts and does not submit the next step.
 *
 * <p>LOOK is slewed so the first tick leaves it ACTIVE; that is the window a
 * concurrent submit can steal the identity.
 */
public class IdentityMismatchAbortsThePlanTest {

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
        act.yaw = 0f;
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(act));
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(act));
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));
    }

    @Test
    public void aRacingLookSubmitAbortsThePlanAndDoesNotSubmitHotbar() {
        ActPlan plan = ActPlan.parse(List.of(
                Map.of("look", Map.of("mode", "set", "yaw", 90.0, "pitch", 0.0,
                        "slewDegPerTick", 1.0)),
                Map.of("interact", Map.of("kind", "hotbar", "hotbarSlot", 3))));
        runtime.submitPlan(plan);
        ActIntent planned = runtime.record(ActSlot.LOOK).intent();

        loop.onTick(new TickEvent(clock.advance()));
        assertEquals("slew leaves LOOK live so a race is possible",
                ActPhase.ACTIVE, runtime.record(ActSlot.LOOK).phase());
        assertSameIdentity(planned, runtime.record(ActSlot.LOOK).intent());

        runtime.submitLook(LookIntent.set(0f, 0f, 0f));
        loop.onTick(new TickEvent(clock.advance()));

        ActPlanStatus st = runtime.planStatus();
        assertTrue("identity mismatch is terminal, not a retry: " + st.phase(),
                st.phase() == ActPlanStatus.Phase.FAILED
                        || st.phase() == ActPlanStatus.Phase.CANCELLED);
        assertTrue("the plan must name supersession so a racing act_set is diagnosable: "
                        + st.message(),
                st.message().toLowerCase().contains("supersed"));
        assertNull("the next step must not be submitted after supersession",
                runtime.record(ActSlot.INTERACT).intent());
    }

    private static void assertSameIdentity(ActIntent expected, ActIntent actual) {
        org.junit.Assert.assertSame("precondition: the planned look is still in the slot",
                expected, actual);
    }
}
