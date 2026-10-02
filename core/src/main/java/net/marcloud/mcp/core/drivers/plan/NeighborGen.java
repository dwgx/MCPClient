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
 * <p><b>Six move kinds, and the two that are not steps across ground are the interesting
 * ones.</b> {@code addClimb} and {@code addSwim} are the only entries here that are not
 * horizontal, and they exist because the four older kinds are all one sentence -- "walk to the
 * next stance, which something underneath is holding up" -- while a ladder and a river are two
 * more ways of being held up that sentence cannot say. A generator with neither would report "no
 * route" for a mineshaft ladder and a river crossing, which is a claim about the world it has not
 * earned: both are places a player walks, and this file is the part of the planner supposed to
 * know it.
 *
 * <p><b>Climbing and swimming are emitted from the stance itself, not per direction.</b> A ladder
 * column and a stretch of water are properties of one cell, so looping them over the four
 * cardinals would emit the same move four times and make the search pay for the duplicates.
 *
 * <p><b>Four cardinal directions, no diagonals.</b> A diagonal step in vanilla clips corners and
 * needs both adjacent cells clear to be safe, and the nav probe's own history says the diagonal
 * case is the one that took longest to ever land (handoff-2026-08-06 section 0(1): "对角线第一次到达").
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
     *
     * <p>{@code airSpent} is the same argument for air, and the reason it is a parameter and not a
     * property of {@code Move} is identical: the world cannot know how deep underwater a
     * hypothetical branch already was, so a generator that read the budget from anywhere else would
     * offer a search the swim that drowns the player -- the underwater twin of the same defect.
     */
    public List<Move> movesFrom(Stance from, int blocksSpent, int airSpent) {
        List<Move> out = new ArrayList<>(8);
        for (int[] dir : CARDINALS) {
            addWalk(out, from, dir[0], dir[1]);
            addStepUp(out, from, dir[0], dir[1]);
            addDrop(out, from, dir[0], dir[1]);
            addBridge(out, from, dir[0], dir[1], blocksSpent);

            addSwim(out, from, dir[0], dir[1], airSpent);
        }
        addClimb(out, from);
        addSwimUp(out, from, airSpent);
        return out;
    }

    /**
     * A cell whose block is a ladder is CLIMB's destination and nobody else's.
     *
     * <p>Every generator that lands a body in a cell asks this first, and the reason is that a
     * player in a ladder cell is held by the ladder rather than by anything they walked onto. A
     * {@link Move.Kind#WALK} or a {@link Move.Kind#STEP_UP} into one would tell the executor to
     * drive a jump, and vanilla only jumps from the ground
     * ({@code EntityLivingBase.onLivingUpdate:2018-2022}) -- so the plan would name a move the
     * executor cannot perform, which is the one thing a plan-time vocabulary must never do.
     *
     * <p>Asked on the destination's FEET cell, because that is the cell
     * {@code EntityLivingBase.isOnLadder:1136-1138} reads. A cell merely ADJACENT to a ladder is
     * ordinary ground and goes through the ordinary rules.
     */
    private boolean isLadderCell(Stance cell) {
        return world.isClimbable(cell.x(), cell.y(), cell.z());
    }

    /**
     * Flat step onto ground that is already there -- INCLUDING into a ladder column, deliberately.
     *
     * <p>An earlier version of this method refused any ladder-cell destination, on the reasoning
     * that a body in a ladder cell is being climbed into rather than walked to. That was wrong, and
     * wrong in the direction that breaks the feature: walking into the bottom of a ladder column is
     * exactly how a player gets on a ladder, that cell is genuinely standable (there is ground
     * under it), and refusing the edge made a ladder reachable only by a caller who happened to
     * start the route already standing inside one. The climb test never caught it because it starts
     * inside the column.
     *
     * <p>What stops a player walking into a MID-COLUMN cell is not this method but
     * {@link Stance#hasFloor}: the cell below is a ladder, which is not a floor, so the stance is
     * not standable and the edge is never offered. That is the right place for the rule, because it
     * is the only question that has to be answered.
     */
    private void addWalk(List<Move> out, Stance from, int dx, int dz) {
        Stance to = from.offset(dx, 0, dz);
        if (to.isStandable(world)) {
            out.add(Move.walk(from, to, to.isBrink(world)));
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
        if (isLadderCell(to)) {
            // The one place a jump is genuinely wrong, and it is worth spelling out: vanilla takes
            // a jump only when onGround (EntityLivingBase.onLivingUpdate:2018-2022), and a ladder
            // cell is not ground. Without this the planner routes a whole mineshaft as a staircase
            // of STEP_UPs and the executor reports a geometry failure on the first one.
            return;
        }
        if (!to.isStandable(world)) {
            return;
        }
        if (!world.isPassable(from.x(), from.y() + Stance.BODY_HEIGHT, from.z())) {
            return;
        }

        // Crept like a walk, and for the same reason: the destination is where the body ends up
        // standing, so it is the destination's neighbours that decide whether it needs the key.
        // A drop is deliberately not stamped -- it lands on a floor by construction, and the body
        // is airborne for the whole move, so Entity.moveEntity:626's `onGround` is false and the
        // guard would not fire even if the key were down.
        out.add(Move.stepUp(from, to, to.isBrink(world)));
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
            if (isLadderCell(landing)) {
                return; // falling into a ladder is a climb the other way, not a fall
            }
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
     *
     * <p><b>Lava is refused here, and only here.</b> {@link BlockView#canPlaceAt} says the WORLD
     * accepts a block placed into lava -- vanilla's liquid materials are replaceable, so it does --
     * and this is the other half of that sentence: the world accepting it is not the planner
     * choosing it. A bridge into lava is a route whose floor is a hazard the player is standing
     * over, and the placement that makes it safe can be refused once the plan has already committed
     * (out of reach, empty hand), at which point the player is walking into lava. The check is on
     * the cell the block would go INTO, because that is the one the destination's floor comes from;
     * lava where the BODY would be is already refused by {@code hasRoom} above.
     */
    private void addBridge(List<Move> out, Stance from, int dx, int dz, int blocksSpent) {
        if (blocksSpent >= world.blockBudget()) {
            return;
        }
        Stance to = from.offset(dx, 0, dz);
        if (isLadderCell(to)) {
            return; // building a floor under a ladder cell is a climb's job, not a bridge's
        }
        if (to.hasFloor(world) || !to.hasRoom(world)) {
            return;
        }
        Stance cell = to.floorCell();
        if (world.walkVerdict(cell.x(), cell.y(), cell.z()) == BlockView.WALK_LAVA) {
            return;
        }
        if (!from.canBridgeTo(world, to)) {
            return;
        }
        out.add(Move.bridge(from, to, cell));
    }

    /**
     * Up and down the ladder or vine this stance is already on.
     *
     * <p><b>Offered from the stance, so the ladder has to be where the player already is.</b> That
     * is vanilla's own arrangement rather than a restriction: {@code isOnLadder:1134-1141} reads
     * the cell the feet are in, and {@code moveEntityWithHeading:1637-1662} only raises the body
     * while that cell holds a ladder. A climb into a ladder column the player is not yet inside is
     * not a thing a player can do -- they walk into the column first, which the ordinary
     * {@code addWalk} handles, because a ladder cell is clear to a body and has whatever floor is
     * under it.
     *
     * <p>Both directions are emitted because both are executable and refusing one would make the
     * ladder a one-way trip. Descending is the same mechanism with the forward key released
     * ({@code moveEntityWithHeading:1644-1647} clamps the fall to 0.15), which is why it is a
     * {@link Move.Kind#CLIMB} and not a {@link Move.Kind#DROP}: a ladder descent is not a fall, it
     * costs no health, and pricing it as one would make a search avoid the way down a shaft.
     */
    private void addClimb(List<Move> out, Stance from) {
        if (!from.isClimbable(world)) {
            return;
        }
        Stance up = from.offset(0, 1, 0);
        if (up.isClimbable(world)) {
            out.add(Move.climb(from, up));
        }
        Stance down = from.offset(0, -1, 0);
        if (down.isClimbable(world)) {
            out.add(Move.climb(from, down));
        }
    }

    /**
     * One block of swimming sideways, into a cell that holds water.
     *
     * <p>Offered on the same condition as a bridge -- the destination has room but nothing under
     * it -- and for the same reason. In open water a cell has no floor to be solid and no ladder to
     * be climbable, so every other generator here refuses it, and a planner that stopped there
     * would report "no route" for a river a player swims across in four seconds. A cell whose
     * floor happens to be solid gets a {@link Move.Kind#WALK} instead, which is the right kind for
     * it: the player is wading, and wading is cheaper than swimming in every sense that matters.
     *
     * <p><b>The air budget is a refusal, not a price.</b> A swim the player cannot afford the air
     * for is a drowning, and no cost makes it not one -- exactly the argument
     * {@code addBridge}'s budget check and {@code addDrop}'s depth ceiling already make about
     * blocks and falls. Offering it and pricing it expensively would hand the search a move that
     * kills the player when it finally chose it.
     */
    private void addSwim(List<Move> out, Stance from, int dx, int dz, int airSpent) {
        Stance to = from.offset(dx, 0, dz);
        if (to.hasFloor(world) || !to.isSwimmable(world)) {
            return;
        }
        if (airSpent + Move.AIR_PER_SWIM_BLOCK > Move.AIR_MAX) {
            return;
        }
        out.add(Move.swim(from, to));
    }

    /**
     * One block of swimming upward, out of a cell that holds water into the cell above it.
     *
     * <p>Separate from the horizontal swim because it is a different vanilla action with a
     * different cost: rising needs the jump key held ({@code onLivingUpdate:2010-2013} into
     * {@code updateAITick:1591}), and it burns about a third more air per block. Folding it into
     * the horizontal case would mean the executor had to re-derive which action a move wants from
     * its two stances, and the air cost would have to be guessed rather than stated.
     *
     * <p>Surfacing is the move that makes any of this survivable, which is why it exists rather
     * than being left as a gap: a planner that can swim down into a flooded shaft and not back out
     * has added a way to kill the player, not a way to move them.
     */
    private void addSwimUp(List<Move> out, Stance from, int airSpent) {
        if (!from.isSwimmable(world)) {
            return;
        }
        Stance to = from.offset(0, 1, 0);
        if (!to.isSwimmable(world)) {
            return;
        }
        if (airSpent + Move.AIR_PER_RISE_BLOCK > Move.AIR_MAX) {
            return;
        }
        out.add(Move.swim(from, to));
    }
}
