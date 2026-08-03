package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The actuator seam must expose the locomotion state a closed-loop controller reads back.
 *
 * <p><b>Why this is a gap and not a preference.</b> {@link ActActuator}'s own javadoc frames the
 * interface as "every game touch a controller needs is a method here", and every other controller
 * in this package reads the world back through it: {@code DigController} polls
 * {@link ActActuator#blockPresent} to learn that the block broke, and fails when
 * {@link ActActuator#pumpDig} reports no progress. The MOVE slot has no equivalent, so
 * {@code MoveApplier} can only count ticks -- it reports "moving (tick N/M)", which means "the key
 * was held", never "the player went anywhere". Measured on a live client: an open-loop intent
 * travels a straight line on flat ground (8.408 blocks, 0.04 degrees off the facing yaw) but
 * nothing in the act package can observe arrival, drift or a jam.
 *
 * <p>The three reads pinned here are the minimum for that, and each is one field on the live
 * {@code EntityPlayerSP} which {@code LivePlayerActuator} already holds at every method:
 *
 * <ul>
 *   <li><b>position</b> -- without it there is no arrival test and no heading correction. Measured
 *       live, vanilla A* snaps a request to the walkable surface, so a follower must compare
 *       against the path's own final point rather than the coordinate it asked for; either way it
 *       needs to know where the player IS.
 *   <li><b>onGround</b> -- distinguishes falling from walking, and gates whether a jump is even
 *       available.
 *   <li><b>collidedHorizontally</b> -- the honest jam signal, and cheaper to reason about than
 *       velocity: it is already the boolean the controller wants. Velocity does distinguish a
 *       jammed player from a walking one (measured: Z component 0.09 against 0.0), but it is
 *       reachable only through the observe path in {@code WorldViewCapture}, not from here, and a
 *       controller comparing floats against a threshold would have to pick the threshold.
 * </ul>
 *
 * <p>Asserted through the INTERFACE rather than by calling the methods, because the defect is that
 * the seam does not offer them at all -- a test that called them would not compile before the fix,
 * which is a build error rather than a red test, and would say nothing about {@code FakeActuator}
 * having kept in step.
 */
public class ActuatorExposesLocomotionStateTest {

    /** Reads a closed-loop locomotion controller cannot be written without. */
    private static final List<String> REQUIRED = Arrays.asList(
            "position", "onGround", "collidedHorizontally");

    private static Method find(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    @Test
    public void theSeamExposesTheStateAClosedLoopControllerReadsBack() {
        for (String name : REQUIRED) {
            assertNotNull("ActActuator must expose " + name + "(): a MOVE controller cannot detect "
                    + "arrival, correct a heading or fail honestly on a jam without reading the "
                    + "world back, which is exactly what DigController does through blockPresent. "
                    + "LivePlayerActuator already holds the live EntityPlayerSP at every method, so "
                    + "this is one field read.",
                find(ActActuator.class, name));
        }
    }

    @Test
    public void positionIsThreeDoublesLikeEyePos() {
        Method m = find(ActActuator.class, "position");
        assertNotNull("ActActuator must expose position()", m);
        assertEquals("position() must return double[] to match eyePos()'s shape -- two different "
                + "conventions for a point in the same interface is a trap for the next reader",
            double[].class, m.getReturnType());
    }

    @Test
    public void theFakeActuatorKeptInStep() {
        // The fake is what makes these controllers headlessly testable -- the highest-leverage
        // choice in this package. A seam method the fake does not implement cannot be exercised in
        // a unit test, so it would silently push the whole controller to live-only verification.
        for (String name : REQUIRED) {
            Method m = find(FakeActuator.class, name);
            assertNotNull("FakeActuator must implement " + name + "() so a nav controller stays "
                    + "headlessly testable", m);
        }
        // Invoked reflectively on purpose: a direct call would not COMPILE before the fix, and a
        // build error is not a red test -- it says nothing about which method is missing.
        try {
            Object pos = find(FakeActuator.class, "position").invoke(new FakeActuator());
            assertTrue("the fake's position must be a readable 3-vector",
                pos instanceof double[] && ((double[]) pos).length == 3);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FakeActuator.position() must be callable", e);
        }
    }
}
