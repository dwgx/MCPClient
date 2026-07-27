package net.marcloud.mcp.dwm.qml;

import io.github.timer_err.qml4j.render.items.core.MouseEvent;

/**
 * Translates LWJGL2 mouse button indices into qml4j's Qt-shaped button values.
 *
 * <p>Two vocabularies meet here and they collide on every value, which is what makes the mistake
 * silent rather than obvious:
 *
 * <ul>
 *   <li><b>LWJGL2 / GLFW</b> — a zero-based index. {@code Mouse.getEventButton()} gives 0 for
 *       left, 1 for right, 2 for middle. This is what {@code UiInput} carries.</li>
 *   <li><b>qml4j</b> — Qt's {@code MouseButton} flags, a bitmask:
 *       {@code NoButton} 0, {@code LeftButton} 1, {@code RightButton} 2, {@code MiddleButton} 4
 *       (see {@link MouseEvent#LEFT_BUTTON}, and {@code Qt.LeftButton} as QML sees it).</li>
 * </ul>
 *
 * <p>Read the raw index as a Qt value and left becomes {@code NoButton} while right becomes
 * {@code LeftButton} — every button off by one identity, with 0 landing on "no button at all".
 *
 * <p><b>Why nothing noticed.</b> qml4j's {@code EventDispatcher.hitTestMouseArea} skips the
 * {@code acceptedButtons} mask test entirely when the button is 0, so a raw left click still
 * reached every {@code MouseArea} — which is what all the Fluent controls are underneath. The
 * paths that test the button for equality against {@code LeftButton} are the ones that went dead:
 * text-field focus and caret placement, {@code AbstractButton} press/release, and
 * {@code Flickable} drag-scroll.
 *
 * <p>Unknown indices map to {@code NoButton} rather than being guessed at. Qt's higher buttons
 * continue as powers of two ({@code BackButton} is 8) but LWJGL2's ordering above the third
 * button is platform-dependent, so inventing a mapping would encode an assumption no one has
 * measured. {@code NoButton} degrades to "a press with no particular button", which the
 * dispatcher already handles.
 */
final class QmlButtonMap {

    /** Qt's {@code NoButton}: a press that names no button. */
    static final int NO_BUTTON = 0;

    private QmlButtonMap() {
    }

    /**
     * @param lwjglButton zero-based index as reported by {@code Mouse.getEventButton()}
     * @return the Qt button flag qml4j expects, or {@link #NO_BUTTON} for anything unmapped
     */
    static int toQml(int lwjglButton) {
        switch (lwjglButton) {
            case 0:  return MouseEvent.LEFT_BUTTON;
            case 1:  return MouseEvent.RIGHT_BUTTON;
            case 2:  return MouseEvent.MIDDLE_BUTTON;
            default: return NO_BUTTON;
        }
    }
}
