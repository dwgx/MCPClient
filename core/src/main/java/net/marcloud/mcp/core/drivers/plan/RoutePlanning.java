package net.marcloud.mcp.core.drivers.plan;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;

/**
 * Turns "reach this block" into a running {@link RouteExecutor}: plan first, then execute.
 *
 * <p>The planning happens ONCE, when the intent is bound, and that is a deliberate limit rather than
 * an oversight. Re-planning every tick would be more robust to a changing world and it is the obvious
 * next step, but it is also a different thing to get right (when to abandon a plan, how to avoid
 * thrashing between two equal routes) and shipping it silently inside a factory would make the first
 * failure hard to attribute. What exists here is honest: one plan, executed, and a failure that says
 * where the player stopped.
 *
 * <p><b>Which world the plan is built against, and why it is reported.</b> The server world is the
 * authority -- it validates the client's prediction and reverts what it refuses -- so a plan built on
 * the client's belief can be rubber-banded away. In single player the integrated server is reachable
 * and is used. On a real server it is not, and the client world is all there is; the plan is still
 * built, because refusing to move on multiplayer would be worse, but the outcome message names which
 * world it used so a caller can tell a rubber-band from a bug.
 */
public final class RoutePlanning {

    private RoutePlanning() {
    }

    /**
     * Plan a route to the target and return the machine that will walk it.
     *
     * <p>Never returns null. A planning failure comes back as a {@link LocomotionController} whose
     * first tick reports that failure and terminates -- so the outcome travels through the MOVE slot
     * exactly like any other failure and {@code act_status} can read it. Returning null, or throwing,
     * would put the reason somewhere the caller cannot see.
     */
    public static LocomotionController executorFor(GameAccess game, int gx, int gy, int gz,
                                                  int blockBudget) {
        if (game == null || !game.isInWorld() || game.player() == null) {
            return refusal("not in a world, so there is nothing to plan a route across");
        }

        World world = serverWorldOr(game);
        boolean authoritative = world != null && world != game.world();
        if (world == null) {
            return refusal("no world could be read to plan against");
        }

        double px = game.player().posX;
        double py = game.player().posY;
        double pz = game.player().posZ;
        LiveBlockView view = new LiveBlockView(world, px,
                py + game.player().getEyeHeight(), pz, blockBudget);

        Stance start = new Stance((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
        Stance goal = new Stance(gx, gy, gz);
        Planner.Plan plan = new Planner(view).plan(start, goal);

        String source = authoritative ? "server world" : "CLIENT world (no integrated server: a plan "
                + "built on the client's prediction can be reverted by the server)";

        if (!plan.found()) {
            // The unread count is the difference between "there is no way there" and "I could not see
            // far enough to tell". Folding them together would make a chunk-loading problem look like
            // a terrain problem, and the caller would go looking at the wrong thing.
            return refusal("no route from " + describe(start) + " to " + describe(goal)
                    + " using the " + source + ": " + plan.failure()
                    + (view.unreadCells() > 0
                        ? " -- and " + view.unreadCells() + " cell(s) could not be read at all, so "
                          + "this may be unloaded chunks rather than impassable ground"
                        : ""));
        }
        return new RouteExecutor(plan, blockBudget);
    }

    /** The integrated server's world for this player when there is one, else the client's. */
    private static World serverWorldOr(GameAccess game) {
        try {
            IntegratedServer srv = game.mc().getIntegratedServer();
            if (srv != null) {
                EntityPlayerMP sp = srv.getConfigurationManager().getPlayerList().isEmpty()
                        ? null : srv.getConfigurationManager().getPlayerList().get(0);
                if (sp != null) {
                    return sp.getServerForPlayer();
                }
            }
        } catch (Throwable ignored) {
            // Any failure reaching the server side falls back to the client world rather than
            // refusing to move: a degraded plan the caller is told about beats no plan at all.
        }
        return game.world();
    }

    private static String describe(Stance s) {
        return "(" + s.x() + "," + s.y() + "," + s.z() + ")";
    }

    /**
     * A machine that does nothing and says why on its first tick.
     *
     * <p>This is how a planning failure reaches {@code act_status}: as a terminal outcome on the slot,
     * indistinguishable in shape from an execution failure, so one reporting path covers both.
     */
    private static LocomotionController refusal(String why) {
        return new LocomotionController() {
            @Override
            public ActOutcome tick(ActActuator act) {
                return ActOutcome.failed("route not planned: " + why);
            }

            @Override
            public float forward() {
                return 0f;
            }

            @Override
            public float strafe() {
                return 0f;
            }

            @Override
            public int ticks() {
                return 0;
            }

            @Override
            public void requestCancel() {
                // Nothing to cancel: this machine is already terminal on its first tick.
            }
        };
    }
}
