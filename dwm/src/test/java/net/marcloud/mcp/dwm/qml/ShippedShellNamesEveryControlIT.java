package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Every control the COMPOSED shell publishes, on every page, is named readably and uniquely.
 *
 * <p>This is the half of the naming property that genuinely needs a live window, and the reason is
 * specific rather than incidental: a page's controls do not exist until the rail has been clicked,
 * and the click takes effect inside a render frame. Loading {@code PageSettings.qml} on its own
 * proves the page names its own controls, but it cannot prove the page is reachable through the
 * shell, and it cannot see the shell's own chrome — the caption buttons exist only as part of a
 * composed window. So this walks the rail the way an agent does and inspects the table at each
 * stop.
 *
 * <p>That chrome is where the collision lived. The two caption buttons are anonymous
 * {@code MouseArea}s inside a named {@code Item}, so both resolved to {@code window}: six controls,
 * five names, on every page, and no way to tell close from maximize. They are also the reason a
 * per-scene test is not enough — standalone, the window's buttons have no ancestor name at all and
 * publish as paths.
 *
 * <p><b>What this cannot cover, and where it is covered instead.</b> The chip page's switches are
 * named from board's chip id, and dwm reaches the roster reflectively through a {@code Backplane}
 * that does not exist here, so the live chips page publishes no chip rows to check. That page is
 * enumerated with a stub roster in {@link QmlElementNamesAreReadableTest}, which needs no display
 * and therefore runs on every build — including CI, where this file self-skips. Neither test
 * subsumes the other: this one cannot run headless, and that one cannot compose the shell.
 *
 * <p>Self-skips without a display, so it costs a headless runner nothing.
 */
public final class ShippedShellNamesEveryControlIT {

    private static final String SCENE = "dwm/Shell.qml";

    /** The rail rows in rail order, each with the page path it must reveal. */
    private static final String[][] RAIL = {
        {"navHome", "pages/PageHome.qml"},
        {"navKernel", "pages/PageKernel.qml"},
        {"navChips", "pages/PageChips.qml"},
        {"navSettings", "pages/PageSettings.qml"},
    };

    /** Published on every page, and the agent's only way between them. */
    private static final String[] CHROME = {
        "navHome", "navKernel", "navChips", "navSettings", "windowMaximize", "windowClose",
    };

    @Test
    public void everyPageOfTheLiveShellPublishesOnlyNamedUniqueControls() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openShell();

            for (String[] stop : RAIL) {
                goToPage(surface, stop[0]);
                assertEquals("clicking " + stop[0] + " must reveal its own page, or the table "
                        + "inspected below is the previous page's and this test proves nothing",
                    stop[1], currentPage(surface));

                List<QmlElementBridge.Hit> hits = table(surface);
                assertNamedAndUnique(stop[0], hits);
                for (String name : CHROME) {
                    assertPublished(stop[0], hits, name);
                }
            }

            // Only the settings page carries controls beyond the chrome, and it carries a known
            // set. Asserting the set keeps the uniqueness sweep above from passing on a page whose
            // interior controls had all gone missing.
            goToPage(surface, "navSettings");
            List<QmlElementBridge.Hit> settings = table(surface);
            assertNamedAndUnique("navSettings", settings);
            for (String name : new String[] {"settingsFullbrightCard", "settingsFullbright",
                "settingsGammaCard", "settingsGamma", "settingsNameCard", "settingsName",
                "settingsAdvanced", "settingsAdvancedChevron",
                "fxMaster", "fxMasterToggle", "fxDetails", "fxDetailsChevron"}) {
                assertPublished("navSettings", settings, name);
            }
            assertEquals("the settings page's control count is pinned too, so a control dropped "
                    + "by a QML edit cannot pass as a page that merely got quieter: "
                    + ids(settings), 20, settings.size());

            close(surface);
            surface = null;
        } finally {
            close(surface);
            destroyDisplay();
        }
    }

    /**
     * The settings page's expander rows are published once opened, and they are named too.
     *
     * <p>Separate because those rows do not exist until then: an expander body is
     * {@code visible: expanded}, so a closed page publishes fourteen interior controls and an open
     * one eighteen. Checking only the closed state leaves the four rows no test ever sees — and they
     * are precisely the ones an agent has to name to choose between "keep open on world switch" and
     * "keep open on pause", two checkboxes stacked at the same x.
     */
    @Test
    public void theSettingsExpandersPublishNamedRowsOnceOpened() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openShell();
            goToPage(surface, "navSettings");
            assertEquals(20, table(surface).size());

            for (String expander : new String[] {"settingsAdvanced", "fxDetails"}) {
                Item node = surface.view().findByObjectName(expander);
                assertNotNull("the settings page must name its expander " + expander, node);
                MouseArea header = hitAreaIn(node);
                assertNotNull(expander + " must own a MouseArea, or it cannot be opened", header);
                header.clicked.emit();
                frame(surface);
            }

            List<QmlElementBridge.Hit> open = table(surface);
            assertNamedAndUnique("both expanders open", open);
            for (String name : new String[] {"settingsTelemetry", "settingsKeepOnPause",
                "fxClientArea", "fxExpand"}) {
                assertPublished("both expanders open", open, name);
            }
            assertEquals("opening both expanders adds exactly their four rows, so a row that "
                    + "silently stopped being published is caught: " + ids(open),
                24, open.size());

            close(surface);
            surface = null;
        } finally {
            close(surface);
            destroyDisplay();
        }
    }

    // ---- harness --------------------------------------------------------------------

    private static QmlUiSurface openShell() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("the shipped shell must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        frame(surface);
        return surface;
    }

    private static void close(QmlUiSurface surface) {
        if (surface != null) {
            surface.close();
        }
    }

    private static void frame(QmlUiSurface surface) {
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
    }

    private static void goToPage(QmlUiSurface surface, String railRow) {
        Item row = surface.view().findByObjectName(railRow);
        assertNotNull("the shell must name its rail row " + railRow, row);
        MouseArea hit = hitAreaIn(row);
        assertNotNull(railRow + " must own a MouseArea, or it cannot be clicked", hit);
        hit.clicked.emit();
        frame(surface);
    }

    /**
     * The table for the live shell.
     *
     * <p>The framebuffer and the screen are the same extent and the scale is 1, so the numbers
     * stay the scene's own. Only the ids are asserted on, and ids do not depend on the units.
     */
    private static List<QmlElementBridge.Hit> table(QmlUiSurface surface) {
        return QmlElementBridge.enumerate(surface.rootItem(), Display.getWidth(),
            Display.getHeight(), Display.getWidth(), Display.getHeight(), surface.uiScale());
    }

    /** The NavigationView's current page path, read off the live binding. */
    private static String currentPage(QmlUiSurface surface) throws Exception {
        Item nav = surface.view().findByObjectName("nav");
        assertNotNull("Shell.qml must name its NavigationView", nav);
        Field f = nav.getClass().getField("currentPage");
        @SuppressWarnings("unchecked")
        Property<Object> p = (Property<Object>) f.get(nav);
        return String.valueOf(p.peek());
    }

    private static void assertNamedAndUnique(String where, List<QmlElementBridge.Hit> hits) {
        assertTrue(where + " must publish controls at all, or the uniqueness sweep below passes "
            + "on an empty table", !hits.isEmpty());
        Set<String> seen = new LinkedHashSet<>();
        for (QmlElementBridge.Hit hit : hits) {
            assertFalse(where + " publishes " + describe(hit) + ", named by its index path — that "
                + "identifies nothing and re-points at a different control after any Loader swap "
                + "or layout change", isPath(hit.id));
            assertTrue(where + " publishes two controls both named " + hit.id + " (one is "
                + describe(hit) + "); an agent cannot choose between two rows with one name",
                seen.add(hit.id));
        }
    }

    private static void assertPublished(String where, List<QmlElementBridge.Hit> hits, String name) {
        Set<String> ids = ids(hits);
        assertTrue(where + " must publish a control named " + name + "; it published " + ids,
            ids.contains(name));
    }

    private static Set<String> ids(List<QmlElementBridge.Hit> hits) {
        Set<String> ids = new TreeSet<>();
        for (QmlElementBridge.Hit hit : hits) {
            ids.add(hit.id);
        }
        return ids;
    }

    private static MouseArea hitAreaIn(Item node) {
        if (node == null) {
            return null;
        }
        if (node instanceof MouseArea) {
            return (MouseArea) node;
        }
        for (Item child : node.children) {
            MouseArea hit = hitAreaIn(child);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean isPath(String id) {
        return id.startsWith("/") || id.matches("\\d+(/\\d+)+");
    }

    private static String describe(QmlElementBridge.Hit hit) {
        return hit.id + " at {" + hit.x + "," + hit.y + " " + hit.w + "x" + hit.h + "}";
    }

    private static boolean createDisplay() {
        try {
            Display.setDisplayMode(new DisplayMode(700, 560));
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
