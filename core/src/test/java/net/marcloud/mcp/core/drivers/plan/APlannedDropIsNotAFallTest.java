package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import org.junit.Test;

/**
 * A drop the PLAN asked for is not a fall, and the guard must be able to tell them apart.
 *
 * <p><b>The defect.</b> The fall guard compared the player's feet against
 * {@code moves.get(index).from().y()} -- the height the move STARTS at -- with 1.5 blocks of slack,
 * on every tick. For a {@link Move.Kind#DROP} the start is the ledge the player is falling FROM, so
 * a planned 2- or 3-block drop crossed the threshold while still in the air and the route aborted
 * with a message claiming the player had left the route. {@code NeighborGen.addDrop} emits drops of
 * up to {@link Stance#SAFE_DROP_MAX} (three blocks, which is vanilla's free fall depth), so the
 * planner could compute a route its own executor refused to finish -- on the first descent there is.
 *
 * <p><b>Why these assertions catch it.</b> The fall is simulated at the heights a fall actually
 * passes through (0.4 blocks a tick, starting only once the body is over the gap) rather than by
 * teleporting to the landing, because teleporting is exactly what hides the defect: the old guard
 * fires as soon as the feet go below the ledge minus 1.5, which a real descent crosses on its way
 * down. The route must complete with the move counted. The second test is the other direction --
 * a player genuinely below where the move goes must still end the route honestly -- so the fix
 * cannot be "delete the guard".
 */
public class APlannedDropIsNotAFallTest {

    /** The plan's world: flat ground at y=63 with a one-cell pit whose floor is at y=61. */
    private static FakeWorld pittedGround() {
        FakeWorld w = new FakeWorld(0).floor(-4, 12, 63, -4, 4);
        w.air(1, 63, 0);   // the ledge's floor over the pit is gone, so the way in is down
        w.solid(1, 61, 0); // and the pit has a floor two blocks below, so the drop has a landing
        return w;
    }

    /** The actuator side of the same terrain: the floor, the hole, and the pit's floor. */
    private static FakeActuator pittedActuator() {
        FakeActuator act = new FakeActuator();
        for (int gx = -6; gx <= 12; gx++) {
            for (int gz = -6; gz <= 6; gz++) {
                act.putBlock(gx, 63, gz);
            }
        }
        act.removeBlock(1, 63, 0);
        act.putBlock(1, 61, 0, "stone");
        act.setPosition(0.5D, 64.0D, 0.5D);
        act.onGround = true;
        return act;
    }

    private static Planner.Plan planIntoThePit() {
        return new Planner(pittedGround()).plan(new Stance(0, 64, 0), new Stance(1, 62, 0));
    }

    @Test
    public void thePlannerOffersTheDropAndTheExecutorFinishesIt() {
        Planner.Plan plan = planIntoThePit();
        assertTrue("getting into a one-cell pit is a drop: " + plan.failure(), plan.found());
        assertEquals("and it is the only move: there is nowhere to walk around a one-cell hole",
                1, plan.moves().size());
        Move drop = plan.moves().get(0);
        assertEquals("the planner must select the DROP it computed", Move.Kind.DROP, drop.kind());
        assertEquals("from the ledge at y=64 to the landing at y=62 -- two blocks, which vanilla "
                + "charges no health for", 2, drop.from().y() - drop.to().y());

        FakeActuator act = pittedActuator();
        RouteExecutor ex = new RouteExecutor(plan, 0);

        ActOutcome out = driveWithFalling(ex, act, plan.moves(), 200);

        assertTrue("a descent the plan chose must be walkable to the end. The old guard compared "
                + "the feet against the height the move STARTED at, so this exact route aborted "
                + "mid-air with 'fell out of the route': " + out.message(),
                out.terminal() && out.ok());
        assertEquals("and the move is credited", 1, ex.movesDone());
        assertEquals("the player ended in the pit's own block", 62,
                (int) Math.floor(act.position()[1]));
    }

    @Test
    public void aFallPastTheLandingStillEndsTheRouteAndNamesIt() {
        Planner.Plan plan = planIntoThePit();
        FakeActuator act = pittedActuator();
        RouteExecutor ex = new RouteExecutor(plan, 0);

        assertFalse("the first tick only starts the walk", ex.tick(act).terminal());
        act.setPosition(1.5D, 55.0D, 0.5D); // the landing was mined out; the player keeps going

        ActOutcome out = ex.tick(act);

        assertTrue("a descent past the plan's own floor is terminal", out.terminal());
        assertFalse("and it must never be reported as success", out.ok());
        assertTrue("the message must say the route was left downward, because 'stuck' or 'timeout' "
                + "would point the caller at the wrong thing entirely: " + out.message(),
                out.message().contains("fell out of the route"));
    }

    /**
     * Drive until terminal, standing in for the physics: walk toward the destination, and fall once
     * the body is over the gap.
     *
     * <p>The fall is stepped at 0.4 blocks a tick rather than snapped to the landing, and it starts
     * only when the cell under the feet is empty -- so the descent passes through the heights a real
     * one does, which is the only way the guard this test is about can be exercised at all.
     */
    private static ActOutcome driveWithFalling(RouteExecutor ex, FakeActuator act, List<Move> moves,
                                               int maxTicks) {
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = ex.tick(act);
            if (last.terminal()) {
                return last;
            }
            if (ex.phase() != RouteExecutor.Phase.WALKING || ex.movesDone() >= moves.size()) {
                continue;
            }
            Move m = moves.get(ex.movesDone());
            advance(act, m.to().x() + 0.5D, m.to().z() + 0.5D);
            double[] p = act.position();
            boolean overTheGap = !act.blockPresent((int) Math.floor(p[0]),
                    (int) Math.floor(p[1]) - 1, (int) Math.floor(p[2]));
            if (overTheGap && p[1] > m.to().y()) {
                double next = p[1] - 0.4D;
                if (next <= m.to().y()) {
                    act.setPosition(p[0], m.to().y(), p[2]);
                    act.onGround = true;
                } else {
                    act.setPosition(p[0], next, p[2]);
                    act.onGround = false;
                }
            } else {
                act.onGround = true;
            }
        }
        return last;
    }

    /** Move toward a point by at most a walking tick, snapping rather than overshooting. */
    private static void advance(FakeActuator act, double tx, double tz) {
        double[] p = act.position();
        double dx = tx - p[0];
        double dz = tz - p[2];
        double d = Math.sqrt(dx * dx + dz * dz);
        double step = 0.25D;
        if (d <= step) {
            act.setPosition(tx, p[1], tz);
        } else {
            act.setPosition(p[0] + dx / d * step, p[1], p[2] + dz / d * step);
        }
    }
}
