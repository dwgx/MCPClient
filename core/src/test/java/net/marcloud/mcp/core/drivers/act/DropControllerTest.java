package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * Teeth for {@link DropController}, and the proof that it READS BACK rather than trusting the
 * click it sent.
 *
 * <p><b>The property this file exists for.</b> {@code Container.slotClick} mode 4 is accepted by
 * the container whether or not it does anything -- an empty slot, a slot whose
 * {@code canTakeStack} refuses, a spectator, a missing {@code PlayerControllerMP} -- so
 * {@code ActActuator.dropStack}'s {@code true} means "the click was ISSUED" and nothing more. A
 * controller that reported the drop from that boolean would report success for a bag that never
 * changed, and the caller freeing a slot in a full inventory would carry on believing it had room.
 *
 * <p>So the assertions below are shaped around the second tick: the one that re-reads the slot.
 * {@link #aDropThatChangesNothingIsReportedAsNotHappening()} is the direction that matters --
 * it is the only test here that fails if the read-back is ever weakened into a re-read of what
 * the controller itself wrote.
 */
public class DropControllerTest {

    /** Drive a controller to a terminal outcome, up to a bound generous enough for two ticks. */
    private static ActOutcome runToEnd(DropController c, FakeActuator act) {
        ActOutcome out = null;
        for (int i = 0; i < 8; i++) {
            out = c.tick(act);
            if (out.terminal()) {
                return out;
            }
        }
        return out;
    }

    @Test
    public void aFullStackIsThrownAndTheSlotIsReadBackEmpty() {
        FakeActuator act = new FakeActuator();
        act.putStack(20, 64);
        DropController c = new DropController(InteractIntent.dropStack(20));

        ActOutcome first = c.tick(act);
        assertFalse("the tick that SENDS must not claim a verdict: the slot has not been re-read"
                + " yet, so any answer is a guess", first.terminal());
        assertEquals("the click was issued exactly once", 1,
                act.calls.stream().filter(c2 -> c2.equals("dropStack(20)")).count());

        ActOutcome second = c.tick(act);
        assertTrue("the second tick must reach a verdict", second.terminal());
        assertTrue("and it must be a success: " + second.message(), second.ok());
        assertEquals("and the slot it named is empty", 0, act.slotStackSize(20));
        // The message quotes BOTH sides of the read-back, which is what makes the number in it
        // evidence rather than decoration.
        assertTrue("the message must name what was there: " + second.message(),
                second.message().contains("64"));
        assertTrue("and what is there now: " + second.message(),
                second.message().contains("reads 0"));
    }

    @Test
    public void aDropThatChangesNothingIsReportedAsNotHappening() {
        // The container accepted the click and applied it to no slot.
        FakeActuator act = new FakeActuator();
        act.putStack(20, 64);
        act.dropIsANoOp = true;
        DropController c = new DropController(InteractIntent.dropStack(20));

        ActOutcome sent = c.tick(act);
        assertFalse(sent.terminal());
        ActOutcome readBack = c.tick(act);

        assertTrue(readBack.terminal());
        assertFalse("a drop that did not land must FAIL, not be reported from the click's own"
                + " return value: " + readBack.message(), readBack.ok());
        assertEquals("the stack is still there", 64, act.slotStackSize(20));
        assertTrue("and the message must say so with both numbers: " + readBack.message(),
                readBack.message().contains("still holds 64") && readBack.message().contains("64"));
    }

    @Test
    public void aPartlyAppliedDropIsAFailureNotASmallerSuccess() {
        // One item gone out of 64: a click nobody asked for, on a verb that throws whole stacks.
        FakeActuator act = new FakeActuator() {
            @Override
            public boolean dropStack(int playerSlot) {
                calls.add("dropStack(" + playerSlot + ")");
                stacks.put(playerSlot, stacks.get(playerSlot) - 1);
                return true;
            }
        };
        act.putStack(20, 64);
        DropController c = new DropController(InteractIntent.dropStack(20));
        c.tick(act);
        ActOutcome out = c.tick(act);

        assertTrue(out.terminal());
        assertFalse("63 of 64 is not what mode 4 with button 1 does, so it must not read as a"
                + " success: " + out.message(), out.ok());
        assertEquals(63, act.slotStackSize(20));
    }

    @Test
    public void aRefusedWriteFailsOnTheTickItIsRefused() {
        FakeActuator act = new FakeActuator();
        act.putStack(20, 64);
        act.dropRefused = true;
        DropController c = new DropController(InteractIntent.dropStack(20));

        ActOutcome out = c.tick(act);
        assertTrue("a refused write is terminal at once; retrying cannot fix a refusal",
                out.terminal());
        assertFalse(out.ok());
        assertEquals("and the stack is untouched", 64, act.slotStackSize(20));
    }

    @Test
    public void anEmptySlotIsRefusedWithNoWriteAtAll() {
        FakeActuator act = new FakeActuator();
        DropController c = new DropController(InteractIntent.dropStack(5));

        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse("an empty slot has nothing to throw", out.ok());
        assertEquals("no click may be sent for it", 0, act.calls.size());
    }

    @Test
    public void slotsOutsideTheThirtySixAreRefusedWithNoWrite() {
        // 36 is the first slot of ContainerPlayer's OWN rows, and -1 is vanilla's NO_SLOT, which
        // is a different gesture entirely. Neither is a player inventory slot, so neither may be
        // sent as one.
        for (int slot : new int[] {-1, 36, 99}) {
            FakeActuator act = new FakeActuator();
            act.putStack(20, 64);
            DropController c = new DropController(InteractIntent.dropStack(slot));

            ActOutcome out = c.tick(act);
            assertTrue("slot " + slot + " must reach a verdict", out.terminal());
            assertFalse("slot " + slot + " is not a player inventory slot", out.ok());
            assertEquals("slot " + slot + ": no write of any kind", 0, act.calls.size());
            assertEquals("slot " + slot + ": the real slot 20 is untouched", 64,
                    act.slotStackSize(20));
        }
    }

    @Test
    public void outOfWorldIsRefusedWithNoWrite() {
        FakeActuator act = new FakeActuator();
        act.inWorld = false;
        act.putStack(20, 64);
        DropController c = new DropController(InteractIntent.dropStack(20));

        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertFalse(out.ok());
        assertEquals(0, act.calls.size());
        assertEquals(64, act.slotStackSize(20));
    }

    @Test
    public void everyOneOfTheThirtySixSlotsIsNameable() {
        // The bound is the whole 36, not the 9 in hand: "free a slot" is the whole point, and a
        // controller limited to the bar could never empty a pack.
        for (int slot = 0; slot < DropController.SLOT_COUNT; slot++) {
            FakeActuator act = new FakeActuator();
            act.putStack(slot, 5);
            DropController c = new DropController(InteractIntent.dropStack(slot));
            assertTrue("slot " + slot + " must complete", runToEnd(c, act).ok());
            assertEquals("slot " + slot + " must be empty afterwards", 0, act.slotStackSize(slot));
        }
    }

    @Test
    public void aDropOnOneSlotLeavesEveryOtherSlotAlone() {
        FakeActuator act = new FakeActuator();
        Map<Integer, Integer> before = Map.of(3, 7, 20, 64, 35, 12);
        before.forEach(act::putStack);
        DropController c = new DropController(InteractIntent.dropStack(20));
        runToEnd(c, act);

        assertEquals(0, act.slotStackSize(20));
        assertEquals(7, act.slotStackSize(3));
        assertEquals(12, act.slotStackSize(35));
    }

    @Test
    public void isDoneOnlyAfterATerminalOutcome() {
        FakeActuator act = new FakeActuator();
        act.putStack(20, 64);
        DropController c = new DropController(InteractIntent.dropStack(20));
        assertFalse("a controller that has not ticked has produced nothing", c.isDone());
        c.tick(act);
        assertFalse("the send tick is not terminal, so it is not done", c.isDone());
        c.tick(act);
        assertTrue(c.isDone());
    }

    @Test
    public void theSlotIsReadableOffTheController() {
        assertEquals(20, new DropController(InteractIntent.dropStack(20)).slot());
    }
}