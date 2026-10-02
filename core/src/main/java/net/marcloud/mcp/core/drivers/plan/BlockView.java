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
 *
 * <p><b>The passability vocabulary is VANILLA'S, borrowed for the same reason the read side
 * borrows it.</b> {@code LocalGrid.walkVerdict} hands the model codes derived straight from
 * {@code WalkNodeProcessor.func_176170_a}; this interface speaks those codes as well, so the half
 * of the repo that TELLS the agent about terrain and the half that ROUTES over it cannot develop
 * two taxonomies that disagree -- which they did, and the disagreement was a planner that walked
 * the player into lava while the tool it read said {@code -2}.
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
     * Vanilla's own passability verdict for a player's BODY at this cell, as an int.
     *
     * <p>The codes are {@code WalkNodeProcessor.func_176170_a}'s, the ones the read side already
     * publishes in {@code LocalGrid.Column.walk}, so a caller that has seen terrain through one
     * side can compare it with the other. For the arguments both sides pass ({@code avoidWater},
     * {@code breakDoors} and {@code enterDoors} all false):
     *
     * <ul>
     *   <li>{@link #WALK_CLEAR} (1) -- nothing in the way</li>
     *   <li>{@link #WALK_WATER} (2) -- clear, but the body is in water or on a closed trapdoor</li>
     *   <li>{@link #WALK_BLOCKED} (0) -- a solid block, or a closed wooden door</li>
     *   <li>{@link #WALK_LAVA} (-2) -- lava</li>
     *   <li>{@link #WALK_FENCE} (-3) -- fence, wall, or a rail the entity is not already on</li>
     *   <li>{@link #WALK_OPEN_TRAPDOOR} (-4) -- an OPEN trapdoor, a vertical panel across the cell
     *       ({@code BlockTrapDoor.isPassable} is {@code !OPEN}, so the CLOSED one is passable and
     *       reads 2; vanilla's own javadoc has these two the wrong way round and the read side's
     *       comment already records that)</li>
     * </ul>
     *
     * <p>An implementation that cannot read the cell answers {@link #WALK_UNKNOWN}, which is a
     * refusal and not a statement about terrain -- the same contract {@link #isSolid} and
     * {@link #isPassable} keep, expressed as a code rather than as two false answers.
     */
    int walkVerdict(int x, int y, int z);

    /** Vanilla's clear verdict: a body fits with nothing to notice. */
    int WALK_CLEAR = 1;

    /**
     * Vanilla's clear-but-wet verdict: water at the body's level, or a closed trapdoor (a trapdoor
     * lying flat is something a player can walk over).
     *
     * <p>Passable, and the code exists so that "passable" does not have to mean "indistinguishable
     * from air": a caller that wants to spend a placement to keep the player dry, or to report what
     * the route crosses, has the fact in hand.
     */
    int WALK_WATER = 2;

    /** Vanilla's blocked verdict: a solid block, or a closed wooden door. */
    int WALK_BLOCKED = 0;

    /**
     * Lava, and the one code the planner overrides.
     *
     * <p>Vanilla answers -2 only while the entity is NOT already in lava
     * ({@code WalkNodeProcessor:255-259}); a player standing in it gets 1. A planner whose map of
     * the world flips under it on the tick the player is burning can route deeper into the lava it
     * is standing in, so the planner reads lava as -2 whether or not the entity is wet. That is a
     * stated policy of this side, not a reproduction of vanilla's answer.
     */
    int WALK_LAVA = -2;

    /** A fence, a wall, or a rail the entity is not already on or above (vanilla's -3). */
    int WALK_FENCE = -3;

    /** An OPEN trapdoor: a vertical panel in the cell, and not something a body passes (vanilla's -4). */
    int WALK_OPEN_TRAPDOOR = -4;

    /**
     * The cell could not be read. Not a statement about terrain, and never passable.
     *
     * <p>The same sentinel value as {@code LocalGrid.WALK_UNKNOWN}, so a caller comparing the two
     * sides is comparing like with like.
     */
    int WALK_UNKNOWN = Integer.MIN_VALUE;

    /**
     * Whether a player's BODY may occupy this cell -- the one place the occupancy rule is written.
     *
     * <p>Derived here rather than left to each implementation, because the whole defect this code
     * exists for was two sides of the repo answering it differently: the read side said lava was
     * -2 and the planner said lava was "not solid, therefore fine to walk into". A {@code default}
     * method means an implementation supplies the vanilla verdict (a fact about the world) and
     * cannot also supply its own opinion about which verdicts are safe (a policy).
     *
     * <p>Water passes and lava does not, and the difference is not decoration: a body in water is
     * wet, a body in lava is dead, and tall grass or a torch is scenery the body walks through
     * ({@code WALK_CLEAR}). Anything the verdict cannot vouch for -- a fence, an open trapdoor, an
     * unread cell -- is refused.
     */
    default boolean isPassable(int x, int y, int z) {
        int verdict = walkVerdict(x, y, z);
        return verdict == WALK_CLEAR || verdict == WALK_WATER;
    }

    /**
     * Whether a block can legally be PLACED into this cell -- world legality only.
     *
     * <p>Measured on the live client (2026-08-05, see docs/agency/HANDOFF.md section 3):
     * this is reach plus emptiness plus not intersecting the player, and it does NOT require a
     * neighbouring face. A floating stone is legal as far as the world is concerned -- vanilla's
     * {@code canBlockBePlaced} accepted one at (5,0,0) with no neighbour at all.
     *
     * <p>The other half of placement -- whether the player can AIM at the cell -- is a separate
     * gate living in {@code ActActuator.rightClickBlock}, which needs a target block plus a face.
     * It is asked by {@link Stance#canBridgeTo} rather than here, because it depends on what is
     * already built and therefore changes as the plan is executed. Folding the two into one
     * question produces a planner that confidently aims at empty space.
     *
     * <p><b>Lava answers true here and that is not an oversight.</b> A block placed into lava
     * replaces it -- vanilla's liquid materials are replaceable -- so the world does accept the
     * placement. Refusing to bridge over lava is a policy of the PLAN, not a fact about the world,
     * and it lives where the plan is built ({@code NeighborGen.addBridge}); putting it here would
     * make this method answer a question it does not claim to answer, and the next reader would
     * trust the claim.
     */
    boolean canPlaceAt(int x, int y, int z);

    /**
     * Whether a ladder or a vine occupies this cell, which is what makes a player standing in it
     * be ON a ladder.
     *
     * <p><b>Vanilla's own predicate, verbatim.</b> {@code EntityLivingBase.isOnLadder:1134-1141}
     * answers exactly this question and nothing more: it floors the player's X, the bottom of
     * their bounding box and their Z, reads that one cell, and tests
     * {@code block == Blocks.ladder || block == Blocks.vine}. This is therefore not a planner's
     * idea of what a ladder is -- it is the same question vanilla asks, at the same granularity,
     * and a planner answering it differently from the physics would be planning climbs the player
     * cannot make.
     *
     * <p><b>Not derived from {@link #isSolid}, and it must not be.</b> A ladder HAS a collision
     * box ({@code BlockLadder.getCollisionBoundingBox:28-32}) and so reads as solid, while its
     * material is {@code Material.circuits} -- a {@code MaterialLogic}, whose
     * {@code blocksMovement()} is false -- so vanilla's walk verdict calls the same cell CLEAR.
     * One cell, two true answers, and {@link #isSolid} is the wrong one to ask. This is the same
     * trap {@code Stance.hasRoom}'s javadoc records for water and lava, and the reason the
     * question is asked rather than inferred.
     *
     * <p>A cell nothing could read answers false, like every other method here: an unread cell is
     * a refusal to claim a climbable, never a claim that there are none.
     */
    boolean isClimbable(int x, int y, int z);

    /**
     * Whether water occupies this cell, which is what puts a body standing in it into swim physics.
     *
     * <p>Distinct from {@link #WALK_WATER} on purpose. That code means "clear, but the body is
     * wet", and vanilla also returns it for a CLOSED trapdoor
     * ({@code WalkNodeProcessor.func_176170_a:211-233} raises the same flag for both), so a
     * verdict of 2 is not a statement that there is water here. A plan that swum on the strength
     * of it would route a player into a closed trapdoor cell and call it a river.
     *
     * <p>Also worth asking separately from {@link #isSolid} for the same reason as the ladder:
     * water has no collision box and so is not solid, and neither fact tells the other.
     */
    boolean isWater(int x, int y, int z);

    /**
     * How many placeable blocks the planner may spend. A bridge is not free, and a search that
     * treats it as free returns plans that strand the player mid-air -- the failure mode
     * docs/agency/HANDOFF.md section 2 lists as "中途没方块 = FAILED 并带位置".
     */
    int blockBudget();
}
