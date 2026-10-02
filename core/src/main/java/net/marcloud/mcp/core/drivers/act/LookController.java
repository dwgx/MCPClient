package net.marcloud.mcp.core.drivers.act;

/**
 * Pure state machine that aims the camera. Ticked by the LOOK applier through an
 * {@link ActActuator}; it never marshals threads itself.
 *
 * <p>States: IDLE (before the first tick) → SLEWING (turning toward the target) →
 * COMPLETE. An instant intent ({@code slewDegPerTick <= 0}) reaches the target in
 * a single tick — it snaps with {@code setRotation} (prev==cur) so the client
 * does not render an interpolated whip-around. A slew turns at most
 * {@code slewDegPerTick} degrees per tick, taking the SHORTER way around the yaw
 * circle, and finishes the tick it lands within one step of the target.
 *
 * <p>For {@link LookIntent.Mode#LOOK_AT} the target yaw/pitch is resolved from the
 * player's eye to the block center or the entity eye EACH tick, so a moving
 * entity is tracked. If a targeted entity is gone the controller fails honestly
 * ({@code ok=false}).
 *
 * <p><b>{@link LookIntent.AimMode#KEEP} is the tracking mode, and arrival is not one of
 * its endings.</b> Re-resolving the angle each tick was only half of following a moving
 * target; the other half is that the correction has to keep being WRITTEN. Under
 * {@link LookIntent.AimMode#ONCE} this returns a terminal outcome the tick the crosshair
 * lands, the applier drops the controller, and the next tick -- the one where the mob has
 * already walked on -- has nobody correcting it. So KEEP returns {@code running()} from the
 * landed branch and ends only on a cause outside the aim itself: a cancel, the targeted
 * entity being gone, the world going away, or {@code durationTicks} elapsing.
 *
 * <p>A landed KEEP tick still writes rotation with {@code setRotation} (prev==cur), the same
 * write ONCE uses, so a per-tick correction renders as a small step at the tick boundary
 * rather than interpolated across frames. Deliberately unchanged: the corrections while
 * following a walking mob are a couple of degrees, and the alternative would have altered the
 * write on the ONCE path too, which nothing here asked for.

 * <p><b>The rate is the saccadic main sequence, and the arrival test asks the hand too.</b>
 * {@code slewDegPerTick} still caps the turn, the minimum-jerk curve still decides HOW the arc
 * is spent, and only the SHAPE of the curve is the caller's. The rate it is scaled to is the
 * slower of the cap and what a human eye reaches at that amplitude
 * ({@link #mainSequencePeakDegPerTick}), and the same bound decides when the aim has ARRIVED --
 * because a cap is not a statement about anybody's arm, and asking only the cap declared a
 * 35-degree aim arrived on the strength of the number, with the last 35 degrees written in a
 * single tick. The rate used to be the cap alone, for every amplitude, which is the half of
 * ADR-0005 §3's complaint about the old linear ramp ("neither human-shaped, nor free of a
 * turn-rate limit") that the min-jerk swap fixed in the shape and left in the rate: a 12-degree
 * aim and a 90-degree flick came out of the same controller at the same angular speed, and with
 * a loose cap the big one came out faster than any saccade.
 *
 * <p><b>The curve used not to reach the game at all.</b> The write added the profile's absolute
 * position to a yaw the previous tick had already advanced, so the trajectory was the running SUM
 * of a bell -- a monotone accelerator that the cap clamp then flattened into exactly the
 * constant-rate ramp the min-jerk work existed to remove. A 150-degree aim with a 40 deg/tick cap
 * was sending 1.8, 10.1, 26.0, 40.1, 40.1, 32.1 degrees per tick: two samples pinned flat against
 * the cap. The write now takes the profile's increment, and the axis split is frozen with the
 * move. The curve's own tests were right about what they measured, because they called
 * {@code shapedProgress} directly; the defect lived in the seam, where nothing looked.
 *
 * <p><b>A slewing aim writes on the intent's FIRST tick. There is no reaction delay here.</b>
 * This channel used to hold 4-8 ticks before the first rotation, on the reasoning that "a
 * human's first rotation lands 214-416 ms after deciding". It is gone, because that reasoning
 * is the framing ADR-0005 §1 bans and §3.1 retracts: it spent 200-400 ms of task time and
 * bought no externally observable property. The MOVE channel's identical delay survives §3.1 on
 * a NAMED property -- a cancel and re-submit must not be faster than the first try -- and a look
 * has no equivalent to buy, so the same cost with no such property is 纯代价 and does not stay.
 * The consequence is a direct capability gain and is stated as one: an aim that traverses real
 * ground now begins 4-8 ticks (200-400 ms) sooner than it did.
 *
 * <p><b>What removing it did NOT touch.</b> An aim whose whole error is within one tick of the
 * caller's cap never paid the delay -- it was excluded by a gate that asked {@code capLanded} --
 * so the micro-correction path is byte-for-byte the same: a 2-degree wrap from 179 to -179, and a
 * 5-degree aim, still land on tick 1, terminal and ok, as
 * {@code theMicroCorrectionStillLandsOnOneTickAndPaidNothing} pins.
 *
 * <p><b>Angle math</b> (vanilla convention): given delta {@code dx,dy,dz} from eye
 * to target, {@code yaw = atan2(dz, dx) * 180/PI - 90} and
 * {@code pitch = -atan2(dy, sqrt(dx*dx+dz*dz)) * 180/PI}. Yaw deltas are wrapped to
 * [-180, 180] so the turn always takes the short arc, e.g. from +179 to -179.
 */
public final class LookController {

    private final LookIntent intent;
    private final boolean keepAiming;
    private final int durationTicks;

    private boolean started;
    private boolean done;
    private boolean cancelRequested;

    /** Ticks this controller has run, i.e. how long the aim has been held. */
    private int ticks;

    /**
     * The total arc of the current aim, and how much of it has been spent.
     *
     * <p>What a minimum-jerk profile is parameterised by: progress through the WHOLE move, not
     * a per-tick velocity. Kept as a fact about the move rather than an accumulator so a
     * dropped tick changes the sampling, not the curve.
     *
     * <p>Reset when the aim lands, so a second aim on the same controller gets its own profile
     * instead of inheriting one that is already at the end of its curve -- which would produce a
     * first step of essentially zero and read as a hang.
     */
    private double arcTotal;
    /** Ticks the profile spreads the arc over, from the peak rate. */
    private double arcTicks = 1;
    /** Ticks emitted so far on this arc; the profile's independent variable. */
    private int arcDoneTick;

    /**
     * Whether the crosshair has ever actually reached the target.
     *
     * <p>Load-bearing only at the {@code durationTicks} ending, and it is what keeps that
     * ending from lying: "tracked for 100 ticks" reads as success, and a caller acts on it by
     * attacking or digging. A slew cap too slow for the target -- or a target moving away
     * faster than the cap -- runs the whole duration without the crosshair ever arriving, and
     * reporting that as done would be a confident claim in the dangerous direction.
     */
    private boolean everAimed;

    /**
     * The previous tick's absolute position along the arc, so the write can take this tick's
     * increment. Zero at the start of a move, which is why the first tick's increment is the
     * profile's first step and not its first absolute position.
     */
    private double arcShapedDone;

    /**
     * The share of each tick's increment that goes to yaw, frozen on the move's first tick.
     *
     * <p>Frozen because it is a fact about the MOVE -- the direction the crosshair travels, which
     * is what the "shorter way for yaw" argument is about -- and recomputing it from the live
     * error each tick lets the split drift as the aim closes on its target, which bends the
     * trajectory off the arc the profile is spending.
     */
    private double arcYawFrac;

    public LookController(LookIntent intent) {
        this.intent = intent;
        // Compared against KEEP rather than ONCE so a null aim -- an intent built by hand instead
        // of through a factory -- degrades to the documented default instead of throwing. Same
        // tolerance HoldController extends to a null holdMode.
        this.keepAiming = intent.aim() == LookIntent.AimMode.KEEP;
        this.durationTicks = intent.durationTicks();
    }

    /** Request cancellation; the next {@link #tick} ends CANCELLED. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** Ticks the aim has run, for status and tests. */
    public int ticks() {
        return ticks;
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /** Advance one tick against {@code act}. */
    public ActOutcome tick(ActActuator act) {
        if (done) {
            return ActOutcome.done("already finished", ActOutcome.READ_DIRECTLY);
        }
        if (cancelRequested) {
            // Nothing to hand back to the game: unlike a hold, which owns a static KeyBinding
            // that outlives the controller, an aim's only effect is the rotation already written,
            // and the player is entitled to be left looking where they were pointed. So the
            // teardown is the report itself -- how long the aim was held, which the caller cannot
            // get anywhere else once the slot is reset.
            //
            // READ_DIRECTLY: the sentence is about this controller's own tick counter and flag. It
            // deliberately makes no claim about where the crosshair ended up, and it could not --
            // the crosshair is traced by the renderer AFTER this tick's write, so nothing this
            // controller can read here has seen the rotation it just published.
            return finish(ActOutcome.cancelled("look cancelled after " + ticks + " ticks",
                    ActOutcome.READ_DIRECTLY));
        }
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world", ActOutcome.READ_DIRECTLY));
        }
        Float targetYaw;
        Float targetPitch;
        if (intent.mode() == LookIntent.Mode.SET) {
            targetYaw = intent.yaw();
            targetPitch = intent.pitch();
        } else {
            float[] resolved = resolveLookAt(act);
            if (resolved == null) {
                // READ_CAME_BACK_EMPTY, and this is the design's 2.C #15 in the rotation seam's
                // clothing. `resolveLookAt` returns null for three different absences -- no eye
                // position, no entity eye position, no resolvable target -- and `entityEyePos`
                // returns null for an id the client does not hold exactly as `blockAt` returns null
                // for an unloaded chunk. So "the look target is gone" is a statement about what this
                // seam could produce, not an observation that nothing is there.
                return finish(ActOutcome.failed(gone(), ActOutcome.READ_CAME_BACK_EMPTY));
            }
            targetYaw = resolved[0];
            targetPitch = resolved[1];
        }

        boolean instant = intent.slewDegPerTick() <= 0f;
        float curYaw = act.yaw();
        float curPitch = act.pitch();
        float yawErr = wrapTo180(targetYaw - curYaw);
        float pitchErr = targetPitch - curPitch;
        // "Has the aim ARRIVED?" is a claim about the rotation: the crosshair is on the target,
        // so what is left is one tick of motion away. That has to be asked against the HAND and
        // not only against the caller's cap, because a cap is not a statement about anybody's
        // arm. With the cap alone, a caller who passed 40 had a 35-degree aim declared arrived
        // on the strength of the cap, and the last 35 degrees was then written in ONE tick --
        // which is the arrival test silently deciding the move is over before the profile has
        // spent any of it, and is why bounding the profile's rate alone was a mechanism that
        // never fired. The mutation check found that; the numbers are in
        // mainSequencePeakDegPerTick.
        //
        // There was a second question here once -- "is this a movement a hand hesitates over?" --
        // and its gate also asked the caller's cap, so a 35-degree aim would not have started
        // paying 200-400 ms it never paid. Both questions are now one: the gate and the delay
        // are gone, and this is the only decision-to-write in the tick. An aim that arrives
        // lands this tick; one that does not starts spending the profile this tick.
        float step = intent.slewDegPerTick();
        double errTotal = Math.abs(yawErr) + Math.abs(pitchErr);
        float arrivalLimit = (float) Math.min(step, mainSequencePeakDegPerTick(errTotal));
        boolean arrived = instant || withinStep(yawErr, pitchErr, arrivalLimit);
        float wroteYaw;
        started = true;
        ticks++;

        if (arrived) {
            // Land on the nearest REACHABLE angle, not on the requested one. "Exactly on target"
            // here means exactly on a mouse position: the caller's 91.37 degrees is not a
            // rotation this client can physically hold, and 91.35 is 0.02 away and is.
            // Snapping (prev==cur) so no interpolation artifact.
            wroteYaw = (float) quantizeMouse(targetYaw);
            float landedPitch = (float) quantizeMouse(clampPitch(targetPitch));
            act.setRotation(wroteYaw, landedPitch);
            everAimed = true;
            // A landed aim ends the curve; the next one starts a fresh profile rather than
            // inheriting one already at the end of its shape, which would open with a step of
            // essentially zero and read as a hang.
            arcTotal = 0;
            arcTicks = 1;
            arcDoneTick = 0;
            arcShapedDone = 0;
            arcYawFrac = 0;
            if (!keepAiming) {
                // DERIVED_FROM_READS and it is the exact claim worth being careful about. The
                // sentence reports a rotation this controller WROTE, and a write read back is not
                // the same as a rotation the game has applied: our axes fire at `runTick` ENTRY
                // (TickAdvice.java:16-19) and vanilla's mouse handler lands at EntityRenderer:1119,
                // after it. The numbers here are about what this client published, and the "snapped
                // to the mouse lattice" clause is the honest acknowledgement that the requested
                // angle is not one this client can hold.
                return finish(ActOutcome.done("aimed at yaw=" + fmt(wroteYaw)
                        + " pitch=" + fmt(landedPitch)
                        + (Math.abs(wroteYaw - targetYaw) > 0.001
                                || Math.abs(landedPitch - clampPitch(targetPitch)) > 0.001
                                ? " (snapped to the mouse lattice; nearest reachable to "
                                        + fmt(targetYaw) + "/" + fmt(clampPitch(targetPitch)) + ")"
                                : ""), ActOutcome.DERIVED_FROM_READS));
            }
        } else {
            // The step is a share of the minimum-jerk profile, not a constant.
            //
            // `slewDegPerTick` still bounds the move from above, and the profile only decides
            // HOW the arc is spent. That keeps the existing argument meaning intact: a caller
            // who passes 30 still gets at most a 30-degree-per-tick turn, just one shaped like a
            // hand rather than a machine -- and, since the peak rate is also bounded below by
            // what a hand can do, possibly a slower one.
            if (arcTotal <= 0) {
                // The whole move, and how long it takes, both captured on its first tick.
                //
                // `slewDegPerTick` is the PEAK rate -- degrees per tick at the fastest point of
                // the curve -- and the number of ticks follows from it. That is the reading that
                // makes the argument mean what it always meant ("at most this fast") while the
                // SHAPE below decides how that speed is spent. Reading it as a share of the arc
                // instead -- the first attempt at this -- makes the step scale with the distance
                // being covered, so a 180-degree aim and a 9-degree one take the same time, and
                // the profile degenerates into a constant step again.
                arcTotal = Math.abs(yawErr) + Math.abs(pitchErr);
                arcYawFrac = Math.abs(yawErr) / (errTotal < 1e-9 ? 1.0 : errTotal);
                if (arcTotal > 1e-9) {
                    // The fastest tick moves (peak slope)/N of the arc, and N follows from the
                    // peak rate. The warped curve's slope at the midpoint is the base curve's
                    // pi/2 times the warp factor r = 1.805/(pi/2), so the peak slope is 1.805 --
                    // the same constant, arrived at from the other side, which is what makes the
                    // two consistent by construction rather than by comment.
                    //
                    // The peak rate is the caller's cap ANDED with the main sequence, and the
                    // AND is the whole of the change: `step` is what the caller permits, the
                    // main sequence is what a hand can do, and the move runs at the slower one.
                    // It can therefore only ever make a look SLOWER, never faster than the
                    // argument the caller passed -- which is what keeps this inside ADR-0005's
                    // "costs time, buys a measurable property" class instead of quietly
                    // reinterpreting `slewDegPerTick`.
                    double peak = Math.min(step, mainSequencePeakDegPerTick(arcTotal));
                    arcTicks = Math.max(1.0, Math.round(arcTotal * VELOCITY_PEAK_MEAN / peak));
                }
            }
            // `shaped` is an ABSOLUTE position along the arc, so the write needs this tick's
            // INCREMENT of it, and the increment is the difference against the last one.
            //
            // It used to write the absolute position straight onto `curYaw`, with the comment
            // claiming that was what implemented the curve. It is the opposite: `curYaw` is read
            // back from the previous tick's write, so adding an absolute position to it
            // double-counts every tick that came before, and the trajectory the game receives is
            // the running SUM of the profile rather than the profile. Summed, a bell is a
            // monotone accelerator, and the `cap` clamp below then flattens it into exactly the
            // constant-rate ramp the minimum-jerk work was written to remove. Measured on a
            // 150-degree aim with a 40 deg/tick cap, the per-tick steps the game was sent were
            // 1.8, 10.1, 26.0, 40.1, 40.1, 32.1 -- accelerating into a flat top, not a bell.
            //
            // The curve itself was never wrong, and the tests that pinned it were right about
            // what they measured; they called `shapedProgress` directly, so the defect lived in
            // the seam between the curve and the write and nothing here could see it. The
            // property is now asserted on the write stream instead.
            //
            // There used to be a `from = arcDone / arcTotal` half here, subtracted so `shaped`
            // was an increment. `arcDone` was never incremented, so `from` was always 0.0 and
            // the subtraction did nothing.
            double to = arcTotal < 1e-9 ? 1.0
                    : Math.min(1.0, (arcDoneTick + 1.0) / arcTicks);
            arcDoneTick++;
            double shaped = arcTotal < 1e-9 ? 1.0
                    : arcTotal * shapedProgress(to, VELOCITY_PEAK_MEAN);
            double increment = shaped - arcShapedDone;
            arcShapedDone = shaped;
            // Split along the error ratio, which is the "shorter way for yaw" the constant step
            // used, so that property survives the change. FROZEN on the move's first tick, like
            // arcTotal: recomputed per tick it drifts as the aim closes on the target, and a
            // split that moves while the move is in progress bends the trajectory off the arc the
            // profile is spending.
            // Never more than the cap: the profile redistributes the arc, it does not license
            // exceeding a bound the caller set. Without this the arrival test can be overshot
            // near u=0.5, where the curve is steepest.
            double cap = Math.abs(clampMag(yawErr, step)) + Math.abs(clampMag(pitchErr, step));
            increment = Math.min(increment, Math.max(0.0, cap));
            // The lattice is applied to the WRITE, which is the thing that leaves this process.
            wroteYaw = (float) quantizeMouse(
                    curYaw + Math.signum(yawErr) * increment * arcYawFrac);
            float nextPitch = (float) quantizeMouse(clampPitch(
                    curPitch + (float) (Math.signum(pitchErr) * increment * (1 - arcYawFrac))));
            // Interp: previous = where we were, current = the step target.
            act.setRotationInterp(curYaw, curPitch, wroteYaw, nextPitch);
            if (!keepAiming) {
                // DERIVED_FROM_READS: `wroteYaw` is this tick's profile increment added to the
                // previous tick's written value and then quantised, and the sentence reports that
                // sum. `curYaw` is read back from our OWN previous write, so the number is a
                // statement about what this controller has been publishing rather than about a
                // rotation the game independently arrived at.
                return ActOutcome.running("slewing toward yaw=" + fmt(targetYaw)
                        + " (now " + fmt(wroteYaw) + ")", ActOutcome.DERIVED_FROM_READS);
            }
        }

        // KEEP from here down. The duration test comes AFTER the write, so the last tick of a
        // bounded track still corrects the aim instead of spending its tick on bookkeeping.
        if (durationTicks > 0 && ticks >= durationTicks) {
            if (!everAimed) {
                // DERIVED_FROM_READS, and the sentence's own clause is what makes that grade honest:
                // "the crosshair never reached the target" is a statement about the ANGLE THIS
                // CLIENT WROTE, and the code says outright "do not read this as having looked at
                // it". The crosshair itself is traced by the renderer after this tick, against the
                // previous frame's rotation -- design 2.C #16, already typed at the `mouseOver()`
                // seam -- so nothing here has seen where the ray actually went.
                return finish(ActOutcome.failed("tracked for " + ticks + " ticks but the crosshair "
                        + "never reached the target, still " + fmt(Math.abs(yawErr))
                        + " degrees of yaw out -- at " + fmt(intent.slewDegPerTick())
                        + " deg/tick the aim could not catch it, so do not read this as having "
                        + "looked at it", ActOutcome.DERIVED_FROM_READS));
            }
            return finish(ActOutcome.done("tracked for " + ticks + " ticks, aim held on yaw="
                    + fmt(targetYaw) + " pitch=" + fmt(clampPitch(targetPitch)),
                    ActOutcome.DERIVED_FROM_READS));
        }
        if (arrived) {
            // READ_DIRECTLY is available here and this is the one site in the class that takes it:
            // every clause is a number the caller passed in, re-rendered, plus this controller's own
            // tick counter. Nothing is read, nothing is derived, and the sentence carefully says
            // "aim held on" rather than "aimed at" -- it is holding the axis it was given.
            return ActOutcome.running("holding aim on yaw=" + fmt(targetYaw)
                    + " pitch=" + fmt(clampPitch(targetPitch)) + " (tick " + ticks
                    + durationLimit() + ")", ActOutcome.READ_DIRECTLY);
        }
        return ActOutcome.running("slewing toward yaw=" + fmt(targetYaw)
                + " (now " + fmt(wroteYaw) + ", tick " + ticks + durationLimit() + ")",
                ActOutcome.DERIVED_FROM_READS);
    }

    /** True once at least one tick has run (for status/debugging). */
    public boolean isStarted() {
        return started;
    }

    /** {@code "/N"} for a bounded track, empty for one that runs until cancelled. */
    private String durationLimit() {
        return durationTicks > 0 ? "/" + durationTicks : "";
    }

    /**
     * Why the target could not be resolved.
     *
     * <p>The tick count is appended only while KEEP, because it is only there that it carries
     * information: a ONCE aim fails on its first tick, so "after 1 ticks" would be noise, while a
     * track that followed a skeleton for 60 ticks and then lost it is telling the caller the mob
     * died or left render distance rather than that the id was wrong to begin with.
     */
    private String gone() {
        String what = intent.hasEntity()
                ? "look target entity " + intent.targetEntityId() + " is gone"
                : "look target unavailable";
        return keepAiming ? what + " after " + ticks + " ticks of tracking" : what;
    }

    private ActOutcome finish(ActOutcome out) {
        done = out.terminal();
        return out;
    }

    // ===== geometry =====

    /**
     * Resolve absolute yaw/pitch aiming from the player's eye at the LOOK_AT
     * target (block center or entity eye), or null if the target is gone.
     */
    private float[] resolveLookAt(ActActuator act) {
        double[] eye = act.eyePos();
        if (eye == null) {
            return null;
        }
        double tx;
        double ty;
        double tz;
        if (intent.hasEntity()) {
            double[] e = act.entityEyePos(intent.targetEntityId());
            if (e == null) {
                return null;
            }
            tx = e[0];
            ty = e[1];
            tz = e[2];
        } else {
            tx = intent.targetBlockX() + 0.5;
            ty = intent.targetBlockY() + 0.5;
            tz = intent.targetBlockZ() + 0.5;
        }
        return anglesTo(eye[0], eye[1], eye[2], tx, ty, tz);
    }

    /** The vanilla-convention yaw/pitch (degrees) aiming from (ex,ey,ez) at (tx,ty,tz). */
    static float[] anglesTo(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(dy, horiz) * 180.0 / Math.PI));
        return new float[] {yaw, pitch};
    }

    /** Wrap an angle delta into [-180, 180] so a turn takes the short arc. */
    static float wrapTo180(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    private static boolean withinStep(float yawErr, float pitchErr, float step) {
        return Math.abs(yawErr) <= step && Math.abs(pitchErr) <= step;
    }

    /**
     * The finest rotation a human's mouse can actually produce, in degrees.
     *
     * <p>0.15, and it is not a tuning choice -- it is arithmetic. The mouse path is two stages:
     * {@code EntityRenderer:1099-1100} computes {@code f1 = (sens*0.6+0.2)^3 * 8} and hands it to
     * {@code Entity.setAngles:392}, which multiplies by {@code 0.15}. At the default sensitivity
     * of 0.5 those cancel to exactly 0.15 degrees per mouse count, so the reachable yaw set is
     * {@code 0.15 * Z}: exactly <b>2400 orientations</b> on the circle, 1200 for pitch.
     *
     * <p>Why it has to be applied HERE. {@code LivePlayerActuator:180-201} writes the four
     * rotation floats directly, bypassing {@code setAngles}, {@code MouseHelper} and
     * {@code EntityRenderer} entirely -- which is what keeps {@code client/src} vanilla, and it
     * also means the quantisation those three would have given us for free simply does not
     * happen. The server does not check it either: {@code C03PacketPlayer} carries float32 as
     * written and {@code NetHandlerPlayServer:343-344} assigns it unchanged. The 1/256-of-a-turn
     * quantisation in 1.8.9 exists only in {@code EntityTrackerEntry}, which is how OTHER
     * players see us, not how the server receives us. A raw float is a rotation no cursor could
     * produce, and it costs nothing not to be one.
     */
    static final double MOUSE_GCD_DEG = 0.15D;

    /**
     * Snap an angle to the nearest reachable mouse position.
     *
     * <p>Wrapping first, because the lattice is circular: 359.97 and -0.03 are one mouse apart
     * and must not become 359.97 and 0.
     */
    static double quantizeMouse(double deg) {
        double wrapped = (double) wrapTo180((float) deg);
        return Math.round(wrapped / MOUSE_GCD_DEG) * MOUSE_GCD_DEG;
    }

    /**
     * This tick's share of a minimum-jerk move, in angle units.
     *
     * <p>A constant-rate ramp was the loudest single thing about synthetic aiming, and the shape
     * that fixes it is measured rather than chosen. Flash and Hogan (1985,
     * <a href="https://www.jneurosci.org/content/5/7/1688">J. Neurosci 5(7):1688</a>) measured
     * aimed movements and found velocity is roughly <b>bell-shaped</b>, peak/mean about
     * <b>1.75</b>: slow away from rest, fastest near the middle, slow into the target. That is the
     * minimum-jerk trajectory, whose jerk is minimised subject to arriving at rest at both ends.
     * A clamp-to-{@code slewDegPerTick} ramp is a flat-topped trapezoid, so it is wrong at BOTH
     * ends -- and wrong most where a person's hand is most conspicuous.
     *
     * <p>The discrete curve is a half period of a raised cosine, {@code 0.5 - 0.5cos(pi*u)}:
     * zero derivative at both ends, 0.5 exactly at the midpoint, integrating to 1.
     * See {@link #minJerkProgress} for why it is neither a full period nor the textbook
     * minimum-jerk polynomial.
     *
     * <p>{@code u} is progress over the WHOLE move rather than a per-tick accumulator, so the
     * profile is the same whichever rate it is sampled at and a dropped tick does not shift it.
     */
    /**
     * The peak-to-mean velocity ratio this controller turns at.
     *
     * <p>Measured, not invented. Flash &amp; Hogan 1985 (doi
     * 10.1523/JNEUROSCI.05-07-01688.1985) fitted the minimum-jerk model to 30 unconstrained
     * point-to-point movements and report {@code C = 1.805 +- 0.153}; the model's 1.875 was
     * accepted at alpha=0.01 and REJECTED at alpha=0.05. So the target is 1.805 and the spread is
     * wide enough that 1.875 is not a correction, it is the far end of the same band.
     *
     * <p><b>This used to be pi/2 = 1.571</b>, with a comment claiming F&amp;H had measured 1.75.
     * Neither survives: 1.75 is a line in the Shadmehr course notes, and pi/2 is the ratio of a
     * half-period raised cosine, which is a different curve from the one that was fitted. The 13%
     * error lived in that constant.
     */
    static final double VELOCITY_PEAK_MEAN = 1.805D;

    /**
     * The saccadic main sequence: the peak angular velocity a human reaches at a given aim
     * amplitude, in degrees per tick.
     *
     * <p><b>Why a look's RATE needs a law and not a constant.</b> Peak-to-mean velocity
     * ({@link #VELOCITY_PEAK_MEAN}) is the curve's SHAPE and was already measured. The rate the
     * curve runs at is a different fact and was not: it was {@code slewDegPerTick}, the same
     * number for every aim, so a 12-degree aim and a 90-degree flick left at the identical
     * angular speed. A human's does not, and the difference is systematic rather than noise.
     *
     * <p><b>The measurement.</b> Gibaldi &amp; Sabatini (2020,
     * <a href="https://doi.org/10.3758/s13428-020-01388-2">Behav Res Methods 53:167</a>) fitted
     * the main sequence to eye traces from nine adults (Eyelink II, 250 Hz) with a one-parameter
     * "fixed sqrt" model, {@code PV(A) = V_A + V*sqrt(A - A_th)}, which was the best of the nine
     * models they compared for repeatability. Their constants: {@code A_th = 1} degree, the
     * accepted micro-saccade amplitude, and {@code V_A = 40} deg/s, the measured mean peak
     * velocity of a 1-degree saccade. {@code V} was fitted per subject over 45.4 to 140.2
     * (deg/s)/sqrt(deg); the median of the nine is 100.4, and <b>100</b> is what this uses.
     *
     * <p>The model's own sanity check passes: at 9 degrees it predicts 323 deg/s, against
     * measured means of 160-414 deg/s in the same paper's Table 5, and the subject with the
     * lowest fitted V (45.4) is the one whose measured mean peak velocity is lowest (160). The
     * shape of the law is theirs: "roughly linear for small saccades, between 1 degree and
     * 5-10 degrees, an inflection point between 10 and 20 degrees, and it smoothly reaches a
     * saturated value for larger saccades".
     *
     * <p><b>The saturation, and why holding the curve is not an extrapolation.</b> Above
     * {@link #MAIN_SEQUENCE_FITTED_MAX_DEG} the rate is held flat rather than grown. Two reasons,
     * and the second is the load-bearing one. First, the paper says so -- peak velocity "smoothly
     * reaches a saturated value for larger saccades" -- and its own protocol only ever presented
     * targets out to 24 degrees. Second, a square root does not know how to stop: left unbounded
     * this law reaches 3150 deg/s at 150 degrees, which is six times the fastest velocity
     * measured anywhere in the source paper and is not a rate an eye produces. Holding the fitted
     * curve at the edge of its fitted domain is the conservative reading. It is also the reading
     * that makes the mechanism fire at all: an unbounded version of this curve sits above every
     * cap the tool is called with, so it would be a mechanism that never runs -- which is exactly
     * what the mutation check caught when this was first written. The held value is 520 deg/s,
     * against measured per-subject means of 160-414 deg/s in the same paper's Table 5 and a
     * top-of-range fitted subject at 712 deg/s: inside the range its own subjects produced, and
     * stated as a bound rather than claimed as a measured ceiling.
     * <p><b>Why this is the whole of the saccade model here, and not a smaller one.</b> The
     * obvious next mechanism is to make the look HOP between fixations rather than sweep. It was
     * not built, and the reason is measurable rather than doctrinal: the same paper's Table 5
     * puts human saccade DURATION at 31-68 ms, while the game samples the mouse once per frame
     * -- {@code EntityRenderer:1094-1100} reads {@code deltaX}/{@code deltaY} and applies them
     * once, and {@code Minecraft:223} runs {@code new Timer(20.0F)}. A saccade is 0.6 to 1.4 ticks
     * long, so its internal structure cannot be written to the game at all, and a 200 ms
     * fixation between hops would be 4 ticks of the player standing still -- pure cost for a
     * shape the channel cannot carry. The amplitude-dependent RATE is the part of the saccade
     * model that survives 20 Hz sampling, so it is the part implemented.
     *
     * <p><b>Cost, measured.</b> At most <b>2 extra ticks (100 ms)</b>, and only on a look that
     * slews at all: over arcs of 45 to 150 degrees against a 40 deg/tick cap the surcharges are
     * 1, 1, 1, 1, 1, 2, 2, 1 ticks. A look whose whole error is inside one tick of hand motion
     * still lands immediately and still costs nothing, which is the micro-correction contract
     * preserved: a 2-degree aim and a 5-degree aim are one tick each, before and after.
     *
     * <p><b>There is no reaction delay on this channel, and that is the decision.</b> There used
     * to be one: {@code 4 + rand(5)} ticks, 200-400 ms, charged on every aim big enough to slew,
     * justified as "a human's first rotation lands 214-416 ms after deciding". It was removed
     * because it bought nothing a caller can observe -- ADR-0005 §1's 纯代价 class -- where the
     * MOVE channel's delay survives §3.1 on a NAMED property (a cancel and re-submit must not be
     * faster than the first try). A look has no such property to buy: cancelling and re-submitting
     * buys nothing on this channel either, because the caller is not waiting for a step it could
     * skip -- the crosshair either is or is not on the target, and the aim starts writing on the
     * intent's own tick.
     *
     * <p>So the tax this channel charges is the one measured above -- the main sequence and the
     * arrival bound, at most 2 extra ticks on a slewing look and 0 on a micro-correction -- and
     * nothing else. The delay's 4-8 ticks were on top of that and are gone.
     */
    static double mainSequencePeakDegPerTick(double amplitudeDeg) {
        double a = amplitudeDeg > 0 ? amplitudeDeg : 0;
        if (a > MAIN_SEQUENCE_FITTED_MAX_DEG) {
            a = MAIN_SEQUENCE_FITTED_MAX_DEG;
        }
        double aboveFloor = a - MAIN_SEQUENCE_AMPLITUDE_FLOOR_DEG;
        double peakDegPerSec = MAIN_SEQUENCE_FLOOR_DEG_PER_S
                + (aboveFloor <= 0 ? 0.0 : MAIN_SEQUENCE_SLOPE * Math.sqrt(aboveFloor));
        return peakDegPerSec / TICKS_PER_SECOND;
    }

    /**
     * Ticks per second, and therefore milliseconds per tick.
     *
     * <p>{@code Minecraft:223} constructs {@code new Timer(20.0F)}, and
     * {@code EntityRenderer:1094-1100} applies one accumulated mouse delta per frame, so the
     * finest rotation the game can be given is one sample per 50 ms. Every rate below is in
     * degrees per SECOND and divided by this exactly once, at the boundary.
     */
    private static final double TICKS_PER_SECOND = 20.0D;

    /** Measured mean peak velocity of a 1-degree saccade: {@code V_A}, in degrees/second. */
    static final double MAIN_SEQUENCE_FLOOR_DEG_PER_S = 40.0D;

    /**
     * The fitted slope {@code V}, in (degrees/second) per sqrt(degree).
     *
     * <p>The median of the nine per-subject fits in Gibaldi &amp; Sabatini's Table 4 is 100.4 and
     * the range is 45.4-140.2, so this is the middle of a measured spread and not a tuning value
     * chosen to make a number come out.
     */
    static final double MAIN_SEQUENCE_SLOPE = 100.0D;

    /** {@code A_th}, the amplitude at or below which the square-root term is zero: 1 degree. */
    static final double MAIN_SEQUENCE_AMPLITUDE_FLOOR_DEG = 1.0D;

    /**
     * The largest amplitude the law grows to: 24 degrees, the biggest eccentricity the source
     * experiment presented and the point at which the curve is held.
     */
    static final double MAIN_SEQUENCE_FITTED_MAX_DEG = 24.0D;

    /** Table resolution for the progress curve. 256 steps over a move is finer than a tick. */
    private static final int PROFILE_STEPS = 256;

    /**
     * Progress along a turn, as a function of {@code u} in [0,1], with the velocity profile
     * {@code v(u) = sin(pi*u)^k} normalised to unit area.
     *
     * <p>That family is the reason it is parameterised by a ratio rather than by a curve
     * constant. {@code k} sweeps the peak-to-mean ratio monotonically: {@code k -> 0} is a
     * constant velocity (ratio 1), {@code k = 1} is the half-period raised cosine (ratio pi/2
     * = 1.5708), {@code k = 2} is a squared one (ratio 2). 1.805 sits between the last two, so it
     * is reached by choosing {@code k}, and <b>the shape is the same for every k</b> -- which is
     * what makes the ratio a calibration number rather than a different curve.
     *
     * <p>Why not just warp {@code u}: warping by {@code r} moves the peak to {@code 0.5/r} and
     * renormalising then scales the peak by {@code 2r}, not {@code r}. That was the first attempt
     * here and it produced 3.61 for a target of 1.805 -- off by a factor of two and with the peak
     * off-centre. Time-warping changes WHERE the speed is, not how fast the middle is.
     *
     * <p>The integral of {@code sin^k} has no closed form for general {@code k}, so the curve is
     * integrated once at class-init into a table. That is a fixed 257-entry cost paid once, and it
     * is exact to the table resolution rather than a curve fit.
     */
    static double shapedProgress(double u, double peakMean) {
        double c = clamp01(u);
        double[] table = profileTable(peakMean);
        if (table == null) {
            return minJerkProgress(c);
        }
        double x = c * PROFILE_STEPS;
        int i = (int) Math.floor(x);
        if (i >= PROFILE_STEPS) {
            return 1.0;
        }
        double f = x - i;
        return table[i] + (table[i + 1] - table[i]) * f;
    }

    /** Cached per ratio; there is exactly one ratio in production, so this holds one array. */
    private static volatile double[] cachedProfile;
    private static volatile double cachedRatio = Double.NaN;

    private static double[] profileTable(double peakMean) {
        if (Double.isNaN(cachedRatio) || cachedRatio != peakMean) {
            synchronized (LookController.class) {
                if (Double.isNaN(cachedRatio) || cachedRatio != peakMean) {
                    cachedProfile = buildProfile(peakMean);
                    cachedRatio = peakMean;
                }
            }
        }
        return cachedProfile;
    }

    private static double[] buildProfile(double peakMean) {
        if (!(peakMean > 1.0) || Double.isNaN(peakMean) || Double.isInfinite(peakMean)) {
            return null;                                  // no warp is needed, or it is meaningless
        }
        // Bisect k so the sampled velocity profile's peak/mean matches the target. The ratio is
        // monotone in k, so a plain bisection converges and needs no derivative.
        double lo = 0.0;
        double hi = 4.0;
        for (int it = 0; it < 60; it++) {
            double mid = (lo + hi) / 2.0;
            if (ratioForExponent(mid) < peakMean) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double k = (lo + hi) / 2.0;
        double[] v = new double[PROFILE_STEPS + 1];
        double area = 0.0;
        for (int i = 0; i <= PROFILE_STEPS; i++) {
            double u = i / (double) PROFILE_STEPS;
            double w = Math.sin(Math.PI * u);
            v[i] = w <= 0.0 ? 0.0 : Math.pow(w, k);
            if (i > 0) {
                area += (v[i] + v[i - 1]) / 2.0 / PROFILE_STEPS;
            }
        }
        if (area <= 1e-12) {
            return null;
        }
        double[] progress = new double[PROFILE_STEPS + 1];
        double acc = 0.0;
        progress[0] = 0.0;
        for (int i = 1; i <= PROFILE_STEPS; i++) {
            acc += (v[i] + v[i - 1]) / 2.0 / PROFILE_STEPS;
            progress[i] = acc / area;
        }
        // The table is unit-area, so the last entry is 1.0 by construction; set it rather than
        // let accumulation error leave the aim a thousandth short of the target forever.
        progress[PROFILE_STEPS] = 1.0;
        return progress;
    }

    /** Peak-to-mean of {@code sin(pi*u)^k} on the same trapezoid rule the table uses. */
    private static double ratioForExponent(double k) {
        double peak = 0.0;
        double area = 0.0;
        for (int i = 0; i <= PROFILE_STEPS; i++) {
            double u = i / (double) PROFILE_STEPS;
            double w = Math.sin(Math.PI * u);
            double val = w <= 0.0 ? 0.0 : Math.pow(w, k);
            peak = Math.max(peak, val);
            if (i > 0) {
                double wPrev = Math.sin(Math.PI * (i - 1) / (double) PROFILE_STEPS);
                double prev = wPrev <= 0.0 ? 0.0 : Math.pow(wPrev, k);
                area += (val + prev) / 2.0 / PROFILE_STEPS;
            }
        }
        return area <= 1e-12 ? 1.0 : peak / area;
    }

    static double minJerkProgress(double u) {
        double c = clamp01(u);
        // A HALF period of a raised cosine, not a full one and not the textbook 16u^3-12u^4.
        //
        // The full period is a bug that is easy to write: 0.5-0.5cos(2*pi*u) reaches 1.0 at
        // u=0.5 and then flatlines, so the aim completes in half the ticks it was budgeted and
        // the back half contributes nothing. A half period, 0.5-0.5cos(pi*u), is the S-curve the
        // description claims: 0 at rest, 1 at rest, flat derivative at both ends, and 0.5 exactly
        // at the midpoint.
        //
        // Its VELOCITY, which is the part Flash & Hogan (1985) actually measured, is
        // 0.5*pi*sin(pi*u): bell-shaped with peak/mean pi/2 = 1.571. That is BELOW their measured
        // 1.805, which is why {@link #shapedProgress} exists and this raw curve is only the
        // fallback for a ratio of 1. (An earlier comment here claimed F&H measured 1.75 and that
        // the textbook polynomial is 6.0; both were wrong -- 1.75 is a Shadmehr course line and
        // 6.0 matched nothing. See VELOCITY_PEAK_MEAN.) The polynomial also opens so flat that
        // is stated rather than tuned away: a fitted beta curve would close it and would also be
        // a fudge nobody could check.
        return 0.5 - 0.5 * Math.cos(Math.PI * c);
    }

    private static double clamp01(double u) {
        return u < 0 ? 0 : (u > 1 ? 1 : u);
    }

    private static float clampMag(float v, float max) {
        if (v > max) {
            return max;
        }
        if (v < -max) {
            return -max;
        }
        return v;
    }

    private static float clampPitch(float p) {
        if (p > 90.0f) {
            return 90.0f;
        }
        if (p < -90.0f) {
            return -90.0f;
        }
        return p;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
