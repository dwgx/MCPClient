package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import org.junit.Test;

/**
 * {@code world_view mode=diff} reports {@code entities.left} for ids that were in the previous
 * snapshot and are not in this one, which is a fact about SAMPLING that reads as a fact about the
 * WORLD. A caller acting on "left" believes the creeper is dead.
 *
 * <p>Two ways an id lands in left while the entity is alive and adjacent, both reproduced below
 * as behaviour rather than asserted from prose:
 * <ul>
 *   <li>the section was not requested -- {@code WorldViewCapture:50-51} hands the differ
 *       {@code List.of()} for an unwanted "entities", and {@code byId} of an empty list makes
 *       EVERY previously known id report left in one go;</li>
 *   <li>the profile's {@code maxEntities} cap truncated it ({@code WorldViewCapture:135-137},
 *       after a distance sort) -- so a nearer entity arriving EVICTS a farther one, and the
 *       eviction is indistinguishable from a departure.</li>
 * </ul>
 *
 * <p>The behaviour half matters because the description is only true while the encoding it
 * describes holds: if a future change made an unrequested section null-and-skipped, the caveat
 * would become misleading in the other direction, and a prose-only test would stay green.
 *
 * <p>Sibling of {@code GridSemanticsAreDocumentedTest}, same defect family (a field whose real
 * meaning lives only in code), different tool section.
 */
public class DiffLeftMeansUnsampledNotGoneTest {

    private static String worldViewDescription() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("world_view not found");
    }

    private static EntityView entity(int id, double dist) {
        return new EntityView(id, "Zombie", dist, 64.0, 0.0, dist, 20, "Zombie");
    }

    private static WorldView view(long tick, List<EntityView> entities) {
        return new WorldView(true, tick, "EXPLORE", null, null, entities, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> left(Map<String, Object> diff) {
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        return ent == null ? List.of() : (List<Object>) ent.get("left");
    }

    @Test
    public void anUnrequestedEntitiesSectionReportsEveryKnownIdAsLeft() {
        WorldView prev = view(1L, List.of(entity(7, 3.0), entity(8, 5.0)));
        // What capture produces when 'sections' omits "entities": not null, an EMPTY list.
        WorldView cur = view(2L, List.of());

        List<Object> left = left(WorldViewDiff.diff(prev, cur));
        assertEquals("both ids report left although nothing was even looked at", 2, left.size());
        assertTrue(left.contains(7) && left.contains(8));
    }

    @Test
    public void anEntityEvictedByTheEntityCapIsReportedAsLeft() {
        // Cap of 1 for the illustration: prev held the farther zombie, a nearer one arrives and
        // takes the only slot. Nothing died; the far one is simply off the end of the sort.
        WorldView prev = view(1L, List.of(entity(8, 9.0)));
        WorldView cur = view(2L, List.of(entity(9, 1.0)));

        Map<String, Object> diff = WorldViewDiff.diff(prev, cur);
        assertTrue("the evicted id reads exactly like a departure", left(diff).contains(8));
        @SuppressWarnings("unchecked")
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        assertTrue("and it arrives alongside an 'entered', which is the only hint available",
                ent.containsKey("entered"));
    }

    @Test
    public void theDescriptionSaysLeftDoesNotMeanGone() {
        String desc = worldViewDescription();
        assertTrue("the description must name the field it is glossing",
                desc.contains("entities.left"));
        assertTrue("and must deny the reading a caller will otherwise take",
                desc.contains("does NOT mean the entity is gone"));
        assertTrue("naming the honest alternative to 'gone'", desc.contains("NOT SAMPLED"));
    }

    @Test
    public void theDescriptionNamesBothUnsampledPaths() {
        String desc = worldViewDescription();
        assertTrue("the unrequested-section path, which produces a whole-set left",
                desc.contains("sections"));
        assertTrue("the truncation path -- and with the real per-profile numbers, since 'a cap "
                + "exists' is not actionable", desc.contains("cap"));
        for (ObserveProfile p : ObserveProfile.values()) {
            assertTrue("the description must state " + p + "'s actual entity cap ("
                    + p.maxEntities + "), or the model cannot tell truncation from departure",
                    desc.contains(String.valueOf(p.maxEntities)));
        }
    }

    /**
     * The caps are derived from {@link ObserveProfile} above rather than hand-copied, so changing
     * one without updating the description fails. Guard against the derivation going hollow: if
     * every profile shared a cap the loop would prove little, and if a cap were a value that
     * appears incidentally in the prose it would pass for the wrong reason.
     */
    @Test
    public void theProfileCapsAreDistinctSoThatDerivationIsNotHollow() {
        long distinct = java.util.Arrays.stream(ObserveProfile.values())
                .mapToInt(p -> p.maxEntities).distinct().count();
        assertEquals("distinct caps per profile keep the assertion above meaningful",
                ObserveProfile.values().length, distinct);
    }
}
