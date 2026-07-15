package net.marcloud.mcp.core.drivers.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHASE W.7 — field-wise diff between two {@link WorldView} snapshots. Pure (no
 * game thread); emits only changed sections (a section unchanged is omitted, a
 * token saver). {@code prev == null} falls back to a full projection. Numeric
 * self/entity motion uses a dead-band so idle jitter does not spam the diff.
 */
public final class WorldViewDiff {

    private WorldViewDiff() {
    }

    private static final double POS_BAND = 0.1;
    private static final double YAW_BAND = 1.0;

    public static Map<String, Object> diff(WorldView prev, WorldView cur) {
        if (cur == null || !cur.present()) {
            return Map.of("present", false);
        }
        if (prev == null || !prev.present()) {
            Map<String, Object> full = WorldViewJson.toMap(cur);
            full.put("mode", "full");
            return full;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "diff");
        out.put("fromTick", prev.tickId());
        out.put("toTick", cur.tickId());

        Map<String, Object> self = selfDiff(prev.self(), cur.self());
        if (!self.isEmpty()) out.put("self", self);

        Map<String, Object> ent = entitiesDiff(prev.entities(), cur.entities());
        if (!ent.isEmpty()) out.put("entities", ent);

        Map<String, Object> inv = inventoryDiff(prev.inventory(), cur.inventory());
        if (!inv.isEmpty()) out.put("inventory", inv);

        if (targetChanged(prev.target(), cur.target())) {
            out.put("target", cur.target() == null ? null : WorldViewJson.targetMap(cur.target()));
        }
        Map<String, Object> env = envDiff(prev.env(), cur.env());
        if (!env.isEmpty()) out.put("env", env);

        return out;
    }

    private static Map<String, Object> selfDiff(SelfView a, SelfView b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null || b == null) {
            if (b != null) m.put("now", WorldViewJson.selfMap(b));
            return m;
        }
        if (Math.abs(a.x() - b.x()) > POS_BAND || Math.abs(a.y() - b.y()) > POS_BAND
                || Math.abs(a.z() - b.z()) > POS_BAND) {
            m.put("pos", List.of(r(b.x()), r(b.y()), r(b.z())));
        }
        if (Math.abs(a.yaw() - b.yaw()) > YAW_BAND || Math.abs(a.pitch() - b.pitch()) > YAW_BAND) {
            m.put("yaw", r(b.yaw()));
            m.put("pitch", r(b.pitch()));
        }
        if (a.health() != b.health()) m.put("health", b.health());
        if (a.food() != b.food()) m.put("food", b.food());
        if (a.xpLevel() != b.xpLevel()) m.put("xpLevel", b.xpLevel());
        if (a.armor() != b.armor()) m.put("armor", b.armor());
        if (a.air() != b.air()) m.put("air", b.air());
        if (!eq(a.gamemode(), b.gamemode())) m.put("gamemode", b.gamemode());
        if (a.onGround() != b.onGround()) m.put("onGround", b.onGround());
        if (a.sneaking() != b.sneaking()) m.put("sneaking", b.sneaking());
        if (a.sprinting() != b.sprinting()) m.put("sprinting", b.sprinting());
        return m;
    }

    private static Map<String, Object> entitiesDiff(List<EntityView> pa, List<EntityView> pb) {
        Map<Integer, EntityView> a = byId(pa);
        Map<Integer, EntityView> b = byId(pb);
        List<Object> entered = new ArrayList<>();
        List<Object> left = new ArrayList<>();
        List<Object> moved = new ArrayList<>();
        for (Map.Entry<Integer, EntityView> e : b.entrySet()) {
            EntityView prev = a.get(e.getKey());
            EntityView cur = e.getValue();
            if (prev == null) {
                entered.add(idType(cur));
            } else if (Math.abs(prev.x() - cur.x()) > POS_BAND
                    || Math.abs(prev.y() - cur.y()) > POS_BAND
                    || Math.abs(prev.z() - cur.z()) > POS_BAND
                    || !eq(prev.hp(), cur.hp())) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", cur.id());
                mm.put("pos", List.of(r(cur.x()), r(cur.y()), r(cur.z())));
                if (cur.hp() != null) mm.put("hp", cur.hp());
                moved.add(mm);
            }
        }
        for (Integer id : a.keySet()) {
            if (!b.containsKey(id)) left.add(id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!entered.isEmpty()) m.put("entered", entered);
        if (!left.isEmpty()) m.put("left", left);
        if (!moved.isEmpty()) m.put("moved", moved);
        return m;
    }

    private static Map<String, Object> inventoryDiff(InventoryView a, InventoryView b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null || b == null) {
            if (b != null) m.put("now", WorldViewJson.invMap(b));
            return m;
        }
        if (a.selectedSlot() != b.selectedSlot()) m.put("selectedSlot", b.selectedSlot());
        Map<Integer, InventoryView.Slot> pa = new LinkedHashMap<>();
        for (InventoryView.Slot s : a.slots()) pa.put(s.index(), s);
        Map<Integer, InventoryView.Slot> pb = new LinkedHashMap<>();
        for (InventoryView.Slot s : b.slots()) pb.put(s.index(), s);
        List<Object> changed = new ArrayList<>();
        List<Object> cleared = new ArrayList<>();
        for (Map.Entry<Integer, InventoryView.Slot> e : pb.entrySet()) {
            InventoryView.Slot prev = pa.get(e.getKey());
            InventoryView.Slot cur = e.getValue();
            if (prev == null || !eq(prev.item(), cur.item()) || prev.count() != cur.count()
                    || prev.damage() != cur.damage()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("index", cur.index());
                sm.put("item", cur.item());
                sm.put("count", cur.count());
                changed.add(sm);
            }
        }
        for (Integer idx : pa.keySet()) {
            if (!pb.containsKey(idx)) cleared.add(idx);
        }
        if (!changed.isEmpty()) m.put("changed", changed);
        if (!cleared.isEmpty()) m.put("cleared", cleared);
        return m;
    }

    private static Map<String, Object> envDiff(EnvView a, EnvView b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null || b == null) return m;
        if (!eq(a.timeOfDay(), b.timeOfDay())) m.put("timeOfDay", b.timeOfDay());
        if (!eq(a.biome(), b.biome())) m.put("biome", b.biome());
        if (!eq(a.dimension(), b.dimension())) m.put("dimension", b.dimension());
        return m;
    }

    private static boolean targetChanged(TargetView a, TargetView b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        if (!eq(a.hitType(), b.hitType())) return true;
        if ("block".equals(b.hitType())) {
            return !eq(a.block(), b.block()) || !eq(a.bx(), b.bx())
                    || !eq(a.by(), b.by()) || !eq(a.bz(), b.bz());
        }
        if ("entity".equals(b.hitType())) {
            return !eq(a.entityId(), b.entityId());
        }
        return false;
    }

    private static Map<Integer, EntityView> byId(List<EntityView> es) {
        Map<Integer, EntityView> m = new LinkedHashMap<>();
        if (es != null) {
            for (EntityView e : es) m.put(e.id(), e);
        }
        return m;
    }

    private static Map<String, Object> idType(EntityView e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type());
        m.put("pos", List.of(r(e.x()), r(e.y()), r(e.z())));
        return m;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static double r(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
