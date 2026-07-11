package net.marcloud.mcp.core.flt.seam.events;

import net.marcloud.mcp.core.ke.event.GameEvent;

/**
 * Fired when the GLFW key hook observes a key event. Parameters match GLFW's
 * {@code glfwSetKeyCallback} signature: key code, scancode, action (press/
 * release/repeat), and modifier flags. Observers may inspect but must not
 * mutate the game's input state.
 */
public final class SeamKeyEvent extends GameEvent {

    private final int key;
    private final int scancode;
    private final int action;
    private final int mods;

    public SeamKeyEvent(int key, int scancode, int action, int mods) {
        this.key = key;
        this.scancode = scancode;
        this.action = action;
        this.mods = mods;
    }

    public int key() {
        return key;
    }

    public int scancode() {
        return scancode;
    }

    public int action() {
        return action;
    }

    public int mods() {
        return mods;
    }
}
