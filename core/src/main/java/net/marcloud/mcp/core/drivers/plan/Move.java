package net.marcloud.mcp.core.drivers.plan;

/**
 * One step of a plan: where the feet end up, and what had to be built, or held, to get there.
 *
 * <p><b>Two resources, deliberately not one.</b> {@link #cost} is search currency -- arbitrary units
 * whose only job is to order two routes against each other. {@link #airTicks} is a real, spendable
 * quantity: vanilla starts a player at 300 air and drains one per tick underwater
 * ({@code EntityLivingBase:326} sets 300, {@code decreaseAirSupply:439} subtracts 1), so a plan that
 * swims further than 300 ticks of water is a plan that drowns the player
 * ({@code EntityLivingBase:303-317} starts taking 2 damage at {@code -20} and every 20 ticks
 * after). They are not merged because "expensive" and "spends the last of your air" are different
 * facts and a cost cannot say the second one -- the same reason {@link #drop} prices depth in the
 * cost while {@link NeighborGen} refuses a drop past {@link Stance#SAFE_DROP_MAX} outright instead
 * of pricing it steeply enough to be free.
 *
 * <p><b>{@code creep} is a fact about the destination, not a preference about the route.</b> It is
 * true when {@link #to()} is a brink: at least one of the four cells beside it at the same height
 * has no floor. That is a fact {@link NeighborGen} can read off the world while generating the
 * move, and it is what makes a creep PLANNABLE rather than merely expressible -- a caller asking to
 * creep cannot tell in advance which block of a thirty-block route is the one that will kill it,
 * and a plan-time vocabulary that cannot say "this move ends at an edge" cannot either. The
 * executor then holds the sneak key for exactly the moves that need it, which is the difference
 * between a route that creeps its two edge moves at full speed on the other twenty-eight and one
 * that creeps the whole thing because the only way to express the request was a route-wide flag.
 *
 * <p><b>It is not in {@link #cost}, and that is the load-bearing decision.</b> Sneaking scales
 * movement input to about 0.3 in 1.8.9, so a creep is genuinely slower -- but pricing it into the
 * search currency would not buy safety, it would buy a DIFFERENT ROUTE. Every route that touches a
 * ledge would gain a detour the search prefers over creeping, and the detour is frequently the
 * thing that is actually dangerous: it is the long way round, past whatever the direct line was
 * avoiding. Vanilla's own answer to a brink is to stop at it, not to route around it, and a
 * planner that re-routes instead is substituting its own opinion for a mechanism the game already
 * ships. So {@code creep} is recorded, reported, and executed; {@link #cost} is untouched and the
 * search picks the same route it picked before.
 *
 * @param from      the stance stepped out of
 * @param to        the stance stepped into
 * @param kind      what sort of step this is, which decides how the executor drives it
 * @param placeCell the block that must be PLACED before the step is possible, or null
 * @param cost      search cost, in arbitrary units where one flat walk is {@link Kind#WALK}'s
 * @param airTicks  ticks of underwater air this move spends. Zero for everything but
 *                  {@link Kind#SWIM}, because nothing else puts the player in water at all
 * @param creep      the destination is a brink, so this move is walked with the sneak key held.
 *                  A fact read off the world at generation time, deliberately NOT priced into
 *                  {@code cost} -- see the class doc for why a creep must not re-route a plan
 */
public record Move(Stance from, Stance to, Move.Kind kind, Stance placeCell, int cost,
                   int airTicks, boolean creep) {

    /**
     * The six-argument form: a move with nothing special about its destination.
     *
     * <p>Kept so that every existing factory call, every test that builds a move by hand, and every
     * plan the executor has already been handed mean what they meant. A {@code Move} that does not
     * say it creeps does not creep, which is the same answer {@link Stance#isStandable} gives for
     * the floor itself.
     */
    public Move(Stance from, Stance to, Move.Kind kind, Stance placeCell, int cost, int airTicks) {
        this(from, to, kind, placeCell, cost, airTicks, false);
    }

    /**
     * Whether this move's destination is a brink and must be walked with the sneak key held.
     *
     * <p>Only ever true for a move that ENDS standing on a floor. A drop lands on one by
     * construction, a bridge places one, and a climb or a swim is held by a ladder or by water --
     * none of which the edge guard has anything to say about, since {@code Entity.moveEntity:626}
     * requires {@code onGround} and a ladder never sets it.
     */
    public boolean creeping() {
        return creep;
    }

    public enum Kind {
        /** Flat step onto existing ground. */
        WALK,
        /** Step up one block; needs a jump in vanilla, since step height is 0.6. */
        STEP_UP,
        /** Controlled fall onto existing ground, bounded by {@link Stance#SAFE_DROP_MAX}. */
        DROP,
        /**
         * Place a block into the gap and step onto it.
         *
         * <p>This is the entry the whole planner exists for: bridging is not a technique the
         * planner knows, it is a MOVE the search may pick when it is cheaper than walking around.
         * That is the difference between "the AI computes what it needs" and "someone hardcoded
         * telly" -- there is no bridge routine anywhere, only a move that happens to place a block,
         * and gap crossings fall out of the search choosing it.
         */
        BRIDGE,
        /**
         * One block along a ladder or vine column, upward or downward.
         *
         * <p>The first kind whose destination is not held up by a FLOOR, and that is the whole
         * reason it is its own kind rather than a {@link #STEP_UP} the steering happens to survive.
         * {@link Stance#isStandable} asks whether the cell below the feet is solid, and four blocks
         * up a ladder shaft it is not: the player is held by the ladder, which never sets vanilla's
         * {@code onGround} at all. A route onto such a cell is executable, and a planner with no
         * kind for it had no way to say so.
         *
         * <p>Both directions are one kind because vanilla's own two are one mechanism
         * ({@code EntityLivingBase.moveEntityWithHeading:1637-1662}): holding forward into the
         * ladder raises the body at 0.2 per tick, and releasing it lets the same clamp carry the
         * body down at no more than 0.15. Which of the two a move is comes from {@link #from} and
         * {@link #to}, not from a flag that could disagree with them.
         */
        CLIMB,
        /**
         * One block of swimming into water, horizontally or upward.
         *
         * <p>The first kind whose destination is held up by neither a floor nor a ladder but by the
         * water itself, which is what makes it cost {@link #airTicks} and what makes
         * {@link Stance#isStandable} the wrong test for it: a cell in a lake has no floor to be
         * solid and no ladder to be climbable, and a planner that only knew those two words had no
         * way to route across a river at all.
         */
        SWIM,
    }

    /** Flat walk: the cheapest thing a player can do, and the unit every other cost is read against. */
    public static final int COST_WALK = 10;

    /** Stepping up costs a jump: slower, and it interrupts the MOVE channel. */
    public static final int COST_STEP_UP = 14;

    /** Falling is fast but gives up height that may have to be re-climbed. */
    public static final int COST_DROP = 12;

    /**
     * Bridging is expensive on purpose, and the number is a POLICY not a measurement.
     *
     * <p>It must exceed a walk by enough that the search prefers any reasonable detour: a block
     * spent is gone, a placement can be refused by the server, and the player is over a void while
     * it happens. Set it too low and the planner bridges across a room it could have walked around;
     * too high and it refuses a two-block gap that has no way around. 6x a walk means the search
     * will walk up to six blocks out of its way rather than place one -- which is the behaviour a
     * caller expects when it says "get there" without saying "and build".
     *
     * <p>Deliberately NOT tuned against a measurement, because there is no measurement to tune it
     * against yet; it is a stated default, and {@code PlannerTest} pins the BEHAVIOUR it produces
     * (prefers the detour at five blocks, bridges at seven) so a future change to the number has to
     * confront what it changes rather than silently re-shaping every plan.
     */
    public static final int COST_BRIDGE = 60;

    /**
     * Climbing costs more than a step up, and the reason is that a climb cannot be abandoned.
     *
     * <p>A stated default on the same footing as {@link #COST_BRIDGE}, not a measurement. What it
     * buys is a search that treats a ladder as a way to gain height without treating it as free: a
     * ladder column beside the direct route is the right answer, a ladder taken instead of a
     * four-block walk is not.
     */
    public static final int COST_CLIMB = 16;

    /**
     * Swimming across, in search units.
     *
     * <p><b>Derived from vanilla's own constants, not guessed.</b> The water branch of
     * {@code EntityLivingBase.moveEntityWithHeading:1700-1733} calls
     * {@code moveFlying(strafe, forward, 0.02F)} and then damps with {@code motionX *= 0.8}, so a
     * body holding one key gains 0.02 per tick and loses a fifth of its speed, settling at
     * {@code 0.02 / 0.2 = 0.08} blocks per tick. A walk is about 0.2 blocks per tick (measured live,
     * see {@code NavController}), so a block of swim is 12.5 ticks against a walk's 5. Everything
     * here is priced at 2 units per tick, which makes that 25.
     *
     * <p>Stated as arithmetic a reader can check, and labelled derived because nobody has yet
     * watched a real client cross a lake. If a live measurement disagrees, this is the number to
     * change and {@link #AIR_PER_SWIM_BLOCK} is the one that has to move with it.
     */
    public static final int COST_SWIM = 25;

    /**
     * Swimming up, in search units, and dearer than swimming across by the same arithmetic.
     *
     * <p>Swimming up is genuinely not the same speed as swimming across, and pricing them alike
     * would make a search undervalue a flooded shaft it has to surface out of. Holding jump in
     * water adds 0.04 per tick ({@code EntityLivingBase.onLivingUpdate:2010-2013} into
     * {@code updateAITick:1591}); the water branch then damps by 0.8 and subtracts 0.02, so the
     * body settles at {@code 0.04 / 0.2 - 0.02 = 0.06} blocks per tick -- 17 ticks a block against
     * the walk's 5, which at 2 units per tick is 34.
     */
    public static final int COST_SWIM_RISE = 34;

    /**
     * Vanilla's full air supply, in ticks ({@code EntityLivingBase:326} sets 300).
     *
     * <p>The ceiling a plan may spend, and deliberately the real number rather than a round one: a
     * route that reaches this has no air left, and the plan that spends it is the plan that drowns.
     */
    public static final int AIR_MAX = 300;

    /**
     * Air spent per block swum horizontally, in ticks.
     *
     * <p>From the same arithmetic as {@link #COST_SWIM}: 12.5 ticks to cross a block of water,
     * rounded UP to 13. Rounding a drowning budget DOWN is the one direction that kills somebody,
     * so the rounding here and in {@link #AIR_PER_RISE_BLOCK} is always away from zero.
     */
    public static final int AIR_PER_SWIM_BLOCK = 13;

    /**
     * Air spent per block swum upward, in ticks: {@code 1 / 0.06}, rounded up.
     *
     * <p>Rising costs about a third more than crossing, and that is not a policy choice -- it falls
     * out of the two different vanilla constants {@link #COST_SWIM} and {@link #COST_SWIM_RISE} are
     * derived from. A search that priced the two alike would happily route up through a flooded
     * shaft instead of along its bank.
     */
    public static final int AIR_PER_RISE_BLOCK = 17;

    /** Whether this move requires building something first. */
    public boolean requiresPlacement() {
        return placeCell != null;
    }

    /** Whether this move puts the player in water, and so spends air. */
    public boolean submerges() {
        return airTicks > 0;
    }

    /**
     * A flat walk, with the creep decided from the world.
     *
     * <p>The two forms exist so a caller that has not looked at the terrain can still build a move,
     * and one that has can say so. What they may not do is invent it: the flag is a question about
     * the cell below and beside the destination, and {@link NeighborGen} is the only thing here
     * holding a {@link BlockView} to answer it.
     */
    static Move walk(Stance from, Stance to) {
        return new Move(from, to, Kind.WALK, null, COST_WALK, 0);
    }

    static Move walk(Stance from, Stance to, boolean creep) {
        return new Move(from, to, Kind.WALK, null, COST_WALK, 0, creep);
    }

    static Move stepUp(Stance from, Stance to) {
        return new Move(from, to, Kind.STEP_UP, null, COST_STEP_UP, 0);
    }

    static Move stepUp(Stance from, Stance to, boolean creep) {
        return new Move(from, to, Kind.STEP_UP, null, COST_STEP_UP, 0, creep);
    }

    static Move drop(Stance from, Stance to, int height) {
        // Deeper falls cost more so a plan prefers a gentle descent when one exists, but the cost
        // stays finite: NeighborGen refuses drops past SAFE_DROP_MAX outright rather than pricing
        // them, because "expensive" and "takes damage" are different facts and a cost cannot say
        // the second one.
        return new Move(from, to, Kind.DROP, null, COST_DROP + height, 0);
    }

    static Move bridge(Stance from, Stance to, Stance placeCell) {
        return new Move(from, to, Kind.BRIDGE, placeCell, COST_BRIDGE, 0);
    }

    /**
     * One block along a ladder or vine, in whichever direction the two stances name.
     *
     * <p>No direction parameter, on purpose: a climb up and a climb down are the same vanilla
     * mechanism with the forward key held or released, so a flag here would be a second place for
     * the two to disagree. {@code to().y() - from().y()} is the direction, and the executor reads it
     * off the very same two stances the planner wrote.
     */
    static Move climb(Stance from, Stance to) {
        return new Move(from, to, Kind.CLIMB, null, COST_CLIMB, 0);
    }

    /**
     * One block of swimming, in whichever direction the two stances name.
     *
     * <p>The air cost is not a parameter because it is not a choice: crossing a block of water
     * costs what vanilla's physics costs, and a caller able to pass a smaller number would be able
     * to plan a drowning.
     */
    static Move swim(Stance from, Stance to) {
        boolean rising = to.y() > from.y();
        return new Move(from, to, Kind.SWIM, null,
                rising ? COST_SWIM_RISE : COST_SWIM,
                rising ? AIR_PER_RISE_BLOCK : AIR_PER_SWIM_BLOCK);
    }
}
