package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * The animation policy layer: the master/subordinate dependency graph, and the values it publishes.
 *
 * <p>This exists because Windows treats animation as SYSTEM POLICY rather than as each control's
 * private business — {@code SPI_GETUIEFFECTS} is a master switch that suppresses every effect below
 * it, and subordinate flags are ignored when their master is off. Reproducing that graph is the
 * thing under test; a per-control duration literal, which is what this module had, makes "turn
 * animations off" unimplementable without editing every file.
 *
 * <p>Values are read off the LIVE singleton rather than parsed out of the QML, for the reason the
 * navigation probe established: a value read from the file proves what was written, and a value read
 * from the running scene proves what is used.
 */
public class MotionPolicyLiveIT {

    private static final String SCENE = "dwm/MotionPolicy.qml";

    /** ControlFasterAnimationDuration, 00:00:00.083. */
    private static final int FASTER = 83;
    /** ControlFastAnimationDuration, 00:00:00.167. */
    private static final int FAST = 167;
    /** ControlNormalAnimationDuration, 00:00:00.250. */
    private static final int NORMAL = 250;
    /** The Expander's own chevron duration, Duration="0:0:0.1". */
    private static final int CHEVRON = 100;

    /**
     * The three WinUI durations, plus the Expander's own, must be exactly these.
     *
     * <p>They come from {@code Common_themeresources_any.xaml} and
     * {@code Expander_themeresources.xaml}. The chevron's 100 is asserted separately BECAUSE it is
     * not one of the three tiers: reaching for the 83ms Faster tier there is a plausible wrong
     * answer, and the shipped resource says 0:0:0.1.
     */
    @Test
    public void theDurationsAreTheWinUiValues() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            assertEquals("ControlFasterAnimationDuration", FASTER, intOf(root, "durationFaster"));
            assertEquals("ControlFastAnimationDuration", FAST, intOf(root, "durationFast"));
            assertEquals("ControlNormalAnimationDuration", NORMAL, intOf(root, "durationNormal"));
            assertEquals("the Expander's chevron animates over 0:0:0.1, which is NOT the 83ms "
                + "control-state tier", CHEVRON, intOf(root, "durationChevron"));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * The easing indices must be qml4j's decelerate and accelerate curves, and must differ.
     *
     * <p>WinUI picks its curve by DIRECTION: entering content decelerates
     * ({@code cubic-bezier(0,0,0,1)}), exiting content accelerates
     * ({@code cubic-bezier(1,0,1,1)}). The trap is index 3 — InOutQuad — which accelerates out of
     * the start and produces a slow-fast-slow motion neither curve has.
     */
    @Test
    public void theEasingsAreDirectionalAndNotInOutQuad() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            int enter = intOf(root, "easeEnter");
            int exit = intOf(root, "easeExit");
            assertEquals("entering content decelerates: OutQuad is qml4j index 2", 2, enter);
            assertEquals("exiting content accelerates: InQuad is qml4j index 1", 1, exit);
            assertTrue("the two directions must not share a curve -- that would erase the "
                + "distinction WinUI draws between entering and exiting", enter != exit);
            assertTrue("neither may be InOutQuad (3), which accelerates out of the start",
                enter != 3 && exit != 3);

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * The master switch must suppress every effect, whatever the subordinate flags say.
     *
     * <p>This is {@code SPI_SETUIEFFECTS(FALSE)}: Windows documents it as disabling every effect at
     * once, and the Performance Options dialog's "Adjust for best performance" is that button. The
     * subordinate flags are deliberately left TRUE here — if the master were merely one term among
     * equals, they would keep their effects alive and this would pass while the checkbox did nothing.
     */
    @Test
    public void theMasterSwitchSuppressesEveryEffect() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            assertTrue("precondition: effects start enabled", boolOf(root, "animateControls"));
            assertTrue("precondition: expand starts enabled", boolOf(root, "animateExpand"));

            setBool(root, "setUiEffects", false);
            frame(surface);

            for (String effect : new String[] {
                    "animateControls", "animateMenus", "animateExpand",
                    "animateScrolling", "animatePages", "showShadows"}) {
                assertTrue("with the master switch off, " + effect + " must be false even though "
                    + "its own flag is still set", !boolOf(root, effect));
            }

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * A subordinate flag must be ignored while its master is off.
     *
     * <p>{@code SPI_GETMENUFADE} is documented as ignored unless menu animation is enabled, which is
     * a real dependency rather than a suggestion: a settings page that let the two be set
     * independently would offer a choice with no effect.
     */
    @Test
    public void aSubordinateFlagIsIgnoredWhileItsMasterIsOff() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            assertTrue("precondition: menus fade when both flags are set",
                boolOf(root, "menuFadesRatherThanSlides"));

            // Menu animation off, fade left ON. The fade must stop applying.
            setBool(root, "setMenuAnimation", false);
            frame(surface);
            assertTrue("menuFade must be ignored while menuAnimation is off",
                !boolOf(root, "menuFadesRatherThanSlides"));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    /**
     * A disabled effect must mean a duration of ZERO, not a frozen animation.
     *
     * <p>The documented expectation for {@code SPI_GETCLIENTAREAANIMATION} is that a well-behaved app
     * skips to the END STATE. Zero expresses that: a transition of length zero still arrives. A
     * control that merely stopped animating mid-flight would be broken rather than un-animated,
     * which is the distinction this pins.
     */
    @Test
    public void aDisabledEffectYieldsAZeroDurationRatherThanAFrozenOne() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = open();
            Item root = viewOf(surface).root();

            assertEquals("enabled, a control transition lasts the Faster tier",
                FASTER, intOf(root, "controlDuration"));
            assertEquals("enabled, an expand lasts the Normal tier",
                NORMAL, intOf(root, "expandDuration"));

            setBool(root, "setUiEffects", false);
            frame(surface);

            assertEquals("disabled, a control transition must be instant (0), which still ENDS in "
                + "the target state", 0, intOf(root, "controlDuration"));
            assertEquals("disabled, an expand must be instant", 0, intOf(root, "expandDuration"));
            assertEquals("disabled, the chevron must be instant", 0, intOf(root, "chevronDuration"));
            assertEquals("disabled, a page transition must be instant",
                0, intOf(root, "pageDuration"));

            surface.close();
            surface = null;
        } finally {
            closeQuietly(surface);
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    private static QmlUiSurface open() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("scene must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        frame(surface);
        return surface;
    }

    private static void frame(QmlUiSurface surface) {
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
    }

    private static int intOf(Item item, String name) throws Exception {
        return Math.round(number(item, name));
    }

    private static float number(Item item, String name) throws Exception {
        Object property = item.getClass().getField(name).get(item);
        Object value = property.getClass().getMethod("peek").invoke(property);
        assertNotNull("property " + name + " must have a value", value);
        return ((Number) value).floatValue();
    }

    private static boolean boolOf(Item item, String name) throws Exception {
        Object property = item.getClass().getField(name).get(item);
        Object value = property.getClass().getMethod("peek").invoke(property);
        assertNotNull("property " + name + " must have a value", value);
        return Boolean.TRUE.equals(value);
    }

    private static void setBool(Item item, String name, boolean value) throws Exception {
        Object property = item.getClass().getField(name).get(item);
        property.getClass().getMethod("set", Object.class).invoke(property, Boolean.valueOf(value));
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        return surface.view();
    }

    private static void closeQuietly(QmlUiSurface surface) {
        if (surface != null) {
            surface.close();
        }
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(300, 200));
            Display.create();
            Display.update();
            return true;
        } catch (Throwable t) {
            System.out.println("[IT] no display (" + t + ") — skipping");
            return false;
        }
    }

    private static void destroyDisplay() {
        try {
            Display.destroy();
        } catch (Throwable ignored) {
            // Teardown of an already-dead display is not actionable.
        }
    }
}
