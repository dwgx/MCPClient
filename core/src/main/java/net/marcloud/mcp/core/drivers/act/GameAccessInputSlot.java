package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;

/**
 * The live {@link PlayerInputSlot} over {@link GameAccess} / {@link EntityPlayerSP}.
 * The ONLY place in the act movement path that touches the vanilla player, kept
 * tiny so everything else stays headless-testable through the interface.
 *
 * <p>Reads and writes the public {@code EntityPlayerSP.movementInput} field
 * directly — no reflection, no Byte Buddy — which is exactly why the client needs
 * zero edits.
 */
public final class GameAccessInputSlot implements PlayerInputSlot {

    private final GameAccess game;

    public GameAccessInputSlot(GameAccess game) {
        this.game = game;
    }

    @Override
    public Object playerIdentity() {
        return game.player();
    }

    @Override
    public MovementInput get() {
        EntityPlayerSP p = game.player();
        return p == null ? null : p.movementInput;
    }

    @Override
    public void set(MovementInput input) {
        EntityPlayerSP p = game.player();
        if (p != null) {
            p.movementInput = input;
        }
    }

    @Override
    public void setSprinting(boolean sprinting) {
        EntityPlayerSP p = game.player();
        if (p != null) {
            p.setSprinting(sprinting);
        }
    }
}
