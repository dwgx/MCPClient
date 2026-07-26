package net.marcloud.mcp.dwm.ui;

/**
 * Backend-neutral key identifiers for {@link UiInput#key}.
 *
 * <p>Deliberately its own small set rather than LWJGL2 scancodes or a backend's constants.
 * The adapter translates at the edge, which is what allows the SPI to stay free of backend
 * types — and it means a scancode change on the input side cannot silently retarget a
 * different key in the UI.
 *
 * <p>Values are arbitrary and carry no wire or persistence meaning; nothing outside this
 * module stores them.
 */
public final class UiKeys {

    /** Not a key we model. Pass with text for plain typed characters. */
    public static final int NONE = 0;

    public static final int BACKSPACE = 1;
    public static final int ENTER = 2;
    public static final int LEFT = 3;
    public static final int RIGHT = 4;
    public static final int UP = 5;
    public static final int DOWN = 6;
    public static final int HOME = 7;
    public static final int END = 8;
    public static final int ESCAPE = 9;
    public static final int TAB = 10;
    /** Shift+Tab, which backends generally treat as its own key rather than a modifier. */
    public static final int BACKTAB = 11;
    public static final int DELETE = 12;

    private UiKeys() {
    }
}
