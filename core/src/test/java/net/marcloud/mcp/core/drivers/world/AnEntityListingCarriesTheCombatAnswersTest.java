package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The combat answers must reach the wire, not just exist as methods.
 *
 * <p>{@link EntityView} grew {@code reachable}/{@code hoverable}/{@code attackable}/{@code hostile}
 * and {@link EntityCombat} holds the rules; neither of those is any use to a model if the JSON
 * projection still emits only id/type/pos/dist/hp. That is not a hypothetical gap -- it is exactly
 * what happened the last time a record in this surface gained fields, and the reason this test
 * reads the projection rather than the record.
 *
 * <p>Two details are pinned because they are the ones a simplification would drop:
 * <ul>
 *   <li><b>Both reach gates are reported, not one.</b> Which of 36.0 or 9.0 applies depends on
 *       whether the SERVER can see the player, which the client cannot observe. Emitting only the
 *       optimistic one is the same defect as emitting a guess.</li>
 *   <li><b>{@code hoverable} is separate from reach.</b> The crosshair clamps at 3.0 while the
 *       server accepts 6.0, so a single "in range" flag conflates two questions with different
 *       answers for anything between 3 and 6 blocks.</li>
 * </ul>
 */
public final class AnEntityListingCarriesTheCombatAnswersTest {

    private static EntityView zombieAt(double dist) {
        return new EntityView(7, "Zombie", 10.0, 64.0, 10.0, dist, 20, "Zombie");
    }

    private static Map<String, Object> wire(EntityView e) {
        List<Object> list = WorldViewJson.entitiesList(List.of(e));
        assertEquals("one entity in, one out", 1, list.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) list.get(0);
        return m;
    }

    @Test
    public void everyCombatVerdictIsOnTheWire() {
        Map<String, Object> m = wire(zombieAt(2.0));
        for (String k : new String[] {"reachableSeen", "reachableBlind", "hoverable",
                "attackable", "hostile"}) {
            assertTrue("the projection still omits '" + k + "', so the rule exists and the model "
                    + "never sees it: " + m.keySet(), m.containsKey(k));
        }
        assertTrue("hp stays: it was already there and a mob's health is not optional context",
                wire(new EntityView(7, "Zombie", 10, 64, 10, 2.0, 17, "Zombie")).containsKey("hp"));
        assertFalse("and a null hp stays absent rather than becoming 0, which would read as dead",
                wire(new EntityView(7, "Zombie", 10, 64, 10, 2.0, null, null)).containsKey("hp"));
    }

    @Test
    public void bothReachGatesAreReportedBecauseSightIsNotKnowable() {
        Map<String, Object> near = wire(zombieAt(4.0));
        assertTrue("4 blocks is inside the seen gate", (Boolean) near.get("reachableSeen"));
        assertFalse("and outside the blind one, so the answer genuinely depends on a fact the "
                + "client cannot observe -- emitting only the first would be a guess", (Boolean)
                near.get("reachableBlind"));
        assertFalse("4 blocks is also past the 3.0 crosshair clamp",
                (Boolean) near.get("hoverable"));

        Map<String, Object> close = wire(zombieAt(2.0));
        assertTrue("2 blocks is inside both", (Boolean) close.get("reachableSeen"));
        assertTrue((Boolean) close.get("reachableBlind"));
        assertTrue("and hoverable", (Boolean) close.get("hoverable"));
    }

    @Test
    public void permissionAndHostilityAreSeparateAnswers() {
        Map<String, Object> orb = wire(new EntityView(9, "XPOrb", 1, 64, 1, 1.0, null, null));
        assertFalse("an XP orb is not hostile", (Boolean) orb.get("hostile"));
        assertFalse("and attacking one disconnects the player, which the listing must say rather "
                + "than let a caller discover by losing the session", (Boolean)
                orb.get("attackable"));
        assertTrue("but it is perfectly reachable", (Boolean) orb.get("reachableSeen"));

        Map<String, Object> cow = wire(new EntityView(10, "Cow", 1, 64, 1, 3.0, 10, "Cow"));
        assertFalse("a cow is neither hostile nor forbidden", (Boolean) cow.get("hostile"));
        assertTrue((Boolean) cow.get("attackable"));
    }

    @Test
    public void theReachBoundaryStaysStrict() {
        assertFalse("exactly 6.0 blocks is NOT inside the seen gate -- the server compares "
                + "strictly, and an inclusive test here reports a target as in range whose click "
                + "will be silently refused",
                (Boolean) wire(zombieAt(6.0)).get("reachableSeen"));
        assertTrue("just inside is", (Boolean) wire(zombieAt(5.9)).get("reachableSeen"));
        assertFalse("exactly 3.0 is not inside the blind gate either",
                (Boolean) wire(zombieAt(3.0)).get("reachableBlind"));
        assertTrue("and 3.0 IS hoverable, because that clamp is inclusive",
                (Boolean) wire(zombieAt(3.0)).get("hoverable"));
    }
}
