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
        // Three states, same split as self.effects: ABSENT means nothing nearby (the common case, so
        // it costs nothing), an explicit NULL means the section was not sampled. Collapsing unsampled
        // into empty is what let a diff report every known id as `left`.
        if (v.entities() == null) {
            m.put("entities", null);
        } else if (!v.entities().isEmpty()) {
            m.put("entities", entitiesList(v.entities()));
        }
        // Only when it actually bit, so the common payload is unchanged. It belongs in full mode as
        // well as diff mode: a caller reading a capped list is looking at a TRUNCATED view of what is
        // around it, and nothing else in the payload says so.
        if (v.entitiesCapped()) {
            m.put("entitiesCapped", true);
        }
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
        // Shipped alongside xpLevel because the level alone cannot answer "can I afford this
        // enchant": vanilla's own XP bar is this fraction, and a caller watching for a pickup
        // between two levels has nothing else that moves. It reached SelfView from capture and was
        // then dropped here, so mode=full never emitted it -- a field carried the whole way across
        // the seam and discarded at the last step, which is invisible from either end.
        m.put("xpProgress", s.xpProgress());
        m.put("armor", s.armor());
        // Omitted, not sentinelled, when the read failed. Every integer that would read as
        // "unknown" is one vanilla itself ticks through on the way to drowning (300 -> 0 -> -19,
        // reset at -20), so a sentinel would have told the model "air unknown" and "19 ticks from
        // 2 HP of drown damage" with the same number. Absence is the payload's existing word for
        // "no value" -- surfaceDy and drop already use it.
        if (s.air() != null) m.put("air", s.air());
        m.put("gamemode", s.gamemode());
        m.put("onGround", s.onGround());
        m.put("sneaking", s.sneaking());
        m.put("sprinting", s.sprinting());
        // The three body facts, sent together because they are read together: a caller asking
        // "am I falling" wants the distance AND what landing costs, and splitting them across a
        // turn boundary is how a caller plans a drop it has not priced. `blocking` is separate
        // because it answers a different question and is usually all a caller wants.
        m.put("fallDistance", round(s.fallDistance()));
        m.put("fallDamageIfLanded", s.fallDamageIfLanded());
        m.put("blocking", s.blocking());
        // Three states, two encodings, and the split is the opposite way round from air's on
        // purpose. ABSENT means the player has no effects -- the common case, so it costs nothing --
        // and an explicit NULL means the capture could not read them (SelfView#effects). Air is
        // inverted because its common case is a readable number, so for air absence is the rare
        // signal; both follow the one rule that the cheap encoding goes to the common case and the
        // failure is never silent. Collapsing unread into empty is what made every live effect
        // report as lost in diff mode.
        if (s.effects() == null) {
            m.put("effects", null);
        } else if (!s.effects().isEmpty()) {
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
        // Weather, because it moves the spawn gate: EntityMob re-reads the cell with
        // skylightSubtracted forced to 10 under thunder, turning a midday 15 into 5. An agent told
        // "noon" and not this plans against the wrong afternoon. lightAtPlayer is the same
        // quantity inspect_block reports for any cell, so the two are comparable.
        //
        // ALL FOUR, ALWAYS, null for a failed read -- where this used to omit the whole group
        // unless one of them was readable. The gate below could not say what it meant, because
        // "all four absent" and "all four false with no light" are the same map, and the first is
        // a real state a night produces: a world whose light and weather reads throw loses
        // `daytime:false` too, so the one key that would have said "it is night" went with them.
        // A caller reading the omission as "no rain" was told a fact nobody had read.
        //
        // null rather than a sentinel, following self.effects and the air rule: absence is the
        // payload's word for "nothing changed"/"nothing there", and there is no absent case left
        // here to spend it on. It costs four keys on a night poll -- less than the wrong night poll
        // it replaces.
        m.put("raining", e.raining());
        m.put("thundering", e.thundering());
        m.put("daytime", e.daytime());
        m.put("lightAtPlayer", e.lightAtPlayer());
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
            // The questions a caller asks on arrival, none of which dist alone can answer.
            // reachableSeen is the 6.0-block gate and reachableBlind the 3.0 one; which applies
            // depends on whether the SERVER can see the player, which the client cannot observe,
            // so both are reported rather than one guessed.
            m.put("reachableSeen", e.reachable(true));
            m.put("reachableBlind", e.reachable(false));
            // Hovering and hitting are different reaches: the crosshair clamps at 3.0.
            m.put("hoverable", e.hoverable());
            // Attacking an item/orb/arrow/self disconnects the player; "attacks players" is what
            // decides whether a fight is needed at all.
            m.put("attackable", e.attackable());
            m.put("hostile", e.hostile());
            if (e.hp() != null) m.put("hp", e.hp());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> invMap(InventoryView inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selectedSlot", inv.selectedSlot());
        m.put("slots", slotRows(inv.slots()));
        // The armour block is its own key, not four more entries in "slots": every consumer of
        // "slots" assumes the 36-wide main inventory, and appending four would make "the player
        // is carrying 40 things" true in a way nobody means. ABSENT means not captured; a present
        // list of four is a real reading, three of which are usually null items.
        if (inv.armour() != null) {
            // Each row carries its piece name and the container slot that equips it. A bare index
            // is not actionable: the caller has to know that index 36 is the helmet AND that the
            // click that equips it is container slot 8, and the second fact is the inverted one
            // that {@link ArmourSlots} exists to publish. Handing over an index alone is how a
            // helmet ends up on the feet.
            m.put("armor", armourRows(inv.armour()));
        }
        return m;
    }

    private static List<Object> armourRows(List<InventoryView.Slot> slots) {
        List<Object> rows = new ArrayList<>();
        for (int armorType = 0; armorType < slots.size(); armorType++) {
            InventoryView.Slot s = slots.get(armorType);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", s.index());
            r.put("piece", ArmourSlots.pieceName(armorType));
            r.put("armorType", armorType);
            r.put("containerSlot", ArmourSlots.containerSlotFor(armorType));
            r.put("item", s.item());
            r.put("count", s.count());
            r.put("damage", s.damage());
            if (s.maxDamage() != null) r.put("maxDamage", s.maxDamage());
            rows.add(r);
        }
        return rows;

    }

    private static List<Object> slotRows(List<InventoryView.Slot> slots) {
        List<Object> rows = new ArrayList<>();
        for (InventoryView.Slot s : slots) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", s.index());
            r.put("item", s.item());
            r.put("count", s.count());
            r.put("damage", s.damage());
            if (s.maxDamage() != null) r.put("maxDamage", s.maxDamage());
            rows.add(r);
        }
        return rows;
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
            // Only when there is something to say: 0 is the overwhelmingly common case (flat
            // ground) and emitting it on every column would add bytes to say nothing. A drop of
            // null means the probe found no floor within its bound, i.e. certainly lethal.
            if (c.dropDepth() == null) {
                cm.put("drop", "deep");
            } else if (c.dropDepth() > 0) {
                cm.put("drop", c.dropDepth());
            }
            // Absent means CLEAR (vanilla's 1), which is the overwhelming majority: measured, the
            // field on every column cost 7.1% of the whole payload to say "walkable" 1089 times.
            // Unknown is emitted as "?" rather than also being omitted, because "we could not ask"
            // and "you can walk here" must not collapse into the same silence.
            if (c.walk() == LocalGrid.WALK_UNKNOWN) {
                cm.put("walk", "?");
            } else if (c.walk() != LocalGrid.WALK_CLEAR) {
                cm.put("walk", c.walk());
            }
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
