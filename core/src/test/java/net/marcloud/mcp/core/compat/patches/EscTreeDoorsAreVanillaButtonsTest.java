package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.drivers.gui.GuiElement;
import net.marcloud.mcp.core.drivers.gui.GuiReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiCustomizeSkin;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiShareToLan;
import net.minecraft.client.gui.GuiSnooper;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScreenChatOptions;
import net.minecraft.client.gui.achievement.GuiAchievements;
import net.minecraft.client.gui.achievement.GuiStats;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.server.integrated.IntegratedServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Every door in the ESC tree, driven by the REAL vanilla {@code actionPerformed} and the REAL
 * vanilla {@code initGui}, so "the agent can reach these" is a statement about the shipped
 * 1.8.9 code and not about a table this project wrote.
 *
 * <p><b>Why this file exists at all.</b> {@link EscMenu} opens the pause menu and deliberately
 * stops there: everything below it is an ordinary {@code buttonList} entry, already published by
 * {@code gui_snapshot} and already driven by {@code gui_click_element} through the genuine
 * {@code GuiScreen.mouseClicked}. So reachability reduces to two facts, and both are checked here
 * against vanilla rather than against prose:
 *
 * <ol>
 *   <li><b>The door points where the tree map says.</b> The real {@link GuiIngameMenu} /
 *       {@link GuiOptions} is built, the real {@code actionPerformed} is invoked with the vanilla
 *       button id, and the screen vanilla ACTUALLY constructs is asserted. Renumber a button in
 *       the client and this fails; so does naming the wrong id.</li>
 *   <li><b>The door is addressable by the agent's route.</b> The real {@code initGui} runs, then
 *       {@link GuiReflect#extract} — the extractor {@code gui_snapshot} is built on — must publish
 *       a button carrying that same vanilla id. A screen vanilla constructs but never puts in
 *       {@code buttonList} would have no element id at all, which is exactly the
 *       {@code GuiEnchantment} failure {@code EnchantTools} documents.</li>
 * </ol>
 *
 * <p><b>Non-vacuity.</b> Nothing here is faked except the {@link Minecraft} singleton, which
 * cannot be built headless. The screens, the buttons, the {@code switch} on {@code button.id} and
 * the constructors are the shipped ones, so a wrong id or a wrong target class fails rather than
 * passing on cooperation. {@code saveOptions()} is overridden to a no-op so a test cannot rewrite
 * the player's real options.txt; it decides nothing about which screen is constructed.
 *
 * <p><b>Two ids are excluded, deliberately, and named rather than faked.</b>
 * {@code GuiIngameMenu} id 1 (Save and Quit to Title) tears the world down and {@code GuiOptions}
 * id 107 (Streaming...) needs a live broadcast; neither is a door INTO the tree, and neither can
 * be constructed without a server or a stream. Their ids are still published to
 * {@code gui_snapshot}, which is all the agent needs to click them; what is unverified here is
 * only what they do afterwards.
 *
 * <p><b>When the harness breaks, the mechanism and not the code under test.</b> {@code
 * sun.misc.Unsafe::allocateInstance} is terminally deprecated and scheduled for removal; on the
 * JDK where it goes this file errors while production is untouched.
 */
public class EscTreeDoorsAreVanillaButtonsTest {

    // The ids this project's tool description documents. Read live off gui_snapshot in anger.
    private static final int MENU_OPTIONS = 0;
    private static final int MENU_ACHIEVEMENTS = 5;
    private static final int MENU_STATS = 6;
    private static final int MENU_SHARE_TO_LAN = 7;
    private static final int OPTIONS_CONTROLS = 100;
    private static final int OPTIONS_VIDEO = 101;
    private static final int OPTIONS_LANGUAGE = 102;
    private static final int OPTIONS_CHAT = 103;
    private static final int OPTIONS_SNOOPER = 104;
    private static final int OPTIONS_RESOURCE_PACKS = 105;
    private static final int OPTIONS_SOUNDS = 106;
    private static final int OPTIONS_SKIN = 110;

    /** A client that reports "not singleplayer" — a multiplayer server, the harder case. */
    private static TreeMinecraft mc;

    /** Vanilla cannot be built headless, and {@code initGui} needs a locale for its captions. */
    @BeforeClass
    public static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
        try {
            Field locale = net.minecraft.client.resources.I18n.class
                    .getDeclaredField("i18nLocale");
            locale.setAccessible(true);
            locale.set(null, new net.minecraft.client.resources.Locale());
        } catch (Exception e) {
            throw new AssertionError("could not install a headless I18n locale", e);
        }
        mc = newClient();
        // GuiOptionSlider's constructor reads Minecraft.getMinecraft().gameSettings -- the
        // SINGLETON, not the screen's own `mc` -- so GuiOptions.initGui cannot run a line
        // without it. Set it here and cleared in @AfterClass, because a test that leaves a fake
        // client in a global would quietly change what every later test in this JVM sees.
        installSingleton(mc);
    }

    @AfterClass
    public static void clearSingleton() {
        installSingleton(null);
    }

    private static void installSingleton(Minecraft client) {
        try {
            Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
            singleton.setAccessible(true);
            singleton.set(null, client);
        } catch (Exception e) {
            throw new AssertionError("could not install the Minecraft singleton", e);
        }
    }

    // ===== level 1: the pause menu =====

    @Test
    public void thePauseMenuDoorsAreTheRealVanillaButtons() {
        assertDoor(new GuiIngameMenu(), MENU_OPTIONS, GuiOptions.class);
        assertDoor(new GuiIngameMenu(), MENU_SHARE_TO_LAN, GuiShareToLan.class);
        assertDoor(new GuiIngameMenu(), MENU_ACHIEVEMENTS, GuiAchievements.class);
        assertDoor(new GuiIngameMenu(), MENU_STATS, GuiStats.class);
    }

    /**
     * Statistics and Achievements — the two the audit called doorless — and the gate they are
     * actually under.
     *
     * <p>From the inventory panel they really are unreachable: {@code GuiInventory.initGui} clears
     * {@code buttonList} and adds nothing, so nothing there leads out. The door that exists is the
     * pause menu's own button, and {@link #thePauseMenuDoorsAreTheRealVanillaButtons} is what says
     * so — the SAME {@link GuiIngameMenu} the ESC key opens constructs {@link GuiStats} for id 6
     * and {@link GuiAchievements} for id 5. No second route was needed and none was invented.
     *
     * <p><b>Which button the singleplayer gate lands on, because the obvious reading is wrong.</b>
     * {@code GuiIngameMenu.initGui} declares {@code GuiButton guibutton;} BEFORE the Share-to-LAN
     * line, so {@code guibutton} is button <b>7</b>, and the trailing
     * {@code guibutton.enabled = isSingleplayer() && !integratedServer.getPublic()} gates
     * <b>Share to LAN</b>. Statistics and Achievements get no such line and are enabled
     * unconditionally. Pinned here because the difference is between "an agent on a multiplayer
     * server can never see statistics" and "it always can".
     */
    @Test
    public void shareToLanIsTheDoorVanillaGatesAndStatisticsAreNot() {
        GuiIngameMenu offServer = new GuiIngameMenu();
        bind(offServer, mc);
        init(offServer);
        assertTrue("Statistics has no enabled=false line in initGui; it is reachable off a "
                        + "multiplayer server too", enabledOf(offServer, MENU_STATS));
        assertTrue("Achievements likewise", enabledOf(offServer, MENU_ACHIEVEMENTS));
        assertFalse("SHARE TO LAN is the gated one -- guibutton is button 7",
                enabledOf(offServer, MENU_SHARE_TO_LAN));

        TreeMinecraft singleplayer = newClient();
        singleplayer.stubServer = stubIntegratedServer(false);
        GuiIngameMenu inWorld = new GuiIngameMenu();
        bind(inWorld, singleplayer);
        init(inWorld);
        assertTrue("singleplayer without LAN sharing is what enables Share to LAN",
                enabledOf(inWorld, MENU_SHARE_TO_LAN));

        TreeMinecraft onLan = newClient();
        onLan.stubServer = stubIntegratedServer(true);
        GuiIngameMenu shared = new GuiIngameMenu();
        bind(shared, onLan);
        init(shared);
        assertFalse("a world already shared to LAN takes the door away again",
                enabledOf(shared, MENU_SHARE_TO_LAN));
        assertTrue("and Statistics stays open regardless", enabledOf(shared, MENU_STATS));
    }

    // ===== level 2: the options sub-pages =====

    @Test
    public void everyOptionsSubPageIsReachedByItsRealVanillaButton() {
        assertDoor(newOptions(), OPTIONS_CONTROLS, GuiControls.class);
        assertDoor(newOptions(), OPTIONS_VIDEO, GuiVideoSettings.class);
        assertDoor(newOptions(), OPTIONS_LANGUAGE, GuiLanguage.class);
        assertDoor(newOptions(), OPTIONS_CHAT, ScreenChatOptions.class);
        assertDoor(newOptions(), OPTIONS_SNOOPER, GuiSnooper.class);
        assertDoor(newOptions(), OPTIONS_RESOURCE_PACKS, GuiScreenResourcePacks.class);
        assertDoor(newOptions(), OPTIONS_SOUNDS, GuiScreenOptionsSounds.class);
        assertDoor(newOptions(), OPTIONS_SKIN, GuiCustomizeSkin.class);
    }

    // ===== the "is it addressable by the agent" half =====

    /**
     * The extractor {@code gui_snapshot} is built on must publish each door under its vanilla id.
     *
     * <p>This is the half that catches a screen vanilla constructs but never puts in
     * {@code buttonList}: such a screen has no element id, so a tree map naming it would be a
     * lie however true the map's first column is.
     */
    @Test
    public void everyDoorIsPublishedToGuiSnapshotUnderItsVanillaId() {
        assertPublished(new GuiIngameMenu(), MENU_OPTIONS, "pause menu -> Options");
        assertPublished(new GuiIngameMenu(), MENU_SHARE_TO_LAN, "pause menu -> Share to LAN");
        assertPublished(newOptions(), OPTIONS_VIDEO, "Options -> Video");
        assertPublished(newOptions(), OPTIONS_CONTROLS, "Options -> Controls");
        assertPublished(newOptions(), OPTIONS_LANGUAGE, "Options -> Language");
        assertPublished(newOptions(), OPTIONS_CHAT, "Options -> Chat");
        assertPublished(newOptions(), OPTIONS_SNOOPER, "Options -> Snooper");
        assertPublished(newOptions(), OPTIONS_RESOURCE_PACKS, "Options -> Resource Packs");
        assertPublished(newOptions(), OPTIONS_SOUNDS, "Options -> Sounds");
        assertPublished(newOptions(), OPTIONS_SKIN, "Options -> Skin");
    }

    // ===== harness =====

    /** Run the real {@code actionPerformed} and report which screen vanilla constructed. */
    private static void assertDoor(GuiScreen screen, int buttonId, Class<?> expected) {
        bind(screen, mc);
        mc.currentScreen = screen;
        invokeActionPerformed(screen, new GuiButton(buttonId, 0, 0, 200, 20, "door"));
        GuiScreen opened = mc.currentScreen;
        assertNotNull("button " + buttonId + " on " + screen.getClass().getSimpleName()
                + " left no screen open, so the door leads nowhere", opened);
        assertEquals("button " + buttonId + " on " + screen.getClass().getSimpleName()
                        + " must really open " + expected.getSimpleName()
                        + " -- this id is what the agent's tree map tells it to click",
                expected, opened.getClass());
    }

    private static void invokeActionPerformed(GuiScreen screen, GuiButton button) {
        try {
            for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("actionPerformed", GuiButton.class);
                    m.setAccessible(true);
                    m.invoke(screen, button);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // keep walking up
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not drive actionPerformed", e);
        }
        throw new AssertionError("no actionPerformed on " + screen.getClass());
    }

    /** Run the real {@code initGui} — what fills {@code buttonList} in production. */
    private static void init(GuiScreen screen) {
        try {
            screen.initGui();
        } catch (RuntimeException e) {
            throw new AssertionError("vanilla initGui threw for " + screen.getClass(), e);
        }
    }

    private static void bind(GuiScreen screen, TreeMinecraft client) {
        try {
            Field mcField = GuiScreen.class.getDeclaredField("mc");
            mcField.setAccessible(true);
            mcField.set(screen, client);
            screen.width = 427;
            screen.height = 240;
        } catch (Exception e) {
            throw new AssertionError("could not bind a screen to a headless client", e);
        }
    }

    private static void assertPublished(GuiScreen screen, int buttonId, String what) {
        bind(screen, mc);
        init(screen);
        assertTrue(what + ": vanilla never puts button " + buttonId + " of "
                        + screen.getClass().getSimpleName() + " in buttonList, so gui_snapshot has "
                        + "no element id for it and the tree map is wrong to name it. Published: "
                        + allButtonIds(screen),
                allButtonIds(screen).contains(buttonId));
    }

    /** Every vanilla button id the screen publishes, in list order. */
    private static List<Integer> allButtonIds(GuiScreen screen) {
        List<Integer> ids = new ArrayList<>();
        for (GuiElement el : GuiReflect.extract(screen, false, new ArrayList<>()).elements()) {
            if (GuiElement.KIND_BUTTON.equals(el.kind())
                    && el.attributes().get("buttonId") instanceof Number n) {
                ids.add(n.intValue());
            }
        }
        return ids;
    }

    private static boolean enabledOf(GuiScreen screen, int buttonId) {
        for (GuiElement el : GuiReflect.extract(screen, false, new ArrayList<>()).elements()) {
            if (GuiElement.KIND_BUTTON.equals(el.kind())
                    && el.attributes().get("buttonId") instanceof Number n
                    && n.intValue() == buttonId) {
                return el.state().enabled();
            }
        }
        throw new AssertionError("no button " + buttonId + " on "
                + screen.getClass().getSimpleName());
    }


    private static GuiOptions newOptions() {
        return new GuiOptions(new GuiIngameMenu(), mc.gameSettings);
    }

    /**
     * The single stand-in.
     *
     * <p>{@code displayGuiScreen} records instead of running, because the real one goes through
     * {@code setWorldAndResolution} → {@code initGui} → a live {@code ScaledResolution}, none of
     * which exist headless. Which class vanilla CONSTRUCTS is decided before
     * {@code displayGuiScreen} is entered, so the substitution cannot affect what is asserted.
     */
    static final class TreeMinecraft extends Minecraft {

        @SuppressWarnings("unused")
        private TreeMinecraft() {
            super((net.minecraft.client.main.GameConfiguration) null);
        }

        /** Non-null makes {@link #isSingleplayer()} answer yes, as vanilla's own guard reads it. */
        IntegratedServer stubServer;

        @Override
        public void displayGuiScreen(GuiScreen screen) {
            currentScreen = screen;
        }

        @Override
        public boolean isSingleplayer() {
            return stubServer != null || super.isSingleplayer();
        }

        @Override
        public IntegratedServer getIntegratedServer() {
            return stubServer != null ? stubServer : super.getIntegratedServer();
        }
    }

    /**
     * A {@code GameSettings} whose save is a no-op.
     *
     * <p>Every options door calls {@code saveOptions()} first, and the real one writes the
     * player's options.txt and then pushes a C17 to the server. Neither belongs in a unit test;
     * neither decides which screen is constructed.
     */
    static final class NoWriteGameSettings extends GameSettings {
        int saves;

        @Override
        public void saveOptions() {
            saves++;
        }
    }

    /**
     * The statistics and achievements doors dereference {@code mc.thePlayer.getStatFileWriter()},
     * and only the writer is read at construction time. Null is therefore the honest stand-in: it
     * proves the screen was built, which is the fact under test, without inventing a stat file
     * that would then be a fabricated fact about the player's history.
     */
    static final class StubPlayer extends net.minecraft.client.entity.EntityPlayerSP {

        @SuppressWarnings("unused")
        private StubPlayer() {
            super(null, null, null, null);
        }

        @Override
        public net.minecraft.stats.StatFileWriter getStatFileWriter() {
            return null;
        }
    }

    private static TreeMinecraft newClient() {
        TreeMinecraft client = (TreeMinecraft) allocate(TreeMinecraft.class);
        client.gameSettings = new NoWriteGameSettings();
        client.thePlayer = (net.minecraft.client.entity.EntityPlayerSP) allocate(StubPlayer.class);
        return client;
    }

    /** An {@code IntegratedServer} whose only reachable state is "is this world shared to LAN". */
    private static IntegratedServer stubIntegratedServer(boolean sharedToLan) {
        IntegratedServer server = (IntegratedServer) allocate(IntegratedServer.class);
        try {
            Field isPublic = findField(IntegratedServer.class, "isPublic");
            isPublic.setAccessible(true);
            isPublic.setBoolean(server, sharedToLan);
        } catch (Exception e) {
            throw new AssertionError("could not set IntegratedServer.isPublic", e);
        }
        return server;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name + " on " + type.getName());
    }

    private static Object allocate(Class<?> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(theUnsafe.get(null), type);
        } catch (Exception e) {
            throw new AssertionError("could not allocate " + type.getName(), e);
        }
    }
}
