package net.marcloud.mcp.core.drivers.act;

import net.minecraft.util.MovementInput;

/**
 * A {@code MovementInput} that lets the act layer drive the player without
 * touching the vanilla client. It wraps the player's original input (normally a
 * {@code MovementInputFromOptions}) and, each tick, first delegates to it — so
 * keyboard/mouse still work and any vanilla side effects run — then, only while
 * the MOVE slot is {@link MoveIntentView#moveActive() active}, OVERRIDES the four
 * movement fields with the AI's intent.
 *
 * <p>This is the "client zero-diff" wedge: {@code EntityPlayerSP.movementInput} is
 * a public field, so {@link MovementInputInstaller} swaps in an instance of this
 * subclass with no edit to any {@code net.minecraft} class and no Byte Buddy — a
 * public field plus a subclass is enough.
 *
 * <p>When the slot is idle this class is behaviourally identical to the wrapped
 * input, so arming it is invisible until the AI actually submits a MOVE intent.
 * Sprint is not a {@code MovementInput} field (the player derives it in
 * {@code onLivingUpdate} from {@code moveForward >= 0.8}); see
 * {@link #sprintRequested()} which the installer applies via the player.
 */
public final class ActMovementInput extends MovementInput {

    private final MovementInput original;
    private final MoveIntentView view;

    public ActMovementInput(MovementInput original, MoveIntentView view) {
        this.original = original;
        this.view = view;
    }

    /** The wrapped vanilla input (so the installer can restore it on disarm). */
    public MovementInput original() {
        return original;
    }

    @Override
    public void updatePlayerMoveState() {
        // Always run vanilla first: preserves keyboard control and side effects.
        if (original != null) {
            original.updatePlayerMoveState();
            this.moveForward = original.moveForward;
            this.moveStrafe = original.moveStrafe;
            this.jump = original.jump;
            this.sneak = original.sneak;
        } else {
            this.moveForward = 0.0F;
            this.moveStrafe = 0.0F;
            this.jump = false;
            this.sneak = false;
        }

        // Override with the AI intent only while the MOVE slot is active.
        if (view != null && view.moveActive()) {
            this.moveForward = view.moveForward();
            this.moveStrafe = view.moveStrafe();
            this.jump = view.jump();
            this.sneak = view.sneak();
        }
    }

    /**
     * Whether the AI wants sprint this tick. Not a {@code MovementInput} field —
     * the player derives sprint from {@code moveForward} in {@code onLivingUpdate}
     * — so the installer reads this and calls {@code player.setSprinting}
     * explicitly when a MOVE intent asks for it.
     */
    public boolean sprintRequested() {
        return view != null && view.moveActive() && view.sprint();
    }
}
