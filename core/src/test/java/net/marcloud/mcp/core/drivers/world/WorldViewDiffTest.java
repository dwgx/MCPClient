package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Teeth for PHASE W.7 diff: pure, no game thread. Builds WorldViews by hand and
 * asserts the diff emits only changed sections and honors the dead-band.
 */
public final class WorldViewDiffTest {

    private static WorldView view(double x, float health, List<EntityView> ents) {
        SelfView self = new SelfView(x, 64, 0, 0, 0, 0, 0f, 0f, health, 20, 5f,
                3, 0.5f, 0, 300, "SURVIVAL", false, false, true, List.of());
        return new WorldView(true, 1L, "explore", self, null, ents, null,
                TargetView.miss(), null);
    }

    @Test
    public void nullPrevFallsBackToFull() {
        Map<String, Object> d = WorldViewDiff.diff(null, view(10, 20f, List.of()));
        assertEquals("full", d.get("mode"));
        assertTrue(d.containsKey("self"));
    }

    @Test
    public void unchangedSelfOmitted() {
        WorldView a = view(10, 20f, List.of());
        WorldView b = view(10, 20f, List.of());
        Map<String, Object> d = WorldViewDiff.diff(a, b);
        assertEquals("diff", d.get("mode"));
        assertFalse("no self change -> section omitted (token saver)", d.containsKey("self"));
    }

    @Test
    public void posMoveBeyondDeadBandEmitted() {
        Map<String, Object> d = WorldViewDiff.diff(view(10, 20f, List.of()), view(12, 20f, List.of()));
        assertTrue(d.containsKey("self"));
        assertTrue(((Map<?, ?>) d.get("self")).containsKey("pos"));
    }

    @Test
    public void tinyJitterUnderDeadBandSuppressed() {
        // 0.05 < POS_BAND 0.1 -> must NOT emit pos
        Map<String, Object> d = WorldViewDiff.diff(view(10.0, 20f, List.of()), view(10.05, 20f, List.of()));
        assertFalse("sub-dead-band jitter suppressed", d.containsKey("self"));
    }

    @Test
    public void healthChangeEmitted() {
        Map<String, Object> d = WorldViewDiff.diff(view(10, 20f, List.of()), view(10, 15f, List.of()));
        assertTrue(d.containsKey("self"));
        assertEquals(15f, ((Map<?, ?>) d.get("self")).get("health"));
    }

    @Test
    public void entityEnteredLeftMoved() {
        EntityView zombie1 = new EntityView(7, "Zombie", 5, 64, 0, 5.0, 20, "Zombie");
        EntityView zombie2moved = new EntityView(7, "Zombie", 8, 64, 0, 8.0, 20, "Zombie");
        EntityView cow = new EntityView(9, "Cow", 3, 64, 0, 3.0, 10, "Cow");

        WorldView a = view(10, 20f, List.of(zombie1));
        WorldView b = view(10, 20f, List.of(zombie2moved, cow));
        Map<String, Object> d = WorldViewDiff.diff(a, b);
        Map<?, ?> ent = (Map<?, ?>) d.get("entities");
        assertTrue("cow entered", ent.containsKey("entered"));
        assertTrue("zombie moved", ent.containsKey("moved"));

        // zombie leaves next frame
        Map<String, Object> d2 = WorldViewDiff.diff(b, view(10, 20f, List.of(cow)));
        Map<?, ?> ent2 = (Map<?, ?>) d2.get("entities");
        assertTrue("zombie left", ent2.containsKey("left"));
    }
}
