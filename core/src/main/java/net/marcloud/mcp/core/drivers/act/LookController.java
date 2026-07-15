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
 * <p><b>Angle math</b> (vanilla convention): given delta {@code dx,dy,dz} from eye
 * to target, {@code yaw = atan2(dz, dx) * 180/PI - 90} and
 * {@code pitch = -atan2(dy, sqrt(dx*dx+dz*dz)) * 180/PI}. Yaw deltas are wrapped to
 * [-180, 180] so the turn always takes the short arc, e.g. from +179 to -179.
 */
public final class LookController {

    private final LookIntent intent;
    private boolean started;

    public LookController(LookIntent intent) {
        this.intent = intent;
    }

    /** Advance one tick against {@code act}. */
    public ActOutcome tick(ActActuator act) {
        if (!act.inWorld()) {
            return ActOutcome.failed("not in world");
        }
        Float targetYaw;
        Float targetPitch;
        if (intent.mode() == LookIntent.Mode.SET) {
            targetYaw = intent.yaw();
            targetPitch = intent.pitch();
        } else {
            float[] resolved = resolveLookAt(act);
            if (resolved == null) {
                return ActOutcome.failed(intent.hasEntity()
                        ? "look target entity " + intent.targetEntityId() + " is gone"
                        : "look target unavailable");
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

        if (instant || withinStep(yawErr, pitchErr, intent.slewDegPerTick())) {
            // Land exactly on target. Snap (prev==cur) so no interpolation artifact.
            act.setRotation(targetYaw, clampPitch(targetPitch));
            return ActOutcome.done("aimed at yaw=" + fmt(targetYaw) + " pitch=" + fmt(clampPitch(targetPitch)));
        }

        // One slew step of at most slewDegPerTick on each axis, shorter way for yaw.
        float step = intent.slewDegPerTick();
        float nextYaw = curYaw + clampMag(yawErr, step);
        float nextPitch = clampPitch(curPitch + clampMag(pitchErr, step));
        // Interp: previous = where we were, current = the step target.
        act.setRotationInterp(curYaw, curPitch, nextYaw, nextPitch);
        return ActOutcome.running("slewing toward yaw=" + fmt(targetYaw)
                + " (now " + fmt(nextYaw) + ")");
    }

    /** True once at least one tick has run (for status/debugging). */
    public boolean isStarted() {
        return started;
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
