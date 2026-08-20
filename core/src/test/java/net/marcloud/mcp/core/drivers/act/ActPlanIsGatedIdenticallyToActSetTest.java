package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;

import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToolRequirement;
import org.junit.Test;

/**
 * {@code act_plan} drives the same live player {@code act_set} does, one step at a
 * time. Its gate is copied from {@code act_set}, not invented: R1, HIGH,
 * SE_WORLD_WRITE, CAP_WORLD_WRITE.
 *
 * <p>{@link net.marcloud.mcp.core.se.PolicySideTableDriftTest} only checks
 * consistency between tables. An unlisted name is invisible to it —
 * {@code forTool} silently hands R3 / null / empty. Comparing to {@code act_set}
 * is what fails when the four rows are missing.
 */
public class ActPlanIsGatedIdenticallyToActSetTest {

    @Test
    public void actPlanMatchesActSetOnEveryGateDimension() {
        SeToolRequirement plan = SeToolRequirement.forTool("act_plan", true);
        SeToolRequirement set = SeToolRequirement.forTool("act_set", true);
        assertEquals("same ring as act_set (R3 here means the Ring row is missing)",
                set.requiredRing(), plan.requiredRing());
        assertEquals(Ring.R1, plan.requiredRing());
        assertEquals("same L3 write as act_set",
                set.writesResourceAt(), plan.writesResourceAt());
        assertEquals("same L4 privilege as act_set — disable_privilege(SE_WORLD_WRITE) "
                        + "must kill the sequencer too",
                set.requiredPrivilege(), plan.requiredPrivilege());
        assertEquals("same L5 capabilities as act_set",
                set.requiredCaps(), plan.requiredCaps());
    }
}
