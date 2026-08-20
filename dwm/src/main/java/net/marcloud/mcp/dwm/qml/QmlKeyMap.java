package net.marcloud.mcp.dwm.qml;

import io.github.timer_err.qml4j.render.QmlView;
import net.marcloud.mcp.dwm.ui.UiKeys;

import org.lwjgl.input.Keyboard;

/**
 * Translates between the three key vocabularies that meet at this boundary.
 *
 * <p>There are genuinely three, and conflating any two of them is a bug that presents as "the
 * wrong key does the wrong thing":</p>
 *
 * <ul>
 *   <li><b>LWJGL2 DirectInput scancodes</b> — what {@code Keyboard.getEventKey()} reports and
 *       what MC persists in {@code options.txt}. The shim translates GLFW to these on the way
 *       in precisely so saved keybinds keep working.</li>
 *   <li><b>{@link UiKeys}</b> — dwm's own small backend-neutral set, so the SPI carries no
 *       backend types.</li>
 *   <li><b>{@link QmlView} KEY_* constants</b> — what qml4j's dispatcher expects.</li>
 * </ul>
 *
 * <p>Only keys with editing semantics need mapping. Ordinary characters travel as text, which
 * MC already gives us decoded via {@code Keyboard.getEventCharacter()} — going through the
 * character avoids reimplementing keyboard layout handling here.
 */
final class QmlKeyMap {

    private QmlKeyMap() {
    }

    /**
     * LWJGL2 scancode to a {@link UiKeys} constant, or {@link UiKeys#NONE} when the key has no
     * editing meaning and should travel as text instead.
     *
     * @param shiftHeld folds Shift+Tab into {@link UiKeys#BACKTAB}, which backends treat as a
     *                  distinct key rather than Tab plus a modifier
     */
    static int fromLwjgl(int scancode, boolean shiftHeld) {
        // if/else rather than switch: the shim's Keyboard.KEY_* are assigned from register(),
        // so they are not compile-time constants and cannot be case labels. Reading them as
        // fields is also the point — hardcoding 0x0E here would be a second source of truth
        // for scancodes MC persists in options.txt.
        if (scancode == Keyboard.KEY_BACK) {
            return UiKeys.BACKSPACE;
        }
        if (scancode == Keyboard.KEY_DELETE) {
            return UiKeys.DELETE;
        }
        if (scancode == Keyboard.KEY_RETURN || scancode == Keyboard.KEY_NUMPADENTER) {
            return UiKeys.ENTER;
        }
        if (scancode == Keyboard.KEY_LEFT) {
            return UiKeys.LEFT;
        }
        if (scancode == Keyboard.KEY_RIGHT) {
            return UiKeys.RIGHT;
        }
        if (scancode == Keyboard.KEY_UP) {
            return UiKeys.UP;
        }
        if (scancode == Keyboard.KEY_DOWN) {
            return UiKeys.DOWN;
        }
        if (scancode == Keyboard.KEY_HOME) {
            return UiKeys.HOME;
        }
        if (scancode == Keyboard.KEY_END) {
            return UiKeys.END;
        }
        if (scancode == Keyboard.KEY_ESCAPE) {
            return UiKeys.ESCAPE;
        }
        if (scancode == Keyboard.KEY_TAB) {
            return shiftHeld ? UiKeys.BACKTAB : UiKeys.TAB;
        }
        return UiKeys.NONE;
    }

    /**
     * {@link UiKeys} constant to the qml4j equivalent, or 0 for text-only input.
     *
     * <p>{@code DELETE} has no qml4j constant as of 0.2.27, so it maps to 0 and forward-delete is
     * simply not forwarded — better than guessing at a constant that does not exist.
     */
    static int toQml(int uiKey) {
        switch (uiKey) {
            case UiKeys.BACKSPACE: return QmlView.KEY_BACKSPACE;
            case UiKeys.ENTER:     return QmlView.KEY_ENTER;
            case UiKeys.LEFT:      return QmlView.KEY_LEFT;
            case UiKeys.RIGHT:     return QmlView.KEY_RIGHT;
            case UiKeys.UP:        return QmlView.KEY_UP;
            case UiKeys.DOWN:      return QmlView.KEY_DOWN;
            case UiKeys.HOME:      return QmlView.KEY_HOME;
            case UiKeys.END:       return QmlView.KEY_END;
            case UiKeys.ESCAPE:    return QmlView.KEY_ESCAPE;
            case UiKeys.TAB:       return QmlView.KEY_TAB;
            case UiKeys.BACKTAB:   return QmlView.KEY_BACKTAB;
            default:               return 0;
        }
    }
}
