package net.marcloud.mcp.core.drivers.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable {@code Map<String,Object>} projection of a {@link WorldView} for the
 * dep-free {@code Json} writer (which only serializes Map/List/scalars — records
 * are NOT reflectively serialized). Fixed key order for readable, diff-friendly output.
 */
public final class WorldViewJson {

    private WorldViewJson() {
    }

    public static Map<String, Object> toMap(WorldView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (v == null || !v.present()) {
            m.put("present", false);
            return m;
        }
        m.put("present", true);
        m.put("tickId", v.tickId());
        m.put("profile", v.profile());
        if (v.self() != null) m.put("self", selfMap(v.self()));
        if (v.env() != null) m.put("env", envMap(v.env()));
        if (v.target() != null) m.put("target", targetMap(v.target()));
        if (v.entities() != null && !v.entities().isEmpty()) m.put("entities", entitiesList(v.entities()));
        if (v.inventory() != null) m.put("inventory", invMap(v.inventory()));
        if (v.grid() != null) m.put("grid", gridMap(v.grid()));
        return m;
    }

    static Map<String, Object> selfMap(SelfView s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pos", List.of(round(s.x()), round(s.y()), round(s.z())));
        m.put("vel", List.of(round(s.vx()), round(s.vy()), round(s.vz())));
        m.put("yaw", round(s.yaw()));
        m.put("pitch", round(s.pitch()));
        m.put("health", s.health());
        m.put("food", s.food());
        m.put("saturation", s.saturation());
        m.put("xpLevel", s.xpLevel());
        m.put("armor", s.armor());
        m.put("air", s.air());
        m.put("gamemode", s.gamemode());
        m.put("onGround", s.onGround());
        m.put("sneaking", s.sneaking());
        m.put("sprinting", s.sprinting());
        if (s.effects() != null && !s.effects().isEmpty()) {
            List<Object> fx = new ArrayList<>();
            for (SelfView.Effect e : s.effects()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", e.id());
                em.put("name", e.name());
                em.put("amplifier", e.amplifier());
                em.put("durationTicks", e.durationTicks());
                fx.add(em);
            }
            m.put("effects", fx);
        }
        return m;
    }

    static Map<String, Object> envMap(EnvView e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", e.dimension());
        m.put("biome", e.biome());
        m.put("timeOfDay", e.timeOfDay());
        m.put("worldTime", e.worldTime());
        return m;
    }

    static Map<String, Object> targetMap(TargetView t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hitType", t.hitType());
        if ("block".equals(t.hitType())) {
            m.put("block", t.block());
            m.put("pos", List.of(t.bx(), t.by(), t.bz()));
            m.put("side", t.side());
        } else if ("entity".equals(t.hitType())) {
            m.put("entityId", t.entityId());
            m.put("entityType", t.entityType());
            if (t.entityHp() != null) m.put("entityHp", t.entityHp());
        }
        m.put("distance", round(t.distance()));
        return m;
    }

    static List<Object> entitiesList(List<EntityView> es) {
        List<Object> out = new ArrayList<>();
        for (EntityView e : es) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("pos", List.of(round(e.x()), round(e.y()), round(e.z())));
            m.put("dist", round(e.dist()));
            if (e.hp() != null) m.put("hp", e.hp());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> invMap(InventoryView inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selectedSlot", inv.selectedSlot());
        List<Object> rows = new ArrayList<>();
        for (InventoryView.Slot s : inv.slots()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", s.index());
            r.put("item", s.item());
            r.put("count", s.count());
            r.put("damage", s.damage());
            if (s.maxDamage() != null) r.put("maxDamage", s.maxDamage());
            rows.add(r);
        }
        m.put("slots", rows);
        return m;
    }

    static Map<String, Object> gridMap(LocalGrid g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("radius", g.radius());
        m.put("mode", g.mode());
        m.put("origin", List.of(g.originX(), g.originY(), g.originZ()));
        m.put("blockCounts", g.blockCounts());
        List<Object> cols = new ArrayList<>();
        for (LocalGrid.Column c : g.columns()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("dx", c.dx());
            cm.put("dz", c.dz());
            cm.put("surface", c.surface());
            if (c.surfaceDy() != null) cm.put("surfaceDy", c.surfaceDy());
            cm.put("feet", c.feet());
            cm.put("head", c.head());
            if (c.profile() != null && !c.profile().isEmpty()) {
                List<Object> runs = new ArrayList<>();
                for (LocalGrid.Run r : c.profile()) {
                    runs.add(List.of(r.block(), r.fromDy(), r.len()));
                }
                cm.put("profile", runs);
            }
            cols.add(cm);
        }
        m.put("columns", cols);
        return m;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
