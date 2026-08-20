package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.ke.GameClock;
import org.junit.Test;

/**
 * Raw MOVE axes with {@code durationTicks <= 0} hold the input until cancelled. A
 * plan cannot cancel itself, so that form is unbounded and is refused at submit.
 *
 * <p>{@code to:} / {@code route:} are outcome-bounded and stay legal; only the
 * open-loop axes need a positive duration.
 */
public class RawAxesWithoutDurationAreRefusedAtSubmitTest {

    @Test
    public void forwardWithoutDurationTicksIsRefused() {
        ActRuntime runtime = new ActRuntime(new GameClock());
        try {
            ActPlan.parse(List.of(Map.of("move", Map.of("forward", 1.0))));
            fail("raw axes without durationTicks must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue("the complaint must name durationTicks: " + e.getMessage(),
                    e.getMessage().contains("durationTicks"));
        }
        assertFalse(runtime.record(ActSlot.MOVE).intent() != null);
    }

    @Test
    public void durationTicksZeroIsTheUnboundedFormAndIsRefused() {
        try {
            ActPlan.parse(List.of(Map.of("move",
                    Map.of("forward", 1.0, "durationTicks", 0))));
            fail("durationTicks 0 must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("durationTicks"));
        }
    }

    @Test
    public void aPositiveDurationIsAccepted() {
        ActPlan plan = ActPlan.parse(List.of(
                Map.of("move", Map.of("forward", 1.0, "durationTicks", 5))));
        ActIntent intent = plan.steps().get(0).intents().get(0);
        assertTrue(intent instanceof MoveIntent);
        org.junit.Assert.assertEquals(5, ((MoveIntent) intent).durationTicks());
    }

    @Test
    public void routeAndToTogetherAreRefused() {
        try {
            ActPlan.parse(List.of(Map.of("move", Map.of(
                    "to", List.of(1.0, 2.0, 3.0),
                    "route", List.of(4.0, 5.0, 6.0)))));
            fail("route and to together must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue("the complaint must name both keys: " + e.getMessage(),
                    e.getMessage().contains("to") && e.getMessage().contains("route"));
        }
    }
}
