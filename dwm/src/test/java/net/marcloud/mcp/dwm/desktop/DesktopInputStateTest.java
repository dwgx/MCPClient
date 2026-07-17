package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Edge-detection teeth for the launcher's keyboard state: typing edits the query, ESC
 * unwinds sub-modes (settings view -> query), and RShift is a NO-OP (the launcher is now a
 * real GuiScreen whose open/close is owned by MC's screen lifecycle + the hotkey system, so
 * this state is only alive while the screen shows — always "open"). Pure logic; fed the
 * per-frame down-key set the GuiScreen's keyTyped produces, no GL.
 *
 * <p>Because input is non-consuming, {@link DesktopInputState#update} treats a key as a
 * press only on the up->down transition, so a key must be released (empty frame) before it
 * can fire again — the tests below insert those release frames explicitly.
 */
public class DesktopInputStateTest {

    private static final int RSHIFT = 0x36;
    private static final int ESC = 0x01;
    // A typeable, NON-movement letter for search-query tests ('y'=0x15). Note: A/W/S/D are
    // movement keys now and never reach the search box, so tests use 'y'/'b' for typing.
    private static final int A = 0x15; // 'y'

    private static void press(DesktopInputState s, int key) {
        s.update(List.of(key)); // down edge
        s.update(List.of());    // release, so the next press is a fresh edge
    }

    @Test
    public void startsOpenAndRShiftIsNoOp() {
        // The launcher is a GuiScreen now: it only exists while shown, so this state is always
        // open, and RShift (the external open key) must NOT flip visibility here — swallowing it
        // prevents a re-press from blanking the visible screen by setting open=false.
        DesktopInputState s = new DesktopInputState();
        assertTrue("launcher state is open while shown", s.isOpen());
        press(s, RSHIFT);
        assertTrue("RShift does not close this state (screen lifecycle owns open/close)", s.isOpen());
        press(s, RSHIFT);
        assertTrue("still open after a second RShift", s.isOpen());
    }

    @Test
    public void typingBuildsQueryWhileOpen() {
        DesktopInputState s = new DesktopInputState();
        press(s, A);
        press(s, A);
        assertEquals("yy", s.query());
    }

    @Test
    public void settingsToggleFlips() {
        DesktopInputState s = new DesktopInputState();
        assertFalse(s.isSettings());
        s.toggleSettings();
        assertTrue("gear opened settings", s.isSettings());
        s.toggleSettings();
        assertFalse("gear closed settings", s.isSettings());
    }

    @Test
    public void escUnwindsSettingsThenQueryButNoLongerClosesHere() {
        // ESC peels sub-modes: settings view -> query. Closing the launcher is owned by the
        // GuiScreen (it intercepts a top-level ESC), so this state never flips itself closed.
        DesktopInputState s = new DesktopInputState();
        press(s, A);                 // query = "y"
        s.setSettings(true);
        assertEquals("y", s.query());

        press(s, ESC);               // level 1: closes the settings view first
        assertFalse("ESC closed settings first", s.isSettings());
        assertEquals("query untouched while settings closed", "y", s.query());
        assertTrue("launcher still open", s.isOpen());

        press(s, ESC);               // level 2: clears the query
        assertEquals("ESC cleared the query", "", s.query());
        assertTrue("launcher still open", s.isOpen());

        press(s, ESC);               // level 3: no sub-mode left -> no-op here (screen closes)
        assertTrue("ESC at top level does not blank this state (screen owns close)", s.isOpen());
    }

    private static final int ENTER = 0x1C;
    private static final int B = 0x30; // 'b'

    @Test
    public void nameEditCapturesTypingAndCommitsOnEnter() {
        DesktopInputState s = new DesktopInputState();
        assertEquals("default account name", "admin", s.accountName());
        s.beginNameEdit();
        assertTrue(s.isEditingName());
        assertEquals("buffer seeds with current name", "admin", s.nameBuffer());

        // Type into the name, NOT the query.
        press(s, B);
        press(s, A);
        assertEquals("adminby", s.nameBuffer());
        assertEquals("typing while editing name must not touch the query", "", s.query());

        press(s, ENTER);
        assertFalse("Enter commits and exits edit mode", s.isEditingName());
        assertEquals("adminby", s.accountName());
    }

    @Test
    public void nameEditCancelsOnEscKeepingOldName() {
        DesktopInputState s = new DesktopInputState();
        s.beginNameEdit();
        press(s, B);
        press(s, ESC);
        assertFalse("Esc exits edit mode", s.isEditingName());
        assertEquals("Esc discards the edit, keeps the committed name", "admin", s.accountName());
    }

    @Test
    public void blankCommittedNameFallsBackToGuest() {
        DesktopInputState s = new DesktopInputState();
        s.beginNameEdit();
        // clear the seeded "admin" (5 chars) via backspace
        for (int i = 0; i < 5; i++) {
            press(s, 0x0E); // KEY_BACK
        }
        assertEquals("", s.nameBuffer());
        press(s, ENTER);
        assertEquals("committing a blank name falls back to admin", "admin", s.accountName());
    }

    @Test
    public void escCancelsNameEditSoSearchIsNeverSwallowed() {
        // Review F7 intent, now via ESC (the sub-mode escape) instead of RShift-close: a
        // dangling name edit must not swallow typing. Esc cancels the edit, then typing reaches
        // the query again. (Full teardown on close is owned by the GuiScreen's onGuiClosed.)
        DesktopInputState s = new DesktopInputState();
        s.beginNameEdit();
        assertTrue(s.isEditingName());
        press(s, ESC);                    // cancel the name edit
        assertFalse("Esc cleared the name edit", s.isEditingName());
        press(s, A);                      // typing now reaches the search query
        assertEquals("typing after cancel edits the query, not the name", "y", s.query());
    }

    @Test
    public void escLeavesSettingsViewThenTypingReachesQuery() {
        DesktopInputState s = new DesktopInputState();
        s.setSettings(true);
        press(s, ESC);                    // leave settings
        assertFalse("ESC left the settings view", s.isSettings());
        press(s, A);
        assertEquals("typing reaches the query after leaving settings", "y", s.query());
    }

    private static final int W = 0x11; // 'w' movement key
    private static final int SPACE = 0x39;

    @Test
    public void movementKeysNeverTypeIntoSearchInAnyMode() {
        // Owner rule: moving never types into search — UNCONDITIONALLY (modal or move-while-open).
        // W/A/S/D/space/shift always drive the player, never the search box.
        int strafeA = 0x1E; // real 'a' scancode = strafe-left movement key
        DesktopInputState modal = new DesktopInputState();
        press(modal, W);
        press(modal, strafeA);
        press(modal, SPACE);   // jump
        assertEquals("movement keys never reach search (modal)", "", modal.query());

        DesktopInputState moveMode = new DesktopInputState();
        moveMode.setAllowMoveWhileOpen(true);
        press(moveMode, W);
        press(moveMode, strafeA);
        assertEquals("movement keys never reach search (move-while-open)", "", moveMode.query());
    }

    @Test
    public void nonMovementLettersStillTypeIntoSearch() {
        // Regression guard: the movement suppression must NOT block ordinary letters.
        DesktopInputState s = new DesktopInputState();
        press(s, 0x15); // 'y'
        press(s, 0x24); // 'j'
        assertEquals("non-movement letters still search", "yj", s.query());
    }

    @Test
    public void movingFlagReflectsHeldMovementKey() {
        DesktopInputState s = new DesktopInputState();
        s.update(List.of(W));          // W held this frame
        assertTrue("moving is true while a movement key is held", s.isMoving());
        s.update(List.of());           // nothing held
        assertFalse("moving clears when movement keys released", s.isMoving());
    }

    @Test
    public void moveWhileOpenSettingGatesGameMovement() {
        DesktopInputState s = new DesktopInputState();
        assertTrue("launcher starts open", s.isOpen());
        assertFalse("move-while-open defaults OFF", s.allowMoveWhileOpen());
        assertFalse("open + setting off => game movement blocked", s.gameMovementAllowed());
        s.toggleAllowMoveWhileOpen();
        assertTrue(s.allowMoveWhileOpen());
        assertTrue("open + setting on => game may move", s.gameMovementAllowed());
        // Visibility is owned by the GuiScreen now (RShift is a no-op here); the move-while-open
        // gate is still driven purely by the allowMoveWhileOpen setting while the launcher shows.
        s.setAllowMoveWhileOpen(false);
        assertFalse("open + setting off => game movement blocked again", s.gameMovementAllowed());
    }

    @Test
    public void typingIgnoredInSettingsView() {
        DesktopInputState s = new DesktopInputState();
        s.setSettings(true);
        press(s, A);
        assertEquals("typing does not edit the hidden search box in settings", "", s.query());
    }
}
