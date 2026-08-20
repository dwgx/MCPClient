package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import org.junit.Test;

/**
 * The plan vocabulary is the same three maps {@code act_set} accepts. A key that
 * looks like a fourth verb ({@code wait}, {@code eval}, {@code craft}, {@code skill})
 * is refused at submit so it cannot be ticked as a no-op and cannot become a
 * write path for generated/eval gameplay code.
 */
public class UnknownVerbIsRefusedNotTickedTest {

    @Test
    public void waitEvalCraftAndSkillAreRefusedAndLeaveTheRuntimeIdle() {
        GameClock clock = new GameClock();
        ActRuntime runtime = new ActRuntime(clock);
        runtime.registerApplier(ActSlot.LOOK, r -> r);
        runtime.registerApplier(ActSlot.INTERACT, r -> r);
        runtime.registerApplier(ActSlot.MOVE, r -> r);
        ActTickLoop loop = new ActTickLoop(runtime);

        for (String verb : List.of("wait", "eval", "craft", "skill")) {
            try {
                ActPlan.parse(List.of(Map.of(verb, 1)));
                fail("unknown verb '" + verb + "' must be refused at submit");
            } catch (IllegalArgumentException e) {
                assertTrue("the complaint must name the unknown key: " + e.getMessage(),
                        e.getMessage().contains(verb));
            }
        }

        loop.onTick(new TickEvent(clock.advance()));
        for (ActSlot slot : ActSlot.values()) {
            assertFalse("unknown verbs must not occupy " + slot,
                    runtime.record(slot).intent() != null);
        }
        assertEqualsIdlePlan(runtime);
    }

    @Test
    public void emptyStepsAreRefused() {
        try {
            ActPlan.parse(List.of());
            fail("empty steps must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().toLowerCase().contains("step"));
        }
    }

    @Test
    public void aStepWithNoMoveLookOrInteractIsRefused() {
        try {
            ActPlan.parse(List.of(Map.of()));
            fail("a step with no channel must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("move")
                    && e.getMessage().contains("look")
                    && e.getMessage().contains("interact"));
        }
    }

    private static void assertEqualsIdlePlan(ActRuntime runtime) {
        ActPlanStatus st = runtime.planStatus();
        org.junit.Assert.assertEquals(ActPlanStatus.Phase.IDLE, st.phase());
        org.junit.Assert.assertEquals(0, st.size());
    }
}
