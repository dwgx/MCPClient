package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link InteractController}: place drives rightClickBlock and completes,
 * a place with no block target fails, attack in reach calls attackEntity+swing and
 * completes, attack out of reach fails WITHOUT dispatching, and use in air/on block.
 */
public class InteractControllerTest {

    @Test
    public void placeAgainstBlockCallsRightClickAndCompletes() {
        FakeActuator act = new FakeActuator();
        act.rightClickResult = true;
        InteractController c = new InteractController(
                InteractIntent.place(1, 2, 3, 1, 0.5, 1.0, 0.5));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal() && out.ok());
        assertEquals(1, act.rightClickCalls);
    }

    @Test
    public void placeRejectedByGameFails() {
        FakeActuator act = new FakeActuator();
        act.rightClickResult = false;
        InteractController c = new InteractController(
                InteractIntent.place(1, 2, 3, 1, 0.5, 1.0, 0.5));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
    }

    @Test
    public void useInAirCallsUseItem() {
        FakeActuator act = new FakeActuator();
        act.useInAirResult = true;
        InteractController c = new InteractController(InteractIntent.useInAir());
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal() && out.ok());
        assertEquals(1, act.useInAirCalls);
        assertEquals("air use must not touch rightClickBlock", 0, act.rightClickCalls);
    }

    @Test
    public void attackInReachCallsAttackThenSwingAndCompletes() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {0, 0, 0};
        act.entityEyes.put(7, new double[] {2, 0, 0}); // 2 blocks, within 4.5 reach
        InteractController c = new InteractController(InteractIntent.attack(7));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal() && out.ok());
        assertEquals(1, act.attackCalls);
        assertEquals(1, act.swingCalls);
        // order: attack then swing
        int ai = act.calls.indexOf("attackEntity(7)");
        int si = act.calls.indexOf("swing()");
        assertTrue("attack precedes swing", ai >= 0 && si > ai);
    }

    @Test
    public void attackOutOfReachFailsWithNoDispatch() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {0, 0, 0};
        act.entityEyes.put(7, new double[] {50, 0, 0}); // far away
        InteractController c = new InteractController(InteractIntent.attack(7));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals("out-of-reach attack must not be dispatched", 0, act.attackCalls);
        assertEquals(0, act.swingCalls);
        assertTrue(out.message().contains("reach"));
    }

    @Test
    public void attackGoneEntityFails() {
        FakeActuator act = new FakeActuator();
        act.eye = new double[] {0, 0, 0};
        InteractController c = new InteractController(InteractIntent.attack(99));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals(0, act.attackCalls);
    }

    @Test
    public void placeWithoutBlockTargetFails() {
        FakeActuator act = new FakeActuator();
        // Build a malformed PLACE with no block target on purpose.
        InteractIntent bad = new InteractIntent(InteractIntent.Kind.PLACE,
                0, 0, 0, false, -1, 0, 0, 0, -1, -1, null, 0);
        InteractController c = new InteractController(bad);
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals(0, act.rightClickCalls);
    }
}
