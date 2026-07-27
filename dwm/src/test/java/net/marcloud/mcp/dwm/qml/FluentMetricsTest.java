package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Locks the metrics dwm takes from Microsoft's published Windows 11 specs.
 *
 * <p>These four numbers are the ones with an external source of truth, so they are the ones
 * worth pinning: 8px for overlay surfaces (flyouts, dialogs, menus), 4px for in-page elements
 * (buttons, list backplates), a 40x40 epx interactive target, and the 14px Body / 20px Subtitle
 * steps of the type ramp. Provenance is recorded in docs/dwm/fluent-spec.md.
 *
 * <p>The 8px-outside-4px nesting in particular is what makes the surface read as Windows 11
 * rather than merely rounded, and it is the kind of value that gets "tidied" to one number by
 * someone who does not know it is deliberate.
 *
 * <p>Asserted against the QML source because that is where the tokens live; the alternative is
 * duplicating them in Java, which would just create a second thing to drift.
 */
public class FluentMetricsTest {

    private static final String TOKENS = "dwm/Fluent.qml";

    private static String tokens() {
        String src = ClasspathResources.readText(TOKENS);
        assertNotNull(TOKENS + " must ship on the classpath", src);
        return src;
    }

    private static int intToken(String src, String name) {
        Matcher m = Pattern.compile("property\\s+int\\s+" + name + "\\s*:\\s*(\\d+)").matcher(src);
        assertTrue("Fluent.qml must define an int property named " + name, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** Overlay surfaces are 8px; in-page elements are 4px. Both are documented values. */
    @Test
    public void cornerRadiiMatchTheWindows11Spec() {
        String src = tokens();
        assertEquals("flyouts/dialogs/menus use an 8px corner radius",
            8, intToken(src, "radiusOverlay"));
        assertEquals("buttons and list backplates use a 4px corner radius",
            4, intToken(src, "radiusControl"));
        assertTrue("the overlay radius must stay larger than the control radius — the 8-outside-4 "
            + "nesting is what makes the surface read as Windows 11",
            intToken(src, "radiusOverlay") > intToken(src, "radiusControl"));
    }

    /** Fluent Standard aligns interactive items to a 40x40 epx target. */
    @Test
    public void rowHeightMatchesTheFluentTarget() {
        assertEquals("menu rows follow the 40x40 epx target", 40, intToken(tokens(), "rowHeight"));
    }

    /** Type ramp steps used by the menu: Body 14, Subtitle 20, Caption 12. */
    @Test
    public void typeRampMatchesTheWindowsTypeRamp() {
        String src = tokens();
        assertEquals("Body is 14px", 14, intToken(src, "fontBody"));
        assertEquals("Subtitle is 20px", 20, intToken(src, "fontSubtitle"));
        assertEquals("Caption is 12px", 12, intToken(src, "fontCaption"));
        assertTrue("12px Regular is the documented minimum legible size",
            intToken(src, "fontCaption") >= 12);
    }

    /**
     * Panel padding must equal the control radius.
     *
     * <p>That is what insets the 4px backplate far enough for its corners to sit inside the
     * panel's 8px ones instead of colliding with them.
     */
    @Test
    public void panelPaddingNestsTheBackplateInsideThePanel() {
        String src = tokens();
        assertEquals("panel padding should match the control radius so the backplate nests",
            intToken(src, "radiusControl"), intToken(src, "panelPadding"));
    }

    /**
     * Hover must be brighter than pressed.
     *
     * <p>Backwards from the usual assumption, which is exactly why it needs a test: in Fluent the
     * backplate dims as it goes down. Compares the alpha of the two #AARRGGBB tokens.
     */
    @Test
    public void hoverIsBrighterThanPressed() {
        String src = tokens();
        int hover = alphaOf(src, "subtleHover");
        int pressed = alphaOf(src, "subtlePressed");
        assertTrue("Fluent dims the backplate on press, so hover alpha (" + hover
            + ") must exceed pressed alpha (" + pressed + ")", hover > pressed);
    }

    private static int alphaOf(String src, String name) {
        Matcher m = Pattern.compile("property\\s+string\\s+" + name + "\\s*:\\s*\"#([0-9a-fA-F]{8})\"")
            .matcher(src);
        assertTrue(name + " must be an #AARRGGBB literal", m.find());
        return Integer.parseInt(m.group(1).substring(0, 2), 16);
    }

    /** Every component the menu is built from must ship and be registered in qmldir. */
    @Test
    public void menuComponentsAreRegistered() {
        String qmldir = ClasspathResources.readText("dwm/qmldir");
        assertNotNull("dwm/qmldir must ship", qmldir);
        assertTrue("Fluent must be registered as a singleton, or Fluent.<token> will not resolve",
            qmldir.contains("singleton Fluent"));
        for (String type : new String[] {"MenuPanel", "MenuItem", "MenuSeparator"}) {
            assertTrue(type + " must be registered in qmldir", qmldir.contains(type + " 1.0"));
            assertNotNull(type + ".qml must ship", ClasspathResources.readText("dwm/" + type + ".qml"));
        }
    }
}
