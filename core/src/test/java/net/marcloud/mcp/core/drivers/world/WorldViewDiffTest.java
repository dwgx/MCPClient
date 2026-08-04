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
        return new WorldView(true, 1L, "explore", self, null, ents, false, null,
                TargetView.miss(), null);
    }

    /** A player standing still: no motion, full food, no effects. Mutate one field per test. */
    private static SelfView idle() {
        return new SelfView(10, 64, 0, 0, 0, 0, 0f, 0f, 20f, 20, 5f,
                3, 0.5f, 0, 300, "SURVIVAL", false, false, true, List.of());
    }

    /** Only the self section is populated, so nothing else can put a key in the diff. */
    private static WorldView selfOnly(long tick, SelfView self) {
        return new WorldView(true, tick, "explore", self, null, List.of(), false, null, null, null);
    }

    private static SelfView withVel(SelfView s, double vx, double vy, double vz) {
        return new SelfView(s.x(), s.y(), s.z(), vx, vy, vz, s.yaw(), s.pitch(),
                s.health(), s.food(), s.saturation(), s.xpLevel(), s.xpProgress(),
                s.armor(), s.air(), s.gamemode(), s.sneaking(), s.sprinting(), s.onGround(),
                s.effects());
    }

    private static SelfView withSaturation(SelfView s, float saturation) {
        return new SelfView(s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), s.yaw(), s.pitch(),
                s.health(), s.food(), saturation, s.xpLevel(), s.xpProgress(),
                s.armor(), s.air(), s.gamemode(), s.sneaking(), s.sprinting(), s.onGround(),
                s.effects());
    }

    private static SelfView withEffects(SelfView s, List<SelfView.Effect> effects) {
        return new SelfView(s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), s.yaw(), s.pitch(),
                s.health(), s.food(), s.saturation(), s.xpLevel(), s.xpProgress(),
                s.armor(), s.air(), s.gamemode(), s.sneaking(), s.sprinting(), s.onGround(),
                effects);
    }

    /** Potion 12 is vanilla's fire resistance -- the effect whose expiry over lava is lethal. */
    private static SelfView withFireResistance(int durationTicks) {
        return withEffects(idle(),
                List.of(new SelfView.Effect(12, "potion.fireResistance", 0, durationTicks)));
    }

    private static Map<?, ?> self(Map<String, Object> diff) {
        return (Map<?, ?>) diff.get("self");
    }

    private static Map<?, ?> effects(Map<String, Object> diff) {
        Map<?, ?> s = self(diff);
        return s == null ? null : (Map<?, ?>) s.get("effects");
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

    // ---- velocity / saturation / effects: fields the full payload ships and diff mode once
    // ---- never examined, so their absence read as "unchanged" instead of "never looked".

    @Test
    public void startingToFallShowsUpAsAVelocityChange() {
        // Standing, then in free fall. Nothing else about the player differs -- in particular pos
        // does not, which is exactly the case an unexamined velocity made invisible.
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, idle()),
                selfOnly(2L, withVel(idle(), 0, -0.6, 0)));
        assertTrue("a velocity change must reach the caller", d.containsKey("self"));
        assertEquals("emitted as a triple under the same key the full payload uses",
                List.of(0.0, -0.6, 0.0), self(d).get("vel"));
    }

    @Test
    public void aStandingPlayersGravityJitterIsNotAVelocityChange() {
        // A player on the floor is not at rest: EntityLivingBase:1677-1680 subtracts 0.08 and
        // damps by 0.98 every tick, and Block.onLanded zeroes motionY again on the collision, so
        // vy alternates between 0 and -0.0784 forever. Comparing vy exactly would make the diff
        // of a player who is doing NOTHING non-empty on every single poll.
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, withVel(idle(), 0, 0, 0)),
                selfOnly(2L, withVel(idle(), 0, -0.08 * 0.9800000190734863, 0)));
        assertFalse("per-tick gravity/floor oscillation must stay off the wire",
                d.containsKey("self"));
    }

    @Test
    public void eatingShowsUpAsASaturationChange() {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, withSaturation(idle(), 5f)),
                selfOnly(2L, withSaturation(idle(), 9f)));
        assertEquals("saturation drives regen and how long until food drops, so a caller "
                + "deciding whether to eat needs it", 9f, self(d).get("saturation"));
    }

    @Test
    public void drinkingAPotionShowsUpAsAnEffectGained() {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, idle()),
                selfOnly(2L, withFireResistance(6000)));
        List<?> gained = (List<?>) effects(d).get("gained");
        assertEquals(1, gained.size());
        Map<?, ?> fx = (Map<?, ?>) gained.get(0);
        assertEquals(12, fx.get("id"));
        assertEquals("potion.fireResistance", fx.get("name"));
        assertEquals("the duration comes with the gain, so one poll is enough to plan around it",
                6000, fx.get("durationTicks"));
    }

    @Test
    public void anEffectRunningOutShowsUpAsLost() {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, withFireResistance(20)),
                selfOnly(2L, idle()));
        List<?> lost = (List<?>) effects(d).get("lost");
        assertEquals(1, lost.size());
        assertEquals("named, not just numbered -- the caller acts on the name",
                "potion.fireResistance", ((Map<?, ?>) lost.get(0)).get("name"));
        assertFalse("no durationTicks on a lost effect: the last duration seen is not time "
                + "remaining, and a number there would be read as exactly that",
                ((Map<?, ?>) lost.get(0)).containsKey("durationTicks"));
    }

    @Test
    public void anAmplifierChangeIsReportedAsAGainBecauseItIsADifferentEffectInPractice() {
        SelfView strengthI = withEffects(idle(),
                List.of(new SelfView.Effect(5, "potion.damageBoost", 0, 3600)));
        SelfView strengthII = withEffects(idle(),
                List.of(new SelfView.Effect(5, "potion.damageBoost", 1, 3600)));
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, strengthI), selfOnly(2L, strengthII));
        List<?> gained = (List<?>) effects(d).get("gained");
        assertEquals("same id, stronger: reported once as a gain rather than a lost+gained pair",
                1, gained.size());
        assertEquals(1, ((Map<?, ?>) gained.get(0)).get("amplifier"));
        assertFalse("and not also as lost, which would read as the effect ending",
                effects(d).containsKey("lost"));
    }

    /**
     * The design decision this task turns on: a duration that ticks down is NOT a change.
     *
     * <p>Effects are the only LIST on SelfView whose contents move every tick --
     * {@code PotionEffect.deincrementDuration} runs once per tick per effect. Comparing the lists
     * by equality (or comparing durationTicks) would make the self diff non-empty on EVERY poll
     * for as long as any effect is active, which is the same as not diffing at all: a caller
     * cannot spot the fire resistance ending in a stream that always says something changed.
     */
    @Test
    public void anIdlePlayerWhoseEffectIsMerelyTickingDownHasAnEmptyDiff() {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, withFireResistance(6000)),
                selfOnly(2L, withFireResistance(5940)));
        assertFalse("three seconds of decay on a five-minute effect is not news",
                d.containsKey("self"));
    }

    /**
     * Expiry is reported on the CROSSING, once, so it is both timely and quiet.
     *
     * <p>The threshold is vanilla's own "about to run out" signal: EntityRenderer:1063-1066 fades
     * night vision below 200 ticks. Reusing it means the diff warns at the same moment a human
     * player would see the screen start to flicker, rather than at a number invented here.
     */
    @Test
    public void anEffectCrossingTheExpiryThresholdIsAnnouncedOnce() {
        Map<String, Object> crossing = WorldViewDiff.diff(selfOnly(1L, withFireResistance(260)),
                selfOnly(2L, withFireResistance(180)));
        List<?> expiring = (List<?>) effects(crossing).get("expiring");
        assertEquals(1, expiring.size());
        assertEquals("carries what is left, so the caller can decide to leave the lava",
                180, ((Map<?, ?>) expiring.get(0)).get("durationTicks"));

        // Already below on both samples: said once, not on every poll for the last ten seconds.
        Map<String, Object> after = WorldViewDiff.diff(selfOnly(1L, withFireResistance(180)),
                selfOnly(2L, withFireResistance(120)));
        assertFalse("repeating it every poll would drown the section it belongs to",
                after.containsKey("self"));
    }

    /**
     * A re-drunk potion is deliberately NOT a change while the amplifier holds.
     *
     * <p>Rejected the obvious alternative -- treat any duration INCREASE as a re-application,
     * since decay can only lower it. TileEntityBeacon:57,89 hands every player in range a fresh
     * 180-tick effect every 80 ticks, so that rule would emit a gain three times a second for
     * anyone standing near a beacon and put the section back to always-non-empty. The caller
     * already knows it drank something; what it cannot see without help is the effect ending.
     */
    @Test
    public void aRefreshedDurationIsNotReportedSoBeaconsDoNotSpamTheDiff() {
        SelfView beaconTick = withEffects(idle(),
                List.of(new SelfView.Effect(1, "potion.moveSpeed", 0, 100)));
        SelfView beaconReapplied = withEffects(idle(),
                List.of(new SelfView.Effect(1, "potion.moveSpeed", 0, 180)));
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, beaconTick),
                selfOnly(2L, beaconReapplied));
        assertFalse("a beacon refreshing an effect every 80 ticks must not read as a new effect",
                d.containsKey("self"));
    }

    @Test
    public void anIdlePlayerWithNoEffectsAtAllStillDiffsToNothing() {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1L, idle()), selfOnly(2L, idle()));
        assertFalse("the token saver still holds after three more compared fields",
                d.containsKey("self"));
        assertEquals("diff", d.get("mode"));
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
