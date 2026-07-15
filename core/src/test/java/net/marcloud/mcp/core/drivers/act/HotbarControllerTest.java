package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link HotbarController}: valid slots 0-8 select and confirm; 9 and -1
 * fail with NO write to the actuator.
 */
public class HotbarControllerTest {

    @Test
    public void validSlotSelectsAndConfirms() {
        FakeActuator act = new FakeActuator();
        for (int slot = 0; slot <= 8; slot++) {
            act.calls.clear();
            HotbarController c = new HotbarController(InteractIntent.hotbar(slot));
            ActOutcome out = c.tick(act);
            assertTrue("slot " + slot + " should complete ok", out.terminal() && out.ok());
            assertEquals(slot, act.heldSlot());
            assertTrue("must have called setHeldSlot", act.calls.contains("setHeldSlot(" + slot + ")"));
        }
    }

    @Test
    public void slotNineFailsWithNoWrite() {
        FakeActuator act = new FakeActuator();
        act.heldSlot = 3;
        HotbarController c = new HotbarController(InteractIntent.hotbar(9));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals("no setHeldSlot call for an invalid slot", 0, act.calls.size());
        assertEquals("held slot untouched", 3, act.heldSlot());
    }

    @Test
    public void negativeSlotFailsWithNoWrite() {
        FakeActuator act = new FakeActuator();
        act.heldSlot = 5;
        HotbarController c = new HotbarController(InteractIntent.hotbar(-1));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals(0, act.calls.size());
        assertEquals(5, act.heldSlot());
    }

    @Test
    public void selectionThatDoesNotTakeFails() {
        // Actuator that refuses to change slot (simulates a rejected select).
        FakeActuator act = new FakeActuator() {
            @Override
            public void setHeldSlot(int slot) {
                calls.add("setHeldSlot(" + slot + ")"); // recorded, but ignored
            }
        };
        act.heldSlot = 0;
        HotbarController c = new HotbarController(InteractIntent.hotbar(4));
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse("select that did not take must fail honestly", out.ok());
    }
}
