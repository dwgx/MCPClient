package net.marcloud.mcp.core.drivers.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over {@link NeighborGen}'s moves. The model says where; this computes how.
 *
 * <p>This is the "code holds the loop" half of Fork D. The model sets a goal and the search decides
 * the steps -- including whether to build. There is no bridge routine and no technique table: a gap
 * crossing is the search picking {@link Move.Kind#BRIDGE} because the alternative was longer, and
 * that is the whole mechanism.
 *
 * <p><b>Search state is (stance, blocksSpent), not stance.</b> Two routes reaching the same cell are
 * not interchangeable if one of them spent its last block getting there: the cheaper one may be
 * unable to continue. Keying visits on the stance alone would let a block-poor route close the cell
 * against a block-rich one and return "no path" for a reachable goal. This costs the search a wider
 * state space and buys correctness that is otherwise unavailable.
 */
public final class Planner {

    /**
     * Hard ceiling on states expanded, so a hopeless goal fails in bounded time.
     *
     * <p>It is a REFUSAL, not a truncation: hitting it returns {@link Plan#exhausted} rather than
     * the best partial route. A partial plan is the more dangerous answer -- it walks the player
     * somewhere it did not ask to be and reports success, which is the class of lie this repo keeps
     * removing from its tools.
     *
     * <p><b>This number was once raised to 400,000 and that was wrong.</b> The reason recorded at
     * the time was a live refusal, "search hit its 20000-state ceiling", for a route between two
     * stance cells one block apart. That message does not support the change: a goal a single step
     * away is found by expanding the start and its neighbours, and
     * {@code TheSearchCeilingDoesNotFireOnAShortGoalTest} measures a four-step walk across open
     * ground at well under 500 expansions -- it passes at 20,000 unchanged. Reaching the ceiling for
     * a near goal means the frontier spread over everything the player can stand on, which is what
     * an UNREACHABLE goal looks like, not a short one.
     *
     * <p>So the ceiling firing is a DIAGNOSIS, not a budget request. Raising it cannot make an
     * unreachable goal appear; it only makes the refusal twenty times slower, and it teaches the
     * next reader to answer "no route" with a bigger number. The refusal semantics stay as they
     * are: hitting it returns no partial route.
     */
    public static final int MAX_EXPANSIONS = 20_000;

    private final BlockView world;
    private final NeighborGen gen;

    public Planner(BlockView world) {
        this.world = world;
        this.gen = new NeighborGen(world);
    }

    /** The outcome of a search: either an ordered move list, or an honest reason there is none. */
    public record Plan(List<Move> moves, String failure, int expansions) {

        public boolean found() {
            return failure == null;
        }

        /** Blocks this plan will consume, so a caller can check its inventory before starting. */
        public int blocksNeeded() {
            int n = 0;
            for (Move m : moves) {
                if (m.requiresPlacement()) {
                    n++;
                }
            }
            return n;
        }

        static Plan of(List<Move> moves, int expansions) {
            return new Plan(List.copyOf(moves), null, expansions);
        }

        static Plan none(String why, int expansions) {
            return new Plan(List.of(), why, expansions);
        }

        static Plan exhausted(int expansions) {
            return none("search hit its " + MAX_EXPANSIONS + "-state ceiling without reaching the "
                    + "goal; no partial route is returned because walking somewhere the caller did "
                    + "not ask for and reporting success is worse than failing", expansions);
        }
    }

    /** One entry in the frontier. */
    private record Node(Stance at, int blocksSpent, int airSpent, int g, int f) { }

    /**
     * A visited state, and the two budgets it was reached with.
     *
     * <p>Air is in the key for exactly the reason blocks are, and it is not a refinement: two
     * routes to the same cell are genuinely different if one of them is nearly out of air, because
     * the block-rich one can still bridge out and the air-poor one cannot swim. Keying on the
     * stance alone would let a nearly-drowned arrival close a cell against a fresh one and return
     * "no path" for a goal the player is standing next to.
     */
    private record Key(Stance at, int blocksSpent, int airSpent) { }

    /**
     * Plan a route from {@code start} to {@code goal}.
     *
     * <p>The start is validated rather than assumed: a caller standing somewhere illegal (mid-fall,
     * inside a block after a teleport) would otherwise get a plan rooted at a position the executor
     * cannot reproduce, and the first move would fail for a reason that has nothing to do with the
     * plan.
     */
    public Plan plan(Stance start, Stance goal) {
        // isOccupiable, not isStandable: a caller standing on a ladder four blocks up a shaft, or
        // in the middle of a river, is in a position a route can start from, and the old test
        // refused both with a message about solid ground. Stance spells the three ways of being
        // held up and this is the one place that has to accept all of them.
        if (!start.isOccupiable(world)) {
            return Plan.none("the start stance is not occupiable: the player is on neither solid "
                    + "ground, a ladder nor water, with room for its body, so no plan from here "
                    + "can be executed", 0);
        }
        if (!goal.hasRoom(world)) {
            // Lava is named, because the two refusals here need different next actions: a cell
            // with a block in it is a coordinate the caller got wrong, while a cell of lava is a
            // destination that cannot be stood in at all, at any coordinate. The room check
            // already refuses lava (it is never passable); this only says which one it was.
            boolean lava = world.walkVerdict(goal.x(), goal.y(), goal.z()) == BlockView.WALK_LAVA;
            return Plan.none("the goal has no room for a body" + (lava
                    ? ": the cell is LAVA, and a route that ends in lava is not a route"
                    : "; a plan that ends inside a block is not a plan"), 0);
        }

        Map<Key, Integer> best = new HashMap<>();
        Map<Key, Move> cameBy = new HashMap<>();
        PriorityQueue<Node> frontier = new PriorityQueue<>((a, b) -> Integer.compare(a.f(), b.f()));

        Key startKey = new Key(start, 0, 0);
        best.put(startKey, 0);
        frontier.add(new Node(start, 0, 0, 0, heuristic(start, goal)));

        int expansions = 0;
        while (!frontier.isEmpty()) {
            if (++expansions > MAX_EXPANSIONS) {
                return Plan.exhausted(expansions);
            }
            Node cur = frontier.poll();
            Key curKey = new Key(cur.at(), cur.blocksSpent(), cur.airSpent());
            Integer known = best.get(curKey);
            if (known != null && known < cur.g()) {
                continue; // a cheaper route to this exact state was already expanded
            }
            if (cur.at().equals(goal)) {
                return Plan.of(reconstruct(cameBy, curKey, start), expansions);
            }

            for (Move m : gen.movesFrom(cur.at(), cur.blocksSpent(), cur.airSpent())) {
                int spent = cur.blocksSpent() + (m.requiresPlacement() ? 1 : 0);
                int air = cur.airSpent() + m.airTicks();
                int g = cur.g() + m.cost();
                Key nextKey = new Key(m.to(), spent, air);
                Integer prior = best.get(nextKey);
                if (prior != null && prior <= g) {
                    continue;
                }
                best.put(nextKey, g);
                cameBy.put(nextKey, m);
                frontier.add(new Node(m.to(), spent, air, g, g + heuristic(m.to(), goal)));
            }
        }
        return Plan.none("every reachable stance was explored and the goal was not among them; with "
                + world.blockBudget() + " block(s) of budget and " + Move.AIR_MAX
                + " ticks of air there is no route", expansions);
    }

    /**
     * Manhattan distance times the walk cost.
     *
     * <p>Admissible on purpose: it never exceeds the true remaining cost, because every move covers
     * at most one horizontal step and no move costs less than a walk. An inadmissible heuristic
     * would make A* return cheap-looking plans that are not the cheapest, and the symptom would be
     * a planner that bridges when it did not have to -- indistinguishable, from the outside, from a
     * cost policy that is simply wrong.
     *
     * <p><b>Still admissible with two vertical kinds, and that took an argument rather than a
     * hope.</b> A climb and a swim-up cover ZERO horizontal blocks, so they make the true remaining
     * cost larger while the estimate stays put -- which can only make the estimate more of an
     * under-estimate, never an over one. Neither is priced below {@link Move#COST_WALK} either, so
     * the "no move costs less than a walk" half of the argument survives too: the two cheapest
     * additions cost 16 and 25 against a walk's 10. Had a climb been priced at, say, 4 to make
     * ladders attractive, this would have become inadmissible for a goal directly overhead, and
     * the search would have preferred a long detour of cheap climbs over one honest one.
     */
    private static int heuristic(Stance from, Stance goal) {
        return from.horizontalDistanceTo(goal) * Move.COST_WALK;
    }

    private static List<Move> reconstruct(Map<Key, Move> cameBy, Key goalKey, Stance start) {
        Deque<Move> back = new ArrayDeque<>();
        Key cursor = goalKey;
        while (true) {
            Move m = cameBy.get(cursor);
            if (m == null) {
                break;
            }
            back.addFirst(m);
            // Both budgets walk backwards, and the stop condition tests BOTH. Testing only the
            // block count would terminate one step early on a route that began with a swim, and
            // the plan handed to the executor would begin in the middle of a river with no move
            // explaining how the player got there.
            int spent = cursor.blocksSpent() - (m.requiresPlacement() ? 1 : 0);
            int air = cursor.airSpent() - m.airTicks();
            cursor = new Key(m.from(), spent, air);
            if (m.from().equals(start) && spent == 0 && air == 0) {
                break;
            }
        }
        return new ArrayList<>(back);
    }

    /** Unmodifiable empty plan, for callers that need a neutral value. */
    public static Plan nothingToDo() {
        return new Plan(Collections.emptyList(), null, 0);
    }
}
