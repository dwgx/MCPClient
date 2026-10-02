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
     * 0.6 for a walking player ({@code EntityLivingBase.java:208}), so a full block requires a
     * jump; a planner that assumes otherwise emits paths the executor cannot follow and blames the
     * executor.
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

    /**
     * Solid ground directly under the feet, and NOT a ladder.
     *
     * <p>Vanilla's own collision read says a ladder IS solid and the planner has to disagree.
     * {@code Block.getCollisionBoundingBox:499-502} returns a box for every block, so
     * {@code BlockProbe} -- correctly, as a statement about collision -- calls a ladder
     * {@code SOLID}. But a ladder is a thin plate on a wall and a player on top of one is standing
     * on air: nothing holds them there. {@code BlockProbe}'s own javadoc says exactly this ("vines,
     * ladders, torches, tall grass, rails and signs are non-solid and must not read as floor") and
     * its code does not deliver it, so the correction belongs HERE, in the one place that answers
     * "will something hold this player's feet up", rather than in a util that is only being asked
     * about collision.
     *
     * <p>What leaving it alone costs is concrete, and it is a lie in the plan. With a ladder
     * counted as a floor, every cell of a ladder column reads as standable and
     * {@code NeighborGen} offers {@link Move.Kind#STEP_UP} moves up it -- a jump, which vanilla
     * only performs from the ground ({@code EntityLivingBase.onLivingUpdate:2018-2022}) and which a
     * player hanging on a ladder cannot do. The route would be computed, offered, and then refused
     * at execution with a message blaming geometry. A climb is the only kind that describes that
     * transition, so a ladder is the only thing allowed to end one.
     */
    public boolean hasFloor(BlockView w) {
        return w.isSolid(x, y - 1, z) && !w.isClimbable(x, y - 1, z);
    }

    /**
     * The feet block and the head block are both clear.
     *
     * <p>"Clear" is {@link BlockView#isPassable}, whose rule is vanilla's walk verdict: water is
     * room, lava is not. Asking per cell rather than as one volume is what lets the feet be in
     * water while the head is not.
     */
    public boolean hasRoom(BlockView w) {
        for (int dy = 0; dy < BODY_HEIGHT; dy++) {
            if (!w.isPassable(x, y + dy, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a player can be climbing here: a ladder or vine under the feet and room for a body.
     *
     * <p><b>Not {@link #isStandable}, and the difference is the whole point of the predicate.</b>
     * {@code isStandable} asks whether the cell BELOW the feet is solid, and on a ladder four
     * blocks up a shaft it is not. The player up there is genuinely there and genuinely held --
     * by the ladder, which vanilla detects in {@code EntityLivingBase.isOnLadder:1134-1141} and
     * acts on in {@code moveEntityWithHeading:1637-1662} -- but it never sets {@code onGround}, so
     * a rule that asks "is there a floor" answers no about a position a player occupies
     * constantly. The ladder is a legal stance and the old vocabulary had no word for it.
     *
     * <p>Room is still required, and asked the same way as everywhere else: the ladder holds the
     * body up, it does not make the cell above the head disappear, and a body cannot be two blocks
     * inside a block.
     */
    public boolean isClimbable(BlockView w) {
        return w.isClimbable(x, y, z) && hasRoom(w);
    }

    /**
     * Whether a player can be swimming here: water where the feet are and room for a body.
     *
     * <p>Same shape as {@link #isClimbable} and for the same reason. A cell in the middle of a
     * lake has no solid block under it and no ladder in it, so both {@link #hasFloor} and
     * {@link #isClimbable} answer false about a position the player is in. Vanilla holds such a
     * body up with the water -- {@code handleWaterMovement:1111-1130} sets {@code inWater} and
     * zeroes {@code fallDistance}, and the water branch of {@code moveEntityWithHeading:1700-1733}
     * is what moves it -- which is a third way of being supported that the two older predicates
     * between them do not cover.
     *
     * <p>The feet cell is the one that has to be water, not the body: vanilla tests an
     * {@code AxisAlignedBB} contracted vertically by 0.4 and shrunk by 0.001
     * ({@code Entity.handleWaterMovement:1113}), which is a statement about the lower body, so a
     * stance at the waterline with its head in air is a stance a player holds.
     */
    public boolean isSwimmable(BlockView w) {
        return w.isWater(x, y, z) && hasRoom(w);
    }

    /**
     * Whether a body fits here and SOMETHING holds it up -- floor, ladder, or water.
     *
     * <p>Written as the disjunction of the three rather than as a new independent rule, because
     * each arm is already the vanilla predicate for its own way of being held up and a fourth
     * spelling of "is this a position a player can occupy" is exactly the second opinion this
     * class exists to prevent.
     *
     * <p>Used for the two places a route may BEGIN or END anywhere, where asking only about floors
     * would refuse to plan out of a ladder shaft or onto a boat ramp. The move generators do NOT
     * use it, because each move has to be offered for its own reason: a swim is offered because
     * the destination is water, not because the destination is somehow occupiable.
     */
    public boolean isOccupiable(BlockView w) {
        return isStandable(w) || isClimbable(w) || isSwimmable(w);
    }

    /**
     * Whether standing here means standing at the lip of something, so the move into it has to be
     * crept.
     *
     * <p>True when at least one of the four cells BESIDE this one, at the same height, has no floor.
     * Two boundaries matter and both are deliberate. The neighbours are the four cardinals and not
     * the eight around them, because a diagonal cell a body never enters cannot be what stops it:
     * vanilla's guard asks whether the bounding box one block DOWN is clear, and a 0.6-wide body
     * walking on a 1-wide ledge has its box over the ledge for its whole length. And the test is
     * {@link #hasFloor} on the neighbour rather than "is not standable", because a neighbour full
     * of water is still a floor to walk beside -- a route along a lake bank is not a route that
     * needs creeping, and treating it as one would put the key down for no reason at all.
     *
     * <p>This lives beside {@link #isStandable} rather than in {@link NeighborGen} because it is
     * the same kind of question -- what does the world hold up here -- asked about the cell next
     * door. The class doc is explicit that every "can a body be here" fact is asked in one place,
     * and a second implementation of "is there floor" in the generator is the exact duplication
     * that note exists to prevent.
     */
    public boolean isBrink(BlockView w) {
        return !offset(1, 0, 0).hasFloor(w)
                || !offset(-1, 0, 0).hasFloor(w)
                || !offset(0, 0, 1).hasFloor(w)
                || !offset(0, 0, -1).hasFloor(w);
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
     * 2026-08-05, docs/agency/HANDOFF.md section 3):
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
