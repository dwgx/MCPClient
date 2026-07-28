pragma Singleton
import QtQuick

// Fluent design tokens for the DWM dark theme, in one place. Referenced as Fluent.<name>.
//
// PROVENANCE. Every colour below is now the value WinUI itself ships, read out of
// microsoft/microsoft-ui-xaml release/2.8 dev/CommonStyles/Common_themeresources_any.xaml,
// x:Key="Default" (which is WinUI's name for the DARK dictionary -- not "Dark"). That file is
// the authoritative theme dictionary the public design docs describe but never enumerate, so
// the values here are no longer approximations read off screenshots.
//
// The geometry (8px overlay / 4px control radius, the type ramp, the 40px row) comes from the
// published Fluent specs; the per-control dimensions live with their controls and cite the
// WinUI resource key they came from. See docs/dwm/fluent-spec.md for the full table.

QtObject {
    // ---- geometry (official) ----
    // Top-level containers: windows, flyouts, dialogs, menus.
    property int radiusOverlay: 8
    // In-page elements: buttons, list backplates.
    property int radiusControl: 4
    // A SettingsCard's corner. Between the two above, and its own value rather than either:
    // Windows Settings cards are visibly rounder than the buttons inside them.
    property int radiusCard: 6
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

    // ---- animation (WinUI ControlAnimationDuration values) ----
    // Real durations from Common_themeresources_any.xaml, as milliseconds. WinUI states them as
    // timespans: 00:00:00.083 / .167 / .250. Faster is what every control-state transition uses
    // (knob resize, colour crossfades); Normal is for larger moves.
    //
    // NOTE these are documentation, not usable in a Behavior: qml4j 0.2.24 reads a Behavior's
    // duration off the template before bindings resolve, so a bound duration silently reverts to
    // its 250ms default. Call sites must repeat the number as a literal, and say why.
    property int durationFaster: 83
    property int durationFast: 167
    property int durationNormal: 250

    // ---- text ----
    property string textPrimary: "#ffffff"
    property string textSecondary: "#c5ffffff"
    property string textTertiary: "#87ffffff"
    property string textDisabled: "#5dffffff"

    // ---- surfaces ----
    // Tonal layer only, no blur: real Acrylic needs an offscreen sample of MC's frame every frame.
    //
    // NEARLY OPAQUE (alpha 242), and the reason is arithmetic rather than taste. WinUI's card and
    // control fills are low-alpha whites — CardBackgroundFillColorDefault is #0DFFFFFF, alpha 13 —
    // which assumes they composite over the OPAQUE SolidBackgroundFillColorBase (#202020). On that
    // base a card lands on #2B2B2B, +11 per channel: subtle, but a visible plate. Over a
    // translucent panel sitting on live gameplay the same white is swamped by whatever is behind
    // it: measured on a real client at the previous alpha of 230, a card's interior read #2B2D39
    // against #2C2E3A outside it — a delta of ONE, i.e. no card at all. Every card, expander and
    // group divider was invisible while every metric was correct.
    //
    // So the panel supplies the opaque base the token values were designed against. FULLY opaque,
    // not merely nearly: at alpha 242 the remaining 5% still admits the scene, and measured against
    // bright grass a card's interior and the panel around it both read #333330 -- the +11 delta was
    // real but swamped by injected background. A card that appears over a dark hillside and
    // vanishes over a field is worse than no card. The cost is deliberate and worth stating: the
    // game is no longer visible through the window.
    property string panelFill: "#ff2a2a2a"
    property string solidFill: "#ff202020"

    // ---- strokes ----
    // ControlStrokeColorDefault / Secondary. The pair exists to be used TOGETHER: they are the
    // two stops of the elevation border below, and Secondary alone is not "a slightly brighter
    // stroke" but specifically the lit edge.
    property string panelStroke: "#12ffffff"
    property string controlStrokeSecondary: "#18ffffff"
    // ControlStrongStrokeColorDefault, for a control whose whole outline IS its unfilled state:
    // an empty checkbox, an off toggle track. panelStroke at #12FFFFFF all but vanishes there.
    property string controlStrokeStrong: "#8bffffff"
    // ControlStrongStrokeColorDisabled. Also what a PRESSED empty checkbox uses -- WinUI dims
    // the outline on the way down rather than filling it.
    property string controlStrokeStrongDisabled: "#28ffffff"
    property string divider: "#15ffffff"
    // SurfaceStrokeColorFlyout: the border of a flyout/menu, darker than a control's because it
    // separates the panel from arbitrary content behind it rather than from a known surface.
    property string surfaceStrokeFlyout: "#33000000"

    // ---- control fills (ControlFillColor*) ----
    // The standard button plate and its state chain. Rest is BRIGHTER than pressed: the plate
    // dims as it goes down, which is counter-intuitive and is what Fluent does.
    property string controlFill: "#0fffffff"
    property string controlFillSecondary: "#15ffffff"
    property string controlFillTertiary: "#08ffffff"
    property string controlFillDisabled: "#0bffffff"
    // ControlFillColorInputActive: the background of a FOCUSED text field. Nearly opaque and dark
    // rather than a brighter tint -- text being edited gets a controlled surface instead of
    // whatever shows through the panel.
    property string controlFillInputActive: "#b31e1e1e"

    // ---- card fills (CardBackgroundFillColor*) ----
    // A SettingsCard's plate. Distinct from controlFill despite being close to it: a card is a
    // CONTAINER surface, and WinUI gives containers their own ladder so a control sitting on a
    // card still reads as raised above it.
    property string cardFill: "#0dffffff"
    property string cardFillSecondary: "#08ffffff"
    // CardStrokeColorDefault. Black-based, unlike every control stroke -- a card's edge is a
    // shadow line, not a highlight.
    property string cardStroke: "#19000000"

    // ---- alt control fills (ControlAltFillColor*) ----
    // For controls drawn as a hollow shape over the page: an off toggle track, an unchecked box.
    // Note Secondary is BLACK-based (#19000000) while the rest are white-based -- the rest state
    // is a recess, and the hover/pressed states lift out of it.
    property string controlAltFillSecondary: "#19000000"
    property string controlAltFillTertiary: "#0bffffff"
    property string controlAltFillQuarternary: "#12ffffff"
    property string controlAltFillDisabled: "#00ffffff"

    // ---- subtle fills (SubtleFillColor*) ----
    // Backplates for list rows and menu items, which have no rest fill at all.
    property string subtleHover: "#0fffffff"
    property string subtlePressed: "#0affffff"
    property string subtleTransparent: "#00ffffff"

    // ---- accent ----
    // AccentFillColorDefaultBrush is SystemAccentColorLight2, and the Secondary/Tertiary steps
    // are THE SAME COLOUR at 90% / 80% opacity -- not three separate values. So the state chain
    // for an accent surface is an opacity ramp, and accentOpacity* below are those two numbers.
    property string accent: "#4cc2ff"
    property real accentOpacityHover: 0.9
    property real accentOpacityPressed: 0.8
    // AccentFillColorDisabled: a disabled accent surface is not a faded accent, it drops to a
    // neutral grey entirely.
    property string accentFillDisabled: "#28ffffff"

    // Text drawn ON an accent fill. TextOnAccentFillColorPrimary is #000000 in the dark theme,
    // because there the accent IS the light surface -- the one place in dwm where the usual
    // light-on-dark polarity inverts.
    property string textOnAccent: "#ff000000"
    // TextOnAccentFillColorSecondary, used for a glyph on a PRESSED accent fill.
    property string textOnAccentSecondary: "#80000000"
    property string textOnAccentDisabled: "#87ffffff"
}
