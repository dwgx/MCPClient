package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;

import org.junit.Test;

/**
 * The ESC seam must go through {@link Minecraft#displayInGameMenu()} — the method the ESC branch
 * of {@code Minecraft.runTick} calls — and through nothing else.
 *
 * <p><b>Why this is the assertion and not a weaker one.</b> The whole reason
 * {@link EscMenu} exists is that no existing tool can deliver ESC, and the fix is deliberately
 * NOT a synthesised key event and NOT a second construction path. A test that only checked "a
 * pause menu is open afterwards" would pass just as happily against
 * {@code mc.displayGuiScreen(new GuiIngameMenu())}, which is precisely the parallel route the
 * Owner was told would not exist. So the recording stand-in counts the two calls separately and
 * the test requires one of them and forbids the other.
 *
 * <p><b>Non-vacuity.</b> Every mutation below breaks a named test:
 * <ul>
 *   <li>swap {@code displayInGameMenu()} for {@code displayGuiScreen(new GuiIngameMenu())}
 *       &rarr; {@link #openingTheMenuCallsVanillasOwnMethodAndNothingElse} (0 display calls)</li>
 *   <li>add the direct construction <em>as well</em> &rarr; same test (displayGuiScreenCalls
 *       non-empty), which is what stops "same path plus a shortcut"</li>
 *   <li>return {@link EscMenu.State#OPENED} without re-reading &rarr;
 *       {@link #anOutcomeIsNeverClaimedFromTheCallReturningAlone()}</li>
 *   <li>bypass vanilla's {@code currentScreen == null} guard &rarr;
 *       {@link #aScreenThatWasAlreadyOpenIsLeftAlone()} (it would be replaced)</li>
 *   <li>assume a null client is "opened" &rarr; {@link #noClientIsNotAnOpenMenu()}</li>
 * </ul>
 *
 * <p><b>When the harness breaks, the mechanism and not EscMenu.</b>
 * {@code sun.misc.Unsafe::allocateInstance} is terminally deprecated and scheduled for removal; on
 * the JDK where it goes these tests error here while production is untouched. That distinction
 * is written down because this repo keeps paying for the opposite mistake — a failing harness
 * that reads like a regression in the code under test. If you are reading this after the removal,
 * the fix is a seam in {@link EscMenu}, not a cleverer allocation.
 */
public class EscMenuRoutesThroughTheVanillaEscapeTest {

    /**
     * A {@link Minecraft} that records which door was used.
     *
     * <p>{@code displayInGameMenu} keeps vanilla's own {@code currentScreen == null} guard, because
     * that guard is one of the things being protected — a stand-in that always opened would let a
     * seam that replaced screens behind the player's back pass.
     */
    static final class RecordingMinecraft extends Minecraft {
        int displayInGameMenuCalls;
        List<GuiScreen> displayGuiScreenCalls = new ArrayList<>();
        /** When false, {@code displayInGameMenu} does nothing at all — vanilla's guard fired. */
        boolean opensWhenUnguarded = true;

        /**
         * Present only so the subclass compiles: Minecraft's real constructor takes a
         * {@link net.minecraft.client.main.GameConfiguration} and cannot run headless. This one is
         * never invoked — {@link #newClient()} allocates without running any constructor.
         */
        @SuppressWarnings("unused")
        private RecordingMinecraft() {
            super((net.minecraft.client.main.GameConfiguration) null);
        }

        @Override
        public void displayInGameMenu() {
            displayInGameMenuCalls++;
            if (currentScreen == null && opensWhenUnguarded) {
                currentScreen = new GuiIngameMenu();
            }
        }

        @Override
        public void displayGuiScreen(GuiScreen screen) {
            displayGuiScreenCalls.add(screen);
            currentScreen = screen;
        }
    }

    @Test
    public void openingTheMenuCallsVanillasOwnMethodAndNothingElse() {
        RecordingMinecraft mc = newClient();

        EscMenu.Outcome outcome = EscMenu.openPauseMenu(mc);

        assertEquals("the ESC key calls Minecraft.displayInGameMenu() and the seam must call that "
                        + "same method; a route that went straight to displayGuiScreen would be "
                        + "the parallel path this file exists to forbid",
                1, mc.displayInGameMenuCalls);
        assertTrue("nothing may construct or display a screen on the side -- every vanilla call "
                        + "must be shared with the player. Saw: " + mc.displayGuiScreenCalls,
                mc.displayGuiScreenCalls.isEmpty());
        assertEquals(EscMenu.State.OPENED, outcome.state());
        assertTrue(outcome.opened());
        assertTrue("opened must be decided by a re-read, not by the call returning",
                mc.currentScreen instanceof GuiIngameMenu);
        assertEquals(EscMenu.PAUSE_MENU, outcome.screen());
    }

    @Test
    public void aScreenThatWasAlreadyOpenIsLeftAlone() {
        RecordingMinecraft mc = newClient();
        GuiScreen owned = new SomeOtherScreen();
        mc.currentScreen = owned;

        EscMenu.Outcome outcome = EscMenu.openPauseMenu(mc);

        assertEquals("ESC only reaches displayInGameMenu when currentScreen is null; a screen that "
                        + "already owns the input must survive the attempt",
                EscMenu.State.BLOCKED, outcome.state());
        assertFalse(outcome.opened());
        assertSame("the screen that was open is still the one open -- nothing was replaced",
                owned, mc.currentScreen);
        assertEquals("and the refusal is vanilla's, not a local guard: the method was still called",
                1, mc.displayInGameMenuCalls);
        assertTrue(mc.displayGuiScreenCalls.isEmpty());
        assertTrue("the reply must name the screen that is in the way",
                outcome.detail().contains("GuiIngameMenu") || outcome.detail().contains(
                        SomeOtherScreen.class.getName()));
    }

    @Test
    public void anOutcomeIsNeverClaimedFromTheCallReturningAlone() {
        RecordingMinecraft mc = newClient();
        // The call happens and does nothing: vanilla's guard fired even though nothing was open.
        mc.opensWhenUnguarded = false;

        EscMenu.Outcome outcome = EscMenu.openPauseMenu(mc);

        assertEquals("the call ran and returned, and no pause menu is there; that is FAILED, not "
                        + "OPENED. A send is not a fact.",
                EscMenu.State.FAILED, outcome.state());
        assertFalse(outcome.opened());
        assertNull(mc.currentScreen);
    }

    @Test
    public void noClientIsNotAnOpenMenu() {
        EscMenu.Outcome outcome = EscMenu.openPauseMenu((Minecraft) null);

        assertEquals(EscMenu.State.NO_CLIENT, outcome.state());
        assertFalse(outcome.opened());
        assertNull(outcome.screen());
        assertNotNull("a headless JVM is a state to report, not a reason to stay silent",
                outcome.detail());
    }

    @Test
    public void theClassifierIsTheWholeHonestyRule() {
        // Pinned directly, so the rule is checkable without a client at all. The three screens
        // are the real vanilla ones -- a pause menu on both sides, one on neither, a stranger on
        // one side -- so a classifier that compared class NAMES by hand and misspelled one fails.
        GuiScreen menu = new GuiIngameMenu();
        GuiScreen stranger = new SomeOtherScreen();

        assertEquals(EscMenu.State.OPENED, EscMenu.classify(null, menu));
        assertEquals(EscMenu.State.OPENED, EscMenu.classify(stranger, menu));
        assertEquals(EscMenu.State.BLOCKED, EscMenu.classify(menu, null));
        assertEquals(EscMenu.State.BLOCKED, EscMenu.classify(stranger, stranger));
        assertEquals(EscMenu.State.FAILED, EscMenu.classify(null, null));
        assertEquals(EscMenu.State.FAILED, EscMenu.classify(null, stranger));
    }

    /** Allocates without running Minecraft's constructor, which cannot run headless. */
    private static RecordingMinecraft newClient() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            RecordingMinecraft mc = (RecordingMinecraft) unsafeClass
                    .getMethod("allocateInstance", Class.class)
                    .invoke(theUnsafe.get(null), RecordingMinecraft.class);
            // Unsafe runs no field initialiser, so the recorder's own state is set up by hand.
            // Left implicit this is a silent trap: displayGuiScreenCalls would be null and
            // opensWhenUnguarded false, so "opening works" would fail for a reason that has
            // nothing to do with the seam.
            mc.displayGuiScreenCalls = new ArrayList<>();
            mc.opensWhenUnguarded = true;
            return mc;
        } catch (Exception e) {
            throw new AssertionError("could not allocate a headless Minecraft stand-in", e);
        }
    }

    /** Any screen that is not the pause menu; stands in for the chat box, a menu, a container. */
    private static final class SomeOtherScreen extends GuiScreen {
        @Override
        public void initGui() {
            buttonList.clear();
        }
    }
}
