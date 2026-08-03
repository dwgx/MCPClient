package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import org.junit.Test;

/**
 * Teeth for one defect shape: a tool EMITS a field whose meaning reaches the model only
 * through a Java comment. {@code world_view} shipped emitting {@code walk} and {@code drop}
 * -- where OMISSION is load-bearing -- while its description named neither, so the model saw
 * {@code "walk":-2} with no legend and could not know that a missing {@code walk} means
 * walkable. {@code find_block} meanwhile told the model {@code blockCounts} "says a type is
 * PRESENT", which is false: it counts each column's surface only.
 *
 * <p>The vocabulary here is DERIVED from what {@link WorldViewJson#gridMap} actually emits
 * rather than hand-listed, so adding a column key without documenting it fails this test. A
 * hand-written keyword list would have been the empty-assertion shape this repo has caught in
 * itself twice (an {@code indexOf} that matched a javadoc instead of a call, and a
 * {@code matches()} assertion with no production caller).
 *
 * <p>The second half pins the OMISSION BEHAVIOUR the legend describes, because a legend is
 * only true while the encoding it documents holds: if {@code walk == 1} stopped being omitted,
 * every assertion below would still pass while the description silently became a lie.
 */
public class GridSemanticsAreDocumentedTest {

    private static String description(String toolName) {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(toolName)) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("tool not found: " + toolName);
    }

    /** A column exercising every emitting branch: a real drop, an unknown walk, a profile. */
    private static Map<String, Object> everyKeyColumn() {
        LocalGrid.Column c = new LocalGrid.Column(1, 2, -1, "stone", "air", "air",
                List.of(new LocalGrid.Run("stone", -3, 2)), 7, LocalGrid.WALK_UNKNOWN);
        LocalGrid g = new LocalGrid(1, "column", 0, 64, 0, List.of(c), Map.of("stone", 1));
        List<?> cols = (List<?>) WorldViewJson.gridMap(g).get("columns");
        return asMap(cols.get(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    public void everyGridColumnKeyIsNamedInTheWorldViewDescription() {
        String desc = description("world_view");
        for (String key : everyKeyColumn().keySet()) {
            assertTrue("world_view emits column key '" + key + "' but its description never "
                    + "names it, so the model has no legend for it", desc.contains(key));
        }
    }

    @Test
    public void theWorldViewDescriptionExplainsThatOmissionIsMeaningful() {
        String desc = description("world_view");
        // Not merely "does it say walk" -- the load-bearing fact is that ABSENCE carries
        // meaning, which is the one thing a reader cannot infer from a sample payload.
        assertTrue("the description must state that an ABSENT key is meaningful, not just "
                + "name the keys", desc.contains("ABSENT") || desc.contains("OMITTED"));
        assertTrue("the description must say what an absent walk means (walkable)",
                desc.contains("ABSENT means WALKABLE"));
        assertTrue("the description must say what an absent drop means (no fall)",
                desc.contains("ABSENT means 0"));
    }

    @Test
    public void theWorldViewDescriptionSeparatesUnknownFromWalkable() {
        String desc = description("world_view");
        // The whole reason "?" is emitted instead of being omitted like 1: "we could not ask"
        // and "you can walk here" must not collapse into the same silence.
        assertTrue("the description must state that \"?\" is NOT walkable, since that is the "
                + "distinction the encoding exists to preserve",
                desc.contains("NOT walkable"));
    }

    @Test
    public void aClearWalkAndZeroDropAreOmittedAsTheLegendClaims() {
        LocalGrid.Column plain = new LocalGrid.Column(0, 0, 0, "grass", "air", "air",
                List.of(), 0, LocalGrid.WALK_CLEAR);
        LocalGrid g = new LocalGrid(1, "surface", 0, 64, 0, List.of(plain), Map.of());
        Map<String, Object> cm = asMap(((List<?>) WorldViewJson.gridMap(g).get("columns")).get(0));
        assertFalse("walk==WALK_CLEAR must stay OFF the wire, or 'absent means walkable' "
                + "becomes false", cm.containsKey("walk"));
        assertFalse("drop==0 must stay OFF the wire, or 'absent means 0' becomes false",
                cm.containsKey("drop"));
    }

    @Test
    public void anUnknownWalkIsSentAsAQuestionMarkNotOmitted() {
        assertEquals("an unobtainable verdict must be SENT as \"?\", never omitted -- omitting "
                + "it would read as walkable", "?", everyKeyColumn().get("walk"));
    }

    @Test
    public void anUnboundedDropIsSentAsDeepSoTheCapIsDistinguishable() {
        LocalGrid.Column bottomless = new LocalGrid.Column(0, 0, 0, "air", "air", "air",
                List.of(), null, LocalGrid.WALK_CLEAR);
        LocalGrid g = new LocalGrid(1, "surface", 0, 64, 0, List.of(bottomless), Map.of());
        Map<String, Object> cm = asMap(((List<?>) WorldViewJson.gridMap(g).get("columns")).get(0));
        assertEquals("a probe that bottomed out must be distinguishable from a measured depth",
                "deep", cm.get("drop"));
        assertTrue("the description must document \"deep\"",
                description("world_view").contains("\"deep\""));
    }

    @Test
    public void blockCountsIsDocumentedAsSurfaceOnlyInBothToolsThatMentionIt() {
        String worldView = description("world_view");
        String findBlock = description("find_block");
        assertTrue("world_view must say blockCounts is SURFACE-only, since a buried block type "
                + "is absent from it entirely", worldView.contains("SURFACE"));
        // find_block's description is where the false gloss lived: it told the model
        // blockCounts "says a type is PRESENT", which invites concluding absence means absent.
        assertFalse("find_block must not claim blockCounts says a type is PRESENT",
                findBlock.contains("says a type is PRESENT"));
        assertTrue("find_block must say why blockCounts cannot answer presence",
                findBlock.contains("SURFACE"));
    }

    // act_set's own undocumented-semantics defect (to:[x,y,z] never steers toward y) is pinned
    // in ActToolsTest instead: actSet() is package-private to ...drivers.action, the same reason
    // this class lives in ...drivers.world rather than beside ToolRegistry.
}
