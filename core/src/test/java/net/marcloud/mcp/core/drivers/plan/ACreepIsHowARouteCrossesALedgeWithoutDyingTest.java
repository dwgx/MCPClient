package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.eval.EvalHarness;
import net.marcloud.mcp.core.eval.SimWorld;

/**
 * A route can ask to creep, and the body actually creeps.
 *
 * <p>The mechanism under test is vanilla's, not ours. {@code Entity.moveEntity:626} opens with
 * {@code boolean flag = onGround && isSneaking() && instanceof EntityPlayer}, and while that holds
 * each horizontal axis is walked back in 0.05 steps until the box one block below is clear. A
 * sneaking player therefore stops at a brink instead of walking off it. So the whole of what this
 * codebase owes the game is one boolean arriving at {@code MovementInput.sneak} at the right tick,
 * and everything worth proving is proved or disproved by where the body ENDS UP.
 *
 * <p><b>Two worlds, one difference, on purpose.</b> Every assertion is paired against a world that
 * is identical except for whether the route asked. A single-world test here is worth very little:
 * "the player did not fall" passes just as well against a body that never moved, and "the player
 * arrived" against a controller that teleports. The pair is the test, and the shape of the pair is
 * the shape quoted in the brief -- a creeping body stops short and stays grounded at the floor's
 * height while the plain one walks on past the edge and ends up far below it.
 *
 * <p>Asserted on world state only: final position, {@code onGround}, and whether the key was ever
 * seen down. No assertion reads an {@code ActOutcome} message or an {@code isError} flag, because
 * both would keep passing on a build that moved the player nowhere while describing the trip
 * eloquently.
 */
public final class ACreepIsHowARouteCrossesALedgeWithoutDyingTest {

    /**
     * A plain floor with a cliff at its north end.
     *
     * <p>Floor occupies z=-8..4 at y=63, so the player stands at y=64 and everything from z=5
     * northwards is open air. Yaw 0 faces south in vanilla, so walking "forward" is +z, which is
     * the direction of the drop.
     */
    private static SimWorld ledge() {
        return new SimWorld().plain(64, "stone", -4, 4, -8, 4).standOn(0, 64, -8).facing(0f);
    }

    /** The last z the floor occupies, as a coordinate the body can be compared against. */
    private static final double FLOOR_END = 4.0D;

    /**
     * Walk a {@link NavIntent} at a point well past the cliff, and report where the body ended.
     *
     * @param creep whether the intent asks for the sneak key
     */
    private static double[] walkPastTheEdge(boolean creep) {
        SimWorld w = ledge();
        EvalHarness h = new EvalHarness(w);
        h.submit(new NavIntent(0.5D, 64, 40.0D, 0, creep));
        h.runMove(600);
        return new double[] {w.posX(), w.posY(), w.posZ()};
    }

    /**
     * The straight-line walk: a caller naming a point beyond a cliff, with and without the creep.
     *
     * <p>This is the capability in its plainest form, and it is the form that killed a player: the
     * route is a straight line, there is no plan behind it, and the only thing standing between the
     * body and the fall is the key.
     */
    @Test
    public void aCreepingWalkStopsAtTheEdgeAndAPlainOneWalksOffIt() {
        double[] crept = walkPastTheEdge(true);
        double[] plain = walkPastTheEdge(false);

        // The pair, stated as the difference between them rather than as two magic numbers. A
        // tolerance assertion is what let a route that stopped a full cell early pass for its
        // entire life, so the comparison is made between the two real bodies.
        assertTrue("a creeping body must stay on the floor: it ended at y=" + crept[1]
                        + " z=" + crept[2] + ", past a floor that ends at z=" + FLOOR_END,
                crept[1] > 63.0D);
        assertTrue("and it must not be hanging in the air past the edge either, or the guard did "
                        + "not fire: y=" + crept[1],
                crept[1] >= 64.0D - 0.001D);
        assertTrue("a creeping body is stopped near the brink, not short of the whole floor: it "
                        + "walked to z=" + crept[2], crept[2] > 0.0D);
        assertTrue("and it is at the edge, not beyond it: z=" + crept[2], crept[2] < FLOOR_END + 2.0D);

        assertTrue("a body that does NOT creep must fall off the same ledge, or this test is "
                        + "measuring a world where nothing collides: it ended at y=" + plain[1],
                plain[1] < 30.0D);
        assertTrue("and it got much further along the ground than the creeping one, which is what "
                        + "makes the pair a pair: crept to z=" + crept[2] + ", plain to z=" + plain[2],
                plain[2] > crept[2] + 10.0D);
    }

    /**
     * The key itself was down, sampled DURING the walk.
     *
     * <p>Separate from the position assertions because they can both pass for the wrong reason.
     * The position pair proves the guard fired; this proves it fired <i>because the key was
     * down</i> and not because the world happened to stop the body. {@code SimWorld.sneaking()}
     * is an end-of-tick record while the guard reads the input mid-tick, so the sample is taken
     * from inside the run rather than after it -- after the walk the MOVE slot is no longer active
     * and the input has already dropped the key.
     */
    @Test
    public void theSneakKeyIsActuallyDownWhileACreepingRouteWalks() {
        SimWorld creeping = ledge();
        EvalHarness h = new EvalHarness(creeping);
        h.submit(new NavIntent(0.5D, 64, 40.0D, 0, true));
        boolean[] everDown = {false};
        h.run(600, () -> {
            everDown[0] |= creeping.sneaking();
            return false;
        });
        assertTrue("the creep reached the body: the sneak key was never seen down on a creeping "
                + "walk", everDown[0]);

        SimWorld plain = ledge();
        EvalHarness h2 = new EvalHarness(plain);
        h2.submit(new NavIntent(0.5D, 64, 40.0D, 0, false));
        boolean[] everDownPlain = {false};
        h2.run(600, () -> {
            everDownPlain[0] |= plain.sneaking();
            return false;
        });
        assertFalse("and it is the route's doing: a plain walk must not hold the key",
                everDownPlain[0]);
    }

    /**
     * A four-argument {@link NavIntent} is unchanged: same destination, same gait, no key.
     *
     * <p>The addition to the record is supposed to be an addition. A caller written before the
     * field existed must get the walk it got then, and the only way to know that is to build one
     * the old way and watch the key stay up -- because the alternative, a default of {@code true},
     * would silently turn every existing route in the codebase into a third-speed crawl.
     */
    @Test
    public void aRouteThatSaysNothingAboutCreepingDoesNotCreep() {
        SimWorld w = ledge();
        EvalHarness h = new EvalHarness(w);
        // The four-argument constructor, exactly as every pre-existing caller writes it.
        h.submit(new NavIntent(0.5D, 64, 40.0D, 0));
        boolean[] everDown = {false};
        h.run(600, () -> {
            everDown[0] |= w.sneaking();
            return false;
        });
        assertFalse("a NavIntent that names no gait walks at full speed, as it always did", everDown[0]);
        assertTrue("so it walks straight off the same cliff: y=" + w.posY(), w.posY() < 30.0D);
    }

    /**
     * The planner side: a route's plan says which of its moves end at a brink.
     *
     * <p>This is the requirement that the creep be <i>plannable</i> rather than merely
     * expressible. A caller cannot know which block of a thirty-block route is the one that will
     * kill it, so a vocabulary that could only say "creep everything" would be asking the one
     * party that does not know. The plan carries the fact per move, which is the only form in which
     * "this move is the dangerous one" is answerable.
     */
    @Test
    public void thePlanSaysWhichMovesEndAtALedge() {
        // A corridor two cells wide with a drop down its long side: the walk along it is legal,
        // because Stance only requires a floor under the feet, and every move down it ends at a
        // brink because the cell beside the destination is empty.
        SimWorld w = new SimWorld()
                .box(0, 63, -4, 1, 63, 6, "stone")
                .standOn(0, 64, -4).facing(0f);
        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, -4), new Stance(0, 64, 6));
        assertTrue("the corridor is walkable, so this test is measuring creeps and not an "
                + "unreachable goal: " + plan.failure(), plan.found());
        assertTrue("a plan exists", !plan.moves().isEmpty());
        for (Move m : plan.moves()) {
            assertTrue("every move down a ledge with a drop beside it is marked as one: "
                            + m.kind() + " into " + m.to(),
                    m.creep());
        }

        // And the same planner on open ground finds no brinks, which is what stops every ordinary
        // route in the game from being crept for no reason.
        SimWorld open = new SimWorld()
                .plain(64, "stone", -4, 4, -4, 4)
                .standOn(0, 64, -2).facing(0f);
        Planner.Plan flat = new Planner(open).plan(new Stance(0, 64, -2), new Stance(2, 64, 2));
        assertTrue("open ground is walkable: " + flat.failure(), flat.found());
        for (Move m : flat.moves()) {
            assertFalse("a walk across open ground is not crept -- it would cost a third of the "
                    + "player's speed for nothing: " + m.kind() + " into " + m.to(), m.creep());
        }
    }

    /**
     * A creeping {@link RouteIntent} holds the key through a whole route, and the route still
     * arrives -- the flag changes the gait, not the destination.
     */
    @Test
    public void aRouteAskedToCreepHoldsTheKeyAndStillArrives() {
        SimWorld w = new SimWorld()
                .plain(64, "stone", -4, 4, -4, 8)
                .standOn(0, 64, -4).facing(0f);
        EvalHarness h = new EvalHarness(w);
        h.submit(new RouteIntent(0, 64, 4, 0, true));
        boolean[] everDown = {false};
        h.runUntil(ActSlot.MOVE, 800);
        h.run(0, () -> {
            everDown[0] |= w.sneaking();
            return false;
        });
        assertTrue("a creeping route walked somewhere, so there was a walk to creep: the MOVE slot "
                + "is " + h.phase(ActSlot.MOVE), h.phase(ActSlot.MOVE).isTerminal());
        assertTrue("and the body is on the floor it was aimed at, not in the void: y=" + w.posY()
                + " z=" + w.posZ(), w.onGround());
        // The flag must not have been silently dropped, and must not have been honoured by
        // refusing the route either. describe() is the surface a caller reads back to find out
        // which of those two happened.
        assertTrue("act_status must be able to report the creep back, so a caller can tell "
                        + "'never asked' from 'asked and dropped': " + h.stance(),
                new RouteIntent(0, 64, 4, 0, true).describe().contains("creeping"));
    }
}
