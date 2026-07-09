package org.lwjgl.input;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.Sys;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.impl.LWJGLImplementationUtils;
import org.lwjgl.impl.input.InputImplementation;
import org.lwjgl.opengl.Display;

/**
 * LWJGL2-compatible raw keyboard facade.
 *
 * <p>This is the static entry point Minecraft 1.8.9 talks to directly. LWJGL3 removed
 * {@code org.lwjgl.input.Keyboard}, so we re-author it here on top of the group-4
 * {@link InputImplementation} backend. Event data flows through a byte buffer using the
 * historical LWJGL2 wire format:</p>
 *
 * <pre>
 *   int  key        (LWJGL2 DirectInput scancode)
 *   byte state      (0 = up, non-zero = down)
 *   int  character  (translated code point)
 *   long nanos      (event time)
 *   byte repeat     (1 = OS auto-repeat)
 *   ------------------------------------------- = 18 bytes
 * </pre>
 *
 * <p>All key codes exposed here (both {@code isKeyDown} indices and {@code getEventKey}
 * results) are LWJGL2 DirectInput scancodes, because those are what MC persists in its
 * keybind options file. The backend must translate raw GLFW key codes to these scancodes;
 * {@link #getKeyIndexFromGLFW(int)} provides the canonical mapping.</p>
 */
public class Keyboard {

    /** Wire size, in bytes, of a single buffered key event. */
    public static final int EVENT_SIZE = 4 + 1 + 4 + 8 + 1;

    /** Maximum number of events buffered between reads. */
    private static final int BUFFER_SIZE = 50;

    /**
     * Size of the key-down state buffer. Fixed at the classic LWJGL2 value of 256, which
     * comfortably spans every DirectInput scancode we emit (the largest being 0xDF).
     */
    public static final int KEYBOARD_SIZE = 256;

    // -- name / code registries (must be initialised before the KEY_* constants below) --

    private static final String[] keyNames = new String[KEYBOARD_SIZE];
    private static final Map<String, Integer> keyMap = new HashMap<String, Integer>(256);

    private static int register(String name, int scancode) {
        if (scancode >= 0 && scancode < keyNames.length) {
            keyNames[scancode] = name;
        }
        keyMap.put(name, Integer.valueOf(scancode));
        return scancode;
    }

    public static final int CHAR_NONE = '\0';

    public static final int KEY_NONE        = register("NONE", 0x00);
    public static final int KEY_ESCAPE      = register("ESCAPE", 0x01);
    public static final int KEY_1           = register("1", 0x02);
    public static final int KEY_2           = register("2", 0x03);
    public static final int KEY_3           = register("3", 0x04);
    public static final int KEY_4           = register("4", 0x05);
    public static final int KEY_5           = register("5", 0x06);
    public static final int KEY_6           = register("6", 0x07);
    public static final int KEY_7           = register("7", 0x08);
    public static final int KEY_8           = register("8", 0x09);
    public static final int KEY_9           = register("9", 0x0A);
    public static final int KEY_0           = register("0", 0x0B);
    public static final int KEY_MINUS       = register("MINUS", 0x0C);
    public static final int KEY_EQUALS      = register("EQUALS", 0x0D);
    public static final int KEY_BACK        = register("BACK", 0x0E);
    public static final int KEY_TAB         = register("TAB", 0x0F);
    public static final int KEY_Q           = register("Q", 0x10);
    public static final int KEY_W           = register("W", 0x11);
    public static final int KEY_E           = register("E", 0x12);
    public static final int KEY_R           = register("R", 0x13);
    public static final int KEY_T           = register("T", 0x14);
    public static final int KEY_Y           = register("Y", 0x15);
    public static final int KEY_U           = register("U", 0x16);
    public static final int KEY_I           = register("I", 0x17);
    public static final int KEY_O           = register("O", 0x18);
    public static final int KEY_P           = register("P", 0x19);
    public static final int KEY_LBRACKET    = register("LBRACKET", 0x1A);
    public static final int KEY_RBRACKET    = register("RBRACKET", 0x1B);
    public static final int KEY_RETURN      = register("RETURN", 0x1C);
    public static final int KEY_LCONTROL    = register("LCONTROL", 0x1D);
    public static final int KEY_A           = register("A", 0x1E);
    public static final int KEY_S           = register("S", 0x1F);
    public static final int KEY_D           = register("D", 0x20);
    public static final int KEY_F           = register("F", 0x21);
    public static final int KEY_G           = register("G", 0x22);
    public static final int KEY_H           = register("H", 0x23);
    public static final int KEY_J           = register("J", 0x24);
    public static final int KEY_K           = register("K", 0x25);
    public static final int KEY_L           = register("L", 0x26);
    public static final int KEY_SEMICOLON   = register("SEMICOLON", 0x27);
    public static final int KEY_APOSTROPHE  = register("APOSTROPHE", 0x28);
    public static final int KEY_GRAVE       = register("GRAVE", 0x29);
    public static final int KEY_LSHIFT      = register("LSHIFT", 0x2A);
    public static final int KEY_BACKSLASH   = register("BACKSLASH", 0x2B);
    public static final int KEY_Z           = register("Z", 0x2C);
    public static final int KEY_X           = register("X", 0x2D);
    public static final int KEY_C           = register("C", 0x2E);
    public static final int KEY_V           = register("V", 0x2F);
    public static final int KEY_B           = register("B", 0x30);
    public static final int KEY_N           = register("N", 0x31);
    public static final int KEY_M           = register("M", 0x32);
    public static final int KEY_COMMA       = register("COMMA", 0x33);
    public static final int KEY_PERIOD      = register("PERIOD", 0x34);
    public static final int KEY_SLASH       = register("SLASH", 0x35);
    public static final int KEY_RSHIFT      = register("RSHIFT", 0x36);
    public static final int KEY_MULTIPLY    = register("MULTIPLY", 0x37);
    public static final int KEY_LMENU       = register("LMENU", 0x38);
    public static final int KEY_SPACE       = register("SPACE", 0x39);
    public static final int KEY_CAPITAL     = register("CAPITAL", 0x3A);
    public static final int KEY_F1          = register("F1", 0x3B);
    public static final int KEY_F2          = register("F2", 0x3C);
    public static final int KEY_F3          = register("F3", 0x3D);
    public static final int KEY_F4          = register("F4", 0x3E);
    public static final int KEY_F5          = register("F5", 0x3F);
    public static final int KEY_F6          = register("F6", 0x40);
    public static final int KEY_F7          = register("F7", 0x41);
    public static final int KEY_F8          = register("F8", 0x42);
    public static final int KEY_F9          = register("F9", 0x43);
    public static final int KEY_F10         = register("F10", 0x44);
    public static final int KEY_NUMLOCK     = register("NUMLOCK", 0x45);
    public static final int KEY_SCROLL      = register("SCROLL", 0x46);
    public static final int KEY_NUMPAD7     = register("NUMPAD7", 0x47);
    public static final int KEY_NUMPAD8     = register("NUMPAD8", 0x48);
    public static final int KEY_NUMPAD9     = register("NUMPAD9", 0x49);
    public static final int KEY_SUBTRACT    = register("SUBTRACT", 0x4A);
    public static final int KEY_NUMPAD4     = register("NUMPAD4", 0x4B);
    public static final int KEY_NUMPAD5     = register("NUMPAD5", 0x4C);
    public static final int KEY_NUMPAD6     = register("NUMPAD6", 0x4D);
    public static final int KEY_ADD         = register("ADD", 0x4E);
    public static final int KEY_NUMPAD1     = register("NUMPAD1", 0x4F);
    public static final int KEY_NUMPAD2     = register("NUMPAD2", 0x50);
    public static final int KEY_NUMPAD3     = register("NUMPAD3", 0x51);
    public static final int KEY_NUMPAD0     = register("NUMPAD0", 0x52);
    public static final int KEY_DECIMAL     = register("DECIMAL", 0x53);
    public static final int KEY_F11         = register("F11", 0x57);
    public static final int KEY_F12         = register("F12", 0x58);
    public static final int KEY_F13         = register("F13", 0x64);
    public static final int KEY_F14         = register("F14", 0x65);
    public static final int KEY_F15         = register("F15", 0x66);
    public static final int KEY_F16         = register("F16", 0x67);
    public static final int KEY_F17         = register("F17", 0x68);
    public static final int KEY_F18         = register("F18", 0x69);
    public static final int KEY_KANA        = register("KANA", 0x70);
    public static final int KEY_F19         = register("F19", 0x71);
    public static final int KEY_CONVERT     = register("CONVERT", 0x79);
    public static final int KEY_NOCONVERT   = register("NOCONVERT", 0x7B);
    public static final int KEY_YEN         = register("YEN", 0x7D);
    public static final int KEY_NUMPADEQUALS= register("NUMPADEQUALS", 0x8D);
    public static final int KEY_CIRCUMFLEX  = register("CIRCUMFLEX", 0x90);
    public static final int KEY_AT          = register("AT", 0x91);
    public static final int KEY_COLON       = register("COLON", 0x92);
    public static final int KEY_UNDERLINE   = register("UNDERLINE", 0x93);
    public static final int KEY_KANJI       = register("KANJI", 0x94);
    public static final int KEY_STOP        = register("STOP", 0x95);
    public static final int KEY_AX          = register("AX", 0x96);
    public static final int KEY_UNLABELED   = register("UNLABELED", 0x97);
    public static final int KEY_NUMPADENTER = register("NUMPADENTER", 0x9C);
    public static final int KEY_RCONTROL    = register("RCONTROL", 0x9D);
    public static final int KEY_NUMPADCOMMA = register("NUMPADCOMMA", 0xB3);
    public static final int KEY_DIVIDE      = register("DIVIDE", 0xB5);
    public static final int KEY_SYSRQ       = register("SYSRQ", 0xB7);
    public static final int KEY_RMENU       = register("RMENU", 0xB8);
    public static final int KEY_PAUSE       = register("PAUSE", 0xC5);
    public static final int KEY_HOME        = register("HOME", 0xC7);
    public static final int KEY_UP          = register("UP", 0xC8);
    public static final int KEY_PRIOR       = register("PRIOR", 0xC9);
    public static final int KEY_LEFT        = register("LEFT", 0xCB);
    public static final int KEY_RIGHT       = register("RIGHT", 0xCD);
    public static final int KEY_END         = register("END", 0xCF);
    public static final int KEY_DOWN        = register("DOWN", 0xD0);
    public static final int KEY_NEXT        = register("NEXT", 0xD1);
    public static final int KEY_INSERT      = register("INSERT", 0xD2);
    public static final int KEY_DELETE      = register("DELETE", 0xD3);
    public static final int KEY_LMETA       = register("LMETA", 0xDB);
    public static final int KEY_RMETA       = register("RMETA", 0xDC);
    public static final int KEY_APPS        = register("APPS", 0xDD);
    public static final int KEY_POWER       = register("POWER", 0xDE);
    public static final int KEY_SLEEP       = register("SLEEP", 0xDF);

    // Historical aliases (share scancodes with the primary names above).
    public static final int KEY_LWIN = KEY_LMETA;
    public static final int KEY_RWIN = KEY_RMETA;

    // -- runtime state --

    private static boolean created;
    private static boolean initialized;
    private static boolean repeatEnabled;

    private static InputImplementation implementation;

    /** Snapshot of key-down state indexed by DirectInput scancode; refreshed by {@link #poll()}. */
    private static final ByteBuffer keyDownBuffer = BufferUtils.createByteBuffer(KEYBOARD_SIZE);

    /** Rolling buffer of unread key events written by the backend. */
    private static ByteBuffer readBuffer;

    private static final KeyEvent currentEvent = new KeyEvent();
    private static final KeyEvent scratchEvent = new KeyEvent();

    private Keyboard() {
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        Sys.initialize();
        initialized = true;
    }

    /** Reflective entry point mirroring LWJGL2 (used by AWT adapters historically). */
    private static void create(InputImplementation impl) throws LWJGLException {
        if (created) {
            return;
        }
        if (!initialized) {
            initialize();
        }
        implementation = impl;
        implementation.createKeyboard();
        created = true;
        readBuffer = ByteBuffer.allocate(EVENT_SIZE * BUFFER_SIZE);
        reset();
    }

    public static void create() throws LWJGLException {
        if (!Display.isCreated()) {
            throw new IllegalStateException("Display must be created.");
        }
        create(LWJGLImplementationUtils.getOrCreateInputImplementation());
    }

    private static void reset() {
        readBuffer.limit(0);
        for (int i = 0; i < KEYBOARD_SIZE; i++) {
            keyDownBuffer.put(i, (byte) 0);
        }
        currentEvent.reset();
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        if (!created) {
            return;
        }
        created = false;
        implementation.destroyKeyboard();
        reset();
    }

    /**
     * Refreshes the polled key-down snapshot and drains pending events from the backend.
     * The window layer must pump OS messages (via Display.update / processMessages) first.
     */
    public static void poll() {
        if (!created) {
            throw new IllegalStateException("Keyboard must be created before you can poll the device");
        }
        implementation.pollKeyboard(keyDownBuffer);
        read();
    }

    private static void read() {
        readBuffer.compact();
        implementation.readKeyboard(readBuffer);
        readBuffer.flip();
    }

    public static boolean isKeyDown(int key) {
        if (!created) {
            throw new IllegalStateException("Keyboard must be created before you can query key state");
        }
        if (key < 0 || key >= KEYBOARD_SIZE) {
            return false;
        }
        return keyDownBuffer.get(key) != 0;
    }

    public static synchronized String getKeyName(int key) {
        if (key < 0 || key >= keyNames.length) {
            return null;
        }
        return keyNames[key];
    }

    public static synchronized int getKeyIndex(String keyName) {
        Integer code = keyMap.get(keyName);
        return code == null ? KEY_NONE : code.intValue();
    }

    public static int getKeyCount() {
        return keyMap.size();
    }

    /**
     * Advances to the next buffered event, honouring the repeat-events flag. When repeats are
     * disabled, auto-repeat events are silently skipped so callers only see genuine transitions.
     *
     * @return true if an event was loaded into the current-event slot
     */
    public static boolean next() {
        if (!created) {
            throw new IllegalStateException("Keyboard must be created before you can read events");
        }
        boolean read;
        while ((read = readNext(currentEvent)) && currentEvent.repeat && !repeatEnabled) {
            // discard repeat events while repeats are disabled
        }
        return read;
    }

    public static int getNumKeyboardEvents() {
        if (!created) {
            throw new IllegalStateException("Keyboard must be created before you can read events");
        }
        int mark = readBuffer.position();
        int count = 0;
        while (readNext(scratchEvent)) {
            if (!scratchEvent.repeat || repeatEnabled) {
                count++;
            }
        }
        readBuffer.position(mark);
        return count;
    }

    private static boolean readNext(KeyEvent event) {
        if (readBuffer.remaining() < EVENT_SIZE) {
            return false;
        }
        event.key = readBuffer.getInt();
        event.state = readBuffer.get() != 0;
        event.character = readBuffer.getInt();
        event.nanos = readBuffer.getLong();
        event.repeat = readBuffer.get() == 1;
        return true;
    }

    public static void enableRepeatEvents(boolean enable) {
        repeatEnabled = enable;
    }

    public static boolean areRepeatEventsEnabled() {
        return repeatEnabled;
    }

    public static char getEventCharacter() {
        return (char) currentEvent.character;
    }

    public static int getEventKey() {
        return currentEvent.key;
    }

    public static boolean getEventKeyState() {
        return currentEvent.state;
    }

    public static long getEventNanoseconds() {
        return currentEvent.nanos;
    }

    public static boolean isRepeatEvent() {
        return currentEvent.repeat;
    }

    // ------------------------------------------------------------------
    // GLFW key code -> LWJGL2 DirectInput scancode translation.
    //
    // The group-4 backend receives raw GLFW key codes from GLFW key callbacks and must
    // convert them to LWJGL2 scancodes before writing them into keyDownBuffer / readBuffer,
    // because those scancodes are what MC persists in keybinds. This table is the single
    // source of truth for that conversion.
    // ------------------------------------------------------------------

    private static final int[] GLFW_TO_LWJGL = new int[GLFW.GLFW_KEY_LAST + 1];

    static {
        for (int i = 0; i < GLFW_TO_LWJGL.length; i++) {
            GLFW_TO_LWJGL[i] = KEY_NONE;
        }

        mapGlfw(GLFW.GLFW_KEY_SPACE, KEY_SPACE);
        mapGlfw(GLFW.GLFW_KEY_APOSTROPHE, KEY_APOSTROPHE);
        mapGlfw(GLFW.GLFW_KEY_COMMA, KEY_COMMA);
        mapGlfw(GLFW.GLFW_KEY_MINUS, KEY_MINUS);
        mapGlfw(GLFW.GLFW_KEY_PERIOD, KEY_PERIOD);
        mapGlfw(GLFW.GLFW_KEY_SLASH, KEY_SLASH);
        mapGlfw(GLFW.GLFW_KEY_0, KEY_0);
        mapGlfw(GLFW.GLFW_KEY_1, KEY_1);
        mapGlfw(GLFW.GLFW_KEY_2, KEY_2);
        mapGlfw(GLFW.GLFW_KEY_3, KEY_3);
        mapGlfw(GLFW.GLFW_KEY_4, KEY_4);
        mapGlfw(GLFW.GLFW_KEY_5, KEY_5);
        mapGlfw(GLFW.GLFW_KEY_6, KEY_6);
        mapGlfw(GLFW.GLFW_KEY_7, KEY_7);
        mapGlfw(GLFW.GLFW_KEY_8, KEY_8);
        mapGlfw(GLFW.GLFW_KEY_9, KEY_9);
        mapGlfw(GLFW.GLFW_KEY_SEMICOLON, KEY_SEMICOLON);
        mapGlfw(GLFW.GLFW_KEY_EQUAL, KEY_EQUALS);
        mapGlfw(GLFW.GLFW_KEY_A, KEY_A);
        mapGlfw(GLFW.GLFW_KEY_B, KEY_B);
        mapGlfw(GLFW.GLFW_KEY_C, KEY_C);
        mapGlfw(GLFW.GLFW_KEY_D, KEY_D);
        mapGlfw(GLFW.GLFW_KEY_E, KEY_E);
        mapGlfw(GLFW.GLFW_KEY_F, KEY_F);
        mapGlfw(GLFW.GLFW_KEY_G, KEY_G);
        mapGlfw(GLFW.GLFW_KEY_H, KEY_H);
        mapGlfw(GLFW.GLFW_KEY_I, KEY_I);
        mapGlfw(GLFW.GLFW_KEY_J, KEY_J);
        mapGlfw(GLFW.GLFW_KEY_K, KEY_K);
        mapGlfw(GLFW.GLFW_KEY_L, KEY_L);
        mapGlfw(GLFW.GLFW_KEY_M, KEY_M);
        mapGlfw(GLFW.GLFW_KEY_N, KEY_N);
        mapGlfw(GLFW.GLFW_KEY_O, KEY_O);
        mapGlfw(GLFW.GLFW_KEY_P, KEY_P);
        mapGlfw(GLFW.GLFW_KEY_Q, KEY_Q);
        mapGlfw(GLFW.GLFW_KEY_R, KEY_R);
        mapGlfw(GLFW.GLFW_KEY_S, KEY_S);
        mapGlfw(GLFW.GLFW_KEY_T, KEY_T);
        mapGlfw(GLFW.GLFW_KEY_U, KEY_U);
        mapGlfw(GLFW.GLFW_KEY_V, KEY_V);
        mapGlfw(GLFW.GLFW_KEY_W, KEY_W);
        mapGlfw(GLFW.GLFW_KEY_X, KEY_X);
        mapGlfw(GLFW.GLFW_KEY_Y, KEY_Y);
        mapGlfw(GLFW.GLFW_KEY_Z, KEY_Z);
        mapGlfw(GLFW.GLFW_KEY_LEFT_BRACKET, KEY_LBRACKET);
        mapGlfw(GLFW.GLFW_KEY_BACKSLASH, KEY_BACKSLASH);
        mapGlfw(GLFW.GLFW_KEY_RIGHT_BRACKET, KEY_RBRACKET);
        mapGlfw(GLFW.GLFW_KEY_GRAVE_ACCENT, KEY_GRAVE);
        mapGlfw(GLFW.GLFW_KEY_WORLD_1, KEY_CIRCUMFLEX);
        mapGlfw(GLFW.GLFW_KEY_WORLD_2, KEY_YEN);
        mapGlfw(GLFW.GLFW_KEY_ESCAPE, KEY_ESCAPE);
        mapGlfw(GLFW.GLFW_KEY_ENTER, KEY_RETURN);
        mapGlfw(GLFW.GLFW_KEY_TAB, KEY_TAB);
        mapGlfw(GLFW.GLFW_KEY_BACKSPACE, KEY_BACK);
        mapGlfw(GLFW.GLFW_KEY_INSERT, KEY_INSERT);
        mapGlfw(GLFW.GLFW_KEY_DELETE, KEY_DELETE);
        mapGlfw(GLFW.GLFW_KEY_RIGHT, KEY_RIGHT);
        mapGlfw(GLFW.GLFW_KEY_LEFT, KEY_LEFT);
        mapGlfw(GLFW.GLFW_KEY_DOWN, KEY_DOWN);
        mapGlfw(GLFW.GLFW_KEY_UP, KEY_UP);
        mapGlfw(GLFW.GLFW_KEY_PAGE_UP, KEY_PRIOR);
        mapGlfw(GLFW.GLFW_KEY_PAGE_DOWN, KEY_NEXT);
        mapGlfw(GLFW.GLFW_KEY_HOME, KEY_HOME);
        mapGlfw(GLFW.GLFW_KEY_END, KEY_END);
        mapGlfw(GLFW.GLFW_KEY_CAPS_LOCK, KEY_CAPITAL);
        mapGlfw(GLFW.GLFW_KEY_SCROLL_LOCK, KEY_SCROLL);
        mapGlfw(GLFW.GLFW_KEY_NUM_LOCK, KEY_NUMLOCK);
        mapGlfw(GLFW.GLFW_KEY_PRINT_SCREEN, KEY_SYSRQ);
        mapGlfw(GLFW.GLFW_KEY_PAUSE, KEY_PAUSE);
        mapGlfw(GLFW.GLFW_KEY_F1, KEY_F1);
        mapGlfw(GLFW.GLFW_KEY_F2, KEY_F2);
        mapGlfw(GLFW.GLFW_KEY_F3, KEY_F3);
        mapGlfw(GLFW.GLFW_KEY_F4, KEY_F4);
        mapGlfw(GLFW.GLFW_KEY_F5, KEY_F5);
        mapGlfw(GLFW.GLFW_KEY_F6, KEY_F6);
        mapGlfw(GLFW.GLFW_KEY_F7, KEY_F7);
        mapGlfw(GLFW.GLFW_KEY_F8, KEY_F8);
        mapGlfw(GLFW.GLFW_KEY_F9, KEY_F9);
        mapGlfw(GLFW.GLFW_KEY_F10, KEY_F10);
        mapGlfw(GLFW.GLFW_KEY_F11, KEY_F11);
        mapGlfw(GLFW.GLFW_KEY_F12, KEY_F12);
        mapGlfw(GLFW.GLFW_KEY_F13, KEY_F13);
        mapGlfw(GLFW.GLFW_KEY_F14, KEY_F14);
        mapGlfw(GLFW.GLFW_KEY_F15, KEY_F15);
        mapGlfw(GLFW.GLFW_KEY_F16, KEY_F16);
        mapGlfw(GLFW.GLFW_KEY_F17, KEY_F17);
        mapGlfw(GLFW.GLFW_KEY_F18, KEY_F18);
        mapGlfw(GLFW.GLFW_KEY_F19, KEY_F19);
        mapGlfw(GLFW.GLFW_KEY_KP_0, KEY_NUMPAD0);
        mapGlfw(GLFW.GLFW_KEY_KP_1, KEY_NUMPAD1);
        mapGlfw(GLFW.GLFW_KEY_KP_2, KEY_NUMPAD2);
        mapGlfw(GLFW.GLFW_KEY_KP_3, KEY_NUMPAD3);
        mapGlfw(GLFW.GLFW_KEY_KP_4, KEY_NUMPAD4);
        mapGlfw(GLFW.GLFW_KEY_KP_5, KEY_NUMPAD5);
        mapGlfw(GLFW.GLFW_KEY_KP_6, KEY_NUMPAD6);
        mapGlfw(GLFW.GLFW_KEY_KP_7, KEY_NUMPAD7);
        mapGlfw(GLFW.GLFW_KEY_KP_8, KEY_NUMPAD8);
        mapGlfw(GLFW.GLFW_KEY_KP_9, KEY_NUMPAD9);
        mapGlfw(GLFW.GLFW_KEY_KP_DECIMAL, KEY_DECIMAL);
        mapGlfw(GLFW.GLFW_KEY_KP_DIVIDE, KEY_DIVIDE);
        mapGlfw(GLFW.GLFW_KEY_KP_MULTIPLY, KEY_MULTIPLY);
        mapGlfw(GLFW.GLFW_KEY_KP_SUBTRACT, KEY_SUBTRACT);
        mapGlfw(GLFW.GLFW_KEY_KP_ADD, KEY_ADD);
        mapGlfw(GLFW.GLFW_KEY_KP_ENTER, KEY_NUMPADENTER);
        mapGlfw(GLFW.GLFW_KEY_KP_EQUAL, KEY_NUMPADEQUALS);
        mapGlfw(GLFW.GLFW_KEY_LEFT_SHIFT, KEY_LSHIFT);
        mapGlfw(GLFW.GLFW_KEY_LEFT_CONTROL, KEY_LCONTROL);
        mapGlfw(GLFW.GLFW_KEY_LEFT_ALT, KEY_LMENU);
        mapGlfw(GLFW.GLFW_KEY_LEFT_SUPER, KEY_LMETA);
        mapGlfw(GLFW.GLFW_KEY_RIGHT_SHIFT, KEY_RSHIFT);
        mapGlfw(GLFW.GLFW_KEY_RIGHT_CONTROL, KEY_RCONTROL);
        mapGlfw(GLFW.GLFW_KEY_RIGHT_ALT, KEY_RMENU);
        mapGlfw(GLFW.GLFW_KEY_RIGHT_SUPER, KEY_RMETA);
        mapGlfw(GLFW.GLFW_KEY_MENU, KEY_APPS);
    }

    private static void mapGlfw(int glfwKey, int lwjglScancode) {
        if (glfwKey >= 0 && glfwKey < GLFW_TO_LWJGL.length) {
            GLFW_TO_LWJGL[glfwKey] = lwjglScancode;
        }
    }

    /**
     * Translates a raw GLFW key code into its LWJGL2 DirectInput scancode.
     *
     * @param glfwKey a GLFW_KEY_* value (or GLFW_KEY_UNKNOWN / out-of-range)
     * @return the matching LWJGL2 scancode, or {@link #KEY_NONE} when there is no mapping
     */
    public static int getKeyIndexFromGLFW(int glfwKey) {
        if (glfwKey < 0 || glfwKey >= GLFW_TO_LWJGL.length) {
            return KEY_NONE;
        }
        return GLFW_TO_LWJGL[glfwKey];
    }

    private static final class KeyEvent {
        private int key;
        private int character;
        private boolean state;
        private long nanos;
        private boolean repeat;

        private void reset() {
            key = 0;
            character = 0;
            state = false;
            nanos = 0L;
            repeat = false;
        }
    }
}