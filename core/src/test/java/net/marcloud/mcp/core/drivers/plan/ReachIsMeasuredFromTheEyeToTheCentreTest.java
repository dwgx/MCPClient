package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the placement reach gate at its boundary, and pins the two origins it is measured between.
 *
 * <p>The gate is the server's, measured rather than assumed:
 * {@code getDistanceSq(block centre) < 64.0} in {@code NetHandlerPlayServer:599}. Live confirmation
 * on 2026-08-05 (docs/agency/HANDOFF.md section 3): of eight blocks placed outward, the six
 * within 7.6 blocks stood and the two at 8.6 and 9.6 were reverted with the items returned to the
 * inventory. The client's own {@code getBlockReachDistance()} is 4.5 -- a different, smaller number
 * that would make the planner refuse placements the server accepts.
 *
 * <p>Every assertion here drives a case that distinguishes the correct arithmetic from a specific
 * plausible mistake. A test that only asked about a cell two blocks away would pass for all of them.
 */
public class ReachIsMeasuredFromTheEyeToTheCentreTest {

    /** A player standing with feet at y=64: eye is 1.62 above, per vanilla's getEyeHeight. */
    private static final double EYE_X = 0.5D;
    private static final double EYE_Y = 65.62D;
    private static final double EYE_Z = 0.5D;

    private static boolean reach(int x, int y, int z) {
        return LiveBlockView.withinServerReach(EYE_X, EYE_Y, EYE_Z, x, y, z);
    }

    @Test
    public void aBlockUnderfootIsWellWithinReach() {
        assertTrue("the cell a bridge goes into is the most common placement there is; if this "
                + "failed the planner could not build at all", reach(0, 63, 0));
    }

    @Test
    public void theGateIsEightBlocksNotTheClientsFourAndAHalf() {
        // Horizontally level with the eye, 7 blocks out: inside 8 but well outside 4.5.
        assertTrue("7 blocks out must be reachable. Using the client's 4.5 here would refuse it, and "
                + "the planner would route around gaps it could have bridged in one step",
                reach(7, 65, 0));
    }

    @Test
    public void justPastEightBlocksIsRefused() {
        // dx = 8.5 - 0.5 = 8.0 exactly on the axis, so distSq = 64.0 + the vertical term: outside.
        assertFalse("at and past the gate the server reverts the block and returns the item, so a "
                + "planner that thinks it can place here spends inventory to accomplish nothing",
                reach(8, 65, 0));
    }

    @Test
    public void theBoundaryIsExclusiveAtExactlySixtyFour() {
        // Construct distSq == 64.0 exactly: 8 blocks on one axis, zero on the others. With the eye
        // at x=0.5 and the cell centre at 8.5, dx = 8.0; put the cell at eye height so dy = 0.
        double eyeY = 65.5D; // centre-aligned with the cell below, so dy is exactly 0
        boolean atExactly64 = LiveBlockView.withinServerReach(0.5D, eyeY, 0.5D, 8, 65, 0);
        assertFalse("the server compares with < 64.0, so exactly 64.0 is OUT. An inclusive "
                + "comparison hands the planner a whole shell of cells it can never build",
                atExactly64);
    }

    @Test
    public void distanceIsMeasuredFromTheEyeNotTheFeet() {
        // A cell high above the head. From the EYE it is inside; from the FEET it would be further
        // by 1.62 and this exact cell is chosen so the two answers differ.
        int highY = 73;
        boolean fromEye = LiveBlockView.withinServerReach(EYE_X, EYE_Y, EYE_Z, 0, highY, 0);
        boolean fromFeet = LiveBlockView.withinServerReach(EYE_X, 64.0D, EYE_Z, 0, highY, 0);
        assertTrue("from the eye this cell is inside the gate", fromEye);
        assertFalse("and from the feet it is outside -- so the two origins are NOT interchangeable, "
                + "which is what makes measuring from posY a silent 1.62-block error", fromFeet);
    }

    @Test
    public void distanceIsMeasuredToTheBlockCentreNotItsCorner() {
        // Pick a cell where centre-vs-corner straddles the gate. The corner nearest the player is
        // half a block closer on each axis, so a corner-based test accepts cells the server refuses.
        int x = 5;
        int y = 70;
        int z = 4;
        double centreSq = sq(x + 0.5D - EYE_X) + sq(y + 0.5D - EYE_Y) + sq(z + 0.5D - EYE_Z);
        double cornerSq = sq(x - EYE_X) + sq(y - EYE_Y) + sq(z - EYE_Z);
        assertTrue("fixture check: this cell must straddle the gate, or the assertion below proves "
                + "nothing. centreSq=" + centreSq + " cornerSq=" + cornerSq,
                cornerSq < LiveBlockView.SERVER_REACH_SQ
                        && centreSq >= LiveBlockView.SERVER_REACH_SQ);
        assertFalse("the server measures to the CENTRE, so this cell is out of reach even though its "
                + "nearest corner is not. A corner-based gate would place blocks the server reverts",
                reach(x, y, z));
    }

    private static double sq(double v) {
        return v * v;
    }
}
