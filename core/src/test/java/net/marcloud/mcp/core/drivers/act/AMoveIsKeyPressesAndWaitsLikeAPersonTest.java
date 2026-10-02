package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Movement must be expressible as key presses, and must wait a human amount of time first.
 *
 * <p>Both halves are the same claim — "this is a person's hands" — and both were false. The
 * controller published an exact bearing as two arbitrary floats every tick
 * ({@code NavController.steer}, the old body computing {@code forward = wz*cos - wx*sin}), and it
 * pressed the first key on the same tick the intent arrived.
 *
 * <p>Why an arbitrary float is not a rounding detail: {@code Entity.moveFlying:1224-1241}
 * NORMALISES the input vector. With {@code |input| >= 1} the acceleration is forced to length
 * {@code a} regardless of direction; with {@code |input| < 1} it is scaled proportionally. So in
 * 1.8.9 <b>speed is decided entirely by the key mask, plus sneak and sprint — yaw contributes
 * nothing to it</b>. The only thing a human can change about how fast they move is which keys
 * are down, and a keyboard has three states per axis. Every value outside
 * {@code {-1, 0, +1}} per axis (or {@code {-0.3, 0, +0.3}} sneaking, applied downstream by
 * {@code MovementInputFromOptions}) is a number no pair of fingers produces.
 *
 * <p>The reaction half: a human's first displacement lands 214-416 ms after deciding to move
 * (150-250 ms decision-to-fingertip per Dye/Green/Bavelier 2009, 4-16 ms hardware, 0-50 ms
 * sampling). The controller was at 0-50 ms — four to eight ticks early. A reaction time no
 * person has is at least as loud as an impossible axis value, and unlike the axis value it
 * cannot be explained away as "the agent is a machine".
 */
public class AMoveIsKeyPressesAndWaitsLikeAPersonTest {

    private FakeActuator act;

    @Before
    public void setUp() {
        act = new FakeActuator();
        act.setPosition(0.5, 64.0, 0.5);
        act.putBlock(0, 63, 0);
        for (int x = 1; x <= 60; x++) {
            act.putBlock(x, 63, 0);
        }
    }

    /**
     * The reachable set, exactly. Computed rather than written out so the test fails if the
     * reasoning above is ever relaxed in the code without this being updated.
     */
    private static final double[] LEGAL_AXIS = {0.0, -1.0, 1.0};

    @Test
    public void everyPublishedAxisIsAKeyPressAPersonCanMake() {
        // Bearings all the way round the circle, so this covers all eight directions and every
        // boundary between them rather than the two the walk tests happen to use.
        for (int deg = 0; deg < 360; deg += 3) {
            NavController nav = new NavController(60.0, 64.0, 0.0, 400);
            act.setPosition(0.5, 64.0, 0.5);
            for (int i = 0; i < 12; i++) {
                act.setPosition(0.5, 64.0, 0.5);
                nav.tick(act);
                double f = nav.forward();
                double s = nav.strafe();
                assertLegal("bearing " + deg + "deg produced forward=" + f + " strafe=" + s, f, s);
            }
        }
    }

    private static void assertLegal(String what, double f, double s) {
        for (double a : new double[] {f, s}) {
            boolean legal = false;
            for (double l : LEGAL_AXIS) {
                if (Math.abs(a - l) < 1e-9) {
                    legal = true;
                    break;
                }
            }
            assertTrue(what + " -- an axis value outside {-1,0,+1} is not a key anyone can press",
                    legal);
        }
    }

    @Test
    public void aWalkInADiagonalPressesTwoFullKeysAndVanillaNormalisesTheLength() {
        // The mask is (1,1), magnitude sqrt(2) -- NOT a shortened (0.707,0.707).
        //
        // This is the part that is easy to get backwards. `moveFlying` normalises |input|>=1 to
        // length a, so (1,1) and (1,0) both walk at full speed and a diagonal is not slower.
        // If the controller instead published a unit-length diagonal, vanilla would scale it
        // proportionally (the |input|<1 branch) and the diagonal would walk at 0.707x -- a
        // gait that does not exist, and one caused by trying to be tidy about the maths.
        NavController diag = new NavController(60.0, 64.0, 60.0, 400);
        act.setPosition(0.5, 64.0, 0.5);
        for (int i = 0; i < 12; i++) {
            diag.tick(act);
        }
        assertEquals("a diagonal is two keys at full value", 1.0, diag.forward(), 1e-9);
        assertEquals(1.0, diag.strafe(), 1e-9);
        assertEquals("so its length is sqrt(2), which is what vanilla's >=1 branch normalises",
                Math.sqrt(2.0), Math.hypot(diag.forward(), diag.strafe()), 1e-9);
    }

    @Test
    public void theKeyMaskDoesNotChatterWhileTheBearingHoldsStill() {
        // Chatter between two diagonals when the target sits on the boundary is its own tell:
        // a person does not re-press W+D, W+A, W+D inside one second while walking straight.
        List<String> pressed = new ArrayList<>();
        NavController nav = new NavController(60.0, 64.0, 0.0, 400);
        for (int i = 0; i < 40; i++) {
            nav.tick(act);
            pressed.add(nav.forward() + "/" + nav.strafe());
        }
        int changes = 0;
        for (int i = 1; i < pressed.size(); i++) {
            if (!pressed.get(i).equals(pressed.get(i - 1))) {
                changes++;
            }
        }
        assertTrue("a straight walk pressed " + changes + " different key pairs in 40 ticks: "
                + pressed, changes <= 1);
    }

    /**
     * Drive a real walk through the applier, which is where the reaction delay lives.
     *
     * <p>It was a {@link NavController} field until the metronome fix, and that was the defect:
     * {@code RouteExecutor} builds a fresh controller per MOVE, so a per-instance draw is a
     * per-BLOCK draw, and a 20-block route emitted ~20 runs of zero displacement. The delay is a
     * property of the DECISION to walk, so it belongs to the component whose lifetime is the walk.
     *
     * <p>Reading the axes off the runtime rather than the controller is deliberate: this file is
     * about what reaches the game, and the applier is what publishes it.
     *
     * @return the first tick (1-based) on which a movement key was published, or -1 for none
     */
    private int firstMovingTick(ActRuntime runtime, MoveApplier applier, int maxTicks) {
        SlotRecord rec = runtime.record(ActSlot.MOVE);
        if (rec.intent() == null) {
            rec = runtime.submitNav(new NavIntent(60.0, 64.0, 0.0, 400));
        }
        for (int tick = 1; tick <= maxTicks; tick++) {
            rec = applier.apply(rec.stampTick(tick));
            if (runtime.moveForward() != 0f || runtime.moveStrafe() != 0f) {
                return tick;
            }
        }
        return -1;
    }

    /** A runtime and applier wired the way production wires them: one applier, one runtime. */
    private ActRuntime wired(MoveApplier[] out) {
        ActRuntime runtime = new ActRuntime();
        MoveApplier applier = new MoveApplier(act, runtime);
        runtime.registerApplier(ActSlot.MOVE, applier);
        out[0] = applier;
        return runtime;
    }

    @Test
    public void theFirstStepWaitsForAHumanAmountOfTime() {
        MoveApplier[] applier = new MoveApplier[1];
        int firstMovingTick = firstMovingTick(wired(applier), applier[0], 30);

        assertTrue("no key was ever pressed", firstMovingTick > 0);
        assertTrue("the first key came on tick " + firstMovingTick
                + " (=" + ((firstMovingTick - 1) * 50) + "ms). A human's first displacement is "
                + "214-416ms after deciding, so 0ms is a reaction time nobody has",
                firstMovingTick >= 5);
        assertTrue("and it is not a person's patience either -- the unskilled band tops out "
                + "at 8 ticks (400ms), got " + firstMovingTick,
                firstMovingTick <= 9);
    }

    @Test
    public void theDelayIsPaidByStandingStillAndThenMovingAtFullSpeed() {
        // A ramp would satisfy "the first step is late" too, and would be wrong: a person pauses
        // and then walks at full speed. So the axes must be exactly zero during the delay and
        // exactly +-1 immediately after, with nothing in between.
        ActRuntime runtime = new ActRuntime();
        MoveApplier applier = new MoveApplier(act, runtime);
        SlotRecord rec = SlotRecord.submitted(new NavIntent(60.0, 64.0, 0.0, 400), 0L, 1L, "s");

        for (int i = 1; i <= 12; i++) {
            rec = applier.apply(rec.stampTick(i));
            if (i <= 4) {
                assertEquals("a delayed walk is still standing still on tick " + i,
                        0.0, runtime.moveForward(), 1e-9);
                assertEquals(0.0, runtime.moveStrafe(), 1e-9);
            }
        }
        assertTrue("and then it is at full key value, not partway: forward=" + runtime.moveForward(),
                Math.abs(runtime.moveForward()) == 1.0 || runtime.moveForward() == 0.0);
    }

    @Test
    public void aFreshIntentEarnsAFreshDelay() {
        // Otherwise a caller can cancel and re-submit to skip the reaction time, and the tell
        // becomes "fast on the second try", which is worse than no delay at all.
        //
        // ONE applier across both walks, and the cancel goes through the runtime the way a
        // caller would. A second applier would draw a second delay for the uninteresting reason
        // that it starts at -1, and the test would pass against a version that never cleared
        // anything -- which is the defect it exists to catch.
        MoveApplier[] applier = new MoveApplier[1];
        ActRuntime runtime = wired(applier);
        runtime.submitNav(new NavIntent(60.0, 64.0, 0.0, 400));

        int first = firstMovingTick(runtime, applier[0], 12);
        assertTrue("premise: the first walk must have started, or this proves nothing: " + first,
                first > 0);

        runtime.cancel(ActSlot.MOVE);
        int again = firstMovingTick(runtime, applier[0], 12);

        assertTrue("a re-submitted walk must react again, got first key on tick " + again,
                again >= 5);
    }

    @Test
    public void everyOneOfTheEightKeyPairsIsReachable() {
        // The defect this file's sibling caught, named directly.
        //
        // The first implementation of nearestKeys derived the eight (forward, strafe) pairs
        // from bit patterns: forward got a sign bit, strafe got only a presence bit. The
        // reachable set was therefore {strafe 0, +1} with (0,+1) duplicated and no way to ever
        // strafe LEFT -- and a walk that needed to strafe left snapped to a right press or to
        // pure forward, walking away from its target at four of nine camera headings. It was
        // caught only as a SYMPTOM (a heading failed to arrive); nothing pinned the cause.
        //
        // So the set is pinned as what it is: sweep the bearing a full turn at a fine step and
        // require every one of the eight pairs to appear. A mask that cannot strafe left cannot
        // pass this, whatever else it gets right.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int deg = 0; deg < 360; deg++) {
            for (float yaw : new float[] {0f, 90f, 180f, 270f}) {
                NavController nav = new NavController(0, 64, 0, 0);
                act.setPosition(0.5, 64.0, 0.5);
                act.yaw = yaw;
                // Point the target along the swept bearing from the player's fixed position.
                double b = Math.toRadians(deg);
                NavController aimed = new NavController(
                        0.5 + 20 * Math.cos(b), 64.0, 0.5 + 20 * Math.sin(b), 400);
                for (int i = 0; i < 12; i++) {
                    aimed.tick(act);
                }
                seen.add((long) aimed.forward() + "/" + (long) aimed.strafe());
            }
        }
        // EIGHT, not nine: 0/0 is "no keys down", which is the reaction pause and the arrived
        // state rather than a direction, and it is pinned where it belongs -- as exactly zero
        // during the delay, by theDelayIsPaidByStandingStillAndThenMovingAtFullSpeed. Asking a
        // swept bearing to produce it would be asking a walk to not walk.
        String[] all = {"1/0", "-1/0", "0/1", "0/-1", "1/1", "1/-1", "-1/1", "-1/-1"};
        StringBuilder missing = new StringBuilder();
        for (String k : all) {
            if (!seen.contains(k)) {
                missing.append(k).append(' ');
            }
        }
        assertTrue("these key pairs were never produced by a full sweep of every bearing at four "
                + "camera headings, and each one is a direction a person's hands can hold: "
                + missing + " (saw " + seen + ")", missing.length() == 0);
    }
}
