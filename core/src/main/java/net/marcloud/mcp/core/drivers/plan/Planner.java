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
     * removing from its tools ("放了 N 块 不是成功判据").
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
    private record Node(Stance at, int blocksSpent, int g, int f) { }

    private record Key(Stance at, int blocksSpent) { }

    /**
     * Plan a route from {@code start} to {@code goal}.
     *
     * <p>The start is validated rather than assumed: a caller standing somewhere illegal (mid-fall,
     * inside a block after a teleport) would otherwise get a plan rooted at a position the executor
     * cannot reproduce, and the first move would fail for a reason that has nothing to do with the
     * plan.
     */
    public Plan plan(Stance start, Stance goal) {
        if (!start.isStandable(world)) {
            return Plan.none("the start stance is not standable: the player is not on solid ground "
                    + "with room for its body, so no plan from here can be executed", 0);
        }
        if (!goal.hasRoom(world)) {
            return Plan.none("the goal has no room for a body; a plan that ends inside a block is "
                    + "not a plan", 0);
        }

        Map<Key, Integer> best = new HashMap<>();
        Map<Key, Move> cameBy = new HashMap<>();
        PriorityQueue<Node> frontier = new PriorityQueue<>((a, b) -> Integer.compare(a.f(), b.f()));

        Key startKey = new Key(start, 0);
        best.put(startKey, 0);
        frontier.add(new Node(start, 0, 0, heuristic(start, goal)));

        int expansions = 0;
        while (!frontier.isEmpty()) {
            if (++expansions > MAX_EXPANSIONS) {
                return Plan.exhausted(expansions);
            }
            Node cur = frontier.poll();
            Key curKey = new Key(cur.at(), cur.blocksSpent());
            Integer known = best.get(curKey);
            if (known != null && known < cur.g()) {
                continue; // a cheaper route to this exact state was already expanded
            }
            if (cur.at().equals(goal)) {
                return Plan.of(reconstruct(cameBy, curKey, start), expansions);
            }

            for (Move m : gen.movesFrom(cur.at(), cur.blocksSpent())) {
                int spent = cur.blocksSpent() + (m.requiresPlacement() ? 1 : 0);
                int g = cur.g() + m.cost();
                Key nextKey = new Key(m.to(), spent);
                Integer prior = best.get(nextKey);
                if (prior != null && prior <= g) {
                    continue;
                }
                best.put(nextKey, g);
                cameBy.put(nextKey, m);
                frontier.add(new Node(m.to(), spent, g, g + heuristic(m.to(), goal)));
            }
        }
        return Plan.none("every reachable stance was explored and the goal was not among them; with "
                + world.blockBudget() + " block(s) of budget there is no route", expansions);
    }

    /**
     * Manhattan distance times the walk cost.
     *
     * <p>Admissible on purpose: it never exceeds the true remaining cost, because every move covers
     * at most one horizontal step and no move costs less than a walk. An inadmissible heuristic
     * would make A* return cheap-looking plans that are not the cheapest, and the symptom would be
     * a planner that bridges when it did not have to -- indistinguishable, from the outside, from a
     * cost policy that is simply wrong.
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
            int spent = cursor.blocksSpent() - (m.requiresPlacement() ? 1 : 0);
            cursor = new Key(m.from(), spent);
            if (m.from().equals(start) && spent == 0) {
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
