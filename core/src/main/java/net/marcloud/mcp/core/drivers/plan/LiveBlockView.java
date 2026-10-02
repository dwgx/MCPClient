package net.marcloud.mcp.core.drivers.plan;

import net.marcloud.mcp.core.util.BlockProbe;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.pathfinder.WalkNodeProcessor;

/**
 * The planner's questions, answered from a real world: solidity and placement through
 * {@link BlockProbe}, passability through vanilla's own walk verdict.
 *
 * <p><b>UNKNOWN is not traversable, and that choice is the whole reason this class is worth reading.</b>
 * An unloaded chunk reads as air in vanilla, so the naive adapter makes the planner see a void where
 * terrain is and cheerfully route through it -- or worse, decide to BRIDGE across solid ground, spend
 * the inventory, and have every placement refused. Treating unread cells as impassable makes the
 * planner refuse instead, which is the safe direction: a refusal costs a round trip, a hallucinated
 * void costs the blocks and leaves the player somewhere it did not intend to be.
 *
 * <p>But refusing silently would be its own lie -- "no route" is a claim about terrain, and the
 * planner would be making it about terrain it never read. So the count is kept and exposed via
 * {@link #unreadCells()}, letting a caller separate "there is no way there" from "I could not see far
 * enough to tell, load the chunks and ask again". That distinction is the one this repo has had to
 * restore four times after it was folded away (self.air, effects, entities.left, block-name
 * sentinels).
 *
 * <p><b>Which world to pass.</b> The server world is the authority -- it validates and reverts what
 * the client predicts -- so a plan that must actually execute should be built against
 * {@code EntityPlayerMP.getServerForPlayer()}. The client world is the right choice only for
 * answering "what does the player believe", and a plan built on it can be rubber-banded. Asking the
 * wrong side is how a probe once reported a precondition holding for a run already doomed
 * (docs/debugging.md section 10 rule 2).
 *
 * <p><b>Passability is vanilla's verdict, asked rather than reimplemented, with one override.</b>
 * {@link #walkVerdict} delegates to {@code WalkNodeProcessor.func_176170_a} -- the same call
 * {@code LocalGrid.walkVerdict} makes -- so "you may walk here" means the same thing to the agent
 * that READS the terrain and to the planner that ROUTES over it. A hand-written copy of that
 * dispatch is exactly the second taxonomy this repo keeps finding: the cases that separate it from
 * a material test are the awkward ones (an OPEN fence gate is passable, a CLOSED trapdoor is, an
 * open one is not), and a copy gets them wrong in the direction nobody tests.
 *
 * <p>The one thing the planner adds is that lava is <b>never</b> passable. Vanilla's answer depends
 * on whether the entity is already in lava, which is a fact about the player and not about the cell,
 * and a map that changes under the search is worse than a map that is stricter than the game.
 */
public final class LiveBlockView implements BlockView {

    private final World world;
    private final int budget;
    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;

    /**
     * The body whose passability verdicts are asked for, or null.
     *
     * <p>Required by {@code func_176170_a}, which opens with {@code new BlockPos(entityIn)} and
     * dereferences it on the first line -- {@code LocalGrid} learned that the hard way, when a null
     * entity silently cost every column its verdict. Null is tolerated here only so the failure is
     * an unreadable cell rather than an exception out of the middle of a search.
     */
    private final Entity body;

    /**
     * Squared server reach for placement, measured rather than assumed: the server compares
     * {@code getDistanceSq(block centre) < 64.0} in {@code NetHandlerPlayServer:599}. The client's
     * own {@code getBlockReachDistance()} is 4.5, a different and smaller number -- using it here
     * would make the planner refuse placements the server would have accepted.
     */
    public static final double SERVER_REACH_SQ = 64.0D;

    private int unread;

    /**
     * @param world  the world to ask; prefer the SERVER world for plans that will execute
     * @param body   the player whose body the walk verdicts describe; null makes every verdict
     *               unreadable rather than wrong
     * @param eyeX   the eye position the reach gate is measured from, X
     * @param eyeY   eye Y, i.e. {@code posY + getEyeHeight()} -- not feet Y
     * @param eyeZ   eye Z
     * @param budget how many blocks the planner may spend
     */
    public LiveBlockView(World world, Entity body, double eyeX, double eyeY, double eyeZ,
                         int budget) {
        this.world = world;
        this.body = body;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.budget = Math.max(0, budget);
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
        if (!s.wasRead()) {
            unread++;
            // Unread is NOT solid: claiming a floor nobody observed is how a planner walks a player
            // off an edge it believed was ground.
            return false;
        }
        return s.holdsPlayerUp();
    }

    /**
     * Vanilla's verdict, with the planner's one override on top of it.
     *
     * <p>{@link BlockProbe} answers first because it is the read that knows what "could not look"
     * means: an unloaded chunk reads as air to every vanilla accessor, so delegating into the
     * verdict before asking whether the cell was readable reports a clear cell that was never seen.
     */
    @Override
    public int walkVerdict(int x, int y, int z) {
        BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
        if (!s.wasRead()) {
            unread++;
            // Unread is NOT passable, the same refusal isSolid makes: an unobserved cell must not
            // be routed through, and WALK_UNKNOWN is how that refusal travels through the one rule
            // in BlockView#isPassable rather than through a second answer to the question.
            return WALK_UNKNOWN;
        }
        if (isLava(x, y, z)) {
            return WALK_LAVA;
        }
        return vanillaVerdict(x, y, z);
    }

    /**
     * Whether lava occupies this cell, checked before vanilla rather than by it.
     *
     * <p>Vanilla returns -2 only while the entity is not already in lava; a player standing in it
     * gets 1. The planner's rule is that lava is never passable, and the reason is not squeamishness:
     * the same query answering differently depending on where the player is standing means the
     * search's map of the world changes between two expansions of the same node, and the case where
     * it changes is the case where the player is burning to death.
     *
     * <p>A read that throws is not lava -- the cell's verdict then comes from the delegation below,
     * which fails to UNKNOWN for a cell nothing can be said about.
     */
    private boolean isLava(int x, int y, int z) {
        try {
            IBlockState state = world.getBlockState(new BlockPos(x, y, z));
            return state != null && state.getBlock().getMaterial() == Material.lava;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * {@code func_176170_a}, asked exactly the way the read side asks it.
     *
     * <p>The volume is this ONE cell (1x1x1) because that is the granularity of the question the
     * planner asks: {@link Stance#hasRoom} walks the body's two cells itself, and asking per cell is
     * what lets the feet be in water while the head is not. The read side asks about the whole
     * 1x2x1 body in a single call; the two agree because {@code func_176170_a} returns the first
     * blocking verdict inside the volume either way.
     *
     * <p>The three trailing flags are constant and load-bearing, and the read side's test pins them
     * for the same reason: {@code avoidWater} false keeps water at 2 instead of the unmapped -1,
     * and the door flags false are what make 0 mean "blocked" of a closed wooden door too.
     */
    private int vanillaVerdict(int x, int y, int z) {
        if (body == null) {
            unread++;
            return WALK_UNKNOWN;
        }
        try {
            return WalkNodeProcessor.func_176170_a(
                    world, body, x, y, z, 1, 1, 1, false, false, false);
        } catch (Throwable t) {
            // Fault-isolated for the read side's reason: this reaches a vanilla static that reads
            // the entity's own world for its rail check, and a throw here must cost one cell's
            // verdict rather than the whole plan.
            unread++;
            return WALK_UNKNOWN;
        }
    }

    /**
     * Vanilla's ladder test, asked about a cell instead of about the player.
     *
     * <p>{@code EntityLivingBase.isOnLadder:1134-1141} is three lines and one of them is the read:
     * floor the X, floor the bottom of the bounding box, floor the Z, read that block, and test it
     * against {@code Blocks.ladder} and {@code Blocks.vine}. This is the same read with the three
     * coordinates supplied by the caller instead of by the entity, which is the only difference --
     * the comparison is identical and lives here so a change to vanilla's set of climbables is a
     * change to this method and not to a second copy of it somewhere in a search loop.
     *
     * <p>{@link BlockProbe} gates the read for the reason every other method here does: an
     * unloaded chunk resolves to air for every vanilla accessor, so asking first would manufacture
     * an "air, therefore not a ladder" answer about terrain nobody looked at. An unread cell is
     * not climbable, and the count is kept so a caller can tell "there is no ladder" from "I could
     * not see far enough to tell".
     */
    @Override
    public boolean isClimbable(int x, int y, int z) {
        BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
        if (!s.wasRead()) {
            unread++;
            return false;
        }
        try {
            IBlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (state == null) {
                unread++;
                return false;
            }
            Block block = state.getBlock();
            return block == Blocks.ladder || block == Blocks.vine;
        } catch (Throwable t) {
            // Fault-isolated for the same reason vanillaVerdict is: a search asks this thousands of
            // times and one unreadable cell must cost that cell, not the plan.
            unread++;
            return false;
        }
    }

    /**
     * Water, by the same two blocks vanilla's own pathfinder tests.
     *
     * <p>{@code WalkNodeProcessor.func_176170_a:213} asks
     * {@code block != Blocks.flowing_water && block != Blocks.water} to decide whether the cell is
     * wet, and {@code WalkNodeProcessor.getPathPointTo:54} walks upward over exactly that same pair
     * to find the surface. Both still water and flowing water are named because vanilla names
     * both, and naming only one of them would make a river read as a line of dry air.
     *
     * <p>Read through {@link BlockProbe} first, for the reason {@link #walkVerdict} does: the
     * answer has to be known to come from a cell that was actually looked at.
     */
    @Override
    public boolean isWater(int x, int y, int z) {
        BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
        if (!s.wasRead()) {
            unread++;
            return false;
        }
        try {
            IBlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (state == null) {
                unread++;
                return false;
            }
            Block block = state.getBlock();
            return block == Blocks.water || block == Blocks.flowing_water;
        } catch (Throwable t) {
            unread++;
            return false;
        }
    }

    @Override
    public boolean canPlaceAt(int x, int y, int z) {
        BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
        if (!s.wasRead()) {
            unread++;
            return false;
        }
        if (!s.isEmptySpace()) {
            return false;
        }
        return withinServerReach(x, y, z);
    }

    /**
     * The reach gate, measured from the EYE to the block CENTRE.
     *
     * <p>Both of those are load-bearing. Measuring from the feet loses 1.62 blocks of vertical
     * offset, and measuring to the block corner instead of its centre shifts the boundary by up to
     * half a block on each axis -- either mistake produces a planner that is confidently wrong at
     * exactly the edge where placements start being refused.
     */
    private boolean withinServerReach(int x, int y, int z) {
        return withinServerReach(eyeX, eyeY, eyeZ, x, y, z);
    }

    /**
     * Package-private and static so the arithmetic can be pinned without a world.
     *
     * <p>This is the highest-risk line in the class and none of its mistakes are visible from the
     * outside: measuring from the feet silently loses 1.62 blocks, and comparing to the block corner
     * instead of its centre moves the boundary by up to half a block per axis. Either one produces a
     * planner that is confidently wrong exactly where placements begin to be refused -- and a test
     * that only checked a near cell would agree with both.
     */
    static boolean withinServerReach(double eyeX, double eyeY, double eyeZ,
                                     int x, int y, int z) {
        double dx = (x + 0.5D) - eyeX;
        double dy = (y + 0.5D) - eyeY;
        double dz = (z + 0.5D) - eyeZ;
        // Strictly less than, matching NetHandlerPlayServer:599. At exactly 64.0 the server refuses,
        // so an inclusive comparison here would hand the planner a whole shell of cells it can never
        // build -- the same off-by-a-boundary the envelope probe's self-check pins from the other side.
        return dx * dx + dy * dy + dz * dz < SERVER_REACH_SQ;
    }

    @Override
    public int blockBudget() {
        return budget;
    }

    /**
     * How many cells this view was asked about and could not read.
     *
     * <p>Non-zero alongside a failed plan means the failure may be ignorance rather than terrain, and
     * the caller should say so instead of reporting "no route". A planner that cannot tell the
     * difference is asserting something about the world it never observed.
     */
    public int unreadCells() {
        return unread;
    }

    /** A stance for a player whose feet are at these coordinates. */
    public static Stance stanceOf(BlockPos feet) {
        return new Stance(feet.getX(), feet.getY(), feet.getZ());
    }
}
