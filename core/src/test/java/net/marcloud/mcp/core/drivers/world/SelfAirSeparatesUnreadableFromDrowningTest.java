package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.DataWatcher;
import org.junit.Test;

/**
 * Teeth for one defect shape: a read-failure sentinel that COLLIDES with a value the game
 * legitimately produces.
 *
 * <p>{@code self.air} shipped through a helper returning {@code -1} on failure, and {@code -1}
 * is vanilla's own first drowning tick: {@code EntityLivingBase:297-326} decrements air once
 * per tick underwater and resets only at exactly {@code -20}, with no {@code isRemote} guard,
 * so the client produces {@code -1..-19} itself. {@code "air":-1} therefore meant either "we
 * could not read it" or "you are 19 ticks from 2 HP of drown damage", and the model could not
 * tell which. The fix omits the key on failure -- the convention {@code surfaceDy} and
 * {@code drop} already use.
 *
 * <p>The load-bearing half is {@link #everyAirValueVanillaTicksThroughSurvivesAsItself}, not the
 * absence case. A test that pinned only absence would stay green if the fix had swallowed the
 * whole negative band as "unreadable", which silently hides drowning -- strictly worse than the
 * ambiguity being fixed. So the band is walked whole and derived from vanilla's reset point
 * rather than spot-checked at a hand-picked value.
 */
public final class SelfAirSeparatesUnreadableFromDrowningTest {

    /** Vanilla's air slot: {@code Entity.getAir} reads watchable object 1 as a short. */
    private static final int AIR_DATAWATCHER_ID = 1;

    /** Identical in every field but air, so a key diff can only be about air. */
    private static SelfView selfWithAir(Integer air) {
        return new SelfView(0, 64, 0, 0, 0, 0, 0f, 0f, 20f, 20, 5f,
                0, 0f, 0, air, "SURVIVAL", false, false, true, List.of());
    }

    /**
     * Vanilla's own storage, driven exactly as {@code Entity.getAir} drives it (
     * {@code Entity:2229-2232}). Used rather than a lambda that throws something invented: with
     * no entry registered, {@code getWatchedObject} returns null and the short cast NPEs, which
     * IS the production failure -- a player whose datawatcher has not been populated yet.
     */
    private static WorldViewCapture.IntSup vanillaAirRead(Short stored) {
        DataWatcher dw = new DataWatcher(null);
        if (stored != null) {
            dw.addObject(AIR_DATAWATCHER_ID, stored);
        }
        return () -> dw.getWatchableObjectShort(AIR_DATAWATCHER_ID);
    }

    @Test
    public void anUnreadableAirYieldsNoValueRatherThanANumber() {
        assertNull("a failed air read must yield NO value: every integer it could return is one "
                        + "vanilla ticks through on the way to drowning, so any sentinel is a lie "
                        + "the model cannot detect",
                WorldViewCapture.boxedInt(vanillaAirRead(null)));
    }

    @Test
    public void anUnreadableAirIsOmittedFromTheProjection() {
        Map<String, Object> m = WorldViewJson.selfMap(selfWithAir(null));
        assertFalse("no value must reach the wire as ABSENCE -- the payload's existing word for "
                + "it, per surfaceDy and drop", m.containsKey("air"));

        // Absence of air only, not a self section that quietly lost other fields with it.
        Set<String> withAir = new LinkedHashSet<>(
                WorldViewJson.selfMap(selfWithAir(SelfView.AIR_FULL)).keySet());
        Set<String> withoutAir = new LinkedHashSet<>(withAir);
        assertTrue("baseline must actually contain air, or this test proves nothing",
                withoutAir.remove("air"));
        assertEquals("omitting air must remove exactly the air key", withoutAir, m.keySet());
    }

    @Test
    public void everyAirValueVanillaTicksThroughSurvivesAsItself() {
        // The whole band, from the out-of-water reset down to the last value observable before
        // vanilla's drowning tick snaps back to 0. Walked rather than sampled because the failure
        // this guards against is a fix that treats "negative" as "unreadable": that passes any
        // spot-check at full air and hides drowning entirely.
        for (int air = SelfView.AIR_FULL; air > SelfView.AIR_DROWN_DAMAGE; air--) {
            Map<String, Object> m = WorldViewJson.selfMap(selfWithAir(air));
            assertTrue("air=" + air + " is a value vanilla itself produces, so it must reach the "
                    + "model instead of being mistaken for a failed read", m.containsKey("air"));
            assertEquals("air=" + air + " must survive as itself, unclamped and unnormalised",
                    Integer.valueOf(air), m.get("air"));
        }
    }

    @Test
    public void aNegativeAirSurvivesVanillasOwnShortStorage() {
        // Grounds the band above in vanilla's transport rather than in this file's constants:
        // air is a short, and the read our helper wraps is the same short cast getAir performs.
        int drowningTick = SelfView.AIR_DROWNING_STARTS - 12;
        assertEquals("a negative air must come back out of vanilla's own datawatcher intact, or "
                        + "the in-band range this test walks would be fiction",
                Integer.valueOf(drowningTick),
                WorldViewCapture.boxedInt(vanillaAirRead((short) drowningTick)));
    }

    @Test
    public void theDiffReportsAirBecomingUnreadableInsteadOfGoingQuiet() {
        // In diff mode a missing key already means "unchanged", so the absence convention of the
        // full projection is not available here: staying silent would assert the last known
        // number still holds while the player might be drowning unobserved.
        Map<String, Object> lost = selfDiff(SelfView.AIR_FULL, null);
        assertTrue("air becoming unreadable is a change and must be reported",
                lost.containsKey("air"));
        assertNull("and reported as no-value, matching the full projection's meaning",
                lost.get("air"));

        Map<String, Object> regained = selfDiff(null, SelfView.AIR_DROWNING_STARTS);
        assertEquals("air becoming readable again must report the real number",
                Integer.valueOf(SelfView.AIR_DROWNING_STARTS), regained.get("air"));
    }

    @Test
    public void theDiffComparesAirByValueSoAFullBreathIsNotChurn() {
        // Boxing hazard introduced by the fix, pinned here: Integer identity only coincides with
        // equality inside the -128..127 cache, and vanilla's full air is 300. Comparing with !=
        // would have emitted an air change on every single poll while the player stood on dry
        // land -- an unchanged field spamming the diff mode whose entire purpose is to omit them.
        assertFalse("unchanged air must not appear in the diff",
                selfDiff(SelfView.AIR_FULL, SelfView.AIR_FULL).containsKey("air"));

        int oneTickLater = SelfView.AIR_DROWNING_STARTS - 1;
        assertEquals("a real one-tick drop must still be reported", Integer.valueOf(oneTickLater),
                selfDiff(SelfView.AIR_DROWNING_STARTS, oneTickLater).get("air"));
    }

    private static Map<String, Object> selfDiff(Integer before, Integer after) {
        WorldView a = wrap(selfWithAir(before));
        WorldView b = wrap(selfWithAir(after));
        Map<String, Object> d = WorldViewDiff.diff(a, b);
        Object self = d.get("self");
        return self == null ? Map.of() : asMap(self);
    }

    private static WorldView wrap(SelfView self) {
        return new WorldView(true, 1L, "explore", self, null, List.of(), null,
                TargetView.miss(), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
