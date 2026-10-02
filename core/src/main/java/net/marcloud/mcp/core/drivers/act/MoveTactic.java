package net.marcloud.mcp.core.drivers.act;

import java.util.Locale;
import java.util.Objects;

/**
 * The way a body was told to move on one tick, as a value somebody else can read.
 *
 * <p><b>What this exists for.</b> {@code NavController} chose between two different things to do on
 * every tick -- walk the straight line, or give the straight line up and work around an obstruction
 * on a named lane -- and published the result as three private fields. No caller, no test and no
 * other component could read what was chosen, or what was spent to choose it. Every downstream
 * component saw the GOAL and nothing about the DECISION, which is why nothing could learn from a
 * decision and nothing could be held accountable for one. This record is that decision, outside.
 *
 * <p><b>The shape is borrowed; the technique is not.</b> The five-field record this is modelled on is
 * {@code DodgePlanner}'s evasion plan, and the two parts worth copying are that the planner returns a
 * value and touches no key at all, and that three different failures have three different encodings.
 * Nothing about dodging is copied -- no technique, no tuning, and no change to what any controller
 * does. What is copied is the discipline of making "what I decided, and what I gave up to decide
 * it" a thing that EXISTS rather than a thing that HAPPENED.
 *
 * <p><b>The invariant, and it is the whole contract:</b> a tactic's {@code forward}, {@code strafe}
 * and {@code jump} are the axes the controller published on the same tick. Not a plan for them, not
 * an explanation of them -- the values themselves, so a reader comparing {@code tactic.forward()}
 * with {@code nav.forward()} gets {@code true} on every tick of every walk. A record of intentions
 * that disagreed with the fingers would be worse than no record at all.
 *
 * @param forward    forward axis as published, in vanilla's sign; always -1, 0 or +1, because
 *                   {@code Entity.moveFlying} normalises the input vector and a human's whole
 *                   reachable input set is one key or the other
 * @param strafe     strafe axis as published, same convention
 * @param jump       TRUE is "jump", FALSE is "asked, and no", and null is "not a question this
 *                   controller asks at all" -- a point walk ({@link NavIntent}) neither climbs nor
 *                   jumps, and a FALSE there could not be told from a decision that was made and lost
 * @param yawChange  degrees of camera rotation asked for, or null for "chose not to rotate".
 *                   {@code null} and {@code 0f} are DIFFERENT DECISIONS and the type says so: 0 is
 *                   "re-aim, and the bearing I computed is the one I had", null is "the camera is
 *                   not mine and I am not asking". A float field could not tell those apart, and
 *                   {@code 0f} from a controller that never turns the camera would be a lie told in
 *                   the direction of looking more capable than it is
 * @param lane       the adjacent lane the body committed to, or null for the direct line. Null is a
 *                   value and not an absence: "no lane" is itself a decision, and it is the one this
 *                   controller makes on almost every tick
 * @param givenUp    what this tactic gave up in order to be the tactic it is. Never null, because a
 *                   decision whose cost is unrecorded cannot be accounted for, and because
 *                   {@link GivenUp#NOTHING} has to mean "nothing was needed" rather than "nobody
 *                   wrote it down"
 */
public record MoveTactic(
        float forward,
        float strafe,
        Boolean jump,
        Float yawChange,
        Lane lane,
        GivenUp givenUp) {

    public MoveTactic {
        Objects.requireNonNull(givenUp, "givenUp is required: 'nothing was given up' and 'nobody "
                + "recorded what was given up' must not be the same value");
    }

    /**
     * A tactic that published no keys at all, because the walk ended on this tick.
     *
     * <p>Zeros rather than "unset", so the terminal tactic still satisfies the invariant: a walk that
     * called {@code stop()} published nothing, and the value that reports the end says so in the
     * same terms as every other tick.
     *
     * @param givenUp why the walk is over, or what it spent to get here -- see {@link GivenUp}
     */
    public static MoveTactic hold(GivenUp givenUp) {
        return new MoveTactic(0f, 0f, Boolean.FALSE, null, null, givenUp);
    }

    /**
     * A tactic that published no keys and stopped on a named lane.
     *
     * <p>The two-argument form is the one {@code stop()} uses, and the lane is the road the body
     * was on when it ended rather than a decision it is still taking -- which is the same fact a
     * running lane is, read one tick later. {@link #onTheDirectLine()} therefore answers false on
     * this record, and that is correct rather than confusing: a walk that died beside a log did not
     * die on the direct line, and a caller reading the end of a walk is exactly the reader who
     * cannot reconstruct that from the axes.
     *
     * <p>A lane that survives here is one that was in force on the terminal tick. A recovery clears
     * it the moment the side-step lands, so a walk that recovered and then arrived still reports
     * null -- true, not missing, and the reason {@link #hold(GivenUp)} stays the right call for
     * every terminal site that is not currently on a lane.
     */
    public static MoveTactic hold(GivenUp givenUp, Lane lane) {
        return new MoveTactic(0f, 0f, Boolean.FALSE, null, lane, givenUp);
    }

    /**
     * A tactic that decided to re-aim the camera by exactly {@code degrees}.
     *
     * <p>{@code rotationTo(0f, ...)} is the decision this exists to keep distinguishable: rotate, by
     * nothing. It is not {@link #hold(GivenUp)}, which is the same axes with a null
     * {@code yawChange}, and the two are unequal records.
     */
    public static MoveTactic rotationTo(float degrees, GivenUp givenUp) {
        return new MoveTactic(0f, 0f, Boolean.FALSE, Float.valueOf(degrees), null, givenUp);
    }

    /** Whether this tactic asks for a jump; never a "yes" to a question that was not put. */
    public boolean jumps() {
        return Boolean.TRUE.equals(jump);
    }

    /**
     * Whether a jump is a question this tactic answers at all.
     *
     * <p>False on a point walk, whose contract is that it neither climbs nor jumps. A caller asking
     * {@link #jumps()} there is told no; a caller asking this is told the question was never put.
     */
    public boolean considersJump() {
        return jump != null;
    }

    /**
     * Whether this tactic asked to move the camera.
     *
     * <p>False for a null {@code yawChange}, which is the answer "chose not to rotate" and not the
     * answer "rotate by zero".
     */
    public boolean rotatesCamera() {
        return yawChange != null;
    }

    /**
     * Whether the body is walking the direct line rather than a recovery lane.
     *
     * <p>The predicate a caller asks most often, and true more often than not: this controller
     * recovers from a wedge rather than routing around terrain, so the lane is the exception.
     */
    public boolean onTheDirectLine() {
        return lane == null;
    }

    /**
     * Human-readable form, for {@code act_status} and for a failure message.
     *
     * <p>The two horizontal axes are named as the KEYS they are rather than printed as the floats
     * they are stored as, because "forward 1.0" reads like a number no pair of fingers can produce on
     * a keyboard -- which is the entire argument {@code NavController.nearestKeys} makes.
     */
    public String describe() {
        return String.format(Locale.ROOT,
                "%s and %s, jump %s, camera %s, %s, gave up %s",
                forward > 0.5D ? "forward" : forward < -0.5D ? "back" : "no forward key",
                strafe > 0.5D ? "right" : strafe < -0.5D ? "left" : "no strafe key",
                jump == null ? "not a question here" : jump ? "yes" : "no",
                yawChange == null
                        ? "not rotated (a decision, not an absence)"
                        : String.format(Locale.ROOT, "turned %.1f degrees", yawChange),
                lane == null ? "direct line" : lane.describe(),
                givenUp.describe());
    }

    /**
     * The adjacent lane a recovery commits the body to, as an OFFSET from the lane it started in.
     *
     * <p>An offset and not a destination, and that is the shape of the recovery itself: the body
     * walks the lane BESIDE the target's and re-joins the direct line past the obstruction, rather
     * than stopping on a cell. A destination cannot express "keep going" -- aiming at a fixed cell
     * eventually falls inside the dead zone and parks the body short of the target it was walking to.
     *
     * @param offX the lane's X offset from the lane the body wedged in, in cells; -1, 0 or +1
     * @param offZ the lane's Z offset from the lane the body wedged in, in cells; -1, 0 or +1
     * @param dirX the lane's FROZEN direction, X, normalised. Frozen because a lane that rotates
     *             with the bearing to the target is not a lane: the target is a point, so that
     *             bearing swings as the body steps sideways, and a swinging lane walks the body back
     *             into the very obstruction it just cleared
     * @param dirZ the lane's FROZEN direction, Z, normalised
     */
    public record Lane(int offX, int offZ, double dirX, double dirZ) {

        public Lane {
            if (offX != 0 && offX != 1 && offX != -1) {
                throw new IllegalArgumentException("lane offset X must be -1, 0 or +1: " + offX);
            }
            if (offZ != 0 && offZ != 1 && offZ != -1) {
                throw new IllegalArgumentException("lane offset Z must be -1, 0 or +1: " + offZ);
            }
            if (Math.abs(dirX) > 1.0D + 1e-9D || Math.abs(dirZ) > 1.0D + 1e-9D) {
                throw new IllegalArgumentException("lane direction must be normalised: ("
                        + dirX + "," + dirZ + ")");
            }
        }

        public String describe() {
            return String.format(Locale.ROOT, "lane at offset (%+d,%+d) heading (%+.2f,%+.2f)",
                    offX, offZ, dirX, dirZ);
        }
    }

    /**
     * What a tactic gave up in order to be the tactic it is.
     *
     * <p><b>Three failure encodings where the reference has three and this package had one.</b>
     * {@code DodgePlanner} distinguishes "not threatened, so no plan is needed" (null from
     * {@code planEvasion}), "threatened, but no escalation is needed" (null from
     * {@code escalateIfNeeded}), and "here is a plan, and it is already at the limit its config
     * allows". Before this enum all three arrived as the same thing: a walk that was still running.
     * They are now {@link #NOTHING} (nothing needed giving up), a named lane give-up, and a set of
     * terminal verdicts that each say what actually ended the walk.
     *
     * <p><b>Why the terminal verdicts are more values and not one.</b> They began as one
     * ({@link #LIMITS_REACHED}, "every option, spent"), which was true at exactly one of the
     * sites that stamped it. Widening the one sentence to "out of options or out of ticks" would
     * have been true and cheap, and it would have cost a caller the only thing this enum was built
     * to give: the difference between a walk that fought the world and lost, which a bigger budget
     * cannot fix, and a walk that was making progress when the clock ran out, which it can. So each
     * terminal cause is its own value, and every one of them is assigned at a site it is true at.
     *
     * <p><b>The clock, the world and the unreadable are three answers to three different
     * questions</b>, and this enum now separates them for all three steering controllers rather
     * than for the walker alone. A caller asking "what should I do differently?" needs exactly
     * that split: reroute ({@link #LIMITS_REACHED}, {@link #THE_UNMOVABLE_MEDIUM}), raise the
     * budget ({@link #OUT_OF_TICKS}, {@link #OUT_OF_TICKS_AFTER_A_LANE}), or give up on reading the
     * obstruction and ask for a walk that carries a world view ({@link #THE_UNNAMED_STALL},
     * {@link #THE_UNSURVEYED_WEDGE}). Each of those is assigned at least one site a caller can
     * reach; none of them is a value nothing can be stamped with.
     *
     * <p>{@link #ARRIVED_AFTER_A_LANE} is a fourth, and it exists because the three above could
     * not tell two successful walks apart: they answer the question per tick, and the one tick a
     * walk is summarised on is the tick a caller reads the most.
     */
    public enum GivenUp {

        /**
         * The direct line, taken. No option was spent to get here.
         *
         * <p>The honest answer for the overwhelming majority of ticks, and the one that has to stay
         * distinguishable from every other value: a walk that has never needed a recovery must not
         * look like a walk that has run out of them.
         */
        NOTHING("nothing: the direct line, and no option was spent to take it"),

        /**
         * The walk ARRIVED, and it arrived after the direct line was given up for a lane.
         *
         * <p>Its own value rather than {@link #NOTHING}, and the difference is the whole of what a
         * caller can learn from the end of a successful walk. A body that wedged against a log,
         * spent {@link #THE_DIRECT_LINE} for up to {@code NavController.UNWEDGE_TICKS_PER_SIDE}
         * ticks, cleared the jam and then arrived has spent an option, and reading NOTHING there
         * said the exact opposite on the one tick the walk is ever summarised on.
         *
         * <p>Distinct from {@link #THE_DIRECT_LINE} because no lane is running any more -- read
         * {@link #lane()} on the terminal tactic to tell a walk that arrived still beside the log
         * from one that arrived after clearing it. And distinct from {@link #LIMITS_REACHED},
         * which is the walk that spent everything and did not arrive at all.
         */
        ARRIVED_AFTER_A_LANE("arrived, after the direct line was given up for a lane"),

        /**
         * A lane was taken instead of the direct line, and the direct line was given up for as long
         * as the lane runs.
         *
         * <p>Bounded by {@code NavController.UNWEDGE_TICKS_PER_SIDE}, so "given up" means "for at
         * most that many ticks", not "for ever".
         */
        THE_DIRECT_LINE("the direct line, for a named lane beside it"),

        /**
         * The first lane was tried, ran out of ticks, and this tactic takes the OTHER one.
         *
         * <p>Its own value rather than another {@link #THE_DIRECT_LINE}, because the two cost a
         * caller different things: the first means recovery is working, the second means recovery is
         * on its last option and the next failure is terminal.
         */
        THE_FIRST_LANE("the first lane, which ran out of ticks before this body was free"),

        /**
         * A hazard was NAMED on this line and the body walks it anyway.
         *
         * <p>This controller reports lava, water and deep drops and steers around none of them --
         * that is its documented contract -- and this is where a reader finds out that it held. The
         * hazard itself is a separate value ({@link NavHazard}); this says only that the line was
         * walked with it known, which is the part a caller cannot otherwise reconstruct from the
         * axes.
         */
        THE_NAMED_HAZARD("the way around a hazard it had already named on the line"),

        /**
         * The walk ran out of TICKS, having spent no option at all.
         *
         * <p>Its own value rather than {@link #LIMITS_REACHED}, and the difference is the whole of
         * what a caller can learn from a walk that failed. A body walking a clear corridor toward a
         * target it cannot reach in a straight line never meets a jam, never takes a lane and never
         * spends a recovery tick; the budget simply ends while it is still making progress. Reading
         * "every option, spent" there claims a choice the walk never made and an exhaustion it never
         * reached, and it sends a caller to reroute a walk that only needed more time.
         *
         * <p>Distinct from {@link #OUT_OF_TICKS_AFTER_A_LANE} on the same tick and the same clock,
         * because {@code NavController.sideTries} -- the counter that means "a lane was actually
         * taken" -- is zero on one walk and positive on the other. That counter is what a caller
         * cannot reconstruct from a terminal record, because the lane is dropped the moment a
         * side-step lands and a walk that recovered and then ran out reports no lane at all.
         */
        OUT_OF_TICKS("out of ticks, having spent no option"),

        /**
         * The walk ran out of ticks AFTER it had already given up the direct line for a lane.
         *
         * <p>Its own value rather than {@link #OUT_OF_TICKS} because the two walks cost a caller
         * different things. This one fought something and then ran out of clock, so the world is
         * part of the bill; the other met nothing at all. And it is NOT
         * {@link #LIMITS_REACHED}: when the clock ends, a second lane is still unspent, so
         * "every option, spent" is false here for the same reason it is false at
         * {@link #OUT_OF_TICKS} and for the opposite reason -- not exhaustion, but the absence of
         * any option having been spent yet.
         */
        OUT_OF_TICKS_AFTER_A_LANE("out of ticks, after the direct line had been given up for a "
                + "lane"),

        /**
         * The body is stalled and in contact, and NO BLOCK INSIDE IT COULD BE NAMED, so the walk
         * stops before any lane is ever offered.
         *
         * <p>Its own value, and it is the one that could not be folded into anything above. A stall
         * nobody can read is a CAPABILITY limit rather than an exhausted one:
         * {@code NavController.beginUnwedge} cannot choose a cell without naming what is in the way,
         * so the walk is terminal before a single option is spent -- not after every one of them is
         * ({@link #LIMITS_REACHED}), and not because a clock ran down ({@link #OUT_OF_TICKS}), which
         * it usually did not: this is the ninth tick of a walk with most of its budget intact.
         *
         * <p>It reports no earlier lane spend either, and that is the whole walk rather than an
         * omission. This walk is reporting the obstruction that stopped it; whether the body cleared
         * a log twenty ticks ago is a fact about the body and not about why it is not moving now,
         * and it is readable from {@code NavController.unwedgeSteps()} and from the running ticks
         * that named the lane. Encoding it here would multiply the value for every distinct history
         * without changing what a reader of THIS terminal record can act on.
         */
        THE_UNNAMED_STALL("a stall it could not name, so no option could be chosen"),

        /**
         * The walk is wedged against a block it NAMED, and could not ask whether there was
         * anywhere to step -- because it has no world view to ask with.
         *
         * <p>Its own value, and the second of the two capability limits, which is not the same
         * limit twice. {@link #THE_UNNAMED_STALL} cannot name what is IN THE WAY; this one names
         * it perfectly -- the message beside it prints the block, its cell and how far the box
         * reaches into it -- and cannot name the NEIGHBOURHOOD. {@code pickSide} returns "no cell"
         * on its first line when {@code standable == null}, so the survey never runs rather than
         * coming back empty, and no option was offered, chosen or refused.
         *
         * <p>It is on the COMMON path, not an edge case: {@code MoveApplier} builds every
         * {@code NavIntent} as a point walk with no standability view, so the ordinary "walk to
         * these coordinates and press into a wall" ends here. And it is why this is not
         * {@link #LIMITS_REACHED}: nothing was surveyed, so nothing could be spent, and the
         * caller reading it is told the walk lacked a capability rather than that it used one up.
         * The response is a different walk -- a route, which carries the view -- not a bigger
         * budget and not a reroute.
         */
        THE_UNSURVEYED_WEDGE("a wedge it could not survey, so no option could be chosen"),

        /**
         * The body is in the medium the move REQUIRES, and the medium is not moving it.
         *
         * <p>The stall verdict for the two gaits that have no recovery to spend: a climb hanging
         * on a ladder that ends here, and a swim in water holding a body against a current or a
         * wall. Both fire on {@code STUCK_TICKS} of no movement out of a budget of tens of
         * ticks, so the clock is demonstrably not what ended the walk.
         *
         * <p>One value for both, because they are one fact. A caller cannot reroute around a
         * ladder that stops rising or a current that will not let go, and it cannot tell those
         * apart from a budget it could have raised: both are the world refusing a body that is
         * already where the move wanted it. It is NOT {@link #THE_UNNAMED_STALL} -- the cause is
         * named here ({@code onClimbable()} was true, {@code inWater()} was true, the position
         * read fine) and it is NOT {@link #LIMITS_REACHED}, because a gait with no lanes and no
         * recovery never had an option to spend in the first place.
         */
        THE_UNMOVABLE_MEDIUM("a ladder or a current that is not carrying the body, which no "
                + "budget can fix"),

        /**
         * Every option this controller HAS was offered, and every one of them failed.
         *
         * <p>The third encoding, and the one that was missing. It is NOT {@link #NOTHING}: "gave up
         * nothing because nothing needed giving up" and "gave up everything" are opposites, and a
         * caller that cannot tell them apart cannot tell a finished walk from a dead one. Always
         * published with zero axes, so it is always a walk that has already stopped.
         *
         * <p><b>Its three sites, and what makes the sentence true at each.</b> It is stamped by
         * {@code NavController.wedged}, and it is the <i>only</i> value that can be, because every
         * path into that method except one is a path on which an option existed and did not work:
         * both lanes were standable and neither freed the body within {@code UNWEDGE_TICKS_PER_SIDE}
         * ticks; or the body had already been freed {@code UNWEDGE_MAX_STEPS} times and wedged
         * again; or both cells beside the jam were MEASURED, found not standable and rejected. In
         * that last one the survey ran and came back empty, which is the only sense in which "every
         * option, spent" was ever true here.
         *
         * <p><b>The fourth path into the same method is not this value, and that is the whole
         * correction.</b> {@code pickSide} also answers "no cell" when the walk has no standability
         * view at all -- on its first line, before it has looked at anything. The survey never ran,
         * so nothing could have been spent, and {@link #THE_UNSURVEYED_WEDGE} is the honest name
         * for it, as {@link #THE_UNNAMED_STALL} is for the sibling case one step earlier where the
         * block itself could not be read.
         */
        LIMITS_REACHED("every option, spent");

        private final String describe;

        GivenUp(String describe) {
            this.describe = describe;
        }

        public String describe() {
            return describe;
        }
    }
}