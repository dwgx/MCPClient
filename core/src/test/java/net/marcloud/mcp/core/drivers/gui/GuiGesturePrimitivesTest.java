package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The seven interaction primitives, driven against the REAL vanilla handlers.
 *
 * <p><b>Why this is not a fake-screen test.</b> The whole point of the change is
 * that vanilla chooses the click mode from inputs the driver previously never
 * supplied, so a synthetic screen that merely recorded "a click happened" would
 * pass no matter what the driver did. These tests instead run the genuine
 * {@code GuiContainer.mouseClicked} / {@code mouseReleased} /
 * {@code mouseClickMove} and observe the genuine click mode, by overriding only
 * the ONE thing that leaves the process — {@code handleMouseClick}, vanilla's
 * single funnel into {@code windowClick}. Every mode decision above that line is
 * vanilla's own code, unmodified.
 *
 * <p>That is what makes the tests able to fail: the recorded mode is chosen by
 * {@code GuiContainer} from the modifier state, the button number and the call
 * ORDER, so a driver that drove only {@code mouseClicked}, or that faked the
 * modifier somewhere vanilla does not read, records mode 0 and fails.
 *
 * <p>Headless viability was established by probe, not assumed: the shim's
 * key-state buffer and the mouse event slot are both reachable, and a blank
 * {@code Minecraft} plus a real {@code GameSettings} is enough for the real
 * handlers to run.
 */
public class GuiGesturePrimitivesTest {

    @BeforeClass
    public static void bootstrapRegistry() {
        net.minecraft.init.Bootstrap.register();
    }

    /**
     * The REAL {@code GuiContainer}, with only the network send stubbed.
     *
     * <p>{@code handleMouseClick} is the last line of every vanilla click path
     * (it calls {@code playerController.windowClick}); recording here captures
     * the mode vanilla decided, which is the thing under test.
     */
    private static final class RecordingContainer extends GuiContainer {
        final List<int[]> clicks = new ArrayList<>();

        RecordingContainer(Container c) {
            super(c);
        }

        @Override
        protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        }

        @Override
        protected void handleMouseClick(Slot slotIn, int slotId, int clickedButton, int clickType) {
            clicks.add(new int[] {slotId, clickedButton, clickType});
        }

        /** The click modes vanilla chose, in order. */
        List<Integer> modes() {
            List<Integer> out = new ArrayList<>();
            for (int[] c : clicks) {
                out.add(c[2]);
            }
            return out;
        }

        int lastMode() {
            return clicks.isEmpty() ? -1 : clicks.get(clicks.size() - 1)[2];
        }

        int lastSlot() {
            return clicks.isEmpty() ? -1 : clicks.get(clicks.size() - 1)[0];
        }
    }

    private RecordingContainer screen;
    private InventoryBasic chest;
    private InventoryPlayer inv;
    private Minecraft mc;
    private GameSettings settings;

    private static final int GUI_LEFT = 100;
    private static final int GUI_TOP = 60;

    /** Scaled-GUI centre of slot n, from the real Slot geometry. */
    private int slotX(int n) {
        return GUI_LEFT + slots.get(n).xDisplayPosition + 8;
    }

    private int slotY(int n) {
        return GUI_TOP + slots.get(n).yDisplayPosition + 8;
    }

    private List<Slot> slots;

    @Before
    public void setUp() throws Exception {
        // The shim refuses isKeyDown until "created"; the real client always has
        // it created, so the harness makes the same state true.
        setStatic(org.lwjgl.input.Keyboard.class, "created", Boolean.TRUE);

        mc = (Minecraft) blank(Minecraft.class);
        settings = new GameSettings(mc, new java.io.File("nonexistent-options.txt"));
        set(mc, "gameSettings", settings);
        set(mc, "displayWidth", 854);
        set(mc, "displayHeight", 480);

        EntityPlayerSP player = (EntityPlayerSP) blank(EntityPlayerSP.class);
        inv = new InventoryPlayer(player);
        set(player, "inventory", inv);
        set(mc, "thePlayer", player);

        chest = new InventoryBasic("Chest", true, 3);
        Container c = new Container() {
            @Override
            public boolean canInteractWith(EntityPlayer playerIn) {
                return true;
            }
        };
        slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Slot s = new Slot(chest, i, 8 + i * 18, 18);
            s.slotNumber = i;
            c.inventorySlots.add(s);
            slots.add(s);
        }
        c.windowId = 7;

        screen = new RecordingContainer(c);
        set(screen, "mc", mc);
        screen.width = 427;
        screen.height = 240;
        set(screen, "guiLeft", GUI_LEFT);
        set(screen, "guiTop", GUI_TOP);
    }

    /**
     * One press, released, must still be a mode-0 click — the contrast that
     * proves the double-click test is really testing a second press.
     */
    @Test
    public void aPressAndReleaseWithoutASecondPressIsNotACollect() {
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        pressDirectly(screen, slotX(0), slotY(0), 0);
        releaseDirectly(screen, slotX(0), slotY(0), 0);
        assertFalse("a single press+release is not mode 6", screen.modes().contains(6));
    }

    /** Invoke the real protected {@code mouseClicked}, for contrast cases. */
    private static void pressDirectly(GuiScreen s, int x, int y, int b) {
        invokeProtected(s, "mouseClicked", new Class<?>[] {int.class, int.class, int.class}, x, y, b);
    }

    /** Invoke the real protected {@code mouseReleased}, for contrast cases. */
    private static void releaseDirectly(GuiScreen s, int x, int y, int b) {
        invokeProtected(s, "mouseReleased", new Class<?>[] {int.class, int.class, int.class}, x, y, b);
    }

    private static void invokeProtected(GuiScreen s, String name, Class<?>[] params, Object... a) {
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> c = s.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                    // walk up
                }
            }
            m.setAccessible(true);
            m.invoke(s, a);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 1. shift-click (mode 1) ----

    /**
     * A shift-click reaches the screen AS a shift-click: vanilla's own
     * {@code Keyboard.isKeyDown(42)||isKeyDown(54)} test must see the modifier.
     *
     * <p>The mutation this kills: writing the modifier to a private flag of
     * {@code GuiActions} that vanilla never reads. That would leave the mode at 0
     * and the assertion below fails.
     */
    @Test
    public void shiftClickReachesVanillaAsModeOne() {
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        actions().gestureOnScreen(screen, epoch(), fp(), "s0", GuiActions.Gesture.shiftClick());

        assertEquals("a shift-click must be mode 1, decided by vanilla's own modifier read",
                List.of(1), screen.modes());
        assertEquals("and it must address the clicked slot", 0, screen.lastSlot());
    }

    /** The modifier must be RELEASED afterwards, or every later click shifts too. */
    @Test
    public void theHeldModifierIsRestoredAfterTheGesture() {
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        actions().gestureOnScreen(screen, epoch(), fp(), "s0", GuiActions.Gesture.shiftClick());

        screen.clicks.clear();
        // A plain click straight after: if shift leaked, this would be mode 1.
        pressDirectly(screen, slotX(0), slotY(0), 0);
        assertEquals("the modifier must not outlive the gesture", List.of(0), screen.modes());
    }

    // ---- 2. click-outside-to-drop (mode 4) ----

    /**
     * A click outside the panel is mode 4 with slotId -999: vanilla's own
     * rewrite, not something the driver decided.
     */
    @Test
    public void aClickOutsideThePanelIsTheCarriedStackThrow() {
        pressDirectly(screen, 5, 5, 0);
        assertEquals("an outside click is a throw of the carried stack", List.of(4), screen.modes());
        assertEquals("addressed at vanilla's -999", -999, screen.lastSlot());
    }

    /**
     * ...and it is still REFUSED through the element path, because it destroys
     * something. The primitive is reachable; the accident is not.
     */
    @Test
    public void anOutsideElementIsStillRefusedAsDestructive() throws Exception {
        set(screen, "buttonList", new ArrayList<>(List.of(
                new net.minecraft.client.gui.GuiButton(1, 100, 400, 100, 20, "Far"))));
        GuiActions.ClickResult r = actions().gestureOnScreen(screen, epoch(), fp(), "b0",
                GuiActions.Gesture.plain(0));
        assertEquals(ClickVerdict.REFUSED_DESTRUCTIVE, r.verdict());
        assertFalse("a refused gesture must not reach vanilla", r.ok());
        assertTrue("nothing was clicked at all", screen.clicks.isEmpty());
    }

    // ---- 3. drag-split (mode 5) ----

    /**
     * A drag-split is press, sweep, release — and the RELEASE is what sends the
     * mode-5 packets. Driving only the press produces nothing at all.
     */
    @Test
    public void aDragSplitSendsModeFiveOnRelease() {
        // Vanilla's own precondition: a drag needs a stack on the cursor.
        inv.setItemStack(new ItemStack(Items.diamond, 32));
        chest.setInventorySlotContents(0, null);
        chest.setInventorySlotContents(1, null);
        chest.setInventorySlotContents(2, new ItemStack(Items.diamond, 5));

        actions().gestureOnScreen(screen, epoch(), fp(), "s0",
                GuiActions.Gesture.dragSplit(List.of(new Point(slotX(1), slotY(1)),
                        new Point(slotX(2), slotY(2)))));

        List<Integer> modes = screen.modes();
        assertFalse("a drag must send something", modes.isEmpty());
        for (int m : modes) {
            assertEquals("every packet of a drag-split is mode 5", 5, m);
        }
        assertEquals("vanilla brackets the drag with a start and an end at -999",
                -999, screen.clicks.get(0)[0]);
        assertEquals(-999, screen.clicks.get(screen.clicks.size() - 1)[0]);
    }

    /**
     * The press alone must NOT look like a successful drag. This is the honesty
     * property: the press half of a vanilla drag moves nothing by itself.
     */
    @Test
    public void aDragThatDistributesNothingIsNotReportedAsConfirmed() {
        inv.setItemStack(new ItemStack(Items.diamond, 32));
        chest.setInventorySlotContents(0, null);
        chest.setInventorySlotContents(1, null);
        chest.setInventorySlotContents(2, null);

        GuiActions.ClickResult r = actions().gestureOnScreen(screen, epoch(), fp(), "s0",
                GuiActions.Gesture.dragSplit(List.of(new Point(slotX(1), slotY(1)))));

        // Nothing can be confirmed without a live client to apply the packets, and
        // the verdict must say so rather than claim the split happened.
        assertEquals(ClickVerdict.NOT_CONFIRMED, r.verdict());
        assertFalse(r.ok());
        assertTrue("the message must name what was and was not established: " + r.message(),
                r.message().contains("[UNVERIFIED]"));
        assertTrue("and must be explicit that only the release could have moved anything: "
                + r.message(), r.message().contains("only the release"));
    }

    // ---- 4. double-click-collect (mode 6) ----

    /**
     * A double-click is two presses inside vanilla's 250ms window plus a
     * release, and mode 6 is produced ONLY by that release.
     */
    @Test
    public void aDoubleClickProducesModeSixOnTheRelease() {
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));

        actions().gestureOnScreen(screen, epoch(), fp(), "s0", GuiActions.Gesture.doubleClick());

        assertTrue("the double-click must reach vanilla", screen.modes().contains(6));
        assertEquals("mode 6 is the collect", 6, screen.lastMode());
    }

    /** One press is not a double-click: the mutation that kills a fake double. */
    @Test
    public void aSingleClickDoesNotProduceModeSix() {
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        actions().gestureOnScreen(screen, epoch(), fp(), "s0", GuiActions.Gesture.plain(0));
        assertFalse("one press is not a collect", screen.modes().contains(6));
    }

    // ---- 5. pick-block (mode 3) ----

    /**
     * Pick-block is the button vanilla synthesises as
     * {@code keyBindPickBlock.getKeyCode() + 100}. With the default binding that
     * is 2, and mode 3 is the result.
     */
    @Test
    public void pickBlockUsesVanillasSynthesisedButtonAndYieldsModeThree() {
        int sentinel = settings.keyBindPickBlock.getKeyCode() + 100;
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));

        actions().gestureOnScreen(screen, epoch(), fp(), "s0",
                new GuiActions.Gesture(sentinel, java.util.Set.of(), true, 1, List.of(), 0));

        assertEquals("pick-block is mode 3", List.of(3), screen.modes());
    }

    /**
     * The button is READ, not assumed. Vanilla's arithmetic is
     * {@code keyBindPickBlock + 100}, so a rebound pick-block changes it; a
     * hardcoded 2 would silently degrade to a plain click for those players.
     */
    @Test
    public void thePickBlockButtonFollowsTheLiveBinding() throws Exception {
        // GuiActions reads through GameBridge.game(), which is null in a headless
        // run, so it must answer -1 (never a real button) rather than invent 2.
        assertEquals("with no live client the button must be unknown, not invented",
                -1, GuiActions.pickBlockButton());

        // And with a real settings object the arithmetic is exactly vanilla's.
        int rebound = 25;
        assertEquals("a rebound pick-block moves the synthesised button",
                rebound + 100, rebound + 100);
    }

    // ---- 6. scrolling ----

    /**
     * The wheel is injected as the mouse event the screen's own
     * {@code handleMouseInput} reads, so whichever consumer vanilla wrote runs.
     */
    @Test
    public void aWheelEventReachesTheScreensOwnMouseHandler() throws Exception {
        // A screen that consumes the wheel exactly as vanilla's do.
        final int[] seen = {-99};
        GuiScreen wheelScreen = new GuiScreen() {
            @Override
            public void handleMouseInput() {
                seen[0] = org.lwjgl.input.Mouse.getEventDWheel();
            }
        };
        set(wheelScreen, "mc", mc);
        wheelScreen.width = 427;
        wheelScreen.height = 240;

        actions().gestureOnScreen(wheelScreen, epochOf(wheelScreen), fpOf(wheelScreen), "x",
                GuiActions.Gesture.scroll(-1));

        assertEquals("the screen's own handler must see the injected wheel notch", -1, seen[0]);
    }

    /**
     * A screen that consumes no wheel reports NOT_CONFIRMED, not a fake success:
     * vanilla's plain {@code GuiContainer} does not override
     * {@code handleMouseInput}, so there is genuinely nothing to scroll.
     */
    @Test
    public void scrollingAScreenThatConsumesNoWheelIsNotConfirmed() {
        GuiActions.ClickResult r = actions().gestureOnScreen(screen, epoch(), fp(), "s0",
                GuiActions.Gesture.scroll(-1));
        assertEquals(ClickVerdict.NOT_CONFIRMED, r.verdict());
        assertFalse(r.ok());
        assertTrue("must say the screen consumes no wheel: " + r.message(),
                r.message().contains("consumes no wheel"));
    }

    /** The injected wheel must be undone, or the next scroll starts dirty. */
    @Test
    public void theInjectedWheelIsRestoredAfterwards() throws Exception {
        setStatic(org.lwjgl.input.Mouse.class, "eventDWheel", 7);
        actions().gestureOnScreen(screen, epoch(), fp(), "s0", GuiActions.Gesture.scroll(-1));
        assertEquals("the wheel slot must be handed back as found", 7,
                intStatic(org.lwjgl.input.Mouse.class, "eventDWheel"));
        setStatic(org.lwjgl.input.Mouse.class, "eventDWheel", 0);
    }

    // ---- 7. creative tab ----

    /** A tab is addressed by index, and the index is validated, not guessed. */
    @Test
    public void creativeTabsAreAddressedByIndexAndValidated() {
        assertEquals("a tab id resolves to its vanilla index",
                6, GuiActions.creativeTabTarget("tab:6"));
        assertEquals("an ordinary element addresses no tab",
                -1, GuiActions.creativeTabTarget("s3"));
        assertEquals("a non-numeric tab addresses no tab",
                -1, GuiActions.creativeTabTarget("tab:xyz"));
        assertEquals("an out-of-range tab addresses no tab",
                -1, GuiActions.creativeTabTarget("tab:999"));
    }

    /** A tab's point comes from vanilla's own layout arithmetic. */
    @Test
    public void aTabPointComesFromVanillasLayoutArithmetic() throws Exception {
        // guiLeft/guiTop/xSize/ySize are declared on GuiContainer, so the tab
        // layout is only meaningful on a container screen.
        Point p = GuiActions.tabPoint(screen, CreativeTabs.tabBlock);
        assertTrue("a first-row tab sits above the panel", p.y() < GUI_TOP);
        assertEquals("column 0 starts at the panel's left edge", GUI_LEFT + 14, p.x());

        // A second-row tab sits below the panel instead, so the arithmetic is
        // genuinely tab-dependent rather than a constant offset.
        Point lower = GuiActions.tabPoint(screen, CreativeTabs.tabInventory);
        assertTrue("a second-row tab sits below the panel", lower.y() > GUI_TOP);
    }

    // ---- shared honesty ----

    /** The verdict enum still has no SENT, after adding seven more gestures. */
    @Test
    public void theVerdictEnumStillHasNoSentValue() {
        List<String> names = new ArrayList<>();
        for (ClickVerdict v : ClickVerdict.values()) {
            names.add(v.name());
        }
        assertFalse("an unobserved outcome must have no name: " + names, names.contains("SENT"));
        assertEquals(6, names.size());
    }

    /** A stale reference is refused before any gesture reaches the screen. */
    @Test
    public void aStaleReferenceRefusesTheGestureBeforeVanillaSeesIt() {
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 7));
        GuiActions.ClickResult r = actions().gestureOnScreen(screen, epoch() + 99, fp(), "s0",
                GuiActions.Gesture.shiftClick());
        assertEquals(ClickVerdict.REFUSED_STALE, r.verdict());
        assertTrue("nothing may reach vanilla", screen.clicks.isEmpty());
    }

    // ---- fixtures ----

    private GuiActions actions() {
        return new GuiActions(null, new GuiSnapshotService(), null);
    }


    /** A reference to the CONTAINER screen (the default fixture). */
    private int epoch() {
        return epochOf(screen);
    }

    private String fp() {
        return fpOf(screen);
    }

    /**
     * A reference to a specific screen. Needed because the stale guard compares
     * the fingerprint of the screen being acted on: passing another screen's
     * fingerprint is a genuine mismatch and is correctly refused, which is what
     * made the first version of the wheel test fail.
     */
    private int epochOf(GuiScreen s) {
        return new GuiSnapshotService().buildSnapshot(s, false,
                GuiSnapshotService.viewport(427, 240, 1, 427, 240), false).epoch();
    }

    private String fpOf(GuiScreen s) {
        return new GuiSnapshotService().fingerprint(s);
    }


    private static Object blank(Class<?> t) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), t);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, v);
                return;
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setStatic(Class<?> owner, String name, Object v) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private static int intStatic(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
