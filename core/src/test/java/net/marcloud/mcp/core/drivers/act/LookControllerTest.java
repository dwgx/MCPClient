package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link LookController}: instant snap (one tick, prev==cur), slew rate
 * math (N degrees at 5/tick = ceil(N/5) ticks), short-arc wrap across ±179,
 * LOOK_AT angle math for a known block/eye, and entity-gone honest failure.
 */
public class LookControllerTest {

    @Test
    public void instantSetSnapsInOneTickWithPrevEqualsCur() {
        FakeActuator act = new FakeActuator();
        act.yaw = 0f;
        act.pitch = 0f;
        LookController c = new LookController(LookIntent.set(90f, 30f, 0f)); // instant
        ActOutcome out = c.tick(act);
        assertTrue("instant set completes in one tick", out.terminal() && out.ok());
        assertTrue("instant uses a snap, not interp", act.lastSetWasSnap);
        assertEquals(90f, act.lastSetYaw, 1e-4);
        assertEquals(30f, act.lastSetPitch, 1e-4);
        assertEquals("prev==cur on a snap so the client renders no whip-around",
                act.lastPrevYaw, act.lastSetYaw);
    }

    @Test
    public void slewTakesCeilOfErrorOverRateTicks() {
        FakeActuator act = new FakeActuator();
        act.yaw = 0f;
        act.pitch = 0f;
        // 22 degrees at a PEAK of 5/tick. The peak is the invariant; the tick COUNT is no
        // longer ceil(22/5), because the aim is now a raised cosine rather than a flat-topped
        // trapezoid: it spends the first and last ticks slower than the peak, which is the
        // measured shape (Flash & Hogan 1985, peak/mean 1.75). What still must hold is that it
        // never exceeds the cap the caller set, never overshoots the target, and arrives.
        LookController c = new LookController(LookIntent.set(22f, 0f, 5f));
        int ticks = 0;
        float previousYaw = 0f;
        ActOutcome out;
        do {
            out = c.tick(act);
            ticks++;
            // 22 is NOT on the mouse lattice (22 / 0.15 = 146.67), so a write that is quantised
            // can land at 22.05 and that is the CORRECT nearest reachable rotation, not an
            // overshoot. What must not happen is overshooting by more than half a mouse.
            assertTrue("must not overshoot 22 by more than half a mouse, got " + act.yaw,
                    act.yaw <= 22f + 0.075000);
            if (!out.terminal()) {
                assertTrue("no tick may move faster than the caller's peak: " + previousYaw
                                + " -> " + act.yaw,
                        Math.abs(act.yaw - previousYaw) <= 5f + 1e-4);
            }
            previousYaw = act.yaw;
        } while (!out.terminal() && ticks < 100);
        assertTrue(out.ok());
        assertTrue("a bell curve is slower at both ends than a trapezoid, so it needs more "
                + "ticks than ceil(arc/peak); got " + ticks, ticks > 5);
        // Arrives on the mouse lattice, which is the finest rotation a cursor can express.
        assertEquals(22f, act.yaw, 0.15);
    }

    @Test
    public void slewIsInterpolatedNotSnappedWhileTurning() {
        FakeActuator act = new FakeActuator();
        LookController c = new LookController(LookIntent.set(50f, 0f, 5f));
        // The loop below skips any tick that ended in a snap, which used to be the reaction delay
        // and now is only the landed branch -- so this test is about the ticks AFTER the first
        // write, not the ticks before it. There is no pause before the first write: see
        // TheLookChannelHasNoReactionDelayTest, which pins that absence. What this test is for
        // is the assertion below -- a mid-slew tick must go through setRotationInterp with
        // distinct prev/cur, or the turn renders as a series of snaps.
        ActOutcome out = c.tick(act);
        for (int i = 0; i < 10 && act.lastSetWasSnap; i++) {
            out = c.tick(act);
        }
        assertFalse("mid-slew tick is non-terminal", out.terminal());
        assertFalse("mid-slew uses interp so the turn renders smoothly", act.lastSetWasSnap);
        // NOT "the first step is the full 5 degrees" any more: that number is what made the
        // ramp a ramp. What this test is actually for is the line above it -- a mid-slew tick
        // must go through setRotationInterp with distinct prev/cur, or the turn renders as a
        // series of snaps. So the first step is now only required to be non-zero and within
        // the cap, which is the bell curve opening.
        assertTrue("the curve opens by moving, not by sitting still at zero: " + act.yaw,
                act.yaw > 0f);
        assertTrue("and never faster than the cap: " + act.yaw, act.yaw <= 5f + 1e-4);
    }

    @Test
    public void slewWrapsTheShortArcAcrossPlusMinus179() {
        FakeActuator act = new FakeActuator();
        act.yaw = 179f;
        // Target -179: short way is +2 degrees (through 180), not -358.
        LookController c = new LookController(LookIntent.set(-179f, 0f, 5f));
        ActOutcome out = c.tick(act);
        assertTrue("2-degree short arc lands in one step", out.terminal() && out.ok());
        // Within one MOUSE, not exactly: -179 is not a position a cursor can express. The
        // lattice is 0.15 degrees, and the whole point of quantising is that the last tick
        // lands on the nearest reachable rotation rather than on the arithmetic request.
        assertEquals("landed on the mouse lattice nearest the target", -179f, act.yaw, 0.15);
    }

    @Test
    public void wrapTo180IsShortArc() {
        assertEquals(2f, LookController.wrapTo180(-179f - 179f), 1e-4); // -358 -> +2
        assertEquals(-2f, LookController.wrapTo180(358f), 1e-4);
        assertEquals(180f, Math.abs(LookController.wrapTo180(180f)), 1e-4);
    }

    @Test
    public void lookAtBlockComputesVanillaAngles() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {0.0, 0.0, 0.0};
        // Block at (0,0,5): center (0.5,0.5,5.5). Aim mostly +Z, slightly down-ish.
        LookController c = new LookController(LookIntent.lookAtBlock(0, 0, 5, 0f));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal() && out.ok());

        float[] expected = LookController.anglesTo(0, 0, 0, 0.5, 0.5, 5.5);
        // To within ONE MOUSE, not to 1e-3. 0.15 degrees is the finest rotation a cursor can
        // express (f1*0.15 at the default sensitivity), so demanding the unrounded value would
        // be demanding a rotation this client cannot physically hold -- which is precisely why
        // the lattice exists. The arithmetic above is still what is being checked; only the
        // final quantisation sits between it and the assertion.
        assertEquals(expected[0], act.lastSetYaw, 0.150000);
        assertEquals(expected[1], act.lastSetPitch, 0.150000);
    }

    @Test
    public void anglesToKnownGeometryDueSouth() {
        // Eye at origin, target due +Z (south in MC). Vanilla yaw for +Z is 0.
        float[] a = LookController.anglesTo(0, 0, 0, 0, 0, 5);
        assertEquals(0f, LookController.wrapTo180(a[0]), 1e-3);
        assertEquals("level shot has ~0 pitch", 0f, a[1], 1e-3);
    }

    @Test
    public void lookAtTracksEntityEyeEachTick() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {0, 0, 0};
        act.entityEyes.put(42, new double[] {5, 0, 0});
        LookController c = new LookController(LookIntent.lookAtEntity(42, 0f));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal() && out.ok());
        float[] expected = LookController.anglesTo(0, 0, 0, 5, 0, 0);
        assertEquals(expected[0], act.lastSetYaw, 1e-3);
    }

    @Test
    public void entityGoneFailsHonestly() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {0, 0, 0};
        // entity 42 not present in entityEyes -> gone
        LookController c = new LookController(LookIntent.lookAtEntity(42, 5f));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse("targeting a gone entity is an honest failure", out.ok());
        assertTrue(out.message().contains("gone"));
    }

    @Test
    public void notInWorldFails() {
        FakeActuator act = new FakeActuator();
        act.inWorld = false;
        LookController c = new LookController(LookIntent.set(0f, 0f, 0f));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
    }
}
