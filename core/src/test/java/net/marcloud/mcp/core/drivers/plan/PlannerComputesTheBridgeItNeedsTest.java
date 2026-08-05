package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * The planner must CROSS a gap by computing a bridge, never by knowing about bridges.
 *
 * <p>This is the difference the owner asked for on 2026-08-05. The earlier framing -- implement
 * telly / god / ninja and let the owner pick one -- was built on a requirement nobody stated (see
 * the correction banner atop docs/agency/telly-test-plan.md). What is wanted is an AI that works out
 * what it needs and does it. So the assertions below never mention a technique: they build terrain
 * with a hole in it, ask for a route, and require that a bridge appears in the answer because the
 * search chose it.
 *
 * <p>The live measurement supports that framing rather than merely permitting it: the jump in those
 * techniques buys no reach at all (telly-test-plan section 7.5 -- every cell gained at the apex is in
 * the body's own layer, and the 8-block reach sphere is eye-centred so raising it moves reach AWAY
 * from where a bridge goes). Those shapes exist to beat a human click-rate ceiling that a 20Hz code
 * loop does not have.
 */
public class PlannerComputesTheBridgeItNeedsTest {

    /** Flat ground at y=63 means stances at y=64. */
    private static FakeWorld flatGround(int budget) {
        return new FakeWorld(budget).floor(-4, 20, 63, -4, 4);
    }

    /**
     * Pins {@link Stance#isCardinalNeighbourOf} at its boundary, directly.
     *
     * <p>Added because widening it from {@code == 1} to {@code <= 2} SURVIVED the whole suite above.
     * That is not a coverage hole in those tests: the planner only ever asks it about two floor
     * cells one cardinal step apart, so the widened cases are unreachable from every current caller
     * -- the same shape as {@code BlockFinder.nameAt} discarding air, where the real guarantee lived
     * elsewhere (handoff-2026-08-07 section 3).
     *
     * <p>It is pinned anyway because the method is PUBLIC and its contract is what makes the bridge
     * chain's face exemption sound. A future caller passing a diagonal would silently get "there is
     * a face to click" for a cell nothing touches, and the failure would appear as a controller
     * aiming at air -- far from this file. A defensive guard nobody can exercise is exactly the kind
     * this repo has repeatedly found to be inert.
     */
    @Test
    public void faceAdjacencyIsExactlyOneStepAndNotADiagonal() {
        Stance c = new Stance(4, 63, 4);
        assertTrue("+x shares a face", c.isCardinalNeighbourOf(new Stance(5, 63, 4)));
        assertTrue("-z shares a face", c.isCardinalNeighbourOf(new Stance(4, 63, 3)));
        assertTrue("above shares a face", c.isCardinalNeighbourOf(new Stance(4, 64, 4)));
        assertFalse("a horizontal diagonal touches only an edge, so there is no face to click and "
                + "the bridge chain's exemption must not fire for it",
                c.isCardinalNeighbourOf(new Stance(5, 63, 5)));
        assertFalse("a vertical diagonal is the same story",
                c.isCardinalNeighbourOf(new Stance(5, 64, 4)));
        assertFalse("the cell is not its own neighbour: treating it as one would make every "
                + "placement self-supporting", c.isCardinalNeighbourOf(c));
        assertFalse("two steps away shares nothing",
                c.isCardinalNeighbourOf(new Stance(6, 63, 4)));
    }

    @Test
    public void aClearWalkNeedsNoBlocksAtAll() {
        FakeWorld w = flatGround(64);
        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertTrue("flat ground must be walkable: " + plan.failure(), plan.found());
        assertEquals("four steps for four blocks of ground", 4, plan.moves().size());
        assertEquals("and it must not spend a single block walking on ground that exists -- a "
                + "planner that bridges what it could walk burns an inventory for nothing",
                0, plan.blocksNeeded());
        for (Move m : plan.moves()) {
            assertEquals("every move on flat ground is a walk", Move.Kind.WALK, m.kind());
        }
    }

    @Test
    public void aOneBlockHoleIsBridgedBecauseThereIsNoWayAround() {
        // A trench spanning the full z-width: no detour exists, so the only route is over it.
        FakeWorld w = flatGround(64);
        for (int z = -4; z <= 4; z++) {
            w.air(2, 63, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertTrue("a bridgeable trench must not defeat the planner: " + plan.failure(),
                plan.found());
        assertEquals("exactly one block is needed for a one-wide trench", 1, plan.blocksNeeded());
        Move bridge = plan.moves().stream()
                .filter(m -> m.kind() == Move.Kind.BRIDGE).findFirst().orElse(null);
        assertNotNull("the crossing must be a BRIDGE move; if the planner reported success without "
                + "one it found a route that does not exist", bridge);
        assertEquals("the block goes UNDER the destination's feet, which is where a floor is",
                new Stance(2, 63, 0), bridge.placeCell());
    }

    @Test
    public void theBridgeChainAttachesEachBlockToTheLastOne() {
        // Three-wide trench: the second and third placements have no ground neighbour, so they are
        // only aimable because the previous placed block is there. This is the property that makes
        // bridging a sequence rather than a fill.
        FakeWorld w = flatGround(64);
        for (int z = -4; z <= 4; z++) {
            w.air(2, 63, z).air(3, 63, z).air(4, 63, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(6, 64, 0));

        assertTrue("a three-wide trench is crossable one block at a time: " + plan.failure(),
                plan.found());
        assertEquals("three placements for three missing floor cells", 3, plan.blocksNeeded());

        List<Move> bridges = plan.moves().stream()
                .filter(m -> m.kind() == Move.Kind.BRIDGE).toList();
        assertEquals("each gap cell is its own move", 3, bridges.size());
        assertEquals("and they run outward in order, because each one is the face the next attaches "
                + "to -- a plan that placed them in any other order could not be executed",
                List.of(new Stance(2, 63, 0), new Stance(3, 63, 0), new Stance(4, 63, 0)),
                bridges.stream().map(Move::placeCell).toList());

        // The plan must actually be buildable, not merely well-formed: replay it into the world.
        w.applyPlacements(plan.moves());
        for (Move m : plan.moves()) {
            assertTrue("after replaying the placements, every stance on the route stands up: "
                    + m.to(), m.to().isStandable(w));
        }
    }

    @Test
    public void aShortDetourIsPreferredToSpendingABlock() {
        // A one-cell notch: walking around costs 2 extra walks, bridging costs one block. The cost
        // policy says a block is worth more than a short detour, so the detour must win.
        FakeWorld w = flatGround(64).air(2, 63, 0);

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertTrue("there is a way around: " + plan.failure(), plan.found());
        assertEquals("a block is a consumable and a detour is not, so the planner must walk round a "
                + "single notch rather than fill it", 0, plan.blocksNeeded());
        assertTrue("and the detour must actually leave the straight line",
                plan.moves().stream().anyMatch(m -> m.to().z() != 0));
    }

    @Test
    public void anImpossibleGoalFailsHonestlyAndNamesWhy() {
        // A trench too wide for the budget: one block, three cells missing.
        FakeWorld w = flatGround(1);
        for (int z = -4; z <= 4; z++) {
            w.air(2, 63, z).air(3, 63, z).air(4, 63, z);
        }

        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(6, 64, 0));

        assertFalse("one block cannot span three cells, and claiming otherwise would strand the "
                + "player over a void mid-plan", plan.found());
        assertNotNull("the failure must say something", plan.failure());
        assertTrue("and it must name the budget, because that is the fact the caller can act on "
                + "(carry more blocks) -- 'no path' alone sends them looking at the terrain: "
                + plan.failure(),
                plan.failure().contains("block"));
    }

    @Test
    public void aStartThatIsNotStandableIsRefusedBeforeAnySearch() {
        FakeWorld w = flatGround(64);
        Planner.Plan plan = new Planner(w).plan(new Stance(0, 80, 0), new Stance(4, 64, 0));

        assertFalse("a player in mid-air has no stance to plan from", plan.found());
        assertTrue("and the reason must point at the start, not the goal: a plan rooted at a "
                + "position the executor cannot reproduce fails on move one for reasons that have "
                + "nothing to do with the route: " + plan.failure(),
                plan.failure().contains("start"));
        assertEquals("and it must cost nothing: refusing early is the point", 0, plan.expansions());
    }

    @Test
    public void theBudgetIsCountedPerRouteNotPerCell() {
        // Two independent one-wide trenches with no way around either. Crossing both needs two
        // blocks; a budget of one must fail, and a budget of two must succeed. This is what the
        // (stance, blocksSpent) search key buys -- keyed on stance alone, the first trench's
        // block-poor arrival would close the cell against the route that can still afford the
        // second.
        for (int budget : new int[]{1, 2}) {
            FakeWorld w = flatGround(budget);
            for (int z = -4; z <= 4; z++) {
                w.air(2, 63, z).air(5, 63, z);
            }
            Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(7, 64, 0));
            if (budget == 1) {
                assertFalse("one block cannot cross two separate trenches", plan.found());
            } else {
                assertTrue("two blocks can: " + plan.failure(), plan.found());
                assertEquals("one per trench", 2, plan.blocksNeeded());
            }
        }
    }
}
