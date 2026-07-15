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
        // 22 degrees at 5/tick => ceil(22/5) = 5 ticks (5,10,15,20, then land).
        LookController c = new LookController(LookIntent.set(22f, 0f, 5f));
        int ticks = 0;
        ActOutcome out;
        do {
            out = c.tick(act);
            ticks++;
            assertTrue("must not overshoot 22", act.yaw <= 22f + 1e-4);
        } while (!out.terminal() && ticks < 100);
        assertTrue(out.ok());
        assertEquals(5, ticks);
        assertEquals(22f, act.yaw, 1e-4);
    }

    @Test
    public void slewIsInterpolatedNotSnappedWhileTurning() {
        FakeActuator act = new FakeActuator();
        LookController c = new LookController(LookIntent.set(50f, 0f, 5f));
        ActOutcome out = c.tick(act);
        assertFalse("mid-slew tick is non-terminal", out.terminal());
        assertFalse("mid-slew uses interp so the turn renders smoothly", act.lastSetWasSnap);
        assertEquals("first step of 5 degrees", 5f, act.yaw, 1e-4);
    }

    @Test
    public void slewWrapsTheShortArcAcrossPlusMinus179() {
        FakeActuator act = new FakeActuator();
        act.yaw = 179f;
        // Target -179: short way is +2 degrees (through 180), not -358.
        LookController c = new LookController(LookIntent.set(-179f, 0f, 5f));
        ActOutcome out = c.tick(act);
        assertTrue("2-degree short arc lands in one step", out.terminal() && out.ok());
        assertEquals("landed exactly on target", -179f, act.yaw, 1e-4);
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
        assertEquals(expected[0], act.lastSetYaw, 1e-3);
        assertEquals(expected[1], act.lastSetPitch, 1e-3);
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
