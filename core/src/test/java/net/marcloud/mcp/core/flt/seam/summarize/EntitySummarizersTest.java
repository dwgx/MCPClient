package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import net.minecraft.network.play.server.S0DPacketCollectItem;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import org.junit.Test;

/**
 * Teeth for the entity-family summarizers. Only packets with test-friendly public
 * primitive constructors are asserted here (S0D collect, S13 destroy) — the spawn
 * packets take an {@code Entity} and cannot be built headless, so their getters are
 * verified at compile time (the summarizers reference them) and their format is
 * exercised live. These assert the typed projection round-trips real values.
 */
public class EntitySummarizersTest {

    private final PacketSummarizerRegistry reg = PacketSummarizerRegistry.defaults();

    @Test
    public void collectItemSurfacesBothIds() {
        S0DPacketCollectItem p = new S0DPacketCollectItem(1001, 42);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("item=1001"));
        assertTrue(s, s.contains("by=42"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(1001, ((Number) m.get("collectedEid")).intValue());
        assertEquals(42, ((Number) m.get("collectorEid")).intValue());
    }

    @Test
    public void destroyEntitiesSurfacesIdList() {
        S13PacketDestroyEntities p = new S13PacketDestroyEntities(7, 8, 9);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("count=3"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(3, ((Number) m.get("count")).intValue());
        @SuppressWarnings("unchecked")
        List<Object> eids = (List<Object>) m.get("eids");
        assertEquals(3, eids.size());
        assertEquals(7, ((Number) eids.get(0)).intValue());
        assertEquals(9, ((Number) eids.get(2)).intValue());
    }

    @Test
    public void destroyEntitiesEmptyIsHonestZero() {
        S13PacketDestroyEntities p = new S13PacketDestroyEntities();
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(0, ((Number) m.get("count")).intValue());
    }
}
