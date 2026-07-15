package net.marcloud.mcp.core.drivers.act;

/**
 * Pure state machine for the single-shot interactions: {@link InteractIntent.Kind#USE},
 * {@link InteractIntent.Kind#PLACE}, and {@link InteractIntent.Kind#ATTACK}. Ticked
 * by the INTERACT applier through an {@link ActActuator}; it never marshals threads.
 *
 * <p>These interactions complete (or fail) in a single tick — there is no
 * multi-tick progress like digging.
 *
 * <ul>
 *   <li><b>PLACE</b> → {@link ActActuator#rightClickBlock} against the target face at
 *       the hit offset. No block target → honest fail.
 *   <li><b>USE</b> → {@code rightClickBlock} if a block target is set, else
 *       {@link ActActuator#useItemInAir()}.
 *   <li><b>ATTACK</b> → reach-check the entity, then {@link ActActuator#attackEntity}
 *       + {@link ActActuator#swing()}. No target / out of reach → fail with NO
 *       actuator call (never send an attack we know the game will reject).
 * </ul>
 */
public final class InteractController {

    private final InteractIntent intent;
    private boolean done;

    public InteractController(InteractIntent intent) {
        this.intent = intent;
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /** Advance one tick against {@code act}. Terminal in a single step. */
    public ActOutcome tick(ActActuator act) {
        if (!act.inWorld()) {
            return finish(ActOutcome.failed("not in world"));
        }
        return switch (intent.kind()) {
            case PLACE -> place(act);
            case USE -> use(act);
            case ATTACK -> attack(act);
            default -> finish(ActOutcome.failed("interact kind " + intent.kind()
                    + " is not handled by InteractController"));
        };
    }

    private ActOutcome place(ActActuator act) {
        if (!intent.hasBlock()) {
            return finish(ActOutcome.failed("place needs a target block face"));
        }
        ActActuator.Face face = ActActuator.Face.fromIndex(intent.face());
        boolean ok = act.rightClickBlock(intent.blockX(), intent.blockY(), intent.blockZ(),
                face, intent.hitX(), intent.hitY(), intent.hitZ());
        if (ok) {
            return finish(ActOutcome.done("placed/activated against ("
                    + intent.blockX() + "," + intent.blockY() + "," + intent.blockZ() + ")"));
        }
        return finish(ActOutcome.failed("place rejected at ("
                + intent.blockX() + "," + intent.blockY() + "," + intent.blockZ() + ")"));
    }

    private ActOutcome use(ActActuator act) {
        boolean ok;
        String where;
        if (intent.hasBlock()) {
            ActActuator.Face face = ActActuator.Face.fromIndex(intent.face());
            ok = act.rightClickBlock(intent.blockX(), intent.blockY(), intent.blockZ(),
                    face, intent.hitX(), intent.hitY(), intent.hitZ());
            where = "on block (" + intent.blockX() + "," + intent.blockY() + "," + intent.blockZ() + ")";
        } else {
            ok = act.useItemInAir();
            where = "in air";
        }
        return finish(ok ? ActOutcome.done("used item " + where)
                : ActOutcome.failed("use rejected " + where));
    }

    private ActOutcome attack(ActActuator act) {
        if (intent.entityId() < 0) {
            return finish(ActOutcome.failed("attack needs a target entity"));
        }
        double[] target = act.entityEyePos(intent.entityId());
        if (target == null) {
            return finish(ActOutcome.failed("attack target entity " + intent.entityId() + " is gone"));
        }
        double[] eye = act.eyePos();
        if (eye != null) {
            double dx = target[0] - eye[0];
            double dy = target[1] - eye[1];
            double dz = target[2] - eye[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > act.reachDistance()) {
                // Fail WITHOUT calling attackEntity — do not send an attack the game rejects.
                return finish(ActOutcome.failed("entity " + intent.entityId() + " is out of reach"));
            }
        }
        boolean ok = act.attackEntity(intent.entityId());
        if (!ok) {
            return finish(ActOutcome.failed("attack on entity " + intent.entityId() + " was refused"));
        }
        act.swing();
        return finish(ActOutcome.done("attacked entity " + intent.entityId()));
    }

    private ActOutcome finish(ActOutcome outcome) {
        this.done = outcome.terminal();
        return outcome;
    }
}
