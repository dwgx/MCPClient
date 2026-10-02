package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.core.eval.SimWorld;
import net.marcloud.mcp.core.eval.EvalHarness;

/**
 * A critical hit is a timed swing, and both halves of that are world facts.
 *
 * <p><b>The positive half.</b> A CRIT attack submitted while the body is falling, off the ground,
 * on no ladder and in no water reaches the target -- which is vanilla's own conjunction at {@code
 * EntityPlayer:1333}, term for term. The fake's {@code attackCalls} is the count of swings the
 * game was actually asked for, so zero means the swing was never spent.
 *
 * <p><b>The negative half, which is the one worth a test.</b> A CRIT attack submitted from flat
 * ground must NOT swing. It waits {@code CRIT_WAIT_TICKS} and then refuses, naming the term that
 * is false. The tempting implementation -- wait, then swing anyway -- spends the attack cooldown on
 * a normal hit and reports it as a crit, which is the exact failure this asserts against.
 *
 * <p><b>Why the fall is measured, not assumed.</b> The second test's positive case runs in
 * {@link SimWorld} rather than on the fake, because the window needs a body that is genuinely
 * falling: {@code fallDistance} has to accumulate by vanilla's own rule
 * ({@code Entity.updateFallState:1052-1055}) before the predicate can be satisfied by anything but
 * a hand-set field. The world is built as a ledge with air past it, the player walks off, and the
 * crit is submitted once {@code onGround} has gone false -- which is only observable at all after
 * the simulator applies {@code Entity.moveEntity:820} unconditionally.
 */
public final class ACritIsSwungInTheFallingWindowAndNotOnFlatGroundTest {

    private static final int TARGET = 7;

    /** A fake with one reachable target, standing at the origin. */
    private static FakeActuator withTarget() {
        FakeActuator act = new FakeActuator();
        act.entityEyes.put(TARGET, new double[] {1.5, 1.62, 0.0});
        return act;
    }

    @Test
    public void aCritSwingsWhileFallingAndNotWhileStanding() {
        FakeActuator act = withTarget();
        InteractController c = new InteractController(InteractIntent.critAttack(TARGET));

        // Flat ground: vanilla's conjunction is false at its very first term, and the swing is the
        // expensive half, so the controller must not spend it.
        act.onGround = true;
        act.fallDistance = 0.0;
        ActOutcome standing = c.tick(act);
        assertFalse("a crit on flat ground must not be terminal yet", standing.terminal());
        assertEquals("a crit on flat ground must not swing", 0, act.attackCalls);

        // The body leaves the ground and starts down: every term the seam carries now holds.
        act.onGround = false;
        act.fallDistance = 0.35D;
        ActOutcome falling = c.tick(act);
        assertTrue("the swing in the falling window is terminal", falling.terminal());
        assertTrue(falling.ok());
        assertEquals("exactly one swing, and it is the one the window allowed", 1, act.attackCalls);
    }

    @Test
    public void aCritThatNeverFindsItsWindowIsRefusedWithoutSwinging() {
        FakeActuator act = withTarget();
        InteractController c = new InteractController(InteractIntent.critAttack(TARGET));
        act.onGround = true;
        act.fallDistance = 0.0;

        ActOutcome last = null;
        for (int i = 0; i < InteractController.CRIT_WAIT_TICKS; i++) {
            last = c.tick(act);
        }
        assertTrue("the wait is bounded and ends", last.terminal());
        assertFalse("and it ends in a refusal, not a swing", last.ok());
        assertEquals("nothing was swung", 0, act.attackCalls);
        // The message has to say WHICH term is false, or a caller goes looking at the wrong thing.
        assertTrue("names the term it is waiting on: " + last.message(),
                last.message().contains("on the ground"));
    }

    @Test
    public void aPlainAttackStillSwingsOnFlatGround() {
        FakeActuator act = withTarget();
        InteractController c = new InteractController(InteractIntent.attack(TARGET));
        act.onGround = true;
        act.fallDistance = 0.0;
        ActOutcome out = c.tick(act);
        assertTrue(out.terminal());
        assertTrue(out.ok());
        assertEquals("a plain attack is not gated on the body", 1, act.attackCalls);
    }

    @Test
    public void aRealFallIsWhatOpensTheWindow() {
        // A ledge, and air past its edge. The player walks north until the world says it is falling.
        SimWorld w = new SimWorld().plain(64, "stone", -24, 24, -8, 24).standOn(0, 64, 0).facing(0f);
        EvalHarness h = new EvalHarness(w);

        // Stand still first and confirm the resting state is NOT a fall, so the positive half below
        // is measuring the fall and not a body that was never grounded.
        assertFalse("standing still is not falling", w.fallDistance() > 0.0D);

        h.submit(new MoveIntent(1f, 0f, false, false, false, 0));
        assertTrue("the body walked off the ledge and started falling",
                h.run(400, () -> !w.onGround() && w.fallDistance() > 0.0D));

        assertTrue("fallDistance rises as the body descends", w.fallDistance() > 0.0D);
        assertTrue("and CritWindow agrees with the body", CritWindow.open(w));
    }
}
