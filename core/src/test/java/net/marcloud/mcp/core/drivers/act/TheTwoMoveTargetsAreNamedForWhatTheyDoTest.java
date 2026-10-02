package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The two ways to ask the MOVE slot to go somewhere are named for what they DO.
 *
 * <p>They were {@code to} and {@code route}. {@code to} is the one that reads like the safe
 * option and is not: it walks a straight line, never plans around anything and never builds, so
 * on a live client it walked the player off a 30-block cliff and drowned them, with no warning at
 * any point (audit 2.3). {@code route} is the one that does the work. A name is the only thing an
 * agent reads before choosing, and the safer option was wearing the shorter name.
 *
 * <p>So: {@code to} becomes {@code walk_straight} and {@code route} becomes {@code go_to}. The
 * rename is <b>breaking and leaves no alias</b> — an old name that still works is a name that will
 * still be chosen, and the whole point is to stop it being the obvious one.
 *
 * <p>Which is why the old names must be REFUSED rather than quietly accepted. A silent alias
 * here would be a tool that looks migrated and is not, and the caller would have no way to tell.
 */
public final class TheTwoMoveTargetsAreNamedForWhatTheyDoTest {

    @Test
    public void walkStraightIsAccepted() {
        Map<String, Object> move = new HashMap<>();
        move.put("walk_straight", List.of(10, 64, 10));
        ActIntent intent = ActIntentParser.parseMoveSlot(move);
        assertTrue("walk_straight must produce a straight-line intent, not a route: " + intent,
                intent instanceof NavIntent);
    }

    @Test
    public void goToIsAccepted() {
        Map<String, Object> move = new HashMap<>();
        move.put("go_to", List.of(10, 64, 10));
        ActIntent intent = ActIntentParser.parseMoveSlot(move);
        assertTrue("go_to must produce a routed intent: " + intent, intent instanceof RouteIntent);
    }

    @Test
    public void theOldNamesAreRefusedWithAnExplanation() {
        for (String old : new String[] {"to", "route"}) {
            Map<String, Object> move = new HashMap<>();
            move.put(old, List.of(10, 64, 10));
            try {
                ActIntentParser.parseMoveSlot(move);
                fail("'" + old + "' was renamed and must not still work: a silent alias is a tool "
                        + "that looks migrated and is not, and the caller gets no signal");
            } catch (IllegalArgumentException e) {
                String m = String.valueOf(e.getMessage());
                assertTrue("the refusal must name the replacement, or a caller upgrading cannot "
                                + "act on it -- got: " + m,
                        m.contains(old.equals("to") ? "walk_straight" : "go_to"));
                // Contains-the-name was NOT enough, and that is a measured fact rather than a
                // worry: with the rename refusal deleted outright, the old key is silently
                // ignored, the map falls through to the inert-axes refusal, and that message
                // mentions BOTH new keys -- so the assertion above passed against a build that
                // had lost the rename entirely. This test survived its own mutation. The word
                // that cannot come from an unrelated refusal is the one naming the change.
                assertTrue("the refusal must be ABOUT the rename: a message that merely mentions "
                        + "the new key while complaining about something else leaves the caller "
                        + "with no way to tell a rename from a bad value -- got: " + m,
                        m.contains("RENAMED"));
            }
        }
    }

    /**
     * The refusal must describe the replacement it names, and the two descriptions are
     * <b>opposites</b>. A message built from one template reused across both names says that
     * {@code go_to} walks a straight line -- reintroducing, in the very sentence written to stop
     * the caller choosing the wrong one, the exact lie this rename exists to remove.
     *
     * <p>Asserting only that the message {@code contains} the replacement is what let that ship:
     * a message can name both keys correctly and still describe the wrong one, and the name is the
     * part a skimming caller reads.
     */
    @Test
    public void eachRefusalDescribesTheKeyItNamesAndNotTheOtherOne() {
        String toMsg = refusalFor("to");
        assertTrue("'to' was the straight-line walk, so its replacement's description must say "
                        + "so and not claim routing: " + toMsg,
                toMsg.contains("STRAIGHT LINE") && !toMsg.contains("REACHES a block"));

        String routeMsg = refusalFor("route");
        assertTrue("'route' was the routed walk, so its replacement's description must say so "
                        + "and not claim a straight line: " + routeMsg,
                routeMsg.contains("REACHES a block") && !routeMsg.contains("STRAIGHT LINE"));
    }

    /**
     * Presence, not value. A map holding a renamed key with a {@code null} is the same reaching
     * for a key that no longer exists, and it must not read as absent.
     */
    @Test
    public void aRenamedKeyHoldingNullIsStillRefusedRatherThanReadAsAbsent() {
        for (String old : new String[] {"to", "route"}) {
            Map<String, Object> move = new HashMap<>();
            move.put(old, null);
            try {
                ActIntentParser.parseMoveSlot(move);
                fail("'" + old + "':null is a caller using a key that no longer exists, and a "
                        + "silent drop of it is how the old name would keep working invisibly");
            } catch (IllegalArgumentException e) {
                assertTrue("got: " + e.getMessage(),
                        String.valueOf(e.getMessage()).contains("RENAMED"));
            }
        }
    }

    private static String refusalFor(String oldKey) {
        Map<String, Object> move = new HashMap<>();
        move.put(oldKey, List.of(10, 64, 10));
        try {
            ActIntentParser.parseMoveSlot(move);
            fail("'" + oldKey + "' must not still work");
            return null;
        } catch (IllegalArgumentException e) {
            return String.valueOf(e.getMessage());
        }
    }

    @Test
    public void givingBothIsStillRefused() {
        Map<String, Object> move = new HashMap<>();
        move.put("walk_straight", List.of(1, 64, 1));
        move.put("go_to", List.of(2, 64, 2));
        try {
            ActIntentParser.parseMoveSlot(move);
            fail("two destinations are two answers to one question and the MOVE slot holds one");
        } catch (IllegalArgumentException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("not both"));
        }
    }
}
