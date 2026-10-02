package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * An {@code act_set move} must never be accepted as a thing that cannot happen.
 *
 * <p>Both halves of this were found by DRIVING A LIVE CLIENT, not by reading, which is the only
 * reason they are written down here. The call
 *
 * <pre>{@code act_set {"channel":"move","move":{"to":{"x":-60,"y":78,"z":256}}}}
 * (the key was called {@code to} then; it is {@code walk_straight} now)}</pre>
 *
 * came back {@code accepted:true, phase:ACTIVE} and then sat at
 * {@code "moving (tick 320), moved 0.00 blocks"} for 16 seconds. The player never moved. A live
 * probe of the running client showed why, and every layer in between had behaved exactly as
 * written:
 *
 * <pre>
 *   movementInput = ActMovementInput   (the installer works)
 *   moveActive()  = true               (the view works)
 *   moveForward   = 0.0                (the axes are the problem)
 * </pre>
 *
 * <p>Two independent doors led there:
 *
 * <ol>
 *   <li>{@code doublesArg} answers "is this a LIST of numbers?", so {@code walk_straight} written
 *       as a map — the shape every other tool in this surface uses, and the shape a model reaches
 *       for — read as NOT SUPPLIED, and {@code parseMoveSlot} fell through to the raw-axes branch.
 *   <li>That branch then built {@code MoveIntent(0, 0, false, false, false, durationTicks=0)}:
 *       every axis at rest, and the applier's only terminal branch is guarded by
 *       {@code duration > 0}. An intent that can never move and can never end.
 * </ol>
 *
 * <p>The combination is worse than either defect alone, because {@code ACTIVE} is reported. A
 * caller that checks the phase sees success. Only a caller that goes and measures the world
 * discovers otherwise, and the tool surface exists so that callers do not have to.
 */
public class AMoveThatCannotHappenIsRefusedTest {

    private static Map<String, Object> move(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String refusalOf(Map<String, Object> moveMap) {
        try {
            ActIntentParser.parseMoveSlot(moveMap);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Test
    public void aCoordinateObjectIsAcceptedRatherThanReadAsAbsent() {
        // The live call's exact shape. It must build a NavIntent, i.e. let NavController steer.
        ActIntent intent = ActIntentParser.parseMoveSlot(
                move("walk_straight", move("x", -60.0, "y", 78.0, "z", 256.0)));

        assertTrue("a map-shaped 'walk_straight' is a destination, not an absence: got " + intent,
                intent instanceof NavIntent);
        NavIntent nav = (NavIntent) intent;
        assertEquals(-60.0, nav.targetX(), 1e-9);
        assertEquals(78.0, nav.targetY(), 1e-9);
        assertEquals(256.0, nav.targetZ(), 1e-9);
    }

    @Test
    public void theListShapeStillWorksSoTheDocumentedFormIsNotBroken() {
        ActIntent intent = ActIntentParser.parseMoveSlot(
                move("walk_straight", List.of(-60.0, 78.0, 256.0)));
        assertTrue("the schema documents [x,y,z] and it must keep working: " + intent,
                intent instanceof NavIntent);
    }

    @Test
    public void aMalformedCoordinateIsRefusedNotSilentlyDropped() {
        // The whole defect in one call: this used to produce a zero-axis MoveIntent.
        String msg = refusalOf(move("walk_straight", "somewhere over there"));
        assertTrue("a 'walk_straight' that is neither shape is a caller error and must be named, "
                + "because dropping it produced an intent that reported itself as moving forever. "
                + "Got: " + msg, msg != null && msg.contains("neither"));

        assertTrue("and the message must say what IS accepted, so the caller can fix it in one "
                + "step instead of guessing: " + msg, msg != null && msg.contains("[x,y,z]"));
    }

    @Test
    public void aCoordinateObjectMissingAnAxisIsRefusedNotTreatedAsZero() {
        // {"x":1,"z":2} silently reading y as 0 would plan a walk through the floor.
        String msg = refusalOf(move("walk_straight", move("x", 1.0, "z", 2.0)));
        assertTrue("a coordinate object missing 'y' must be refused, not defaulted to 0 -- the "
                + "plan would walk the player into whatever is at y=0. Got: " + msg,
                msg != null && msg.contains("'y'"));
    }

    @Test
    public void anEmptyCoordinateListIsRefused() {
        String msg = refusalOf(move("walk_straight", List.of()));
        assertTrue("an empty list is not a destination: " + msg, msg != null);
    }

    @Test
    public void aRawMoveWithNoAxesAndNoDurationIsRefused() {
        // The second door. Even with 'walk_straight' now validated, this shape is reachable
        // directly, and it is the one that produced the infinite "moving (tick 320)".
        String msg = refusalOf(move());
        assertTrue("forward, strafe, jump, sneak, sprint all at rest with durationTicks 0 asks for "
                + "nothing and can never end; accepting it reports fake success forever. Got: "
                + msg, msg != null && msg.contains("no movement at all"));
    }

    @Test
    public void anInertRawMoveThatHasADurationIsStillAccepted() {
        // "Stand still for 20 ticks" is a real request -- a wait -- and the guard must not eat it.
        ActIntent intent = ActIntentParser.parseMoveSlot(move("durationTicks", 20));
        assertTrue("a bounded wait is legitimate: " + intent, intent instanceof MoveIntent);
        assertEquals(20, ((MoveIntent) intent).durationTicks());
    }

    @Test
    public void everyRealAxisCombinationStillParses() {
        // The guard must not have narrowed the surface. Each of these moves something.
        for (Map<String, Object> m : List.of(
                move("forward", 1.0),
                move("forward", -1.0),
                move("strafe", 1.0),
                move("strafe", -0.5),
                move("jump", true),
                move("sneak", true),
                move("sprint", true),
                move("forward", 0.5, "strafe", 0.5, "jump", true, "sprint", true))) {
            try {
                assertTrue("must still parse: " + m,
                        ActIntentParser.parseMoveSlot(m) instanceof MoveIntent);
            } catch (IllegalArgumentException e) {
                fail("the new inert-move guard rejected a real move " + m + ": " + e.getMessage());
            }
        }
    }

    @Test
    public void goToAlsoAcceptsTheObjectShape() {
        ActIntent intent = ActIntentParser.parseMoveSlot(
                move("go_to", move("x", 4.0, "y", 64.0, "z", -3.0)));
        assertTrue("'go_to' has the same two natural shapes as 'walk_straight': " + intent,
                intent instanceof RouteIntent);
        assertEquals(4, ((RouteIntent) intent).targetX());
    }
}
