package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Pure logic teeth for the launcher catalog: query filtering, category grouping, pinning,
 * and layout toggle — all backend-free, so they run headless with no GL/context.
 */
public class SoftwareCatalogTest {

    private static SoftwareCatalog catalog() {
        return new SoftwareCatalog(List.of(
                new SoftwareView("esp", "ESP", "Render", 0, false),
                new SoftwareView("tracers", "Tracers", "Render", 0, true),
                new SoftwareView("speed", "Speed", "Movement", 0, false),
                new SoftwareView("misc", "Misc Thing", "", 0, false)));
    }

    @Test
    public void filterMatchesNameAndCategoryCaseInsensitive() {
        SoftwareCatalog c = catalog();
        c.setQuery("render");
        List<SoftwareView> f = c.filtered();
        assertEquals("two Render-category apps match", 2, f.size());
        c.setQuery("SPE");
        assertEquals("name prefix matches case-insensitively", 1, c.filtered().size());
        c.setQuery("");
        assertEquals("blank query returns all", 4, c.filtered().size());
    }

    @Test
    public void groupsByCategoryUncategorisedBecomesOther() {
        var groups = catalog().grouped();
        assertTrue(groups.containsKey("Render"));
        assertTrue(groups.containsKey("Movement"));
        assertTrue("blank category grouped under Other", groups.containsKey("Other"));
        assertEquals(2, groups.get("Render").size());
    }

    @Test
    public void pinTogglePersistsAndFiltersWithQuery() {
        SoftwareCatalog c = catalog();
        assertFalse(c.isPinned("esp"));
        assertTrue("first toggle pins", c.togglePin("esp"));
        assertTrue(c.isPinned("esp"));
        assertEquals(1, c.pinnedViews().size());
        // pinned views also honor the active query
        c.togglePin("speed");
        c.setQuery("render");
        assertEquals("only pinned apps matching the query show", 1, c.pinnedViews().size());
        assertFalse("second toggle unpins", c.togglePin("esp"));
        c.setQuery("");
        assertEquals(1, c.pinnedViews().size());
    }

    @Test
    public void layoutToggleFlips() {
        SoftwareCatalog c = catalog();
        assertEquals(SoftwareCatalog.Layout.LIST, c.layout());
        assertEquals(SoftwareCatalog.Layout.GRID, c.toggleLayout());
        assertEquals(SoftwareCatalog.Layout.LIST, c.toggleLayout());
    }

    @Test
    public void softwareViewMatchAndGroupLabel() {
        SoftwareView v = new SoftwareView("id", "Fly Hack", "Movement", 0, false);
        assertTrue(v.matches("fly"));
        assertTrue(v.matches("MOVE"));
        assertFalse(v.matches("zzz"));
        assertEquals("Movement", v.groupLabel());
        assertEquals("Other", new SoftwareView("x", "X", "", 0, false).groupLabel());
    }
}
