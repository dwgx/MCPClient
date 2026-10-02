package net.marcloud.mcp.core.drivers.plan;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.drivers.act.MoveTactic;
import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;
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
            return refusal(observed("not in a world, so there is nothing to plan a route across"),
                    NO_SEARCH_RAN);
        }

        World world = serverWorldOr(game);
        boolean authoritative = world != null && world != game.world();
        if (world == null) {
            return refusal(observed("no world could be read to plan against"), NO_SEARCH_RAN);
        }

        double px = game.player().posX;
        double py = game.player().posY;
        double pz = game.player().posZ;
        // The player goes in so the walk verdicts are vanilla's own: func_176170_a sizes and
        // dereferences the entity on its first line, and LocalGrid already documents what a null
        // one silently costs. It is the CLIENT player even when the plan is built against the
        // server world -- the one case where that matters is the verdict's rail branch, which reads
        // the entity's own world, and both sides agree about a rail the player is standing on.
        LiveBlockView view = new LiveBlockView(world, game.player(), px,
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
            //
            // The count was ALREADY being read here, to build this sentence. Grading the refusal
            // therefore costs zero extra world reads, which is the only reason the grade is
            // affordable at all (ADR-0005 rejects a mechanism that costs capability and buys none).
            // The mapping is the honest one and not the cheap one: unreadCells() > 0 means part of
            // the searched area was never seen, so "no route" is a statement about this client
            // rather than about the terrain. Mapping it to OBSERVED -- the cheapest mapping, and the
            // one that looks right, because the search definitely ran -- is the lie this layer
            // exists to prevent.
            //
            // And it TRAVELS as a value, which is the decision this wave made rather than deferred.
            // It was already computed; a caller that could read the grade could learn THAT the
            // search could not see enough and still not learn HOW MUCH, which is the same defect
            // NavHazard was added to close and the one the dig's `why` was promoted out of prose to
            // close. A caller in that position substring-matches the sentence, and the sentence is
            // for a reader. The deferred shape was only defensible if the quantity were not already
            // computed anywhere, and here it was.
            return refusal(noRouteMessage(start, goal, source, plan.failure(), view.unreadCells()),
                    view.unreadCells());
        }
        // The view goes on with the plan: a body that wedges mid-route asks it whether a
        // neighbouring cell is standable, and the only view allowed to answer that is the one the
        // plan was searched against. Two views would be two answers to one question.
        return new RouteExecutor(plan, blockBudget, view);
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
     * A refusal whose reason was read off a live field on this very call.
     *
     * <p>Both refusals above are OBSERVED and the claim is exact rather than generous: "not in a
     * world" is {@code game.isInWorld()} and {@code game.player() == null}, and "no world could be
     * read" is {@code serverWorldOr} having returned null. Every one of those is a field read in the
     * expression that produces the sentence -- there is no derivation to hide behind, and the
     * sentence cannot be wrong in the way a derived one can.
     *
     * <p><b>Why this does not go through {@code Graded.observed(...)}.</b> That factory is
     * package-private to {@code core.util} precisely so the cheap claim is awkward, and
     * {@code RoutePlanning} lives in {@code drivers.plan}. Naming the constant here is the
     * deliberate escape hatch, and it is not invisible: this file is in the allowlist that
     * {@code GradedCallSitesAreAllowlistedTest} checks, so this class can be seen claiming OBSERVED
     * without anyone going looking for it.
     */
    private static Graded<String> observed(String why) {
        return new Graded<>(why, Belief.OBSERVED, null);
    }

    /**
     * The machine a graded refusal is delivered through, exposed so the grade can be driven
     * headlessly end to end.
     *
     * <p>{@link #executorFor} is the only public door into routing and it needs a
     * {@code GameAccess} and a live {@code World}, neither of which {@code core/src/test} can build
     * -- so without this the grade could be asserted in isolation and then quietly dropped on the
     * way to the slot, which is precisely the failure that leaves every other assertion green.
     * Package-private, not public: it adds no capability, it makes an existing one observable.
     *
     * <p>And with the count, because the count is the half that has to survive the same journey. A
     * grade that reached the slot while the number beside it did not would be a row reading
     * {@code belief: "UNKNOWN", unreadCells: null} -- exactly as unreadable as the prose shape this
     * replaced, one field down.
     */
    static LocomotionController refusalFor(Graded<String> why) {
        return refusal(why, NO_SEARCH_RAN);
    }

    /** As {@link #refusalFor}, with the unread-cell count travelling beside the grade. */
    static LocomotionController refusalFor(Graded<String> why, Integer unreadCells) {
        return refusal(why, unreadCells);
    }

    /**
     * No search produced a count, so no count travels.
     *
     * <p>Distinct from {@code 0}. A zero would say the search ran and every cell it asked about was
     * readable -- a stronger claim about the world than either of the two refusals above can make,
     * because neither of them ran a search at all. Collapsing them onto {@code 0} would let a caller
     * read a capability it was never given: "this failure did not involve unread terrain" and "this
     * failure involved unread terrain and found none of it unread" are different sentences, and
     * {@code null} is the only value that says the first without pretending to be the second.
     */
    static final Integer NO_SEARCH_RAN = null;

    /**
     * The refusal a failed plan produces, carrying how well-earned it is AND how much of the
     * searched area could not be read.
     *
     * <p>Extracted from {@link #executorFor} so the grading is a pure function of the unread count
     * and can be driven headless. That is the same reason {@code BlockProbe.decide} is separated
     * from {@code BlockProbe.at} (BlockProbe.java:81-84): {@code World} cannot be constructed in
     * {@code core/src/test}, and a rule that cannot be tested headless is a rule whose ordering
     * nobody will ever check. Here the thing worth checking is the MAPPING, and it is the whole
     * point of the change.
     *
     * <p><b>The message text is byte-identical to what it was before the belief existed</b>, and
     * that is deliberate rather than incidental. A reader's existing understanding of the refusal
     * prose must not shift under them, and the grade is meant to be something they can now ask a
     * question of -- not a rewording.
     *
     * @param unreadCells cells the search asked about and could not read; zero means the whole
     *                    searched area was seen
     */
    static Graded<String> noRouteMessage(Stance start, Stance goal, String source, String failure,
                                         int unreadCells) {
        String message = "no route from " + describe(start) + " to " + describe(goal)
                + " using the " + source + ": " + failure
                + (unreadCells > 0
                    ? " -- and " + unreadCells + " cell(s) could not be read at all, so "
                      + "this may be unloaded chunks rather than impassable ground"
                    : "");
        if (unreadCells > 0) {
            return Graded.unknown(message, "the search could not read " + unreadCells
                    + " cell(s) of the area it searched, so this refusal is a statement about what "
                    + "this client could not see, not about the terrain");
        }
        return new Graded<>(message, Belief.OBSERVED, null);
    }

    /**
     * A refusal whose grade carries with it how many of the searched cells could not be read.
     *
     * <p>The count is an argument rather than something {@link #noRouteMessage} carries inside its
     * sentence, and the reason is reachability: a caller that has the grade still cannot get the
     * count without re-reading prose. {@link ActOutcome#unreadCells()} is the door, and it accepts
     * {@code null} for the two refusals that ran no search.
     */
    private static LocomotionController refusal(Graded<String> why, Integer unreadCells) {
        return new LocomotionController() {
            @Override
            public ActOutcome tick(ActActuator act) {
                // The grade AND the count travel out with the prose, on ADDED overloads, so a caller
                // can ask "was that a statement about the terrain" and "how much could not be seen"
                // without substring-matching a sentence and without the eight pre-existing
                // factories changing by one byte.
                return unreadCells == null
                        ? ActOutcome.failed("route not planned: " + why.value(), why.belief())
                        : ActOutcome.failed("route not planned: " + why.value(), why.belief(),
                                unreadCells);
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
            public boolean jump() {
                // Nothing to climb: this machine is terminal on its first tick, and a jump axis is
                // only meaningful alongside a walk it belongs to.
                return false;
            }

            @Override
            public boolean creeping() {
                // No plan means no move, so there is no destination to be a brink. Same shape as
                // the jump axis above, and for the same reason: this machine is terminal on its
                // first tick and never walks anything.
                return false;
            }

            @Override
            public MoveTactic tactic() {
                // Null, and it is not a gap: this machine published no axes because it never ran a
                // controller. It is terminal on its first tick and reads no world, so there is no
                // tactic to report and inventing one would be a fabricated read.
                return null;
            }

            @Override
            public int ticks() {
                return 0;
            }

            @Override
            public void requestCancel() {
                // Already terminal on its first tick; there is nothing to tear down.
            }
        };
    }
}
