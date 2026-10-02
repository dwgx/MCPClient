package net.marcloud.mcp.core.compat.patches;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;

/**
 * KI-12 — the Escape key: the one door into the whole pause-menu tree, exposed to an agent.
 *
 * <p><b>The gap, and why no existing tool can reach it.</b> {@code Minecraft.runTick} handles ESC
 * like this ({@code Minecraft.java:1939-1944}), inside {@code while (Keyboard.next())}:
 *
 * <pre>
 *   if (this.currentScreen != null) { this.currentScreen.handleKeyboardInput(); }
 *   else                           { if (k == 1) { this.displayInGameMenu(); } ... }
 * </pre>
 *
 * That is a raw LWJGL key event, and the two existing doors both miss it by construction:
 *
 * <ul>
 *   <li>{@code press_key_binding} reproduces vanilla's own two calls for a keystroke
 *       ({@code KeyBinding.setKeyBindState} then {@code KeyBinding.onTick}). ESC is LWJGL code 1,
 *       and no vanilla binding defaults to it — the whole default set is 17/30/31/32/57/42/29/18/
 *       -99/16/-100/-98/20/15/53/60/63/0/87/64/65/0/0/hotbar 10-18 — so {@code onTick(1)}
 *       dispatches to nothing and the tool answers {@code bindingClaimed=false}.</li>
 *   <li>{@code gui_press_key} drives {@code GuiScreen.keyTyped}, which vanilla only ever reaches
 *       through the OTHER branch above, the one taken when a screen is ALREADY open. On the
 *       {@code currentScreen == null} branch it is never called.</li>
 * </ul>
 *
 * So the entire pause-menu tree — options, video, controls, language, sounds, chat, snooper,
 * resource packs, statistics, achievements, share-to-LAN — was one structural gap away, and no
 * amount of existing tool could cross it.
 *
 * <p><b>The fix is the pattern KI-11 already proved, applied to vanilla instead of to dwm.</b>
 * Not a synthesised key event, and not a second construction path: this class calls
 * {@link Minecraft#displayInGameMenu()} — the exact method the ESC branch calls — so the
 * {@code currentScreen == null} guard, the {@code GuiIngameMenu} construction and the
 * singleplayer sound pause are all vanilla's, shared. If the agent and the player ever ended up
 * on different menus that could not happen from here.
 *
 * <p><b>Below the pause menu there is no second door to build, and that is the design, not an
 * omission.</b> Everything under it is an ordinary {@code buttonList} entry, so
 * {@code gui_snapshot} already publishes it as an addressable element and {@code gui_click_element}
 * already drives it through the real {@code GuiScreen.mouseClicked} — the same call a person's
 * click produces. Adding a second "press button id N" verb would have created exactly the drift
 * this file exists to prevent: the toggle-not-stack and refuse-to-replace rules would live in two
 * places and eventually disagree.
 *
 * <p><b>Direct linkage, deliberately unlike {@link DwmHotkey}.</b> DwmHotkey reflects because
 * {@code dwm} is a DETACHABLE auxiliary this module must not bind to, and because it is invoked
 * from an ASM hook injected into a method whose stack map frames it must not disturb. Neither
 * constraint applies here: {@code client} is a {@code provided} dependency of {@code core} and
 * {@code GuiActions}/{@code EnchantTools} link vanilla directly. A reflective copy of
 * {@code displayInGameMenu} would be an unverified mirror of a call the compiler already checks —
 * and this project has been burned by eight wrong API guesses in one session, each of which a
 * direct call would have caught at compile time.
 *
 * <p><b>A send is not a fact.</b> {@link State} has no {@code SENT} member: the outcome is
 * decided by re-reading {@code currentScreen} AFTER the call, so "opened" is only ever reported
 * when a real {@link GuiIngameMenu} is actually there.
 *
 * <p><b>Game thread only.</b> {@code displayInGameMenu} touches screen and sound state. The tool
 * marshals through {@code GameBridge.onGameThread}; a direct caller on another thread is a bug in
 * the caller, not something this class can repair.
 */
public final class EscMenu {

    /**
     * What the re-read established. Deliberately has no {@code SENT}: the call returning is not
     * an outcome, only {@code currentScreen} afterwards is.
     */
    public enum State {
        /** A real {@link GuiIngameMenu} is on screen afterwards. */
        OPENED,
        /** Vanilla's own {@code currentScreen == null} guard refused: something else owns the screen. */
        BLOCKED,
        /** There is no client at all (headless JVM), so there is nothing to press ESC on. */
        NO_CLIENT,
        /** The call was made and no pause menu is up afterwards, with nothing that was open first. */
        FAILED
    }

    /**
     * The outcome, and the screen that was actually there afterwards.
     *
     * @param state  what the re-read established
     * @param screen the {@code currentScreen} class name afterwards, or {@code null} for none
     * @param detail why, in one line — a message for a human reading a tool reply
     */
    public record Outcome(State state, String screen, String detail) {

        /** Whether the pause menu is genuinely up. Never a guess from the call returning. */
        public boolean opened() {
            return state == State.OPENED;
        }
    }

    /** Canonical name of the screen ESC opens, as a constant so nothing re-spells it. */
    static final String PAUSE_MENU = "net.minecraft.client.gui.GuiIngameMenu";

    private EscMenu() {
    }

    /**
     * Open the pause menu the way ESC does. Game thread only.
     *
     * @return what the re-read established; never {@code null}
     */
    public static Outcome openPauseMenu() {
        return openPauseMenu(Minecraft.getMinecraft());
    }

    /**
     * The seam {@link #openPauseMenu()} and the headless test share.
     *
     * <p>It takes the client rather than reaching for the singleton so a test can hand it a
     * recording stand-in. There is still exactly ONE production call site — the no-argument
     * overload above — so this cannot become a second route; it only replaces the only line that
     * names the client.
     *
     * @param mc the client to press ESC on; {@code null} yields {@link State#NO_CLIENT}
     */
    static Outcome openPauseMenu(Minecraft mc) {
        if (mc == null) {
            return new Outcome(State.NO_CLIENT, null,
                    "Minecraft.getMinecraft() is null, so the client is not running. There is no "
                            + "screen to press ESC on.");
        }
        GuiScreen before = mc.currentScreen;
        try {
            // THE door. Not displayGuiScreen(new GuiIngameMenu()), not a key event: this is the
            // call the ESC branch of Minecraft.runTick makes, with the same guard.
            mc.displayInGameMenu();
        } catch (Throwable t) {
            return new Outcome(State.FAILED, screenName(mc.currentScreen),
                    "Minecraft.displayInGameMenu() threw " + t);
        }
        GuiScreen after = mc.currentScreen;
        return new Outcome(classify(before, after), screenName(after), why(before, after));
    }

    /**
     * The decision, separated from the call so it is reachable with no client at all.
     *
     * <p>Pure over the two screens, and it is the whole honesty rule: {@link State#OPENED}
     * requires a pause menu to actually be there, {@link State#BLOCKED} requires something to
     * have been there first (vanilla's {@code currentScreen == null} guard is what refused), and
     * {@link State#FAILED} is the residual case where the call ran and changed nothing with
     * nothing in the way.
     *
     * @param before {@code currentScreen} immediately before the call
     * @param after  {@code currentScreen} immediately after it
     */
    static State classify(GuiScreen before, GuiScreen after) {
        if (after != null && PAUSE_MENU.equals(after.getClass().getName())) {
            return State.OPENED;
        }
        return before != null ? State.BLOCKED : State.FAILED;
    }

    /** One line naming what happened, for the tool reply. */
    private static String why(GuiScreen before, GuiScreen after) {
        return switch (classify(before, after)) {
            case OPENED -> "the pause menu (GuiIngameMenu) is up";
            case BLOCKED -> "vanilla's own guard in Minecraft.displayInGameMenu() refused: ESC only "
                    + "opens the pause menu when currentScreen is null, and " + screenName(before)
                    + " owns the screen. Nothing was closed -- close that one with "
                    + "gui_press_key 'Escape', then call this again";
            case FAILED -> "Minecraft.displayInGameMenu() returned and no pause menu is up; "
                    + "currentScreen now reads " + screenName(after);
            case NO_CLIENT -> "no client";
        };
    }

    private static String screenName(GuiScreen s) {
        return s == null ? null : s.getClass().getName();
    }
}
