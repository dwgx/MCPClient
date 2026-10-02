package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.drivers.plan.BlockView;

/**
 * A body that WALKS, so a controller's steering can be tested against a world that pushes back.
 *
 * <p><b>Why this exists rather than a scripted position.</b> Every previous locomotion test set
 * {@code act.setPosition(...)} between ticks, which is right for asking "does the controller accept
 * this position" and useless for asking "what does the controller do when it cannot move". The
 * wedge this file's tests exist for is not a position a test can simply declare: it is the state
 * where the axes are held, the world refuses the step, and the controller has to work out why from
 * the body box and the block in it. A fake that teleports cannot produce that state, so a fake that
 * teleports cannot prove a recovery works.
 *
 * <p>So this integrates one tick of vanilla's own shape, which is three rules and no more:
 *
 * <ol>
 *   <li>The axes are turned back into a world bearing by inverting {@code Entity.moveFlying}'s
 *       rotation -- the same inversion {@link NavController#steer} is the inverse of, so the two
 *       agree by construction rather than by a shared constant.</li>
 *   <li>The step is a fixed {@value #STEP} blocks, the measured walk speed (4.2 blocks/second on
 *       a live client). Vanilla normalises the input vector rather than the speed, so the length of
 *       the key mask does not change how far the body goes -- {@code moveFlying:1224-1241}.</li>
 *   <li>The new AABB is intersected with the world's solid cells, and if it intersects the body
       does not move at all and {@code collidedHorizontally} is set.</li>
 * </ol>
 *
 * <p><b>The whole move or nothing, and that is the one modelling choice worth arguing about.</b>
 * An earlier version slid on each axis in turn, the way an axis-aligned relief would, and it
 * <b>could not produce a wedge at all</b>: a body pressing east into a log simply slid north past
 * it, which is a correct model of a purely axial press and a useless one for the case these tests
 * exist for. The measured failure is a DIAGONAL squeeze -- a body whose box reaches 0.06 blocks
 * into a log and stops, having advanced not at all -- and on a diagonal the two axes are not
 * separable: the component that would relieve the box is the one carrying it into the next block.
 * A whole-move rule reproduces the measured state exactly, and it is the state the live route was
 * in for ten blocks.
 *
 * <p><b>The intersection rule is vanilla's, and it is strict.</b> {@code AxisAlignedBB.intersectsWith}
 * is {@code a.maxX > b.minX && a.minX < b.maxX}, so a box exactly flush against a block's face is
 * NOT intersecting -- and that is what a body resting against a wall looks like, which is why
 * {@link NavController}'s jam read counts CONTACT while this collision model does not. The two
 * rules differ deliberately and the tests pin both: the body stops one step short of penetrating,
 * and the jam read still sees the block it is flush against.
 *
 * <p>No gravity and no vertical motion: the feet stay where a test put them and {@code onGround} is
 * the test's to set, because a fall is {@code RouteExecutor}'s subject and simulating one here would
 * make every wedge test about falling instead.
 */
public final class BodySim {

    /** Blocks per tick at full walk: the measured 4.2 blocks/second, one tick at a time. */
    private static final double STEP = 0.2D;

    private BodySim() {
    }

    /**
     * Advance the fake body one tick from the axes a controller just published.
     *
     * <p>Call it AFTER {@code tick}, so the axes published on this tick are the ones that move
     * the body, and it sets {@code collidedHorizontally} from the outcome -- which is the signal
     * the controller reads back next tick to decide whether it is wedged.
     *
     * <p>The axes are passed rather than read off the actuator because they are not on the
     * actuator: they are what the controller published, and {@code MoveApplier} is what normally
     * writes them into the game's input. Reading them back out of the actuator would test the
     * applier instead of the controller.
     */
    public static void step(FakeActuator act, float forward, float strafe) {
        double yaw = Math.toRadians(act.yaw());
        double f = forward;
        double s = strafe;
        double mag = Math.hypot(f, s);
        double wx = 0;
        double wz = 0;
        if (mag > 1e-9) {
            // The inverse of NavController.keysForBearing, i.e. of moveFlying's own rotation:
            // forward = wz*cos(yaw) - wx*sin(yaw); strafe = wx*cos(yaw) + wz*sin(yaw).
            wx = (-Math.sin(yaw) * f + Math.cos(yaw) * s) / mag;
            wz = (Math.cos(yaw) * f + Math.sin(yaw) * s) / mag;
        }
        double dx = wx * STEP;
        double dz = wz * STEP;
        double px = act.pos[0];
        double pz = act.pos[2];

        double nx = px + dx;
        double nz = pz + dz;
        boolean blocked = intersects(act, nx, nz);
        act.setPosition(blocked ? px : nx, act.pos[1], blocked ? pz : nz);
        act.collidedHorizontally = blocked;
    }

    /**
     * Whether the body box at this XZ would overlap a solid cell, by vanilla's strict rule.
     *
     * <p>Vertical span is the two cells a 1.8-tall box reaches from a whole-block stance, and the
     * block under the feet is excluded by construction -- a box resting on a floor does not
     * intersect it.
     */
    private static boolean intersects(FakeActuator act, double x, double z) {
        double minX = x - NavController.BODY_HALF;
        double maxX = x + NavController.BODY_HALF;
        double minZ = z - NavController.BODY_HALF;
        double maxZ = z + NavController.BODY_HALF;
        double minY = act.pos[1];
        double maxY = minY + NavController.BODY_HEIGHT;
        for (int cx = (int) Math.floor(minX); cx <= (int) Math.floor(maxX); cx++) {
            if (overlap(minX, maxX, cx) <= 0) {
                continue;
            }
            for (int cz = (int) Math.floor(minZ); cz <= (int) Math.floor(maxZ); cz++) {
                if (overlap(minZ, maxZ, cz) <= 0) {
                    continue;
                }
                for (int cy = (int) Math.floor(minY); cy <= (int) Math.floor(maxY); cy++) {
                    if (overlap(minY, maxY, cy) > 0 && act.blockPresent(cx, cy, cz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double overlap(double lo, double hi, int cell) {
        return Math.min(hi, cell + 1.0D) - Math.max(lo, cell);
    }

    /**
     * The planner's view of a {@link FakeActuator}'s world, so a test can hand the same cells to
     * {@code NavController} for standability that it hands the actuator for collision.
     *
     * <p>One world, not two: the whole point of the recovery is that the agent asks the PLANNER
     * whether a cell is standable, and a test that gave the controller a different world from the
     * one the body collides with would be testing two worlds and would pass for the wrong reason.
     * This adapter supplies facts and nothing else -- {@link net.marcloud.mcp.core.drivers.plan.Stance}
     * still owns the rule, exactly as it does in production.
     */
    public static BlockView blockView(FakeActuator act) {
        return new BlockView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return act.blockPresent(x, y, z);
            }

            @Override
            public int walkVerdict(int x, int y, int z) {
                String n = act.blockAt(x, y, z);
                if (n != null) {
                    String bare = n.replace("minecraft:", "");
                    if (bare.equals("lava") || bare.equals("flowing_lava")) {
                        return WALK_LAVA;
                    }
                    if (bare.equals("water") || bare.equals("flowing_water")) {
                        return WALK_WATER;
                    }
                }
                return act.blockPresent(x, y, z) ? WALK_BLOCKED : WALK_CLEAR;
            }

            @Override
            public boolean canPlaceAt(int x, int y, int z) {
                return !act.blockPresent(x, y, z);
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                String n = act.blockAt(x, y, z);
                return n != null && n.replace("minecraft:", "").endsWith("water");
            }

            @Override
            public int blockBudget() {
                return 0;
            }
        };
    }
}