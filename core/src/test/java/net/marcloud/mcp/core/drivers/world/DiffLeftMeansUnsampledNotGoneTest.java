package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import org.junit.Test;

/**
 * {@code world_view mode=diff} reports {@code entities.left} for ids that were in the previous
 * snapshot and are not in this one, which is a fact about SAMPLING that reads as a fact about the
 * WORLD. A caller acting on "left" believes the creeper is dead.
 *
 * <p>Two ways an id lands in left while the entity is alive and adjacent, both reproduced below
 * as behaviour rather than asserted from prose:
 * <ul>
 *   <li>the section was not requested -- {@code WorldViewCapture:50-51} hands the differ
 *       {@code List.of()} for an unwanted "entities", and {@code byId} of an empty list makes
 *       EVERY previously known id report left in one go;</li>
 *   <li>the profile's {@code maxEntities} cap truncated it ({@code WorldViewCapture:135-137},
 *       after a distance sort) -- so a nearer entity arriving EVICTS a farther one, and the
 *       eviction is indistinguishable from a departure.</li>
 * </ul>
 *
 * <p>The behaviour half matters because the description is only true while the encoding it
 * describes holds: if a future change made an unrequested section null-and-skipped, the caveat
 * would become misleading in the other direction, and a prose-only test would stay green.
 *
 * <p>Sibling of {@code GridSemanticsAreDocumentedTest}, same defect family (a field whose real
 * meaning lives only in code), different tool section.
 */
public class DiffLeftMeansUnsampledNotGoneTest {

    private static String worldViewDescription() {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals("world_view")) {
                return spec.tool().description();
            }
        }
        throw new AssertionError("world_view not found");
    }

    private static EntityView entity(int id, double dist) {
        return new EntityView(id, "Zombie", dist, 64.0, 0.0, dist, 20, "Zombie");
    }

    private static WorldView view(long tick, List<EntityView> entities) {
        return new WorldView(true, tick, "EXPLORE", null, null, entities, false, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> left(Map<String, Object> diff) {
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        if (ent == null) {
            return List.of();
        }
        // Empty rather than null when the key is absent: "no departures" and "no entities section"
        // are both "nothing left", and a caller of this helper is asking which ids departed. Returning
        // null made an assertion about an empty left list NPE instead of passing.
        List<Object> l = (List<Object>) ent.get("left");
        return l == null ? List.of() : l;
    }

    /**
     * An unrequested entities section must report NOTHING as left.
     *
     * <p>This test used to assert the opposite, and the inversion is the point. It said "both ids
     * report left although nothing was even looked at" and passed -- documenting a defect rather
     * than guarding against one, because {@code WorldViewCapture} handed the differ {@code List.of()}
     * for a section nobody asked for, and {@code byId} of an empty list makes EVERY previously known
     * id report left in one go. A caller reads that as every mob around it having died at once.
     *
     * <p>The capture now returns {@code null} for an unsampled section, which is the same
     * null-versus-empty convention {@code SelfView#air} and {@code SelfView#effects} already use, so
     * the differ can tell "nobody looked" from "nothing there" without a new wire contract.
     */
    @Test
    public void anUnrequestedEntitiesSectionReportsNothingAsLeft() {
        WorldView prev = view(1L, List.of(entity(7, 3.0), entity(8, 5.0)));
        // What capture produces when 'sections' omits "entities": null, NOT an empty list.
        WorldView cur = view(2L, null);

        Map<String, Object> diff = WorldViewDiff.diff(prev, cur);
        assertTrue("nothing may be reported left when nothing was sampled: " + diff,
                left(diff).isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        assertEquals("it must say it did not look, because silence in diff mode means 'unchanged' "
                + "and would re-assert the last known set", Boolean.TRUE, ent.get("unsampled"));
    }

    /**
     * A genuinely EMPTY sampled section must still report the departures.
     *
     * <p>The other half, and without it the fix could have suppressed {@code left} outright -- which
     * would hide every real departure and be strictly worse than the ambiguity being fixed. Same
     * shape as the effects round: the null case and the empty case must stay distinguishable in BOTH
     * directions.
     */
    @Test
    public void asampledButEmptySectionStillReportsTheDepartures() {
        WorldView prev = view(1L, List.of(entity(7, 3.0), entity(8, 5.0)));
        WorldView cur = view(2L, List.of());   // looked, and found nothing: they really are gone

        List<Object> left = left(WorldViewDiff.diff(prev, cur));
        assertEquals("an empty sample is a statement about the world, so these did leave",
                2, left.size());
        assertTrue(left.contains(7) && left.contains(8));
    }

    /**
     * A cap that actually truncated must FLAG the left list rather than leaving it bare.
     *
     * <p>The second of the three ways an id lands in {@code left} while alive. The capture now
     * reports truncation from the same scan that builds the list, so this is measured rather than
     * inferred from {@code size() == cap} -- which would be wrong whenever a scan legitimately found
     * exactly {@code cap} entities and dropped none.
     */
    @Test
    public void aCapThatTruncatedFlagsTheLeftListSoAnEvictionIsNotReadAsADeath() {
        WorldView prev = new WorldView(true, 1L, "EXPLORE", null, null,
                List.of(entity(8, 9.0)), false, null, null, null);
        // The near one arrived and took the only slot the cap allows: id 8 stopped being sampled.
        WorldView cur = new WorldView(true, 2L, "EXPLORE", null, null,
                List.of(entity(9, 1.0)), true, null, null, null);

        Map<String, Object> diff = WorldViewDiff.diff(prev, cur);
        assertTrue("the id really did stop being sampled, so it still ships", left(diff).contains(8));
        @SuppressWarnings("unchecked")
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        assertEquals("and the caller must be told an eviction is among the possibilities",
                Boolean.TRUE, ent.get("capped"));
    }

    /** No truncation, no flag: a caller must not be warned about a cap that cost it nothing. */
    @Test
    public void anUntruncatedPollDoesNotClaimTheCapBit() {
        WorldView prev = view(1L, List.of(entity(7, 3.0)));
        WorldView cur = view(2L, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> ent = (Map<String, Object>) WorldViewDiff.diff(prev, cur).get("entities");
        assertTrue("a genuine departure with no cap involved must not be flagged as an eviction",
                ent != null && !ent.containsKey("capped"));
    }

    /**
     * The cap flag is only emitted alongside a {@code left}, because that is the list it qualifies.
     *
     * <p>Found by a surviving mutation: dropping the {@code !left.isEmpty()} condition changed
     * nothing that any test could see, so the flag could have started appearing on every capped poll
     * -- noise on a payload whose whole design is to omit what has not changed, and a warning about a
     * cost the caller cannot observe.
     */
    @Test
    public void theCapFlagIsNotEmittedOnAPollWithNoDepartures() {
        EntityView same = entity(7, 3.0);
        WorldView prev = new WorldView(true, 1L, "EXPLORE", null, null, List.of(same), true,
                null, null, null);
        WorldView cur = new WorldView(true, 2L, "EXPLORE", null, null, List.of(same), true,
                null, null, null);

        Map<String, Object> diff = WorldViewDiff.diff(prev, cur);
        @SuppressWarnings("unchecked")
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        assertTrue("nothing departed, so there is no left list for the flag to qualify: " + diff,
                ent == null || !ent.containsKey("capped"));
    }

    // ---- the CAPTURE side of the same two facts.
    // ---- Driven directly, because a mutation run proved the differ tests cannot reach it: restoring
    // ---- the original defect in capture (an unsampled section becoming List.of()) left every test
    // ---- green, and so did nailing the cap flag to false.

    /**
     * An unsampled section must reach the view as {@code null}, not as an empty list.
     *
     * <p>The production defect, at its source. An empty list is a claim about the world; null is a
     * statement about the sampling. The differ turns the first into "every entity you knew has left".
     */
    @Test
    public void captureKeepsAnUnsampledSectionNullRatherThanEmpty() {
        assertNull("null must PROPAGATE -- becoming an empty list here is the original defect",
                WorldViewCapture.entitiesSection(null, 12));
        assertEquals("a sampled but empty scan stays an empty LIST, which is a different claim",
                List.of(), WorldViewCapture.entitiesSection(List.of(), 12));
    }

    /** The cap flag must be true exactly when the cap dropped something the scan had found. */
    @Test
    public void captureReportsTheCapBitOnlyWhenItActuallyTruncated() {
        List<EntityView> three = List.of(entity(1, 1.0), entity(2, 2.0), entity(3, 3.0));

        assertTrue("three found, two kept: something alive was dropped",
                WorldViewCapture.capTruncated(three, 2));
        assertFalse("three found, three kept: nothing was dropped, so claiming an eviction would be "
                        + "a warning about a cost that does not exist",
                WorldViewCapture.capTruncated(three, 3));
        assertFalse("EXACTLY at the cap is NOT truncation -- this is the case that makes "
                        + "size() == cap an unsound inference and the reason the flag is measured",
                WorldViewCapture.capTruncated(three, 3));
        assertFalse("a headroom cap drops nothing", WorldViewCapture.capTruncated(three, 99));
        assertFalse("an unsampled section cannot have been truncated",
                WorldViewCapture.capTruncated(null, 1));
    }

    /**
     * The cap itself evicts a live entity, and the diff then cannot tell that from a departure.
     *
     * <p>Drives {@code WorldViewCapture.nearestWithinCap}, the real truncation, rather than
     * hand-building two id sets. The first version did the latter: it made one view hold id 8 and the
     * next hold id 9 and asserted 8 was reported left -- which holds for ANY id-set difference, and
     * applied no cap at all despite a comment saying "cap of 1 for the illustration". Production
     * could have stopped truncating entirely and it would have stayed green. Both entities are alive
     * in both samples here; only the cap moves.
     */
    @Test
    public void anEntityEvictedByTheEntityCapIsReportedAsLeft() {
        EntityView far = entity(8, 9.0);
        EntityView near = entity(9, 1.0);

        // Both alive both times. First sample sees only the far one; then the near one shows up and
        // takes the single slot the cap allows.
        List<EntityView> before = WorldViewCapture.nearestWithinCap(List.of(far), 1);
        List<EntityView> after = WorldViewCapture.nearestWithinCap(List.of(far, near), 1);

        assertEquals("precondition: the cap kept exactly one", 1, after.size());
        assertEquals("and it kept the NEARER one, which is what evicts the far one", 9,
                after.get(0).id());

        Map<String, Object> diff = WorldViewDiff.diff(view(1L, before), view(2L, after));
        assertTrue("the evicted id reads exactly like a departure, though it never moved",
                left(diff).contains(8));
        @SuppressWarnings("unchecked")
        Map<String, Object> ent = (Map<String, Object>) diff.get("entities");
        assertTrue("and it arrives alongside an 'entered', which is the only hint available",
                ent.containsKey("entered"));
    }

    /**
     * The cap keeps the NEAREST, not the first seen. If it truncated in arrival order the eviction
     * would be arbitrary, and "a newly arrived closer entity evicts a farther one" -- which the tool
     * description states as fact -- would not be true.
     */
    @Test
    public void theCapKeepsTheNearestRatherThanWhateverArrivedFirst() {
        List<EntityView> kept = WorldViewCapture.nearestWithinCap(
                List.of(entity(1, 30.0), entity(2, 2.0), entity(3, 11.0)), 2);
        assertEquals(2, kept.size());
        assertEquals("nearest first", 2, kept.get(0).id());
        assertEquals("then the next nearest, not the one listed first", 3, kept.get(1).id());
    }

    @Test
    public void theDescriptionSaysLeftDoesNotMeanGone() {
        String desc = worldViewDescription();
        assertTrue("the description must name the field it is glossing",
                desc.contains("entities.left"));
        assertTrue("and must deny the reading a caller will otherwise take",
                desc.contains("does NOT mean the entity is gone"));
        assertTrue("naming the honest alternative to 'gone'", desc.contains("SAMPLING"));
    }

    /**
     * Each profile's cap must be stated NEXT TO ITS OWN NAME, not merely present somewhere.
     *
     * <p>The first version asserted {@code desc.contains(String.valueOf(p.maxEntities))} and could
     * not fail. The description is ~3KB, so a bare number matches incidentally: COMBAT's cap of 24
     * was satisfied by "its 24-block bound" in the drop legend thirty words earlier. That was not
     * hypothetical -- while this assertion was green the description ACTUALLY SAID combat's cap was
     * ninety-nine, and the test that exists to catch exactly that said nothing, for eight commits.
     * (It said so because a reviewer's mutation was swept into a commit by {@code git add -A}; the
     * assertion's job was to make that impossible to miss, and it failed at it.)
     *
     * <p>So the pattern requires the profile's own lowercase name immediately before its number.
     * Tying the two together is what makes a wrong cap unrepresentable rather than merely unlikely:
     * the number can no longer be borrowed from unrelated prose, and renaming a profile or changing
     * a cap both turn this red.
     */
    @Test
    public void everyProfileCapIsStatedBesideItsOwnName() {
        String desc = worldViewDescription();
        for (ObserveProfile p : ObserveProfile.values()) {
            String expected = p.name().toLowerCase(java.util.Locale.ROOT) + " " + p.maxEntities;
            assertTrue("the description must say \"" + expected + "\" so the number cannot be "
                    + "satisfied by an unrelated one elsewhere in the text; a model that reads the "
                    + "wrong cap mistakes an eviction for a departure. Description was: " + desc,
                    desc.contains(expected));
        }
    }

    /**
     * The unrequested-section path, named specifically enough that pre-existing prose cannot satisfy
     * it.
     *
     * <p>{@code contains("sections")} and {@code contains("cap")} both passed on the description as
     * it stood BEFORE any of this work: "'sections' picks a subset" and "(sorted, capped)" were
     * already there. Two of the three sub-assertions in the original method were therefore inert, so
     * the caveat they claim to guard could have been deleted down to a sentence and stayed green.
     * These phrases only exist in the diff-mode caveat itself.
     */
    @Test
    public void theDescriptionNamesTheUnsampledMechanismNotJustTheWordSections() {
        String desc = worldViewDescription();
        // Asserts the CURRENT behaviour, not the old one. This used to require the sentence
        // "every id you knew reports left", which described the defect -- and when the defect was
        // fixed the assertion went red, which is precisely what a description guard is for. The
        // replacement pins what the payload now DOES: the marker a caller branches on, and the
        // promise that nothing is reported left when nothing was looked at.
        assertTrue("the unsampled marker must be named, or a caller cannot branch on it: " + desc,
                desc.contains("'unsampled':true"));
        assertTrue("and the description must promise what that means -- that NOTHING is reported "
                + "left -- since the old behaviour was the opposite: " + desc,
                desc.contains("NOTHING is reported left"));
        assertTrue("the cap flag must be named too, since a bare left list cannot be told from an "
                + "eviction without it", desc.contains("'capped':true"));
        assertTrue("and it must tie truncation to the entity cap in the same breath, since "
                + "'a cap exists' is not actionable", desc.contains("entity cap"));
        assertFalse("the OLD claim must be gone, not merely contradicted further down -- a "
                + "description that still says every known id reports left teaches the fixed "
                + "defect: " + desc,
                desc.contains("every id you knew reports left at once"));
    }

    /**
     * The caps are derived from {@link ObserveProfile} rather than hand-copied, so changing one
     * without updating the description fails. Guard against the derivation going hollow a different
     * way: if every profile shared a cap the loop above would prove almost nothing.
     */
    @Test
    public void theProfileCapsAreDistinctSoThatDerivationIsNotHollow() {
        long distinct = java.util.Arrays.stream(ObserveProfile.values())
                .mapToInt(p -> p.maxEntities).distinct().count();
        assertEquals("distinct caps per profile keep the assertion above meaningful",
                ObserveProfile.values().length, distinct);
    }

    // ---- The same "absence means unchanged" convention, applied to the self section.
    // ---- selfDiff compared eleven fields and never looked at vx/vy/vz, saturation or effects,
    // ---- so those read as unchanged forever. Below: the rule, derived, not hand-listed.

    private static final SelfView IDLE = new SelfView(10, 64, 0, 0, 0, 0, 0f, 0f, 20f, 20, 5f,
            3, 0.5f, 0, 300, "SURVIVAL", false, false, true, List.of());

    private static WorldView selfOnly(long tick, SelfView self) {
        return new WorldView(true, tick, "explore", self, null, List.of(), false, null, null, null);
    }

    /** A value of the right type, far enough from the original to clear every dead-band. */
    private static Object mutate(Class<?> type, Object old) {
        if (type == double.class) return (Double) old + 5.0;
        if (type == float.class) return (Float) old + 5.0f;
        if (type == int.class) return (Integer) old + 5;
        if (type == boolean.class) return !(Boolean) old;
        if (type == String.class) return old + "_MUTATED";
        if (type == List.class) {
            return List.of(new SelfView.Effect(12, "potion.fireResistance", 0, 6000));
        }
        if (type == Integer.class) {
            // Boxed because air carries null for "could not be read" (SelfView#air). Mutating to
            // ANOTHER READABLE VALUE rather than to null is deliberate: this rule exists to prove
            // every shipped field is observable in diff mode, and null would instead exercise the
            // readable -> unreadable transition, which is a different property with its own test
            // (SelfAirSeparatesUnreadableFromDrowningTest). Mutating to null here would quietly
            // move this test off the property its name claims.
            //
            // This branch was added at a merge: the boxing and this reflection sweep arrived from
            // two changes written in parallel, and the sweep's refusal to skip an unknown type is
            // what surfaced it -- exactly as its own message intends. A version that skipped
            // silently would have left air undiffed and still green.
            return old == null ? 5 : (Integer) old + 5;
        }
        throw new AssertionError("no mutation rule for " + type + "; add one rather than "
                + "skipping the component, or this test goes quietly hollow");
    }

    private static SelfView copyWithOneFieldChanged(int componentIndex) throws Exception {
        RecordComponent[] rcs = SelfView.class.getRecordComponents();
        Class<?>[] types = new Class<?>[rcs.length];
        Object[] args = new Object[rcs.length];
        for (int i = 0; i < rcs.length; i++) {
            types[i] = rcs[i].getType();
            args[i] = rcs[i].getAccessor().invoke(IDLE);
        }
        args[componentIndex] = mutate(types[componentIndex], args[componentIndex]);
        return SelfView.class.getDeclaredConstructor(types).newInstance(args);
    }

    /**
     * Every self field that mode=full SHIPS must be observable in mode=diff.
     *
     * <p>The rule is derived twice over: the fields come from {@link SelfView}'s record
     * components, and whether full mode ships one is decided by asking
     * {@link WorldViewJson#selfMap} whether the projection moved. A hand-written list of field
     * names would have been the hollow shape this repo keeps catching in itself -- it would pass
     * unchanged the day someone adds a field to SelfView, which is precisely when it needs to
     * fail.
     *
     * <p>Collects every violation instead of failing on the first, because the point of the run
     * is the SET of unexamined fields; on the code this was written against it named all five
     * (vx, vy, vz, saturation, effects) in one message.
     */
    @Test
    public void everySelfFieldTheFullPayloadShipsIsObservableInDiffMode() throws Exception {
        RecordComponent[] rcs = SelfView.class.getRecordComponents();
        List<String> unexamined = new ArrayList<>();
        for (int i = 0; i < rcs.length; i++) {
            SelfView mutated = copyWithOneFieldChanged(i);
            boolean fullShipsIt = !WorldViewJson.selfMap(IDLE).equals(WorldViewJson.selfMap(mutated));
            boolean diffSaysSo = WorldViewDiff.diff(selfOnly(1L, IDLE), selfOnly(2L, mutated))
                    .containsKey("self");
            if (fullShipsIt && !diffSaysSo) {
                unexamined.add(rcs[i].getName());
            }
        }
        assertTrue("mode=full ships these self fields but mode=diff never compares them, so a "
                + "caller polling diff reads their absence as 'unchanged' when it means 'never "
                + "looked': " + unexamined, unexamined.isEmpty());
    }

    /**
     * Guards the derivation above from going hollow the other way.
     *
     * <p>The loop can only judge a field it can see on the wire, so a field mode=full does not emit
     * is silently skipped by it. The set is now EMPTY, and it did not start that way: it was
     * {@code [xpProgress]}, a field that reached {@link SelfView} from capture and was then dropped
     * by {@code selfMap} -- carried the whole way across the seam and discarded at the last step,
     * which is invisible from either end. This assertion is what turned red when xpProgress was put
     * on the wire, and staying red until {@code selfDiff} learned to compare it is the entire
     * purpose of pinning the set exactly rather than asserting {@code size() <= 1}.
     *
     * <p>Kept rather than deleted now that it is empty: a NEW field added to SelfView and projected
     * nowhere lands here, and the loop above would skip it in silence.
     */
    @Test
    public void noSelfFieldIsInvisibleToThisRule() throws Exception {
        RecordComponent[] rcs = SelfView.class.getRecordComponents();
        List<String> notOnTheWire = new ArrayList<>();
        for (int i = 0; i < rcs.length; i++) {
            if (WorldViewJson.selfMap(IDLE).equals(WorldViewJson.selfMap(copyWithOneFieldChanged(i)))) {
                notOnTheWire.add(rcs[i].getName());
            }
        }
        assertEquals("every SelfView field must be observable in mode=full, or the rule above skips "
                + "it in silence and proves less than it claims. Fields full mode drops: "
                + notOnTheWire, List.of(), notOnTheWire);
    }

    // ---- the numbers the description quotes, recovered from BEHAVIOUR rather than copied ----
    //
    // Both thresholds live in private constants, so a test cannot read them and widening them for a
    // test's convenience would be the wrong trade. Bisecting the real diff instead makes these
    // assertions stronger than a field read would be: they fail if the CONSTANT changes, and also if
    // the comparison around it changes while the constant stays put. Copying the number into the
    // assertion is what this file is here to avoid -- a hand-copied 0.1 agrees with a description
    // that says 0.1 forever, including after the code stops meaning it.

    /** Smallest single-axis velocity delta the diff will actually report, to two decimals. */
    private static double measuredVelDeadBand() {
        for (int hundredths = 1; hundredths <= 100; hundredths++) {
            double d = hundredths / 100.0;
            SelfView moved = new SelfView(IDLE.x(), IDLE.y(), IDLE.z(), IDLE.vx() + d, IDLE.vy(),
                    IDLE.vz(), IDLE.yaw(), IDLE.pitch(), IDLE.health(), IDLE.food(),
                    IDLE.saturation(), IDLE.xpLevel(), IDLE.xpProgress(), IDLE.armor(), IDLE.air(),
                    IDLE.gamemode(), IDLE.sneaking(), IDLE.sprinting(), IDLE.onGround(),
                    IDLE.effects());
            if (selfKeys(IDLE, moved).contains("vel")) {
                return d;
            }
        }
        throw new AssertionError("no single-axis velocity delta up to 1.0 was ever reported; the "
                + "dead-band is either absent or larger than any plausible movement");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> selfKeys(SelfView before, SelfView after) {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1, before), selfOnly(2, after));
        Object self = d.get("self");
        return self == null ? java.util.Set.of() : ((Map<String, Object>) self).keySet();
    }

    /**
     * The velocity dead-band the description quotes must be the one the code applies.
     *
     * <p>Not cosmetic. A caller reading "reported only past a 0.1 dead-band" sizes its polling and
     * its own idle-detection around that figure; if the code moved to 0.05 the diff would start
     * reporting gravity jitter as movement and the stated reason for the band -- that idle jitter
     * stays below it -- would be false while the sentence still read as true.
     */
    @Test
    public void theVelocityDeadBandInTheDescriptionIsTheOneTheCodeApplies() {
        double band = measuredVelDeadBand();
        String quoted = String.format(java.util.Locale.ROOT, "%.1f", band);
        assertTrue("the diff reports velocity past " + quoted + " but world_view's description does "
                        + "not quote that number; a description naming a different dead-band is "
                        + "worse than one naming none, because a caller sizes its polling on it",
                worldViewDescription().contains(quoted + " dead-band"));
    }

    /**
     * The expiring edge the description quotes must be the crossing the code actually uses.
     *
     * <p>The number matters more than most: "fires once, on crossing N" is the whole contract, and a
     * caller that polls more coarsely than N can miss the only notification it will ever get. A
     * stated N that no longer matches the code turns that from a known limitation into a silent one.
     */
    @Test
    public void theExpiringEdgeInTheDescriptionIsTheOneTheCodeApplies() {
        int edge = measuredExpiryEdge();
        assertTrue("the diff warns on crossing " + edge + " ticks remaining, but the description "
                        + "does not quote that number",
                worldViewDescription().contains(edge + " ticks remaining"));
    }

    /** Largest remaining duration that still triggers 'expiring' when crossed into. */
    private static int measuredExpiryEdge() {
        for (int ticks = 1; ticks <= 2000; ticks++) {
            SelfView before = withEffect(ticks + 1);
            SelfView after = withEffect(ticks);
            if (effectsKeys(before, after).contains("expiring")) {
                return ticks;
            }
        }
        throw new AssertionError("no crossing up to 2000 ticks produced an 'expiring' report");
    }

    private static SelfView withEffect(int durationTicks) {
        return new SelfView(IDLE.x(), IDLE.y(), IDLE.z(), IDLE.vx(), IDLE.vy(), IDLE.vz(),
                IDLE.yaw(), IDLE.pitch(), IDLE.health(), IDLE.food(), IDLE.saturation(),
                IDLE.xpLevel(), IDLE.xpProgress(), IDLE.armor(), IDLE.air(), IDLE.gamemode(),
                IDLE.sneaking(), IDLE.sprinting(), IDLE.onGround(),
                List.of(new SelfView.Effect(12, "potion.fireResistance", 0, durationTicks)));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> effectsKeys(SelfView before, SelfView after) {
        Map<String, Object> d = WorldViewDiff.diff(selfOnly(1, before), selfOnly(2, after));
        Object self = d.get("self");
        if (self == null) {
            return java.util.Set.of();
        }
        Object fx = ((Map<String, Object>) self).get("effects");
        return fx == null ? java.util.Set.of() : ((Map<String, Object>) fx).keySet();
    }
}
