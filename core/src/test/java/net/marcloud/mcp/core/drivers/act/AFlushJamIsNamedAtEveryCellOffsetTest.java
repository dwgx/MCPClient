package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import org.junit.Test;

/**
 * A body stopped by a wall has to be able to say which wall.
 *
 * <p><b>The defect.</b> {@code NavController.readJam} skipped a cell when the body box's reach into
 * it was negative and accepted a reach of exactly zero as contact, while the collision model it is
 * reading the world through ({@code BodySim}, and vanilla's {@code AxisAlignedBB.intersectsWith})
 * refuses to block on anything short of a positive overlap. The two disagree about the one position
 * that matters: a body that has walked up to a wall. A body that halts a few times 1e-15 short of a
 * face is correctly "not intersecting" per the physics, and was then reported by the jam read as
 * having nothing readable in its box at all -- {@code THE_UNNAMED_STALL}, a capability claim about
 * a block the body is touching.
 *
 * <p><b>And zero was not the size of the hole.</b> The reported spans were 2.2e-16 to 7.1e-15, which
 * reads like accumulated rounding, so the obvious repair is an epsilon. It is not enough. A body
 * halts at the last position from which one more tick of travel would not have crossed, so it stops
 * short by whatever the step lattice leaves -- measured below, a full {@code 0.20} blocks at cells 4
 * to 9, where the previous accepted position put its face at {@code wall - 0.2} and the next
 * {@code +0.2} step would have crossed. {@link NavController#JAM_REACH} is that one tick of travel
 * and this file pins the consequence: at every cell offset from 1 to 12 the block is named, and at
 * the six offsets the original report listed the halt positions and reaches are asserted to the
 * digit, so a repair that quietly returns to an epsilon fails here rather than in the field.
 *
 * <p><b>Why a view-less walk.</b> {@code readJam} is a private method, and a test that reached it
 * by reflection would pin the arithmetic and not the claim. Every case here drives a real
 * {@link BodySim} into a real wall and reads the verdict a caller reads. A stance walk built
 * without a {@link Standable} cannot recover -- {@code pickSide} has no world to ask -- so the walk
 * terminates on the first thing the jam read found, and that thing is the answer under test.
 *
 * <p><b>And why the negative cases are here too.</b> A tolerance that widens the jam read also
 * widens what the controller is willing to call an obstruction, and inventing one is a worse defect
 * than losing one: the body walks around a block that is not there, and the caller is told a route
 * is blocked by it. The last three tests are the other direction of the same bound.
 */
public class AFlushJamIsNamedAtEveryCellOffsetTest {

    private static final int GROUND = 63;
    private static final int FEET = 64;

    /**
     * The halt positions and reaches measured by driving {@link BodySim} east into a wall, quoted
     * from the run rather than computed, so the file states what the physics does instead of
     * restating it. Index 0 is the wall's cell, 1 the body's X when it stopped, 2 how far its box
     * reached into the wall's cell.
     *
     * <p>Two families, and both had to be fixed: exact zeros (cells 1, 3, 10), rounding-scale
     * negatives (cells 2, 11, 12) and full-tick negatives (cells 4 to 9). The first family is the
     * one the old predicate accepted, which is why the pinned digest did not move.
     */
    private static final double[][] MEASURED = {
        {1, 0.70000000000000000D, 0.0D},
        {2, 1.6999999999999997D, -2.220446049250313E-16D},
        {3, 2.7000000000000000D, 0.0D},
        {4, 3.500000000000001D, -0.1999999999999993D},
        {5, 4.500000000000002D, -0.1999999999999984D},
        {6, 5.500000000000003D, -0.1999999999999975D},
        {7, 6.5000000000000036D, -0.19999999999999662D},
        {8, 7.500000000000004D, -0.19999999999999574D},
        {9, 8.500000000000004D, -0.19999999999999574D},
        {10, 9.7000000000000000D, 0.0D},
        {11, 10.699999999999996D, -3.552713678800501E-15D},
        {12, 11.699999999999992D, -7.105427357601002E-15D},
    };

    /** A flat corridor with one log in it, and the body at the west end facing east. */
    private static FakeActuator corridorWithALogAt(int wallX) {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 30; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z, "minecraft:stone");
            }
        }
        act.putBlock(wallX, FEET, 0, "minecraft:log");
        act.putBlock(wallX, FEET + 1, 0, "minecraft:log");
        act.setPosition(-0.5D, FEET, 0.5D);
        act.onGround = true;
        act.yaw = 90f;
        return act;
    }

    /** How far the body box at this X reaches into the cell {@code [cell, cell+1]}. */
    private static double reachInto(FakeActuator act, int cell) {
        double lo = act.pos[0] - NavController.BODY_HALF;
        double hi = act.pos[0] + NavController.BODY_HALF;
        return Math.min(hi, cell + 1.0D) - Math.max(lo, cell);
    }

    /** Tick until terminal, moving the body the way the axes ask. */
    private static ActOutcome drive(NavController nav, FakeActuator act) {
        ActOutcome last = null;
        for (int i = 0; i < 200; i++) {
            last = nav.tick(act);
            if (last.terminal()) {
                return last;
            }
            BodySim.step(act, nav.forward(), nav.strafe());
        }
        return last;
    }

    /**
     * The primary property, at every cell offset: the body is stopped and the block it is stopped
     * BY is named, so the verdict is a survey that failed rather than a capability limit.
     *
     * <p>Every row is asserted, not a sample of them. A predicate that works at cell 3 and fails at
     * cell 4 is not a predicate, and the pinned digest could not see the difference because its
     * only jam sits at cell 3.
     */
    @Test
    public void aBodyShortOfAWallByAnyAmountIsStillNamingTheWall() {
        for (double[] row : MEASURED) {
            int wall = (int) row[0];
            FakeActuator act = corridorWithALogAt(wall);
            NavController nav = NavController.toStance(20.5D, FEET, 0.5D, 200);

            ActOutcome out = drive(nav, act);

            String where = " (wall at cell " + wall + ", body halted at x=" + act.pos[0]
                    + ", reach " + reachInto(act, wall) + ")";
            assertTrue("the walk must end rather than press into the wall forever" + where,
                    out != null && out.terminal());
            assertFalse("and it must not claim arrival" + where, out.ok());
            assertEquals("a body stopped by a block it is touching has NAMED that block, so the "
                    + "verdict is a survey that could not recover, not an unnamed stall" + where,
                    MoveTactic.GivenUp.THE_UNSURVEYED_WEDGE, nav.tactic().givenUp());
            assertTrue("and the message must name the block and its cell, because that is the part "
                    + "a caller can act on" + where,
                    out.message().contains("log") && out.message().contains("(" + wall + "," + FEET + ",0)"));
        }
    }

    /**
     * The measured numbers themselves, so the file says what the physics does.
     *
     * <p>Pinned to the digit because the size of the reach is the whole argument: a repair sized to
     * the reported 7.1e-15 passes cells 2, 11 and 12 and fails 4 to 9, and the only way a reader
     * can tell those two repairs apart is from the numbers.
     */
    @Test
    public void theHaltPositionsAreTheOnesTheReachWasSizedFrom() {
        for (double[] row : MEASURED) {
            int wall = (int) row[0];
            FakeActuator act = corridorWithALogAt(wall);
            drive(NavController.toStance(20.5D, FEET, 0.5D, 200), act);

            assertEquals("the body halts where the step lattice puts it, and that is a fact about "
                    + "the physics rather than about this controller", row[1], act.pos[0], 0.0D);
            assertEquals("and it stops short of the face by exactly what is left of the step",
                    row[2], reachInto(act, wall), 0.0D);
        }
    }

    /**
     * The reach is one tick of travel and not more, and this is the side of the bound that keeps
     * the controller honest: a block it cannot reach is not what stopped this body.
     *
     * <p>The body is stopped by WATER at cell 3, which the jam read excludes by name -- a
     * deliberate taxonomy, documented on {@code readJam}, and the reason the honest verdict here is
     * the unnamed stall rather than a named obstruction. The log at cell 4 is a full block beyond
     * the body's leading face and stopped nothing at all. A reach widened to a cell would scan it,
     * find a block, and tell the caller the route is blocked by a log the body never touched: the
     * body would then walk around it, and the message a human reads to debug a route failure would
     * be about a block that is not in the way. Inventing an obstruction is worse than losing one.
     */
    @Test
    public void aBlockTheBodyIsNotAgainstIsNotNamed() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 30; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z, "minecraft:stone");
            }
        }
        act.putBlock(3, FEET, 0, "minecraft:water");
        act.putBlock(3, FEET + 1, 0, "minecraft:water");
        act.putBlock(4, FEET, 0, "minecraft:log");
        act.setPosition(-0.5D, FEET, 0.5D);
        act.onGround = true;
        act.yaw = 90f;
        NavController nav = NavController.toStance(20.5D, FEET, 0.5D, 200);

        ActOutcome out = drive(nav, act);

        assertEquals("premise: the body is stopped by the water at cell 3 and its box is flush "
                        + "with that cell's face", 3, (int) Math.floor(act.pos[0] + NavController.BODY_HALF));
        assertEquals("and water is excluded from the jam read by name, so nothing readable is "
                        + "inside the box and the verdict is the unnamed stall",
                MoveTactic.GivenUp.THE_UNNAMED_STALL, nav.tactic().givenUp());
        assertFalse("the log at cell 4 is a whole block beyond the body's leading face and stopped "
                + "nothing, so naming it would hand the caller a route obstruction that does not "
                + "exist: " + out.message(), out.message().contains("log"));
    }

    /**
     * Vertical contact is still not contact, and widening the horizontal reach must not have
     * widened it.
     *
     * <p>The floor is directly under the body and directly under every part of its box. A reach
     * applied to Y would name the ground the body is standing on as the thing in the way, which is
     * the same invented certainty the horizontal case is about.
     */
    @Test
    public void theFloorTheBodyStandsOnIsStillNotAJam() {
        FakeActuator act = corridorWithALogAt(3);
        NavController nav = NavController.toStance(20.5D, FEET, 0.5D, 200);

        ActOutcome out = drive(nav, act);

        assertTrue("the walk must name the log it is stopped by: " + out.message(),
                out.message().contains("log") && out.message().contains("(3," + FEET + ",0)"));
        assertFalse("and never the stone floor at y=" + GROUND + ", which is under the body and not "
                + "in its way: " + out.message(),
                out.message().contains("stone") || out.message().contains("(" + GROUND + ","));
    }

    /**
     * A cell only the reach can see never outranks one the box measurably reaches into.
     *
     * <p>This is the failure the reach itself introduces, so it is pinned rather than left to the
     * ranking to be trusted about, and the geometry is built to make the two rules disagree. The
     * body stands at (3.50,3.00) on a south-east bearing with its box on x[3.20,3.80]: the
     * cobblestone at (3,64,2) is 0.30 blocks INSIDE that box and lies BEHIND the bearing, and the
     * planks at (4,64,3) lie 0.20 blocks AHEAD of the box's face and are ahead on the bearing. The
     * old ranking put "ahead" above everything, so it named the planks -- a block the body cannot
     * touch and that stopped nothing. A measurement has to outrank a guess.
     */
    @Test
    public void aMeasuredOverlapOutranksACellOnlyTheReachCanSee() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 30; x++) {
            for (int z = -2; z <= 30; z++) {
                act.putBlock(x, GROUND, z, "minecraft:stone");
            }
        }
        act.putBlock(3, FEET, 2, "minecraft:cobblestone");
        act.putBlock(4, FEET, 3, "minecraft:planks");
        act.setPosition(3.5D, FEET, 3.0D);
        act.onGround = true;
        act.yaw = 90f;
        act.collidedHorizontally = true;
        NavController nav = NavController.toStance(20.5D, FEET, 20.5D, 200);

        ActOutcome out = null;
        for (int i = 0; i < 40 && (out == null || !out.terminal()); i++) {
            out = nav.tick(act);
            // No BodySim.step: the body is frozen where a jammed one stands, so the read happens on
            // the position the assertions are about.
        }

        assertTrue("the walk must end", out != null && out.terminal());
        assertTrue("and name the cobblestone the box is measurably inside: " + out.message(),
                out.message().contains("cobblestone") && out.message().contains("(3," + FEET + ",2)"));
        assertFalse("and not the planks, which are ahead on the bearing but 0.20 blocks beyond the "
                + "box's face and stop nothing: " + out.message(), out.message().contains("planks"));
        assertTrue("and the reach must be reported rather than clamped away: a body 0.30 blocks "
                        + "into a block has reached into it. Message was: " + out.message(),
                out.message().contains("reaching 0.30 blocks"));
    }
}
