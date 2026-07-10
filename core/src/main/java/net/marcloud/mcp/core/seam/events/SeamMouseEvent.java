package net.marcloud.mcp.core.seam.events;

import net.marcloud.mcp.core.event.GameEvent;

/**
 * Fired when the GLFW mouse hook observes a mouse button event. Parameters
 * match GLFW's {@code glfwSetMouseButtonCallback} signature: button code,
 * action (press/release), and modifier flags. Observers may inspect but must
 * not mutate the game's input state.
 */
public final class SeamMouseEvent extends GameEvent {

    private final int button;
    private final int action;
    private final int mods;

    public SeamMouseEvent(int button, int action, int mods) {
        this.button = button;
        this.action = action;
        this.mods = mods;
    }

    public int button() {
        return button;
    }

    public int action() {
        return action;
    }

    public int mods() {
        return mods;
    }
}
