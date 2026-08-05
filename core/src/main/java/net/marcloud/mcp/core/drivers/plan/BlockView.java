package net.marcloud.mcp.core.drivers.plan;

/**
 * The only thing a planner is allowed to ask about the world.
 *
 * <p>Deliberately four questions, not a world handle. {@code LocalGrid} already samples the world
 * but it samples it for the {@code world_view} TOOL -- columnar summaries shaped for a model to
 * read, keyed to a radius and an origin, needing a live {@code WorldClient}. A search asks a
 * different shape of question ("is THIS cell solid") a few thousand times per plan, and it has to
 * be answerable headless or the planner cannot be tested at all. That is the whole reason this
 * interface exists rather than the planner taking a world.
 *
 * <p><b>Coordinates are absolute block coordinates.</b> No origin, no radius, no offsets: the
 * planner's own bug surface is coordinate arithmetic, and the telly envelope probe already paid
 * for that once -- it anchored offsets to {@code floor(hypothetical y)} instead of the player's
 * standing block, so the same offset named different blocks on different rows and two overlapping
 * envelopes came out with an empty intersection. Absolute coordinates cannot drift that way.
 *
 * <p><b>What is NOT here, and why.</b> No "is this walkable" and no "can I stand here": those are
 * DERIVED, and deriving them in one place ({@link Stance}) is what keeps the search and the
 * executor from disagreeing about what a legal position is. This repo has the scar for the
 * opposite arrangement -- one name rule implemented in six places with three different answers,
 * and a floor check built on {@code isFullCube()} that could not fail because vanilla returns true
 * for air.
 */
public interface BlockView {

    /**
     * Whether a full-cube collision body occupies this block, i.e. whether it holds a player up
     * and blocks movement through it.
     *
     * <p>Implementations must answer this from the block's actual collision box, NOT from
     * {@code isFullCube()}: {@code Block.isFullCube()} returns true unconditionally in 1.8.9 and
     * {@code BlockAir} does not override it, so a check written on it reports air as solid and can
     * never fail. That trap cost this repo a probe that reported a clean floor while the player
     * stood over a pit.
     */
    boolean isSolid(int x, int y, int z);

    /**
     * Whether this block is empty enough for a player's body to occupy it.
     *
     * <p>Not simply {@code !isSolid}: water, tall grass and torches are all non-solid but they are
     * not all equally safe to route through, and the distinction belongs to the implementation
     * that can see the real block. A planner that treats "not solid" as "fine to walk into" walks
     * into lava.
     */
    boolean isPassable(int x, int y, int z);

    /**
     * Whether a block can legally be PLACED into this cell -- world legality only.
     *
     * <p>Measured on the live client (2026-08-05, see docs/agency/telly-test-plan.md section 7.5):
     * this is reach plus emptiness plus not intersecting the player, and it does NOT require a
     * neighbouring face. A floating stone is legal as far as the world is concerned -- vanilla's
     * {@code canBlockBePlaced} accepted one at (5,0,0) with no neighbour at all.
     *
     * <p>The other half of placement -- whether the player can AIM at the cell -- is a separate
     * gate living in {@code ActActuator.rightClickBlock}, which needs a target block plus a face.
     * It is asked by {@link Stance#canBridgeTo} rather than here, because it depends on what is
     * already built and therefore changes as the plan is executed. Folding the two into one
     * question produces a planner that confidently aims at empty space.
     */
    boolean canPlaceAt(int x, int y, int z);

    /**
     * How many placeable blocks the planner may spend. A bridge is not free, and a search that
     * treats it as free returns plans that strand the player mid-air -- the failure mode
     * docs/agency/handoff-2026-08-08.md section 5 lists as "中途没方块 = FAILED 并带位置".
     */
    int blockBudget();
}
