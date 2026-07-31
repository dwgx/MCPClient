package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;
import io.github.timer_err.qml4j.render.items.input.TextField;
import io.github.timer_err.qml4j.render.items.view.Loader;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Asserts the window shell navigates: clicking a rail row swaps the page, and the page arrives.
 *
 * <p>The page-swap assertion is the one that matters, because its failure mode is silent. A Loader
 * whose source cannot be compiled leaves the PREVIOUS item in place, so a broken page shows up as a
 * selected nav row still displaying the old content — no exception, no blank area, nothing a
 * screenshot distinguishes from working software. Asserting that the loaded subtree actually changed
 * is the only way to see it.
 *
 * <p>That is not hypothetical here. Pages live in a subdirectory, and qml4j resolves a string import
 * against different directories depending on how the page was reached: a page registered in qmldir
 * comes back as an already-compiled class, while an unregistered one is compiled fresh with the
 * FILE's own directory as its baseDir. Getting that wrong makes every control in the page an unknown
 * type, and the Loader swallows it.
 */
public class NavigationShellLiveIT {

    private static final String SCENE = "dwm/Shell.qml";

    /** The four rail destinations, in the order the rail lists them. */
    private static final String[] PAGES = {
        "pages/PageHome.qml",
        "pages/PageKernel.qml",
        "pages/PageChips.qml",
        "pages/PageSettings.qml",
    };

    /** The rail rows, by the names NavigationView gives them. */
    private static final String[] ROW_NAMES = {
        "navHome", "navKernel", "navChips", "navSettings",
    };

    @Test
    public void everyRailRowLoadsItsOwnPage() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openShell();
            QmlView view = viewOf(surface);
            Item nav = view.findByObjectName("nav");
            assertNotNull("Shell.qml must name its NavigationView", nav);

            Loader content = loaderIn(nav);
            assertNotNull("the navigation view must hold a Loader for its content", content);
            assertEquals("the default page must be loaded before any click",
                1, content.children.size());

            List<MouseArea> rows = railRows(view);

            for (int i = 0; i < PAGES.length; i++) {
                Item before = content.children.isEmpty() ? null : content.children.get(0);

                rows.get(i).clicked.emit();
                surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

                assertEquals("clicking rail row " + i + " must select its page",
                    PAGES[i], currentPage(nav));
                assertEquals("row " + i + " must leave exactly one page loaded", 1,
                    content.children.size());

                Item after = content.children.get(0);
                assertTrue("row " + i + " (" + PAGES[i] + ") must produce a page with content; an "
                        + "empty subtree means the Loader failed to compile it and silently kept "
                        + "the previous page",
                    after.children.size() > 0);
                if (i > 0 && before != null) {
                    assertTrue("row " + i + " must actually swap the loaded item, not keep the "
                            + "previous page while the row lights up",
                        after != before);
                }
            }
            surface.close();
            surface = null;
        } finally {
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    /**
     * Exactly one rail row may be selected at a time.
     *
     * <p>Selection is derived from comparing each row's page to {@code currentPage} rather than
     * stored per row, so two lit rows would mean that derivation has been replaced by state that can
     * drift. Cheap to assert and impossible to notice by eye once the rail grows.
     */
    @Test
    public void exactlyOneRailRowIsSelectedAfterEachClick() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openShell();
            QmlView view = viewOf(surface);
            Item nav = view.findByObjectName("nav");
            List<MouseArea> rows = railRows(view);

            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).clicked.emit();
                surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());

                int selected = countSelected(nav);
                assertEquals("after clicking row " + i + " exactly one row may read as selected",
                    1, selected);
            }
            surface.close();
            surface = null;
        } finally {
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    /**
     * Nothing interactive anywhere in the shell may be zero-sized.
     *
     * <p>Extends the menu's walk to the window chrome, the rail and every page interior, and covers
     * {@link TextField} as well as {@link MouseArea}: a zero-sized field can never be clicked into
     * focus, so it renders correctly and never receives a keystroke.
     */
    @Test
    public void nothingInteractiveIsZeroSized() throws Exception {
        Assume.assumeTrue("needs a display", createDisplay());
        QmlUiSurface surface = null;
        try {
            surface = openShell();
            QmlView view = viewOf(surface);
            List<MouseArea> rows = railRows(view);

            // Visit every page, since a page only exists once its row has been selected.
            StringBuilder dead = new StringBuilder();
            int found = countInteractive(view.root(), dead);
            for (MouseArea row : rows) {
                row.clicked.emit();
                surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
                found += countInteractive(view.root(), dead);
            }

            assertTrue("the shell must contain interactive nodes at all", found > 0);
            assertTrue("nothing interactive may be zero-sized; such a node renders perfectly and "
                + "never receives input:" + dead, dead.length() == 0);

            surface.close();
            surface = null;
        } finally {
            if (surface != null) {
                surface.close();
            }
            destroyDisplay();
        }
    }

    // ---- harness ---------------------------------------------------------------

    private static QmlUiSurface openShell() {
        QmlUiSurface surface = new QmlUiSurface(SCENE);
        assertTrue("shell must open; " + surface.lastError(),
            surface.open(Display.getWidth(), Display.getHeight()));
        surface.setFramebufferId(0);
        surface.frame(Display.getWidth(), Display.getHeight(), System.nanoTime());
        return surface;
    }

    /** The navigation view's current page path. */
    private static String currentPage(Item nav) throws Exception {
        Field f = nav.getClass().getField("currentPage");
        @SuppressWarnings("unchecked")
        Property<Object> p = (Property<Object>) f.get(nav);
        return String.valueOf(p.peek());
    }

    /**
     * The rail's hit areas, found through the named NavItem that owns each one.
     *
     * <p>By name, not by geometry: a first version matched "as wide as the rail and one row tall"
     * and picked up six areas instead of four, because a page's own rows happen to be that size
     * too. Naming the rows makes the selection exact and says out loud which nodes the test drives.
     */
    private static List<MouseArea> railRows(QmlView view) {
        List<MouseArea> rows = new ArrayList<>();
        for (String name : ROW_NAMES) {
            Item row = view.findByObjectName(name);
            assertNotNull("NavigationView must name its rail row " + name, row);
            MouseArea hit = hitAreaIn(row);
            assertNotNull(name + " must own a MouseArea, or it cannot be clicked", hit);
            rows.add(hit);
        }
        return rows;
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

    /** How many rail rows currently read as selected. */
    private static int countSelected(Item nav) {
        return countSelectedIn(nav);
    }

    private static int countSelectedIn(Item node) {
        if (node == null) {
            return 0;
        }
        int count = 0;
        try {
            Field f = node.getClass().getField("selected");
            @SuppressWarnings("unchecked")
            Property<Object> p = (Property<Object>) f.get(node);
            if (Boolean.TRUE.equals(p.peek())) {
                count++;
            }
        } catch (ReflectiveOperationException notANavItem) {
            // Most nodes have no `selected` property; that is the common case, not an error.
        }
        for (Item child : node.children) {
            count += countSelectedIn(child);
        }
        return count;
    }

    private static int countInteractive(Item node, StringBuilder dead) {
        if (node == null) {
            return 0;
        }
        int found = 0;
        if (node instanceof MouseArea || node instanceof TextField) {
            found++;
            if (node.width.peekFloat() <= 0.0F || node.height.peekFloat() <= 0.0F) {
                dead.append("\n  ").append(node.getClass().getSimpleName())
                    .append(" ").append(node.width.peekFloat())
                    .append("x").append(node.height.peekFloat());
            }
        }
        for (Item child : node.children) {
            found += countInteractive(child, dead);
        }
        return found;
    }

    private static Loader loaderIn(Item node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Loader) {
            return (Loader) node;
        }
        for (Item child : node.children) {
            Loader hit = loaderIn(child);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static QmlView viewOf(QmlUiSurface surface) throws Exception {
        return surface.view();
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
