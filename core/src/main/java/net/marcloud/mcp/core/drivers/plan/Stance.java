package net.marcloud.mcp.core.drivers.plan;

/**
 * A place a player can legally be, and the single authority on what "legally" means.
 *
 * <p>A stance is the block the FEET occupy. The body needs that block and the one above it clear,
 * and the block below solid to stand on. Every one of those three facts is asked here and nowhere
 * else: the search, the cost function and the executor all route through this class, so they cannot
 * develop separate opinions about what a legal position is. That arrangement is not stylistic --
 * this repo has paid twice for its opposite (one block-name rule implemented in six places with
 * three different failure answers, and a clearance check duplicated into a test fixture so the live
 * one went unasserted at its boundary).
 */
public record Stance(int x, int y, int z) {

    /** Player height in blocks: feet block plus head block. */
    public static final int BODY_HEIGHT = 2;

    /**
     * The highest step a player can walk up without jumping, in blocks. Vanilla's step height is
     * 0.5 for a walking player, so a full block requires a jump; a planner that assumes otherwise
     * emits paths the executor cannot follow and blames the executor.
     */
    public static final int STEP_UP_MAX = 1;

    /**
     * How far a planner will let the player fall on purpose. Survival fall damage starts above
     * three blocks, so three is the honest ceiling for a route that must not cost health.
     */
    public static final int SAFE_DROP_MAX = 3;

    /** Whether a body fits here and something holds it up. */
    public boolean isStandable(BlockView w) {
        return hasFloor(w) && hasRoom(w);
    }

    /** Solid ground directly under the feet. */
    public boolean hasFloor(BlockView w) {
        return w.isSolid(x, y - 1, z);
    }

    /** The feet block and the head block are both clear. */
    public boolean hasRoom(BlockView w) {
        for (int dy = 0; dy < BODY_HEIGHT; dy++) {
            if (!w.isPassable(x, y + dy, z)) {
                return false;
            }
        }
        return true;
    }

    /** The stance one step away on an axis, at the same height. */
    public Stance offset(int dx, int dy, int dz) {
        return new Stance(x + dx, y + dy, z + dz);
    }

    /** The block a bridge for this stance would sit in: directly under the feet. */
    public Stance floorCell() {
        return new Stance(x, y - 1, z);
    }

    /**
     * Whether a block can be placed under {@code target}'s feet, given what exists now.
     *
     * <p>Two gates, and they are separate because they live in separate places (measured
     * 2026-08-05, docs/agency/telly-test-plan.md section 7.5):
     *
     * <ul>
     *   <li>the WORLD must accept a block there -- {@link BlockView#canPlaceAt};</li>
     *   <li>the player must be able to AIM at it, which needs an already-solid face next to the
     *       cell, because {@code ActActuator.rightClickBlock} takes a target block plus a face.
     *       The world happily holds a floating block; a controller cannot conjure one.</li>
     * </ul>
     *
     * <p>The aiming gate is why bridging is a CHAIN and not a teleport: each placed block becomes
     * the face the next one attaches to, which is exactly why a planner has to model it as a
     * sequence of moves rather than "fill the gap".
     */
    public boolean canBridgeTo(BlockView w, Stance target) {
        Stance cell = target.floorCell();
        if (!w.canPlaceAt(cell.x(), cell.y(), cell.z())) {
            return false;
        }
        return hasAdjacentFace(w, cell, this.floorCell());
    }

    /**
     * At least one of the six neighbours is solid, so there is a face to click.
     *
     * <p>{@code standingOn} counts as solid WITHOUT asking the world, and that exemption is the
     * whole reason a bridge chain works. A search explores hypothetically: the blocks it decided to
     * place are not in the world, so the second cell of a three-wide trench has no world-solid
     * neighbour and the chain dies after one block. The first version of this class did exactly
     * that, and {@code PlannerComputesTheBridgeItNeedsTest} caught it on the first run.
     *
     * <p>The exemption is sound rather than convenient: the player is STANDING at the stance doing
     * the placing, so the block under its feet is load-bearing by construction -- whether it is
     * original terrain or a block this same plan placed a step ago. And because a bridge only ever
     * targets a cardinal neighbour, that floor block is always face-adjacent to the cell being
     * filled. Tracking a per-node set of placements would reach the same answer at many times the
     * state-space cost.
     */
    private static boolean hasAdjacentFace(BlockView w, Stance cell, Stance standingOn) {
        if (standingOn.isCardinalNeighbourOf(cell)) {
            return true;
        }
        return w.isSolid(cell.x() + 1, cell.y(), cell.z())
                || w.isSolid(cell.x() - 1, cell.y(), cell.z())
                || w.isSolid(cell.x(), cell.y() + 1, cell.z())
                || w.isSolid(cell.x(), cell.y() - 1, cell.z())
                || w.isSolid(cell.x(), cell.y(), cell.z() + 1)
                || w.isSolid(cell.x(), cell.y(), cell.z() - 1);
    }

    /** Whether {@code other} shares a face with this cell (not a diagonal, not itself). */
    public boolean isCardinalNeighbourOf(Stance other) {
        int dx = Math.abs(x - other.x());
        int dy = Math.abs(y - other.y());
        int dz = Math.abs(z - other.z());
        return dx + dy + dz == 1;
    }

    /** Manhattan distance on the horizontal plane, the heuristic's basis. */
    public int horizontalDistanceTo(Stance other) {
        return Math.abs(x - other.x()) + Math.abs(z - other.z());
    }
}
