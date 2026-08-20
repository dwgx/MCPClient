package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.ke.GameClock;
import org.junit.Test;

/**
 * A KEEP look with {@code durationTicks <= 0} never completes on its own. Inside a
 * plan that would strand the sequencer waiting on LOOK forever, so it is refused at
 * parse/submit rather than accepted and sat on.
 *
 * <p>{@code act_set} still allows the unbounded form — that is the useful "follow
 * until I cancel" call. The refusal is a plan-only gate.
 */
public class AnUnboundedKeepLookIsRefusedAtSubmitTest {

    @Test
    public void trackWithoutAPositiveDurationIsRefusedAndOccupiesNoSlot() {
        ActRuntime runtime = new ActRuntime(new GameClock());
        try {
            ActPlan.parse(List.of(Map.of("look",
                    Map.of("mode", "look_at", "entityId", 1, "track", true))));
            fail("unbounded KEEP look must be refused at submit");
        } catch (IllegalArgumentException e) {
            assertTrue("the complaint must name track and durationTicks: " + e.getMessage(),
                    e.getMessage().contains("track") && e.getMessage().contains("durationTicks"));
        }

        try {
            ActPlan.parse(List.of(Map.of("look",
                    Map.of("mode", "look_at", "entityId", 1, "track", true, "durationTicks", 0))));
            fail("durationTicks 0 is the unbounded KEEP form and must be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("durationTicks"));
        }

        assertFalse("nothing was submitted on the way to the refusal",
                runtime.record(ActSlot.LOOK).intent() != null);
    }

    @Test
    public void aBoundedTrackIsAccepted() {
        ActPlan plan = ActPlan.parse(List.of(Map.of("look",
                Map.of("mode", "look_at", "entityId", 1, "track", true, "durationTicks", 8))));
        assertEqualsLookKeep(plan);
    }

    private static void assertEqualsLookKeep(ActPlan plan) {
        ActIntent intent = plan.steps().get(0).intents().get(0);
        assertTrue(intent instanceof LookIntent);
        LookIntent li = (LookIntent) intent;
        assertTrue(li.keepsAiming());
        org.junit.Assert.assertEquals(8, li.durationTicks());
    }
}
