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
 *
 * <p>Omission is load-bearing here, which makes a field this class forgets to compare worse than
 * one it compares badly: absence says "unchanged" in the encoding above, so an unexamined field
 * reports healthy while meaning "never looked". Every self field {@link WorldViewJson#selfMap}
 * ships must therefore be compared by {@code selfDiff}, and
 * {@code DiffLeftMeansUnsampledNotGoneTest} derives that check from the record rather than
 * trusting this sentence.
 */
public final class WorldViewDiff {

    private WorldViewDiff() {
    }

    private static final double POS_BAND = 0.1;
    private static final double YAW_BAND = 1.0;

    /**
     * Velocity dead-band, wider than a standing player's own gravity oscillation.
     *
     * <p>A player on the floor is not at rest: {@code EntityLivingBase:1677-1680} subtracts 0.08
     * from motionY and damps it by 0.98 every tick, and {@code Block.onLanded} zeroes it again on
     * the collision, so vy alternates between 0 and -0.0784 for as long as the player does
     * nothing. Comparing exactly would put a "vel" key in every single poll of an idle player.
     * 0.1 sits above that 0.0784 and well below everything worth reporting -- a jump is 0.42
     * ({@code getJumpUpwardsMotion}), a walk settles near 0.216 (base speed 0.1 against 0.546
     * ground friction), a fall passes 0.1 within two ticks.
     */
    private static final double VEL_BAND = 0.1;

    /**
     * Below this many ticks left, an effect is reported as expiring -- once, on the crossing.
     *
     * <p>Vanilla's own threshold for the same signal: {@code EntityRenderer:1063-1066} starts
     * fading night vision below 200 ticks, so the diff warns at the moment a human player would
     * see the screen begin to flicker rather than at a number invented here.
     */
    private static final int EXPIRY_WARN_TICKS = 200;

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

        Map<String, Object> ent = entitiesDiff(prev.entities(), cur.entities(),
                prev.entitiesCapped() || cur.entitiesCapped());
        if (!ent.isEmpty()) out.put("entities", ent);

        Map<String, Object> inv = inventoryDiff(prev.inventory(), cur.inventory());
        if (!inv.isEmpty()) out.put("inventory", inv);

        if (targetChanged(prev.target(), cur.target())) {
            out.put("target", cur.target() == null ? null : WorldViewJson.targetMap(cur.target()));
        }
        Map<String, Object> env = envDiff(prev.env(), cur.env());
        if (!env.isEmpty()) out.put("env", env);

        // The grid, whole, when it changed at all.
        //
        // It was absent from the diff entirely, which made the hazard terms structurally
        // unreachable in this mode: a caller polling mode=diff could never learn that lava had
        // flowed into the next column or that a floor had been mined out from under a path, because
        // no grid shipped. And absence is load-bearing in the full encoding -- a missing "walk"
        // means CLEAR -- so a diff that omitted the grid read as "nothing changed" rather than "not
        // sampled", which is the same class of quiet wrongness the drop terms were added to fix.
        //
        // Emitted whole rather than per-column: a column is small, the geometry the caller
        // navigates by has to be internally consistent, and a per-column patch set would need its
        // own absence convention on top of the one the columns already use. When nothing changed it
        // costs nothing, because the key is simply not present.
        if (cur.grid() != null && !java.util.Objects.equals(prev.grid(), cur.grid())) {
            out.put("grid", WorldViewJson.gridMap(cur.grid()));
        }

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
        // Velocity, whole, when any component leaves the band.
        //
        // It was never examined at all, which is the defect this section was rewritten for: the
        // full payload ships "vel", so a caller polling mode=diff reads its absence as "unchanged"
        // under this payload's own convention, when it actually meant "never looked". Falling,
        // being knocked back and boat/minecart motion all leave pos within a tick's dead-band
        // while vel is the only field that says what is happening.
        //
        // Emitted as a triple like pos rather than per-axis, because a single component is not
        // actionable on its own -- a caller asking "am I falling or being pushed" needs all three
        // from the same tick, and splitting them would invite comparing a fresh vy against a
        // remembered vx.
        if (Math.abs(a.vx() - b.vx()) > VEL_BAND || Math.abs(a.vy() - b.vy()) > VEL_BAND
                || Math.abs(a.vz() - b.vz()) > VEL_BAND) {
            m.put("vel", List.of(r(b.vx()), r(b.vy()), r(b.vz())));
        }
        if (Math.abs(a.yaw() - b.yaw()) > YAW_BAND || Math.abs(a.pitch() - b.pitch()) > YAW_BAND) {
            m.put("yaw", r(b.yaw()));
            m.put("pitch", r(b.pitch()));
        }
        if (a.health() != b.health()) m.put("health", b.health());
        if (a.food() != b.food()) m.put("food", b.food());
        // Saturation, exactly, no dead-band. Vanilla moves it in whole units: FoodStats:47-51
        // drops it by 1.0 only once exhaustion passes 4.0, and addStats raises it on eating. So an
        // exact compare fires on the events a caller acts on and is silent between them -- a band
        // here would only hide the 1.0 steps it exists to report.
        if (a.saturation() != b.saturation()) m.put("saturation", b.saturation());
        if (a.xpLevel() != b.xpLevel()) m.put("xpLevel", b.xpLevel());
        // Exactly, no dead-band, the same call saturation makes: xpProgress does not drift on its
        // own. EntityPlayer.addExperience moves it only when XP is picked up or spent, so an exact
        // compare fires on the events a caller acts on and is silent between them. A band here
        // would hide small pickups, which is the opposite of what the field is for.
        if (a.xpProgress() != b.xpProgress()) m.put("xpProgress", b.xpProgress());
        if (a.armor() != b.armor()) m.put("armor", b.armor());
        // eq(), not !=: air is boxed (null = unreadable, see SelfView#air) and Integer identity
        // only coincides with equality inside the -128..127 cache, so != would have reported a
        // change on every poll at full air (300) while suppressing none of the real ones.
        //
        // An explicit null goes on the wire when air BECOMES unreadable. Omitting it is not
        // available here the way it is in the full projection: in diff mode a missing key already
        // means "unchanged", so silence would claim the last known number still holds.
        if (!eq(a.air(), b.air())) m.put("air", b.air());
        if (!eq(a.gamemode(), b.gamemode())) m.put("gamemode", b.gamemode());
        if (a.onGround() != b.onGround()) m.put("onGround", b.onGround());
        if (a.sneaking() != b.sneaking()) m.put("sneaking", b.sneaking());
        if (a.sprinting() != b.sprinting()) m.put("sprinting", b.sprinting());
        Map<String, Object> fx = effectsDiff(a.effects(), b.effects());
        if (!fx.isEmpty()) m.put("effects", fx);
        return m;
    }

    /**
     * Effects: gained / lost / expiring. The one self field whose contents move on their own.
     *
     * <p>THE RULE, and why it is this one. Three things are reportable -- an effect appears, an
     * effect is gone, an effect is about to run out -- and a duration merely ticking down is NOT a
     * change. {@code PotionEffect.deincrementDuration} runs once per tick per effect, so comparing
     * the lists by equality (or comparing durationTicks at all) would make the self section
     * non-empty on EVERY poll for as long as anything is active. That is indistinguishable from
     * not diffing: a caller cannot spot its fire resistance ending in a stream that always says
     * something changed, which is the failure this method exists to avoid, not a cosmetic one.
     *
     * <p>Also rejected: treating a duration INCREASE as a re-application. Decay can only lower it,
     * so the inference is sound, but {@code TileEntityBeacon:57,89} hands every player in range a
     * fresh 180-tick effect every 80 ticks -- the rule would emit a gain three times a second for
     * anyone near a beacon and put the section straight back to always-non-empty. A caller knows
     * what it drank; what it cannot see without help is the effect ending.
     *
     * <p>Keyed by potion id, with an amplifier change reported as a gain rather than a lost+gained
     * pair: {@code PotionEffect.combine} raises the amplifier in place on the SAME effect, so
     * "lost" there would read as the effect ending at the moment it got stronger. Compared with
     * {@code !=} rather than {@code >} so a lower amplifier (the old effect expired and a weaker
     * one was applied between two polls) is reported honestly instead of silently.
     *
     * <p><b>Now distinguished</b>, which it was not: an empty list because the player has no
     * effects, versus an empty list because the capture could not read them. Both used to arrive
     * as {@code List.of()} and every active effect reported {@code lost} -- a model reads that as
     * its fire resistance having just expired, and next to lava that is the fatal direction to be
     * wrong in. The earlier note here said the fix had to be the CAPTURE reporting whether it
     * managed to read, because no amount of comparing prev to cur can separate the two; that is
     * now {@code WorldViewCapture#effectsOrNull}, which returns null on a failed read, and the
     * two null cases are handled below.
     *
     * <p>Silence is not available for either of them, because in diff mode a missing key already
     * means "unchanged" -- the same reason air puts an explicit null on the wire when it becomes
     * unreadable rather than omitting itself.
     *
     * <ul>
     *   <li><b>Current unread</b> ({@code pb == null}): {@code unread:true}, and NOTHING is
     *       reported lost. The honest statement is "I could not look", and it is strictly more
     *       useful than a list of departures that did not happen.
     *   <li><b>Baseline unread</b> ({@code pa == null}, current readable): there is nothing to
     *       diff against, so the whole current set ships as {@code now}. Otherwise every effect
     *       the player has held since before the failed read would report as {@code gained} the
     *       moment reading resumed. {@code selfDiff} and {@code inventoryDiff} already use
     *       {@code now} for exactly this "no baseline" case.
     * </ul>
     */
    private static Map<String, Object> effectsDiff(List<SelfView.Effect> pa,
                                                   List<SelfView.Effect> pb) {
        if (pb == null) {
            // EVERY failed poll says so, including a run of them -- deliberately unlike air, which
            // compares by value and therefore reports only the TRANSITION to unreadable.
            //
            // The difference is what silence would mean afterwards. Air is a single value: once the
            // caller has been handed an explicit null, later silence reads as "still that", and
            // "that" is already "unknown". An effect SET has no such resting state -- silence means
            // "unchanged", and the last set the caller actually saw is the one from before the
            // failure, so going quiet on the second failed poll would re-assert a stale set as
            // current. Repeating it keeps "I still cannot see your buffs" unambiguous, and the cost
            // is one key on a poll that is already anomalous.
            return Map.of("unread", true);
        }
        if (pa == null) {
            List<Object> now = new ArrayList<>();
            for (SelfView.Effect e : pb) {
                now.add(effectMap(e, true));
            }
            // An empty current set with an unread baseline: say so rather than omitting, because
            // the caller's previous poll left it not knowing what it holds.
            return Map.of("now", now);
        }
        Map<Integer, SelfView.Effect> a = byPotionId(pa);
        Map<Integer, SelfView.Effect> b = byPotionId(pb);
        List<Object> gained = new ArrayList<>();
        List<Object> lost = new ArrayList<>();
        List<Object> expiring = new ArrayList<>();
        for (Map.Entry<Integer, SelfView.Effect> e : b.entrySet()) {
            SelfView.Effect prev = a.get(e.getKey());
            SelfView.Effect cur = e.getValue();
            if (prev == null || prev.amplifier() != cur.amplifier()) {
                gained.add(effectMap(cur, true));
            } else if (prev.durationTicks() > EXPIRY_WARN_TICKS
                    && cur.durationTicks() <= EXPIRY_WARN_TICKS) {
                // The crossing only, so it is said once instead of on every poll of the last ten
                // seconds. A caller that polls too coarsely to catch the edge still got the
                // duration when the effect was gained.
                expiring.add(effectMap(cur, true));
            }
        }
        for (Map.Entry<Integer, SelfView.Effect> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) lost.add(effectMap(e.getValue(), false));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!gained.isEmpty()) m.put("gained", gained);
        if (!lost.isEmpty()) m.put("lost", lost);
        if (!expiring.isEmpty()) m.put("expiring", expiring);
        return m;
    }

    private static Map<Integer, SelfView.Effect> byPotionId(List<SelfView.Effect> es) {
        Map<Integer, SelfView.Effect> m = new LinkedHashMap<>();
        if (es != null) {
            for (SelfView.Effect e : es) m.put(e.id(), e);
        }
        return m;
    }

    /** Named as well as numbered: the id alone is not something a caller can reason about. */
    private static Map<String, Object> effectMap(SelfView.Effect e, boolean withDuration) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("name", e.name());
        m.put("amplifier", e.amplifier());
        // Omitted on "lost": the last duration seen is not how long it has been gone, and a number
        // there would be read as time remaining on an effect that no longer exists.
        if (withDuration) m.put("durationTicks", e.durationTicks());
        return m;
    }

    /**
     * Entities: entered / left / moved, with {@code left} now qualified by how it was sampled.
     *
     * <p>{@code left} means WAS SAMPLED, IS NOT SAMPLED NOW -- strictly weaker than "gone", and the
     * gap matters because a caller reads it as "that creeper is dead". Two of the three ways an id
     * lands there while the entity is alive are now answered structurally rather than in prose:
     *
     * <ul>
     *   <li><b>The section was not sampled</b> ({@code pb == null}): {@code unsampled:true} and
     *       NOTHING is reported left. Previously an unrequested section arrived as {@code List.of()}
     *       and made every previously known id report left in one go -- the whole-set case, and the
     *       most destructive of the three.
     *   <li><b>The cap truncated</b> ({@code capped}): the {@code left} list still ships, because
     *       those ids really did stop being sampled, but it is flagged so the caller knows an
     *       eviction is among the possibilities. The capture now reports this from the same scan
     *       that built the list, so it is measured rather than inferred from {@code size() == cap} --
     *       which would be wrong whenever a scan legitimately found exactly {@code cap} entities.
     *   <li><b>The caller changed radius or profile</b> between calls: still not detectable here,
     *       and still in the description. The caller knows it changed them; nothing in the payload
     *       does.
     * </ul>
     *
     * <p>Silence is not available for the unsampled case: in diff mode a missing key already means
     * "unchanged", so saying nothing would assert the last known entity set still holds. Same
     * reasoning as air's explicit null and effects' {@code unread}.
     */
    private static Map<String, Object> entitiesDiff(List<EntityView> pa, List<EntityView> pb,
                                                    boolean capped) {
        if (pb == null) {
            // Both unsampled is still worth saying: two polls in a row learned nothing, and omitting
            // it would claim the set was unchanged across them.
            return Map.of("unsampled", true);
        }
        if (pa == null) {
            // No baseline: everything present would otherwise report as `entered`, which reads as
            // "these just arrived" when they may have been standing there the whole time. `now` is
            // the convention selfDiff and inventoryDiff already use for exactly this.
            List<Object> now = new ArrayList<>();
            for (EntityView e : pb) {
                now.add(idType(e));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("now", now);
            if (capped) {
                m.put("capped", true);
            }
            return m;
        }
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
        // Both remaining ids really did stop being sampled. The unsampled-section case never gets
        // here (handled above), so what is left is a genuine departure, a move out of range, or a
        // cap eviction -- and `capped` below says whether the third is even possible.
        //
        // The old note here said doing this honestly needed the CAPTURE to report which sections it
        // sampled and whether the cap bit, and called that a wire-format decision for the owner. It
        // turned out not to need a new contract: the section case reuses null-versus-empty, the
        // convention air and effects already use, and the cap flag is one boolean measured in the
        // same scan that builds the list.
        for (Integer id : a.keySet()) {
            if (!b.containsKey(id)) left.add(id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!entered.isEmpty()) m.put("entered", entered);
        if (!left.isEmpty()) m.put("left", left);
        if (!moved.isEmpty()) m.put("moved", moved);
        // Only alongside a `left`: the flag exists to qualify that list, and on a poll with no
        // departures there is nothing for it to qualify. A caller does not need to be told the cap
        // is active when the cap has not cost it anything it can see.
        if (capped && !left.isEmpty()) {
            m.put("capped", true);
        }
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
