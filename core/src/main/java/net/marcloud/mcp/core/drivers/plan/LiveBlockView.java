package net.marcloud.mcp.core.drivers.plan;

import net.marcloud.mcp.core.util.BlockProbe;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * The planner's four questions, answered from a real world through {@link BlockProbe}.
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
 */
public final class LiveBlockView implements BlockView {

    private final World world;
    private final int budget;
    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;

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
     * @param eyeX   the eye position the reach gate is measured from, X
     * @param eyeY   eye Y, i.e. {@code posY + getEyeHeight()} -- not feet Y
     * @param eyeZ   eye Z
     * @param budget how many blocks the planner may spend
     */
    public LiveBlockView(World world, double eyeX, double eyeY, double eyeZ, int budget) {
        this.world = world;
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

    @Override
    public boolean isPassable(int x, int y, int z) {
        BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
        if (!s.wasRead()) {
            unread++;
            // Unread is NOT passable either. Both answers being false for the same cell is
            // deliberate and is what makes UNKNOWN impassable without every caller knowing the
            // third state exists.
            return false;
        }
        return s.isEmptySpace();
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
