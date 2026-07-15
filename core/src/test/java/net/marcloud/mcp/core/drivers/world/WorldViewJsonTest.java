package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Teeth for PHASE W JSON projection + profiles: pure, no game thread. Verifies the
 * reference-free Map projection (Json only serializes Map/List/scalars) and profile
 * budget knobs.
 */
public final class WorldViewJsonTest {

    @Test
    public void absentSerializesPresentFalse() {
        Map<String, Object> m = WorldViewJson.toMap(WorldView.absent());
        assertEquals(false, m.get("present"));
        assertFalse(m.containsKey("self"));
    }

    @Test
    public void selfProjectionIsReferenceFreeScalars() {
        SelfView self = new SelfView(1.234, 64, -5.678, 0, 0, 0, 90f, 10f, 20f, 18, 4f,
                5, 0.3f, 4, 300, "CREATIVE", true, false, true,
                List.of(new SelfView.Effect(1, "speed", 0, 200)));
        Map<String, Object> m = WorldViewJson.selfMap(self);
        // pos rounded to 2dp and is a List of scalars, not an object
        assertTrue(m.get("pos") instanceof List);
        assertEquals(1.23, ((List<?>) m.get("pos")).get(0));
        assertEquals("CREATIVE", m.get("gamemode"));
        assertEquals(true, m.get("sneaking"));
        // effect projected as a Map of scalars
        List<?> fx = (List<?>) m.get("effects");
        assertEquals(1, fx.size());
        assertEquals("speed", ((Map<?, ?>) fx.get(0)).get("name"));
    }

    @Test
    public void inventoryUsesPerSlotRows() {
        InventoryView inv = new InventoryView(2, List.of(
                new InventoryView.Slot(0, "dirt", 64, 0, null),
                new InventoryView.Slot(3, "diamond_pickaxe", 1, 12, 1561)));
        Map<String, Object> m = WorldViewJson.invMap(inv);
        assertEquals(2, m.get("selectedSlot"));
        List<?> slots = (List<?>) m.get("slots");
        assertEquals(2, slots.size());
        Map<?, ?> pick = (Map<?, ?>) slots.get(1);
        assertEquals("diamond_pickaxe", pick.get("item"));
        assertEquals(1561, pick.get("maxDamage"));   // damageable surfaces maxDamage
        Map<?, ?> dirt = (Map<?, ?>) slots.get(0);
        assertFalse("non-damageable omits maxDamage", dirt.containsKey("maxDamage"));
    }

    @Test
    public void profileBudgetsDiffer() {
        assertTrue("combat casts a wider entity net than sparse",
                ObserveProfile.COMBAT.maxEntities > ObserveProfile.SPARSE.maxEntities);
        assertFalse("sparse does not emit full vertical profile", ObserveProfile.SPARSE.emitProfile);
        assertTrue("explore emits full vertical profile", ObserveProfile.EXPLORE.emitProfile);
        assertEquals("unknown profile defaults to explore", ObserveProfile.EXPLORE, ObserveProfile.parse("bogus"));
        assertEquals(ObserveProfile.COMBAT, ObserveProfile.parse("combat"));
    }

    @Test
    public void targetBlockProjectsPosAndSide() {
        TargetView t = new TargetView("block", "stone", 10, 64, -3, "UP", null, null, null, 4.5);
        Map<String, Object> m = WorldViewJson.targetMap(t);
        assertEquals("block", m.get("hitType"));
        assertEquals("stone", m.get("block"));
        assertEquals("UP", m.get("side"));
        assertEquals(List.of(10, 64, -3), m.get("pos"));
    }
}
