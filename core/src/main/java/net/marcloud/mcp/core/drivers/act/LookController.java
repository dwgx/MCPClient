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
     * Whether the crosshair has ever actually reached the target.
     *
     * <p>Load-bearing only at the {@code durationTicks} ending, and it is what keeps that
     * ending from lying: "tracked for 100 ticks" reads as success, and a caller acts on it by
     * attacking or digging. A slew cap too slow for the target -- or a target moving away
     * faster than the cap -- runs the whole duration without the crosshair ever arriving, and
     * reporting that as done would be a confident claim in the dangerous direction.
     */
    private boolean everAimed;

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
            return ActOutcome.done("already finished");
        }
        if (cancelRequested) {
            // Nothing to hand back to the game: unlike a hold, which owns a static KeyBinding
            // that outlives the controller, an aim's only effect is the rotation already written,
            // and the player is entitled to be left looking where they were pointed. So the
            // teardown is the report itself -- how long the aim was held, which the caller cannot
            // get anywhere else once the slot is reset.
            return finish(ActOutcome.cancelled("look cancelled after " + ticks + " ticks"));
        }
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world"));
        }
        Float targetYaw;
        Float targetPitch;
        if (intent.mode() == LookIntent.Mode.SET) {
            targetYaw = intent.yaw();
            targetPitch = intent.pitch();
        } else {
            float[] resolved = resolveLookAt(act);
            if (resolved == null) {
                return finish(ActOutcome.failed(gone()));
            }
            targetYaw = resolved[0];
            targetPitch = resolved[1];
        }

        float curYaw = act.yaw();
        float curPitch = act.pitch();
        float yawErr = wrapTo180(targetYaw - curYaw);
        float pitchErr = targetPitch - curPitch;

        boolean instant = intent.slewDegPerTick() <= 0f;
        started = true;
        ticks++;

        boolean landed = instant || withinStep(yawErr, pitchErr, intent.slewDegPerTick());
        float wroteYaw;
        if (landed) {
            // Land exactly on target. Snap (prev==cur) so no interpolation artifact.
            wroteYaw = targetYaw;
            act.setRotation(targetYaw, clampPitch(targetPitch));
            everAimed = true;
            if (!keepAiming) {
                return finish(ActOutcome.done("aimed at yaw=" + fmt(targetYaw)
                        + " pitch=" + fmt(clampPitch(targetPitch))));
            }
        } else {
            // One slew step of at most slewDegPerTick on each axis, shorter way for yaw.
            float step = intent.slewDegPerTick();
            wroteYaw = curYaw + clampMag(yawErr, step);
            float nextPitch = clampPitch(curPitch + clampMag(pitchErr, step));
            // Interp: previous = where we were, current = the step target.
            act.setRotationInterp(curYaw, curPitch, wroteYaw, nextPitch);
            if (!keepAiming) {
                return ActOutcome.running("slewing toward yaw=" + fmt(targetYaw)
                        + " (now " + fmt(wroteYaw) + ")");
            }
        }

        // KEEP from here down. The duration test comes AFTER the write, so the last tick of a
        // bounded track still corrects the aim instead of spending its tick on bookkeeping.
        if (durationTicks > 0 && ticks >= durationTicks) {
            if (!everAimed) {
                return finish(ActOutcome.failed("tracked for " + ticks + " ticks but the crosshair "
                        + "never reached the target, still " + fmt(Math.abs(yawErr))
                        + " degrees of yaw out -- at " + fmt(intent.slewDegPerTick())
                        + " deg/tick the aim could not catch it, so do not read this as having "
                        + "looked at it"));
            }
            return finish(ActOutcome.done("tracked for " + ticks + " ticks, aim held on yaw="
                    + fmt(targetYaw) + " pitch=" + fmt(clampPitch(targetPitch))));
        }
        if (landed) {
            return ActOutcome.running("holding aim on yaw=" + fmt(targetYaw)
                    + " pitch=" + fmt(clampPitch(targetPitch)) + " (tick " + ticks
                    + durationLimit() + ")");
        }
        return ActOutcome.running("slewing toward yaw=" + fmt(targetYaw)
                + " (now " + fmt(wroteYaw) + ", tick " + ticks + durationLimit() + ")");
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
