package net.marcloud.mcp.core.drivers.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Every move available from one stance. Fork B's own generator, not vanilla's.
 *
 * <p>Fork B was decided against wrapping vanilla's {@code WalkNodeProcessor} for a reason this class
 * makes concrete: vanilla's generator only knows how to walk on terrain that already exists. It has
 * no concept of a move that CREATES its own floor, so no amount of wrapping produces a bridge. The
 * cost of writing our own is this file; the benefit is that "place a block" is an ordinary entry in
 * the move list and gap-crossing becomes a search result instead of a scripted routine.
 *
 * <p><b>Four cardinal directions, no diagonals.</b> A diagonal step in vanilla clips corners and
 * needs both adjacent cells clear to be safe, and the nav probe's own history says the diagonal case
 * is the one that took longest to ever land (handoff-2026-08-06 section 0(1): "对角线第一次到达").
 * Adding diagonals before the cardinal path is proven end-to-end would be adding the hardest case
 * first. They are a deliberate omission, not an oversight -- and being an omission, they cost the
 * planner completeness, not correctness: a route exists in the cardinal graph whenever one exists
 * diagonally, only longer.
 */
public final class NeighborGen {

    /** The four cardinal horizontal directions as {dx, dz}. */
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final BlockView world;

    public NeighborGen(BlockView world) {
        this.world = world;
    }

    /**
     * All legal moves out of {@code from}, in no particular order.
     *
     * <p>{@code blocksSpent} is passed in rather than read off the world because the search explores
     * branches: a plan that has already spent three blocks must not be offered a fourth when the
     * budget is three, and the world cannot know how many a hypothetical branch used. Threading it
     * through is what keeps the search from returning a plan the executor cannot finish -- the
     * "中途没方块" failure the MANEUVER analysis names.
     */
    public List<Move> movesFrom(Stance from, int blocksSpent) {
        List<Move> out = new ArrayList<>(8);
        for (int[] dir : CARDINALS) {
            addWalk(out, from, dir[0], dir[1]);
            addStepUp(out, from, dir[0], dir[1]);
            addDrop(out, from, dir[0], dir[1]);
            addBridge(out, from, dir[0], dir[1], blocksSpent);
        }
        return out;
    }

    /** Flat step onto ground that is already there. */
    private void addWalk(List<Move> out, Stance from, int dx, int dz) {
        Stance to = from.offset(dx, 0, dz);
        if (to.isStandable(world)) {
            out.add(Move.walk(from, to));
        }
    }

    /**
     * Step up one block. Requires headroom at the DESTINATION and also that the ceiling above the
     * current stance is clear -- a player who cannot raise their head cannot jump, and omitting that
     * check produces plans that stall silently under an overhang with the controller reporting it
     * honestly and looking wrong.
     */
    private void addStepUp(List<Move> out, Stance from, int dx, int dz) {
        Stance to = from.offset(dx, Stance.STEP_UP_MAX, dz);
        if (!to.isStandable(world)) {
            return;
        }
        if (!world.isPassable(from.x(), from.y() + Stance.BODY_HEIGHT, from.z())) {
            return;
        }
        out.add(Move.stepUp(from, to));
    }

    /**
     * Walk off an edge and land on the first floor within {@link Stance#SAFE_DROP_MAX}.
     *
     * <p>Only the FIRST landing is offered, not every depth: a plan that could choose to fall past a
     * ledge it would land on is describing something the physics will not do.
     */
    private void addDrop(List<Move> out, Stance from, int dx, int dz) {
        Stance edge = from.offset(dx, 0, dz);
        if (!edge.hasRoom(world) || edge.hasFloor(world)) {
            return; // blocked, or it is a walk rather than a drop
        }
        for (int depth = 1; depth <= Stance.SAFE_DROP_MAX; depth++) {
            Stance landing = edge.offset(0, -depth, 0);
            if (landing.isStandable(world)) {
                out.add(Move.drop(from, landing, depth));
                return;
            }
            if (!landing.hasRoom(world)) {
                return; // something in the way that is not a floor: not a fall, a collision
            }
        }
    }

    /**
     * Place a block into the gap ahead and step onto it.
     *
     * <p>Offered only when the destination has ROOM but no FLOOR -- i.e. exactly the case a walk
     * cannot serve. The order of these checks matters: asking {@code canBridgeTo} first would spend
     * a world query on cells that are already walkable, and on a 3000-cell search that is the
     * difference between a plan and a stall.
     */
    private void addBridge(List<Move> out, Stance from, int dx, int dz, int blocksSpent) {
        if (blocksSpent >= world.blockBudget()) {
            return;
        }
        Stance to = from.offset(dx, 0, dz);
        if (to.hasFloor(world) || !to.hasRoom(world)) {
            return;
        }
        if (!from.canBridgeTo(world, to)) {
            return;
        }
        out.add(Move.bridge(from, to, to.floorCell()));
    }
}
