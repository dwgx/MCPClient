package net.marcloud.mcp.core.drivers.act;

import net.minecraft.util.MovementInput;

/**
 * The single abstraction over "the player's {@code movementInput} field" — get
 * the current input, set a new one, and identify which player object owns it.
 * {@link MovementInputInstaller} works only through this seam, so its swap/restore
 * and identity-change logic can be exercised headlessly with a fake, while the
 * live {@link GameAccessInputSlot} is the only piece that touches
 * {@code EntityPlayerSP}.
 *
 * <p>{@link #playerIdentity()} returns the current player object (or null when not
 * in world). The installer swaps the input whenever this identity changes, because
 * world-join / respawn / dimension change re-instantiates the player and its
 * {@code movementInput}, dropping any previously-installed override.
 */
public interface PlayerInputSlot {

    /** The current player object, used only for identity comparison. Null if not in world. */
    Object playerIdentity();

    /** The player's current {@code movementInput}, or null if not in world. */
    MovementInput get();

    /** Set the player's {@code movementInput}. No-op if not in world. */
    void set(MovementInput input);

    /** Apply sprint to the live player (sprint is not a {@code MovementInput} field). */
    void setSprinting(boolean sprinting);
}
