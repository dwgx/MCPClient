package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.minecraft.potion.PotionEffect;
import org.junit.Test;

/**
 * The same defect shape {@code SelfAirSeparatesUnreadableFromDrowningTest} covers, one field over:
 * a read failure that is indistinguishable from a legitimate value.
 *
 * <p>{@code self.effects} swallowed a Throwable and returned an empty list, so "you have no
 * effects" and "we could not read your effects" arrived at {@link WorldViewDiff} identically. The
 * differ compares effect SETS, so a failed read made every live effect report as {@code lost} --
 * and a model reads {@code lost: fire_resistance} as its protection having just run out. Standing
 * in lava, that is the fatal direction to be wrong in. {@code WorldViewDiff}'s own javadoc named
 * this gap and said the fix had to be the capture reporting whether it managed to read.
 *
 * <p><b>The failure is reproduced, not invented.</b> {@code activePotionsMap} is a final
 * {@code HashMap} that is never null ({@code EntityLivingBase:60}), so a lambda throwing an
 * arbitrary exception would be testing fiction. The real trigger is a
 * {@link java.util.ConcurrentModificationException}: {@code getActivePotionEffects} hands out
 * {@code activePotionsMap.values()} and the capture iterates it on the game thread, while
 * {@code NetHandlerPlayClient:1511,1613} puts and removes entries from the same map when an effect
 * packet arrives. That is also why the partial-list case matters -- the throw lands PARTWAY through
 * the loop, so what has been collected so far is a set that is quietly missing entries, and every
 * missing entry would report lost.
 */
public final class SelfEffectsSeparateUnreadFromNoneTest {

    private static PotionEffect effect(int id, int duration) {
        return new PotionEffect(id, duration);
    }

    /**
     * Vanilla's own container, iterated exactly as the capture iterates it, with the concurrent
     * write happening after {@code n} elements.
     *
     * <p>A real {@code HashMap.values()} view over a map that is mutated mid-iteration, so the
     * exception comes from the same operation production performs rather than from a throw written
     * for the test. {@code n > 0} is what produces the PARTIAL read: some effects have already been
     * collected when it fires.
     */
    private static Iterable<Object> mutatedDuringIteration(Map<Integer, PotionEffect> map,
                                                           int after) {
        return () -> {
            var it = map.values().iterator();
            return new java.util.Iterator<Object>() {
                private int seen;

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Object next() {
                    if (seen == after) {
                        // What the packet handler does to the same map on the same thread.
                        map.put(9999, effect(9999, 100));
                    }
                    seen++;
                    return it.next();   // throws CME once the map has been structurally modified
                }
            };
        };
    }

    private static Map<Integer, PotionEffect> liveMap(int... ids) {
        Map<Integer, PotionEffect> m = new HashMap<>();
        for (int id : ids) {
            m.put(id, effect(id, 6000));
        }
        return m;
    }

    /** Identical in every field but effects, so a key diff can only be about effects. */
    private static SelfView selfWithEffects(List<SelfView.Effect> effects) {
        return new SelfView(0, 64, 0, 0, 0, 0, 0f, 0f, 20f, 20, 5f,
                0, 0f, 0, 300, "SURVIVAL", false, false, true, effects);
    }

    private static SelfView.Effect fireResistance(int durationTicks) {
        return new SelfView.Effect(12, "potion.fireResistance", 0, durationTicks);
    }

    // ===== capture: the three states exist at all =====

    @Test
    public void aReadableEffectListSurvivesAsItself() {
        List<SelfView.Effect> read = WorldViewCapture.effectsOrNull(
                () -> liveMap(12).values());
        assertNotNull("a successful read must not be reported as a failure", read);
        assertEquals(1, read.size());
        assertEquals("the potion id must survive", 12, read.get(0).id());
        assertEquals("and so must the duration, which is what 'expiring' is computed from",
                6000, read.get(0).durationTicks());
    }

    @Test
    public void aGenuinelyEmptyEffectListIsAnEmptyListAndNotNull() {
        List<SelfView.Effect> read = WorldViewCapture.effectsOrNull(() -> liveMap().values());
        assertNotNull("no effects is a successful read of nothing, not a failure", read);
        assertTrue(read.isEmpty());
    }

    @Test
    public void anUnreadableEffectListYieldsNullRatherThanAnEmptyList() {
        // Concurrent modification on the first element: nothing was collected.
        assertNull("a failed read must be distinguishable from having no effects, because the "
                        + "differ compares sets and would report every live effect as lost",
                WorldViewCapture.effectsOrNull(() -> mutatedDuringIteration(liveMap(12, 16), 0)));
    }

    /**
     * A read that fails HALFWAY must discard what it collected.
     *
     * <p>The dangerous case, and the reason the helper is all-or-nothing. Three effects, the map
     * mutated after the second: returning the two would be a set quietly missing one, and that one
     * reports lost -- the exact false ending this whole change exists to prevent. Returning null
     * says "could not read", which the differ knows how to handle.
     */
    @Test
    public void aReadThatFailsPartwayDiscardsWhatItAlreadyCollected() {
        Map<Integer, PotionEffect> map = liveMap(12, 16, 11);
        List<SelfView.Effect> read = WorldViewCapture.effectsOrNull(
                () -> mutatedDuringIteration(map, 2));
        assertNull("a partial list is worse than no list: the effects it is missing would each "
                + "report as lost, which is the false ending being fixed", read);
    }

    /** Precondition: the fake really does reproduce a CME, or every assertion above is vacuous. */
    @Test
    public void theConcurrentModificationIsRealRatherThanSimulated() {
        Map<Integer, PotionEffect> map = liveMap(12, 16, 11);
        try {
            for (Object ignored : mutatedDuringIteration(map, 1)) {
                // drain
            }
            org.junit.Assert.fail("iterating a map mutated mid-loop must throw "
                    + "ConcurrentModificationException, or this file is testing a throw it invented "
                    + "rather than the failure NetHandlerPlayClient actually causes");
        } catch (java.util.ConcurrentModificationException expected) {
            // This is the production failure: EntityLivingBase:60 is a plain HashMap and
            // NetHandlerPlayClient:1511,1613 writes to it when an effect packet lands.
            assertTrue(true);
        }
    }

    // ===== full projection: absent means none, null means unread =====

    @Test
    public void noEffectsIsAbsentFromTheFullProjection() {
        Map<String, Object> m = WorldViewJson.selfMap(selfWithEffects(List.of()));
        assertFalse("the common case costs nothing on the wire", m.containsKey("effects"));
    }

    @Test
    public void unreadEffectsReachTheWireAsAnExplicitNullNotAsAbsence() {
        Map<String, Object> m = WorldViewJson.selfMap(selfWithEffects(null));
        assertTrue("a failed read must be VISIBLE, since absence already means 'you have none'",
                m.containsKey("effects"));
        assertNull(m.get("effects"));

        // Exactly that one key differs; the section must not have lost anything else with it.
        Map<String, Object> none = WorldViewJson.selfMap(selfWithEffects(List.of()));
        assertEquals("the unread encoding adds the effects key and changes nothing else",
                none.keySet().size() + 1, m.keySet().size());
    }

    // ===== diff: the false 'lost' is gone =====

    @Test
    public void anUnreadCurrentSampleReportsNothingAsLost() {
        Map<String, Object> fx = effectsDiff(List.of(fireResistance(6000)), null);
        assertFalse("THE REGRESSION: a failed read used to report every live effect as lost, and a "
                        + "model reads that as its fire resistance having just expired",
                fx.containsKey("lost"));
        assertEquals("it must say it could not look instead", Boolean.TRUE, fx.get("unread"));
    }

    @Test
    public void anUnreadCurrentSampleIsReportedRatherThanOmitted() {
        // In diff mode a missing key means "unchanged", so silence would claim the effect set still
        // holds -- the same reason air puts an explicit null on the wire when it becomes unreadable.
        assertFalse("an unread sample must not be silent", effectsDiff(List.of(), null).isEmpty());
    }

    @Test
    public void aReadableSampleAfterAnUnreadOneShipsTheWholeCurrentSet() {
        Map<String, Object> fx = effectsDiff(null, List.of(fireResistance(6000)));
        assertFalse("with no baseline there is nothing to have 'gained' against",
                fx.containsKey("gained"));
        List<?> now = (List<?>) fx.get("now");
        assertNotNull("the caller does not know what it holds after a failed poll, so send the set",
                now);
        assertEquals(1, now.size());
        assertEquals("potion.fireResistance", asMap(now.get(0)).get("name"));
    }

    /**
     * The normal path must be untouched: a real expiry still reports lost.
     *
     * <p>Without this the fix could have suppressed the lost branch outright -- which would hide
     * every genuine expiry and be strictly worse than the ambiguity being fixed. Same shape as the
     * air band being walked whole rather than spot-checked.
     */
    @Test
    public void aGenuineExpiryStillReportsLost() {
        Map<String, Object> fx = effectsDiff(List.of(fireResistance(20)), List.of());
        List<?> lost = (List<?>) fx.get("lost");
        assertNotNull("an effect really ending must still be reported", lost);
        assertEquals(1, lost.size());
        assertEquals(12, asMap(lost.get(0)).get("id"));
    }

    @Test
    public void aGainStillReportsGained() {
        Map<String, Object> fx = effectsDiff(List.of(), List.of(fireResistance(6000)));
        assertNotNull("drinking a potion must still be reported", fx.get("gained"));
    }

    @Test
    public void twoUnreadSamplesInARowStillSayUnreadRatherThanGoingQuiet() {
        assertEquals("both polls failing is not 'unchanged'", Boolean.TRUE,
                effectsDiff(null, null).get("unread"));
    }

    // ===== the description must teach the distinction it now makes =====

    @Test
    public void theDescriptionSaysUnreadIsNotTheSameAsHavingNoEffects() {
        String desc = worldViewDescription();
        assertTrue("the two states must be called distinct, since the old text said they were not: "
                        + desc,
                desc.contains("'no effects' and 'could not read them' are DISTINCT"));
        assertTrue("the diff encoding must be named, or a caller cannot branch on it",
                desc.contains("'unread':true"));
        assertTrue("and the reading that must not be taken",
                desc.contains("never as 'your buffs ended'"));
        assertTrue("the no-baseline follow-up needs naming too",
                desc.contains("'now':[...]"));
    }

    /**
     * The stale claim must be GONE, not merely contradicted further down.
     *
     * <p>The description used to state as fact that an empty list cannot distinguish the two cases.
     * Leaving that sentence in place while adding the new one would leave the model two answers and
     * no way to choose -- and this repo has already shipped a defect built by copying a stale
     * document ({@code command-to-action.md} §3's inverted walk legend). A contains-assertion on
     * the NEW text alone would stay green with the old sentence still sitting there.
     */
    @Test
    public void theDescriptionNoLongerTeachesTheDefectThatWasJustFixed() {
        String desc = worldViewDescription();
        assertFalse("the old claim -- that a failed read makes every effect report lost -- is now "
                        + "false, and a description that still says it teaches the fixed defect: "
                        + desc,
                desc.contains("cannot distinguish 'no effects'"));
        assertFalse("and the consequence it promised must go with it",
                desc.contains("in which case every active effect reports lost"));
    }

    @Test
    public void theDescriptionDocumentsXpProgressNowThatFullModeShipsIt() {
        String desc = worldViewDescription();
        assertTrue("a field on the wire and absent from the description cannot be used",
                desc.contains("xpProgress"));
        assertTrue("its scale must be stated -- a bare fraction is not self-describing",
                desc.contains("0..1 fraction"));
    }

    // ===== helpers =====

    private static Map<String, Object> effectsDiff(List<SelfView.Effect> before,
                                                  List<SelfView.Effect> after) {
        Map<String, Object> d = WorldViewDiff.diff(wrap(selfWithEffects(before)),
                wrap(selfWithEffects(after)));
        Object self = d.get("self");
        if (self == null) {
            return Map.of();
        }
        Object fx = asMap(self).get("effects");
        return fx == null ? Map.of() : asMap(fx);
    }

    private static WorldView wrap(SelfView self) {
        return new WorldView(true, 1L, "explore", self, null, List.of(), null,
                TargetView.miss(), null);
    }

    private static String worldViewDescription() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("world_view not found");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
