package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Blocking is the one sustained use with no ending of its own, and the thing that was missing was
 * not the ability to start it but the ability to <em>stop</em> it.
 *
 * <p>{@code HoldMode.THEN_RELEASE} can block, but only for a tick count the caller has to guess,
 * and "block until it is my turn to swing" has no guessable count. {@code HoldMode.WHILE_BLOCKING}
 * has no clock at all, and {@code Kind.RELEASE} is how it ends.
 *
 * <p>Each assertion below is a world fact, not a message: whether vanilla's use key reads down,
 * whether the game still considers an item in use, and -- the one that distinguishes a block from a
 * meal -- whether vanilla's own {@code isBlocking} says BLOCK. A test that asserted
 * {@code isUsingItem} alone would pass on a player eating bread.
 */
public final class ABlockLastsUntilTheCallerReleasesItTest {

    /** A fake whose held item is a BLOCK use (a sword: 72000 ticks, EnumAction.BLOCK). */
    private static FakeActuator swordUp() {
        FakeActuator act = new FakeActuator();
        act.useStartCount = 72000;
        act.maxUseDuration = 72000;
        act.blocking = true;
        return act;
    }

    @Test
    public void aBlockHoldsIndefinitelyAndTheReleaseEndsIt() {
        FakeActuator act = swordUp();
        HoldController block = new HoldController(InteractIntent.block());

        ActOutcome started = block.tick(act);
        assertFalse("a block does not finish on the tick it starts", started.terminal());
        assertTrue("and vanilla's use key is asserted", act.useKeyHeld());
        assertTrue("and vanilla calls it a block", act.blocking());

        // Far longer than any THEN_RELEASE would have been willing to guess, and nothing expires.
        for (int i = 0; i < 400; i++) {
            ActOutcome held = block.tick(act);
            assertFalse("tick " + i + " ended a block that was still going", held.terminal());
            act.advanceGameTick();
        }
        assertTrue("400 ticks on, the use key is still asserted", act.useKeyHeld());
        assertTrue("400 ticks on, the player is still blocking", act.blocking());

        // The caller decides. This is the assertion the whole mode exists for.
        InteractController release = new InteractController(InteractIntent.releaseUse());
        ActOutcome lettingGo = release.tick(act);
        assertFalse("the release is not confirmed on the tick the key is written", lettingGo.terminal());
        // Vanilla's own stop branch runs later in the tick; the fake models that as the game tick.
        act.usingItem = false;
        act.blocking = false;
        act.advanceGameTick();
        ActOutcome released = release.tick(act);
        assertTrue("the release is confirmed once vanilla let go", released.terminal());
        assertTrue(released.ok());
        assertFalse("and the use key is up", act.useKeyHeld());
    }

    @Test
    public void aHoldOnSomethingThatIsNotABlockIsRefusedAndTheUseIsLetGo() {
        FakeActuator act = new FakeActuator();
        act.useStartCount = 32;      // food: a real use, 32 ticks
        act.maxUseDuration = 32;
        act.blocking = false;       // and vanilla says it is not a BLOCK use
        HoldController block = new HoldController(InteractIntent.block());
        ActOutcome out = block.tick(act);
        assertTrue("holding a meal open forever is refused", out.terminal());
        assertFalse(out.ok());
        assertFalse("and the use was released rather than abandoned", act.useKeyHeld());
    }

    @Test
    public void aReleaseWithNothingHeldStillReportsCleanly() {
        FakeActuator act = new FakeActuator();
        InteractController release = new InteractController(InteractIntent.releaseUse());
        ActOutcome first = release.tick(act);
        assertFalse(first.terminal());
        ActOutcome second = release.tick(act);
        assertTrue("a release with nothing in use is a release, not a failure", second.terminal());
        assertTrue(second.ok());
    }
}
