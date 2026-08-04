package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToolRequirement;
import org.junit.Test;

/**
 * Teeth for {@code craft_plan}'s registration and its gate.
 *
 * <p>Why this file exists rather than leaning on {@code PolicySideTableDriftTest}: that test checks
 * CONSISTENCY between the tables (a HIGH writer must declare an L4 privilege, an SE_NET_RAW holder
 * must be a declared sender), and it cannot see a tool that is in NONE of them --
 * {@code SeToolRequirement.forTool} silently hands an unlisted builtin R3 with no privilege and no
 * capability. So a new tool that reads live world state can ship gated as a harmless local
 * bookkeeping call and every existing test stays green. Measured while writing this: the drift test
 * passed before either row was added.
 *
 * <p>The gate chosen is R2 + CAP_WORLD_READ with no L3 write and no L4 privilege, matching
 * {@code world_view} and {@code find_block}. That is deliberate and the reasoning is worth pinning
 * because the opposite mistake was on the table: an earlier plan for this work said
 * SE_WORLD_WRITE, reasoning from the repository's own note at {@code SeToolRequirement} that
 * actuation needs a write privilege. That note is right about the tool that PERFORMS a craft. This
 * one performs nothing -- it reads a static recipe table and the inventory -- and declaring a write
 * privilege for a read would be the same class of untruth as the descriptions this batch spent its
 * time fixing: a gate that says something other than what the code does.
 */
public class CraftPlanIsRegisteredAndGatedTest {

    private static SyncToolSpecification tool(String name) {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    @Test
    public void craftPlanIsRegistered() {
        assertNotNull("craft_plan must be registered, or the whole craft package stays unreachable "
                + "from the model it was built for", tool("craft_plan"));
    }

    @Test
    public void craftPlanIsGatedAsAnObserverNotAsAnActuator() {
        SeToolRequirement req = SeToolRequirement.forTool("craft_plan", true);
        assertEquals("craft_plan reads and never writes, so it belongs at R2 with the other "
                        + "observers; R3 is what an UNLISTED tool silently gets, so seeing R3 here "
                        + "means the Ring row is missing rather than that R3 was chosen",
                Ring.R2, req.requiredRing());
        assertTrue("it must require CAP_WORLD_READ: it reads the live inventory",
                req.requiredCaps().contains(CapabilitySid.CAP_WORLD_READ));
        assertEquals("and nothing more than that", 1, req.requiredCaps().size());
    }

    @Test
    public void craftPlanDeclaresNoWritePrivilegeBecauseItWritesNothing() {
        SeToolRequirement req = SeToolRequirement.forTool("craft_plan", true);
        assertNull("craft_plan must NOT declare an L4 privilege. A write privilege on a read is a "
                + "gate that describes something other than what the code does, and it would also "
                + "make disable_privilege(SE_WORLD_WRITE) switch off a read -- an operator locking "
                + "down actuation would silently lose the ability to ASK what a recipe needs",
                req.requiredPrivilege());
        assertNull("nor an L3 write target, for the same reason", req.writesResourceAt());
    }

    @Test
    public void craftPlanIsGatedExactlyLikeTheOtherObservers() {
        SeToolRequirement mine = SeToolRequirement.forTool("craft_plan", true);
        SeToolRequirement peer = SeToolRequirement.forTool("find_block", true);
        assertEquals("same ring as find_block", peer.requiredRing(), mine.requiredRing());
        assertEquals("same capabilities", peer.requiredCaps(), mine.requiredCaps());
        assertEquals("same (absent) privilege", peer.requiredPrivilege(), mine.requiredPrivilege());
    }

    /**
     * The description must admit that nothing here crafts.
     *
     * <p>A model reading a tool called craft_plan can reasonably assume a sibling exists that
     * performs the craft. None does: the multi-tick controller is written and tested but nothing
     * drives it, because that needs a live handle on the open container window. Leaving that unsaid
     * would have the model plan a craft and then hunt for the tool that executes it.
     */
    @Test
    public void theDescriptionSaysNoToolPerformsACraftYet() {
        String desc = tool("craft_plan").tool().description();
        assertTrue("must state that planning is not acting: " + desc,
                desc.contains("no tool that performs a craft"));
        assertTrue("must say cells are not slot indices, since the slot depends on the open window",
                desc.contains("NOT a slot index"));
        assertTrue("must say the shortfall names the ingredient, which is the actionable part",
                desc.contains("held/needed counts"));
    }
}
