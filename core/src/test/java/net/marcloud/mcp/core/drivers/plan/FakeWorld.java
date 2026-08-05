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
 */
final class FakeWorld implements BlockView {

    private final Set<Long> solid = new HashSet<>();
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

    @Override
    public boolean isSolid(int x, int y, int z) {
        return solid.contains(key(x, y, z));
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        return !isSolid(x, y, z);
    }

    @Override
    public boolean canPlaceAt(int x, int y, int z) {
        // World legality only, matching what was measured on the live client: emptiness, and NOT a
        // requirement for a neighbouring face. The aiming gate lives in Stance.canBridgeTo.
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
