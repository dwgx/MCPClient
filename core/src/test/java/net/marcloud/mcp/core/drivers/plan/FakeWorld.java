package net.marcloud.mcp.core.drivers.plan;

import java.util.HashSet;
import java.util.Set;

/**
 * A hand-built world for planner tests: solid where told, air everywhere else.
 *
 * <p>Placements MUTATE it, because that is the property the planner's bridge chain depends on: each
 * placed block becomes the face the next one attaches to. A fake that accepted placements without
 * recording them would let a test pass a plan that cannot actually be built, and the plan's second
 * bridge step would be the one that fails on a real client -- headless-green, live-red, which is the
 * split docs/debugging.md section 10 exists to prevent.
 *
 * <p>It answers with the same verdict vocabulary {@link BlockView} uses rather than with a boolean,
 * because "water is passable and lava is not" is the rule under test: a fake that could only say
 * solid/not-solid would make the two identical and the lava tests vacuous.
 */
final class FakeWorld implements BlockView {

    private final Set<Long> solid = new HashSet<>();
    private final Set<Long> water = new HashSet<>();
    private final Set<Long> lava = new HashSet<>();
    private final Set<Long> climbable = new HashSet<>();
    private int budget;

    FakeWorld(int budget) {
        this.budget = budget;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }

    FakeWorld solid(int x, int y, int z) {
        solid.add(key(x, y, z));
        return this;
    }

    /** A solid floor slab over an inclusive x/z rectangle at height y. */
    FakeWorld floor(int x0, int x1, int y, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                solid(x, y, z);
            }
        }
        return this;
    }

    /** Carve a cell back to air, for cutting gaps into a slab. */
    FakeWorld air(int x, int y, int z) {
        solid.remove(key(x, y, z));
        return this;
    }

    /** Put water in a cell: passable, and the reason water is not simply "air". */
    FakeWorld water(int x, int y, int z) {
        water.add(key(x, y, z));
        solid.remove(key(x, y, z));
        return this;
    }

    /** Put lava in a cell: never passable, whatever a solidity-only view would say. */
    FakeWorld lava(int x, int y, int z) {
        lava.add(key(x, y, z));
        solid.remove(key(x, y, z));
        return this;
    }

    /**
     * A ladder, which vanilla makes solid for collision and clear for a body AT THE SAME TIME.
     *
     * <p>{@code Block.getCollisionBoundingBox:499-502} returns a box for every block, so
     * {@code BlockProbe} reports a ladder as SOLID; and {@code BlockLadder}'s
     * {@code Material.circuits} does not block movement, so
     * {@code WalkNodeProcessor.func_176170_a} reports the same cell CLEAR. Both are true of the same
     * block, which is exactly why {@link BlockView} grew an {@code isClimbable} question instead of
     * the planner inferring ladders from either existing answer.
     *
     * <p>Modelled faithfully rather than conveniently: a fake that made a ladder non-solid would
     * let {@code Stance.hasFloor} agree with it, and hide the defect where a whole ladder column is
     * planned as a staircase of jumps the executor cannot perform on a ladder.
     */
    FakeWorld ladder(int x, int y, int z) {
        climbable.add(key(x, y, z));
        solid.add(key(x, y, z));
        return this;
    }

    /** A ladder column one block wide, from {@code y0} to {@code y1} inclusive. */
    FakeWorld ladderColumn(int x, int y0, int y1, int z) {
        for (int y = y0; y <= y1; y++) {
            ladder(x, y, z);
        }
        return this;
    }

    @Override
    public boolean isClimbable(int x, int y, int z) {
        return climbable.contains(key(x, y, z));
    }

    @Override
    public int walkVerdict(int x, int y, int z) {
        long k = key(x, y, z);
        if (lava.contains(k)) {
            return WALK_LAVA;
        }
        if (water.contains(k)) {
            return WALK_WATER;
        }
        // A ladder is CLEAR to a body while still reading as solid to a collision query, so the
        // solid branch below must not swallow it. Getting this wrong in the other direction --
        // treating a ladder as blocked -- would make Stance.hasRoom refuse the cell and no climb
        // would ever be legal.
        if (climbable.contains(k)) {
            return WALK_CLEAR;
        }
        return solid.contains(k) ? WALK_BLOCKED : WALK_CLEAR;
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return water.contains(key(x, y, z));
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return solid.contains(key(x, y, z));
    }

    @Override
    public boolean canPlaceAt(int x, int y, int z) {
        // World legality only, matching what was measured on the live client: emptiness, and NOT a
        // requirement for a neighbouring face. The aiming gate lives in Stance.canBridgeTo. Lava is
        // "empty" here for the same reason it is on the live view -- a block placed into it replaces
        // it -- so a test that wants "no bridge over lava" is testing the planner's policy and not
        // this fake's.
        return !isSolid(x, y, z);
    }

    @Override
    public int blockBudget() {
        return budget;
    }

    /** Apply a plan's placements, so a test can assert the world it would leave behind. */
    void applyPlacements(Iterable<Move> moves) {
        for (Move m : moves) {
            if (m.requiresPlacement()) {
                Stance c = m.placeCell();
                solid(c.x(), c.y(), c.z());
                budget--;
            }
        }
    }
}
