package net.marcloud.mcp.dwm.desktop;

import java.util.HashSet;
import java.util.Set;

import net.marcloud.mcp.dwm.compositor.WidgetState;

/**
 * Retained input state for the launcher: turns the per-frame set of DOWN keys (from
 * GameInput's non-consuming poll) into edges — RShift toggles the launcher open/closed,
 * and typed characters build the search query while open. Backend-agnostic (no GL type).
 *
 * <p>Non-consuming input means we see "key is down" each frame, not discrete press events,
 * so we edge-detect here: a key counts as a press on the frame it transitions from up to
 * down. Key repeat while held is intentionally suppressed (one char per physical press) —
 * good enough for a search box without the consumed event queue.
 *
 * <p>LWJGL scancodes (fixed DirectInput layout): RSHIFT=0x36, BACK=0x0E; letters/digits are
 * mapped in {@link #CHAR_MAP}. Only these keys are acted on; everything else is ignored so
 * the overlay never fights MC for other keys.
 */
public final class DesktopInputState implements WidgetState {

    private static final int KEY_RSHIFT = 0x36;
    private static final int KEY_BACK = 0x0E;
    private static final int KEY_SPACE = 0x39;
    private static final int KEY_ESCAPE = 0x01;
    private static final int KEY_ENTER = 0x1C;
    /** Max account-name length while editing (keeps the footer name from overflowing). */
    private static final int NAME_MAX = 16;

    // Movement keys (LWJGL/DirectInput scancodes): W/A/S/D + space (jump) + LShift (sneak).
    // While ANY of these is held, typing does NOT go to the search box — the player is
    // moving, not searching — regardless of the open-then-move / move-then-open order.
    private static final int KEY_W = 0x11, KEY_A_MOVE = 0x1E, KEY_S_MOVE = 0x1F, KEY_D = 0x20;
    private static final int KEY_LSHIFT = 0x2A, KEY_LCTRL = 0x1D;
    private static final Set<Integer> MOVEMENT_KEYS = Set.of(
            KEY_W, KEY_A_MOVE, KEY_S_MOVE, KEY_D, KEY_SPACE, KEY_LSHIFT, KEY_LCTRL);

    // Launcher starts VISIBLE (RShift then toggles it closed/open). A start-menu the user
    // never sees would look broken; making it open-on-arm matches how the overlay is used.
    private boolean open = true;
    private boolean settings;     // settings/theme view is showing instead of the app list
    private final StringBuilder query = new StringBuilder();
    // Account-name editing: while active, typing edits nameBuffer (not the search query);
    // Enter commits it to accountName, Esc cancels back to the last committed name.
    private boolean editingName;
    private String accountName = "admin";
    private final StringBuilder nameBuffer = new StringBuilder();
    private Set<Integer> prevDown = new HashSet<>();

    // Whether the player may keep moving (WASD) while the launcher is open. Default OFF:
    // opening the launcher is fully modal (game frozen, movement disabled, all keys feed the
    // search box). When ON, the player can walk with the launcher up, movement keys drive the
    // game (never the search box), and only non-movement typing edits the query.
    private boolean allowMoveWhileOpen;
    // True while a movement key is held THIS frame — published so the backend can decide
    // whether to let movement through and so typing is suppressed during movement.
    private boolean moving;

    @Override
    public void tick(float dtSeconds) {
        // no timeline; edges are computed in update()
    }

    @Override
    public boolean animating() {
        return false;
    }

    /**
     * Feed this frame's down-key set; apply edges (RShift toggle, typing). Returns nothing;
     * read {@link #isOpen()} / {@link #query()} after.
     */
    public void update(java.util.List<Integer> keysDown) {
        Set<Integer> now = keysDown == null ? Set.of() : new HashSet<>(keysDown);
        // Track movement first: if any movement key is held this frame, the player is moving,
        // and fresh presses are treated as movement — never fed to the search box.
        moving = false;
        for (int key : now) {
            if (MOVEMENT_KEYS.contains(key)) {
                moving = true;
                break;
            }
        }
        for (int key : now) {
            if (prevDown.contains(key)) {
                continue; // still held — not a fresh press
            }
            onPress(key);
        }
        prevDown = now;
    }

    private void onPress(int key) {
        if (key == KEY_RSHIFT) {
            // RShift no longer toggles visibility here: the launcher is now a real GuiScreen
            // whose open/close is owned by MC's screen lifecycle (opened by the independent
            // hotkey system, closed by ESC / re-press). This state only exists WHILE the
            // screen is showing, so it is always "open"; RShift is swallowed so it can never
            // blank the visible screen by flipping this flag to false.
            return;
        }
        if (!open) {
            return; // typing only affects the search box while the launcher is open
        }
        // Movement keys (WASD / space / shift / ctrl) are ALWAYS movement, never search text —
        // unconditionally, so a fresh press while walking (or after opening the menu mid-walk)
        // drives the player, not the search box. Owner rule: "moving never types into search."
        // Trade-off: these letters cannot be typed into the search field; software is found by
        // its other letters. (If full typing is ever wanted, relax this guard.)
        if (MOVEMENT_KEYS.contains(key)) {
            return;
        }
        // Account-name editing captures all typing until Enter (commit) or Esc (cancel).
        if (editingName) {
            editName(key);
            return;
        }
        if (key == KEY_ESCAPE) {
            // ESC unwinds one sub-mode at a time: settings view -> query. Closing the launcher
            // is NOT done here anymore — the GuiScreen intercepts ESC at the top level and
            // calls displayGuiScreen(null) itself (the reference-client pattern). So this only
            // ever peels back a sub-mode; when there is none, it is a harmless no-op (the screen
            // already handled the close before this state would see it).
            if (settings) {
                settings = false;
            } else if (query.length() > 0) {
                query.setLength(0);
            }
            return;
        }
        if (settings) {
            return; // in the settings view, typing does not edit the (hidden) search box
        }
        if (key == KEY_BACK) {
            if (query.length() > 0) {
                query.setLength(query.length() - 1);
            }
            return;
        }
        if (key == KEY_SPACE) {
            query.append(' ');
            return;
        }
        char c = CHAR_MAP.length > key && key >= 0 ? CHAR_MAP[key] : 0;
        if (c != 0) {
            query.append(c);
        }
    }

    /** Apply one keypress while editing the account name: Enter commits, Esc cancels. */
    private void editName(int key) {
        if (key == KEY_ENTER) {
            commitName();
            return;
        }
        if (key == KEY_ESCAPE) {
            cancelNameEdit();
            return;
        }
        if (key == KEY_BACK) {
            if (nameBuffer.length() > 0) {
                nameBuffer.setLength(nameBuffer.length() - 1);
            }
            return;
        }
        if (nameBuffer.length() >= NAME_MAX) {
            return; // buffer full — ignore further characters
        }
        if (key == KEY_SPACE) {
            nameBuffer.append(' ');
            return;
        }
        char c = CHAR_MAP.length > key && key >= 0 ? CHAR_MAP[key] : 0;
        if (c != 0) {
            nameBuffer.append(c);
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    /** True when the settings/theme view is showing instead of the software list. */
    public boolean isSettings() {
        return settings;
    }

    /** Flip between the software list and the settings/theme view (the gear button). */
    public void toggleSettings() {
        settings = !settings;
    }

    public void setSettings(boolean settings) {
        this.settings = settings;
    }

    /** Whether the player may keep moving (WASD) while the launcher is open (default off). */
    public boolean allowMoveWhileOpen() {
        return allowMoveWhileOpen;
    }

    public void setAllowMoveWhileOpen(boolean allow) {
        this.allowMoveWhileOpen = allow;
    }

    /** Flip the move-while-open setting; returns the new value (for a settings toggle). */
    public boolean toggleAllowMoveWhileOpen() {
        allowMoveWhileOpen = !allowMoveWhileOpen;
        return allowMoveWhileOpen;
    }

    /** True while a movement key (WASD/space/shift/ctrl) is held this frame. */
    public boolean isMoving() {
        return moving;
    }

    /**
     * Whether the game should currently be allowed to receive movement while the launcher is
     * open. The backend uses this to decide whether to fully freeze input (modal) or let the
     * player walk. Closed launcher = always let the game move.
     */
    public boolean gameMovementAllowed() {
        return !open || allowMoveWhileOpen;
    }

    /** True while the account name is being edited (typing feeds the name, not the query). */
    public boolean isEditingName() {
        return editingName;
    }

    /** Enter account-name edit mode, seeding the buffer with the current committed name. */
    public void beginNameEdit() {
        editingName = true;
        nameBuffer.setLength(0);
        nameBuffer.append(accountName);
    }

    /** Commit the edited name (trimmed; blank falls back to "Guest") and exit edit mode. */
    public void commitName() {
        String trimmed = nameBuffer.toString().trim();
        accountName = trimmed.isEmpty() ? "admin" : trimmed;
        editingName = false;
    }

    /** Cancel editing, discarding the buffer and keeping the last committed name. */
    public void cancelNameEdit() {
        editingName = false;
    }

    /** The committed account name shown in the footer. */
    public String accountName() {
        return accountName;
    }

    /** The live edit buffer (what AccountBar shows with a caret while {@link #isEditingName()}). */
    public String nameBuffer() {
        return nameBuffer.toString();
    }

    public String query() {
        return query.toString();
    }

    // LWJGL/DirectInput scancode -> lowercase char, for the keys a search box needs.
    private static final char[] CHAR_MAP = new char[0x40];

    static {
        put(0x02, '1'); put(0x03, '2'); put(0x04, '3'); put(0x05, '4'); put(0x06, '5');
        put(0x07, '6'); put(0x08, '7'); put(0x09, '8'); put(0x0A, '9'); put(0x0B, '0');
        put(0x10, 'q'); put(0x11, 'w'); put(0x12, 'e'); put(0x13, 'r'); put(0x14, 't');
        put(0x15, 'y'); put(0x16, 'u'); put(0x17, 'i'); put(0x18, 'o'); put(0x19, 'p');
        put(0x1E, 'a'); put(0x1F, 's'); put(0x20, 'd'); put(0x21, 'f'); put(0x22, 'g');
        put(0x23, 'h'); put(0x24, 'j'); put(0x25, 'k'); put(0x26, 'l');
        put(0x2C, 'z'); put(0x2D, 'x'); put(0x2E, 'c'); put(0x2F, 'v'); put(0x30, 'b');
        put(0x31, 'n'); put(0x32, 'm');
    }

    private static void put(int code, char c) {
        if (code >= 0 && code < CHAR_MAP.length) {
            CHAR_MAP[code] = c;
        }
    }
}
