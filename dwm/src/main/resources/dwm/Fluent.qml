pragma Singleton
import QtQuick

// Fluent design tokens for the DWM dark theme, in one place. Referenced as Fluent.<name>.
//
// Provenance is documented in docs/dwm/fluent-spec.md, and the distinction matters when
// editing: the radii (8px overlay / 4px control), the type ramp sizes and the 40px row height
// are from Microsoft's published specs, while the dark colour values other than textPrimary
// (#FFFFFF, which is documented) are approximations — WinUI's authoritative values live in
// the theme dictionaries shipped inside the Windows App SDK, not in public docs.

QtObject {
    // ---- geometry (official) ----
    // Top-level containers: windows, flyouts, dialogs, menus.
    property int radiusOverlay: 8
    // In-page elements: buttons, list backplates.
    property int radiusControl: 4
    // Fluent Standard aligns interactive items to a 40x40 epx target.
    property int rowHeight: 40

    // ---- spacing ----
    // 4px panel padding is what nests a 4px backplate inside an 8px panel cleanly.
    property int panelPadding: 4
    property int itemPaddingH: 12
    property int gutter: 8

    // ---- type ramp (official sizes) ----
    property int fontCaption: 12
    property int fontBody: 14
    property int fontBodyLarge: 18
    property int fontSubtitle: 20
    property int fontTitle: 28

    // ---- text ----
    property string textPrimary: "#ffffff"
    property string textSecondary: "#c5ffffff"
    property string textTertiary: "#87ffffff"
    property string textDisabled: "#5dffffff"

    // ---- surfaces ----
    // Tonal layer only, no blur: real Acrylic needs an offscreen sample of MC's frame every
    // frame, and keeping the game legible behind the menu is desirable anyway.
    property string panelFill: "#e62c2c2c"
    property string panelStroke: "#12ffffff"
    property string solidFill: "#ff202020"

    // Hover is BRIGHTER than pressed. Counter-intuitive, but it is what Fluent does — the
    // backplate dims as it goes down.
    property string subtleHover: "#0fffffff"
    property string subtlePressed: "#0affffff"

    property string divider: "#15ffffff"
    property string accent: "#4cc2ff"
}
