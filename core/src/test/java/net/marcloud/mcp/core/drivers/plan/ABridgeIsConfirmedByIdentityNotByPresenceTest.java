package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import org.junit.Test;

/**
 * A placement is confirmed by what the cell BECAME, never by the cell being non-air.
 *
 * <p><b>The defect.</b> {@code RouteExecutor} asked {@code blockPresent} -- which is
 * {@code getMaterial() != Material.air}, and therefore true for water, flowing water, lava, gravel,
 * tall grass and a torch. The planner selects exactly those cells for a bridge, because a bridge is
 * needed where there is NO FLOOR and water, lava and tall grass are all floorless. So on a bridge
 * over water the executor reported "floor confirmed", debited a block that was still in the
 * inventory, and walked the player onto a cell it had not built anything in -- and on lava it
 * walked the player into the hazard the bridge existed to cross.
 *
 * <p><b>Why these assertions catch it.</b> The first test drives a bridge over a WATER cell whose
 * click never lands, and requires the route to fail with the block count untouched and the move
 * uncounted. The old predicate says "water is present, the floor is confirmed" and reports a
 * completed crossing, so every one of those three assertions is wrong on the old code; the failure
 * is the defect, not a wording difference. The second test is the control that stops the fix from
 * being "refuse everything": a click that really does replace the water is a bridge and must be
 * counted. The third test pins the other half of the same review -- arrival -- where the old code
 * asked the same weak predicate about the cell under the feet and credited a player standing one
 * block below the plan as having arrived.
 */
public class ABridgeIsConfirmedByIdentityNotByPresenceTest {

    /** Flat ground at y=63 so stances sit at y=64, matching the planner tests. */
    private static FakeActuator groundedAt(int x, int y, int z) {
        FakeActuator act = new FakeActuator();
        for (int gx = x - 6; gx <= x + 12; gx++) {
            for (int gz = z - 6; gz <= z + 6; gz++) {
                act.putBlock(gx, y - 1, gz);
            }
        }
        act.setPosition(x + 0.5D, y, z + 0.5D);
        act.onGround = true;
        return act;
    }

    private static Planner.Plan planOf(Move... moves) {
        return new Planner.Plan(List.of(moves), null, 0);
    }

    /** A bridge over one cell, from (0,64,0) onto (1,64,0), whose floor must be placed at (1,63,0). */
    private static Move bridgeOverCell(int z) {
        return Move.bridge(new Stance(0, 64, z), new Stance(1, 64, z), new Stance(1, 63, z));
    }

    @Test
    public void aPlacementOverWaterIsNotAFloorTheExecutorMayWalkOnto() {
        FakeActuator act = groundedAt(0, 64, 0);
        // The cell the bridge has to fill holds water, and the click that should replace it is
        // issued but lands nothing -- the out-of-reach / nothing-in-hand refusal, modelled.
        act.putBlock(1, 63, 0, "water");
        act.rightClickPlacesBlock = false;

        Move bridge = bridgeOverCell(0);
        RouteExecutor ex = new RouteExecutor(planOf(bridge), 64);

        ActOutcome out = drive(ex, act, List.of(bridge), 200);

        assertTrue("the route must end rather than tick forever", out.terminal());
        assertFalse("water is not a floor this move built, so the route must fail: " + out.message(),
                out.ok());
        assertEquals("and no block may be counted as spent for a placement that did not happen",
                0, ex.blocksSpent());
        assertEquals("and the move must not be credited: the player was never walked onto anything "
                + "this route put there", 0, ex.movesDone());
        assertEquals("the click must still have been retried rather than abandoned after one "
                + "refusal -- water has to be ruled out by the confirmation, not by giving up",
                RouteExecutor.PLACE_RETRIES, act.rightClickCalls);
    }

    @Test
    public void aClickThatReallyReplacesTheWaterIsStillABridge() {
        FakeActuator act = groundedAt(0, 64, 0);
        act.putBlock(1, 63, 0, "water");
        act.rightClickPlacesBlock = true; // the held block replaces the water, as vanilla allows

        Move bridge = bridgeOverCell(0);
        RouteExecutor ex = new RouteExecutor(planOf(bridge), 64);

        ActOutcome out = drive(ex, act, List.of(bridge), 200);

        assertTrue("a placement INTO water is a placement -- the cell changed, and that is the "
                + "identity this confirmation is for: " + out.message(), out.terminal() && out.ok());
        assertEquals("and it costs exactly one block", 1, ex.blocksSpent());
        assertTrue("the block must exist in the world afterwards", act.blockPresent(1, 63, 0));
    }

    /**
     * The other half of the same review: arrival.
     *
     * <p>The old check asked {@code blockPresent} about the cell under the feet, so a player
     * standing one block BELOW the destination -- in a dip, which has a floor of its own -- read as
     * supported and was credited with an arrival the plan never described. The plan said the feet
     * end in y=64; the player's are in y=63, and no amount of "something is under me" makes that
     * true.
     */
    @Test
    public void aPlayerInADipBelowTheDestinationIsNotCreditedWithArrival() {
        FakeActuator act = groundedAt(0, 64, 0);
        act.removeBlock(2, 63, 0);          // a one-block dip on the way
        act.putBlock(2, 62, 0, "stone");    // with a floor of its own, so "supported" cannot tell

        Move walk = Move.walk(new Stance(0, 64, 0), new Stance(2, 64, 0));
        RouteExecutor ex = new RouteExecutor(planOf(walk), 64);

        ActOutcome out = null;
        boolean nudged = false;
        for (int i = 0; i < 200 && (out == null || !out.terminal()); i++) {
            out = ex.tick(act);
            if (!nudged && ex.phase() == RouteExecutor.Phase.WALKING) {
                // 0.3 blocks from the destination's centre horizontally -- inside the tolerance --
                // but one block down, standing on the dip's own floor.
                act.setPosition(2.2D, 63.0D, 0.5D);
                nudged = true;
            }
        }

        assertFalse("the plan put the feet in y=64 and they are in y=63: " + out.message(),
                out.ok());
        assertTrue("and the message must name the height it wanted, because 'did not arrive' alone "
                + "cannot distinguish a dip from a wall: " + out.message(),
                out.message().contains("rather than in cell y=64"));
        assertEquals("no move may be counted", 0, ex.movesDone());
    }

    /**
     * The other side of {@link RouteExecutor#ARRIVE_HEIGHT_SLACK}, and the reason it exists.
     *
     * <p>{@code Stance.isStandable} asks whether the cell below has a collision box, and a bottom
     * slab has one, so a route onto a slab is legitimately PLANNED with the body occupying cell 64.
     * The world then rests the feet at 63.5, on the slab's top surface. An integer equality
     * rejected that arrival, which made the planner emit moves its own executor could never
     * credit — the failure mode where the two halves of one layer disagree.
     *
     * <p>Teeth: with the check reverted to {@code floor(pos[1]) == m.to().y()}, this fails at the
     * {@code assertTrue}, and {@link #aPlayerInADipBelowTheDestinationIsNotCreditedWithArrival}
     * fails if the band is widened to a whole block instead.
     */
    @Test
    public void aRouteOntoAPartialBlockArrivesWithTheFeetOnTheSlabsTopSurface() {
        FakeActuator act = groundedAt(0, 64, 0);
        act.putBlock(2, 63, 0, "stone");
        act.putBlock(2, 63, 0, "stone_slab");  // a top surface at 63.5, not 64

        Move walk = Move.walk(new Stance(0, 64, 0), new Stance(2, 64, 0));
        RouteExecutor ex = new RouteExecutor(planOf(walk), 64);

        ActOutcome out = null;
        boolean placed = false;
        for (int i = 0; i < 200 && (out == null || !out.terminal()); i++) {
            out = ex.tick(act);
            if (!placed && ex.phase() == RouteExecutor.Phase.WALKING) {
                act.setPosition(2.5D, 63.5D, 0.5D);
                act.onGround = true;
                placed = true;
            }
        }

        assertTrue("feet at 63.5 on a slab is arrival, not a dip: " + out.message(), out.ok());
        assertEquals("the move is credited", 1, ex.movesDone());
    }

    /**
     * Drive until terminal or the tick ceiling, standing in for movement the executor does not
     * model: whenever it is steering, put the player where that move ends.
     *
     * <p>The same shape {@code RouteExecutorReportsWhatTheWorldSaysTest} uses, kept local because
     * that one is private to its class. Movement is not what these tests are about; the placement
     * confirmation and the arrival check are, and both are reached with the player simply being
     * where the plan says.
     */
    private static ActOutcome drive(RouteExecutor ex, FakeActuator act, List<Move> moves,
                                    int maxTicks) {
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = ex.tick(act);
            if (last.terminal()) {
                return last;
            }
            if (ex.phase() == RouteExecutor.Phase.WALKING && ex.movesDone() < moves.size()) {
                Stance to = moves.get(ex.movesDone()).to();
                act.setPosition(to.x() + 0.5D, to.y(), to.z() + 0.5D);
                act.onGround = true;
            }
        }
        return last;
    }
}
