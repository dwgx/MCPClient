package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Lava is never a cell a body may occupy, and both halves of the repo say so with the same codes.
 *
 * <p><b>The defect.</b> {@code LiveBlockView.isPassable} returned {@code isEmptySpace()} -- "has no
 * collision box" -- and {@code BlockProbe} folds every block without one into air. Lava has no
 * collision box, so the planner read it as empty space and routed the player through it: the read
 * side had told the model {@code -2} for the same cell since {@code LocalGrid} was written. The
 * consequences are the two the tests below drive -- a corridor whose only open cell is lava is
 * WALKED through, and a goal inside lava is accepted as a destination.
 *
 * <p><b>What is asserted, and why each one is load-bearing.</b> A route across lava must not be
 * found (the walk case); a goal in lava must be refused and the refusal must name the hazard (the
 * caller's next action differs from "there is a block in the way"); water must still be room, or
 * the fix has simply banned everything that is not air and every pond becomes a detour; and a
 * bridge must not be planned into lava even though the WORLD accepts a block there, which is the
 * one case where "is it legal to place" and "should this plan place it" come apart.
 *
 * <p>The fakes answer in {@link BlockView}'s verdict vocabulary rather than solid/not-solid, because
 * a fake that could only say "solid" cannot tell water from lava and every assertion here would hold
 * for both.
 */
public class LavaIsNeverPassableToARouteTest {

    /**
     * A SEALED chamber: floor at y=63 over x=-4..12 / z=-4..4, and solid walls all the way round
     * at x=-5, x=13, z=-5 and z=5, two blocks high. The interior is open air.
     *
     * <p>Two earlier versions of this file built a world that did not match its own description,
     * and both failed for the same reason: the planner found a legitimate way around and the test
     * had asserted "no route" anyway. One walled only z=+/-1, leaving z=+/-2..4 open. The other
     * walled every z but left the corridor's ENDS open, and a corridor with open ends is a
     * hallway the player can walk out of — the planner did exactly that, then bridged across
     * ordinary void to come back. Both worlds had a real answer; the assertions were the fiction.
     *
     * <p>That is the failure mode this file exists to prevent, committed in the tests written to
     * catch it, which is why the sealing is explicit and commented rather than implied by a
     * rectangle.
     */
    private static FakeWorld sealedChamber() {
        FakeWorld w = new FakeWorld(32).floor(-4, 12, 63, -4, 4);
        for (int x = -5; x <= 13; x++) {
            for (int z = -5; z <= 5; z++) {
                boolean onWallRing = (x == -5 || x == 13 || z == -5 || z == 5);
                if (!onWallRing) {
                    continue;
                }
                for (int dy = 0; dy < Stance.BODY_HEIGHT; dy++) {
                    w.solid(x, 64 + dy, z);
                }
            }
        }
        return w;
    }

    @Test
    public void waterIsRoomAndLavaIsNot() {
        FakeWorld w = new FakeWorld(0);
        w.water(1, 64, 0);
        w.lava(2, 64, 0);

        assertTrue("water is a cell a body may occupy: wet, not fatal",
                new Stance(1, 64, 0).hasRoom(w));
        assertFalse("lava is not -- and a solidity-only view of the world cannot tell: lava has no "
                + "collision box, so the old isPassable called it empty space",
                new Stance(2, 64, 0).hasRoom(w));
        assertEquals("and the codes are the read side's, so the half that reports terrain and the "
                + "half that routes over it cannot disagree about what the agent was told",
                BlockView.WALK_LAVA, w.walkVerdict(2, 64, 0));
        assertEquals(BlockView.WALK_WATER, w.walkVerdict(1, 64, 0));
    }

    @Test
    public void aWallOfLavaAcrossTheWholeChamberHasNoRoute() {
        FakeWorld w = sealedChamber();
        for (int z = -4; z <= 4; z++) {
            w.lava(2, 64, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertFalse("the chamber is sealed and every cell the player would have to occupy at x=2 "
                + "is lava, so there is no route through it -- the old planner walked it: "
                + plan.failure(), plan.found());
    }

    @Test
    public void theSameWallOfWaterIsAWalkableRoute() {
        FakeWorld w = sealedChamber();
        for (int z = -4; z <= 4; z++) {
            w.water(2, 64, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertTrue("water IS room. A fix that made every non-air cell impassable would refuse every "
                + "pond and every stream, which is not the rule either half of the repo states -- "
                + "and vanilla's own verdict answers 2, not a refusal, for water when avoidWater is "
                + "false: " + plan.failure(), plan.found());
        assertEquals("and it is crossed by swimming, not by building", 0, plan.blocksNeeded());
    }

    @Test
    public void aGoalInsideLavaIsRefusedAndTheReasonNamesLava() {
        FakeWorld w = new FakeWorld(32).floor(-4, 12, 63, -4, 4);
        w.lava(4, 64, 0);

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertFalse("a destination the body cannot occupy is not a destination: " + plan.failure(),
                plan.found());
        assertNotNull("and the refusal must say why", plan.failure());
        assertTrue("'no room for a body' sends the caller looking for a block, and there is no "
                + "block here -- there is lava, which is a different next action: " + plan.failure(),
                plan.failure().contains("LAVA"));
        assertEquals("and it must be refused before the search, like the start check: sending 20000 "
                + "states after a goal that can never be expanded is waste, not diligence",
                0, plan.expansions());
    }

    /**
     * The bridge refusal, isolated from the body-cell refusal.
     *
     * <p>The cells the player would OCCUPY at x=2 are plain air here — so {@code hasRoom} is
     * satisfied and nothing is refused for the wrong reason. Only the FLOOR is lava, which is the
     * one case {@code hasRoom} cannot catch and {@code NeighborGen.addBridge} must: the world
     * would accept a block placed into lava (vanilla's liquid materials are replaceable, so
     * {@code canPlaceAt} still says so), and the placement that would make the crossing safe can
     * be refused by the server once the route has already committed to it.
     */
    @Test
    public void aGapWhoseOnlyFloorIsLavaIsNotBridged() {
        FakeWorld w = sealedChamber();
        for (int z = -4; z <= 4; z++) {
            w.air(2, 63, z);
            w.lava(2, 63, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        if (plan.found()) {
            assertEquals("a route whose FLOOR is the hazard is the defect: going around is correct "
                    + "and costs nothing, bridging makes the hazard the thing the player stands "
                    + "on. plan=" + plan, 0, plan.blocksNeeded());
        } else {
            assertNotNull("and a refusal must say why: " + plan.failure(), plan.failure());
        }
    }

    /**
     * The control, and the reason the pair is worth having: the same chamber with the same gap
     * over ordinary void. A fix that simply stopped bridging would turn this red alongside the
     * lava case, and would leave the agent unable to cross a one-wide ravine at all.
     */
    @Test
    public void theSameGapOverPlainVoidIsBridged() {
        FakeWorld w = sealedChamber();
        for (int z = -4; z <= 4; z++) {
            w.air(2, 63, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertTrue("bridging must survive the lava refusal: " + plan.failure(), plan.found());
        assertEquals("one cell wide, so one block spent", 1, plan.blocksNeeded());
    }
}
