package net.marcloud.mcp.dwm.desktop.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.dwm.theme.MdcTheme.ColorRole;
import net.marcloud.mcp.dwm.theme.MdcTheme.TypeRole;

/**
 * Teeth for the live theme provider: editing the shared {@link ThemeState} changes the
 * colors/type the theme reports, so a settings panel recolors the whole UI live. Pure math,
 * no GL.
 */
public class DesktopThemeTest {

    @Test
    public void accentDrivesPrimaryLive() {
        ThemeState st = new ThemeState();
        DesktopTheme theme = new DesktopTheme(st);
        st.setAccent(0xFF2ED47A);
        assertEquals("primary follows accent", 0xFF2ED47A, theme.color(ColorRole.PRIMARY));
        st.setAccent(0xFFFF4D6D);
        assertEquals("primary updates live on next read", 0xFFFF4D6D, theme.color(ColorRole.PRIMARY));
    }

    @Test
    public void presetSwapChangesSurfaceAndDarkFlag() {
        ThemeState st = new ThemeState();
        DesktopTheme theme = new DesktopTheme(st);
        int darkSurface = theme.color(ColorRole.SURFACE);
        assertTrue("midnight preset is dark", theme.dark());
        st.setPreset(ThemeState.Preset.LIGHT);
        assertEquals("light preset flips dark flag", false, theme.dark());
        assertNotEquals("surface changes on preset swap", darkSurface, theme.color(ColorRole.SURFACE));
    }

    @Test
    public void fontScaleMultipliesEveryTypeSize() {
        ThemeState st = new ThemeState();
        DesktopTheme theme = new DesktopTheme(st);
        float baseBody = theme.typeSizePx(TypeRole.BODY_MEDIUM);
        st.setFontScale(1.5f);
        assertEquals("body size scales 1.5x", baseBody * 1.5f, theme.typeSizePx(TypeRole.BODY_MEDIUM), 0.01f);
        st.setFontScale(0.5f); // clamped to 0.75
        assertEquals("font scale clamped to floor 0.75", baseBody * 0.75f,
                theme.typeSizePx(TypeRole.BODY_MEDIUM), 0.01f);
    }

    @Test
    public void onColorContrastsWithAccentLuminance() {
        ThemeState st = new ThemeState();
        DesktopTheme theme = new DesktopTheme(st);
        st.setAccent(0xFFFFFFFF);  // white accent → dark on-color
        assertEquals("dark text on light accent", 0xFF16161A, theme.color(ColorRole.ON_PRIMARY));
        st.setAccent(0xFF101010);  // near-black accent → light on-color
        assertEquals("light text on dark accent", 0xFFFFFFFF, theme.color(ColorRole.ON_PRIMARY));
    }

    @Test
    public void panelOpacityClampsAndReports() {
        ThemeState st = new ThemeState();
        st.setPanelOpacity(300);
        assertEquals(255, st.panelOpacity());
        st.setPanelOpacity(-5);
        assertEquals(0, st.panelOpacity());
    }
}
