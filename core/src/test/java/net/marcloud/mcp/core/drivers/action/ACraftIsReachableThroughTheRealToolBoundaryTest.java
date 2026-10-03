package net.marcloud.mcp.core.drivers.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.drivers.act.ActIntentParser;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.io.IoProbe;

import org.junit.Test;

/**
 * {@code act_set interact kind='craft'} must be reachable THROUGH THE TOOL BOUNDARY, not merely
 * understood by the parser.
 *
 * <p><b>Why this file exists at all.</b> This is the DROP defect wearing a different hat. The DROP
 * verb was built, driven and green in the eval harness, and was still uncallable by a model: the
 * boundary validates every argument against the tool's PUBLISHED JSON schema before the handler
 * runs, and {@code interact.kind} was an enum that did not contain {@code drop}. The verb was inert
 * one layer above the actuators. The craft had the mirror-image problem -- a complete, tested
 * controller with zero producers in the main tree, which is the eighth entry in
 * {@code failure-shapes.md} -- and the same boundary is where it would have been refused again.
 *
 * <p><b>Chained, not split.</b> Every assertion here validates the same map against the schema the
 * tool actually publishes and then parses that same map. A test that only called
 * {@link ActIntentParser} would pass against a boundary that refuses the call, which is precisely
 * how the DROP verb stayed green for so long.
 */
public final class ACraftIsReachableThroughTheRealToolBoundaryTest {

    /** The {@code interact} sub-schema {@code act_set} actually publishes. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> interactSchema() {
        return (Map<String, Object>) ((Map<String, Object>)
                new ActTools().actSet().tool().inputSchema().get("properties")).get("interact");
    }

    /** The interact argument map a model would send to craft {@code item}, and nothing else. */
    private static Map<String, Object> craftCall(String item) {
        Map<String, Object> interact = new LinkedHashMap<>();
        interact.put("kind", "craft");
        if (item != null) {
            interact.put("item", item);
        }
        return interact;
    }

    /**
     * The whole path: the boundary accepts it, and the parser builds a CRAFT intent carrying the
     * item name that was sent.
     *
     * <p>Both halves are asserted in one test deliberately -- each was green while the other was
     * broken, so neither alone can catch this defect.
     */
    @Test
    public void aCraftCallPassesTheBoundaryAndCarriesTheItemIntoTheIntent() {
        Map<String, Object> interact = craftCall("minecraft:stick");
        var check = IoProbe.validate(interactSchema(), interact);
        assertTrue("the boundary must accept kind=craft; it refuses with " + check.message(),
                check.ok());

        InteractIntent intent = ActIntentParser.parseInteract(interact);
        assertEquals(InteractIntent.Kind.CRAFT, intent.kind());
        assertEquals("the item must survive the boundary AND the parser",
                "minecraft:stick", intent.craftItem());
    }

    /** The published enum must carry {@code craft}, spelled as the parser switches on it. */
    @Test
    @SuppressWarnings("unchecked")
    public void theKindEnumCarriesCraftAndTheParserAgreesOnTheSpelling() {
        Map<String, Object> kind =
                (Map<String, Object>) ((Map<String, Object>) interactSchema().get("properties"))
                        .get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue("the published enum is what the boundary validates against, and it publishes "
                + kinds, kinds.contains("craft"));
        assertEquals("the parser must accept exactly the spelling the schema offers",
                InteractIntent.Kind.CRAFT,
                ActIntentParser.parseInteract(craftCall("stick")).kind());
    }

    /** A missing {@code item} is refused by name, not defaulted onto some recipe. */
    @Test
    public void aMissingItemIsRefusedRatherThanCraftingSomethingUnnamed() {
        String refused = null;
        try {
            ActIntentParser.parseInteract(craftCall(null));
        } catch (IllegalArgumentException e) {
            refused = e.getMessage();
        }
        assertTrue("kind='craft' with no item names no recipe at all and must be REFUSED, got: "
                + refused, refused != null);
        assertTrue("the refusal must point at craft_plan, which is how a model learns what to ask "
                + "for: " + refused, refused.contains("craft_plan"));
    }

    /** A blank item is refused too -- {@code ""} has no recipe either. */
    @Test
    public void aBlankItemIsRefusedRatherThanTreatedAsAnEmptyName() {
        String refused = null;
        try {
            ActIntentParser.parseInteract(craftCall("   "));
        } catch (IllegalArgumentException e) {
            refused = e.getMessage();
        }
        assertTrue("a blank item names nothing and must be refused, got: " + refused,
                refused != null);
    }

    /**
     * Block-target arguments are REFUSED on {@code craft}, never dropped.
     *
     * <p>A craft acts on the open crafting window and an item name. A caller who also named a block
     * was pointing at something else, and honouring the craft while discarding the block would
     * report success for a request whose actual target was never touched -- the rule
     * {@code refuseUnusableTarget} exists for.
     */
    @Test
    public void aBlockTargetOnACraftIsRefusedRatherThanSilentlyDropped() {
        for (String key : List.of("block", "face", "hitX", "hitY", "hitZ", "entityId",
                "hotbarSlot", "slot", "holdTicks", "attack")) {
            Map<String, Object> interact = craftCall("stick");
            interact.put(key, key.equals("block") || key.equals("hitX") || key.equals("hitY")
                    || key.equals("hitZ") ? 1 : 0);
            String refused = null;
            try {
                ActIntentParser.parseInteract(interact);
            } catch (IllegalArgumentException e) {
                refused = e.getMessage();
            }
            assertTrue("'" + key + "' has nowhere to go on a craft and must be REFUSED by name, "
                    + "got: " + refused, refused != null);
            assertTrue("the refusal must name the offending key, got: " + refused,
                    refused.contains(key));
        }
    }

    /**
     * The two tools must not blur into each other: {@code craft_plan} plans and changes nothing,
     * and the verb that spends things is named on {@code act_set}.
     *
     * <p>This is the honesty half of the change. {@code craft_plan}'s description used to say
     * "there is no tool that performs a craft yet", which was the eighth {@code failure-shapes}
     * entry -- a description promising the absence of a capability, in a file whose whole subject is
     * descriptions that overclaim. After the change it names the acting verb instead, and this
     * assertion fails if either tool's description drifts back into claiming the other's job.
     */
    @Test
    public void neitherToolsDescriptionClaimsTheOthersJob() {
        String plan = new net.marcloud.mcp.core.io.transport.ToolRegistry(
                new net.marcloud.mcp.core.io.transport.ToolContext(null, null, null, null, null))
                .all().stream()
                .filter(s -> "craft_plan".equals(s.tool().name()))
                .findFirst().orElseThrow()
                .tool().description();

        assertTrue("craft_plan must not still claim nothing can perform a craft; that sentence is "
                + "what this change exists to remove", !plan.contains("no tool that performs a craft"));
        assertTrue("craft_plan must now name the verb that performs the craft, or a model reading "
                + "it is told how to plan and not how to act: " + plan,
                plan.contains("kind='craft'"));

        String actSet = new ActTools().actSet().tool().description();
        assertTrue("act_set must carry craft_plan's grid and shortfall half in its description, so "
                + "a model holding only act_set knows to plan first", actSet.contains("craft_plan"));
    }
}