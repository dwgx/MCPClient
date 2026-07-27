import QtQuick
import "."

// A Fluent check box: 4px-cornered square plus a label, accent-filled when checked.
//
// Both dimensions are now confirmed WinUI values rather than the approximations this file used to
// claim, from microsoft-ui-xaml release/2.8 dev/CommonStyles/CheckBox_themeresources.xaml:
// CheckBoxSize 20, CheckBoxGlyphSize 12, CheckBoxBorderThickness 1. The 20px box and the 12px
// glyph were guessed correctly; the colours were not.
//
// The state chain (x:Key="Default", the dark dictionary):
//
//   unchecked fill    ControlAltFillColorSecondary   #19000000  <- black-based: a recess
//     hover           ControlAltFillColorTertiary    #0BFFFFFF
//     pressed         ControlAltFillColorQuarternary #12FFFFFF
//   unchecked stroke  ControlStrongStrokeColorDefault  #8BFFFFFF
//     PRESSED stroke  ControlStrongStrokeColorDisabled #28FFFFFF  <- dims, not brightens
//   checked fill      AccentFillColorDefault, then 90% / 80% opacity
//   glyph             TextOnAccentFillColorPrimary, Secondary when pressed
//
// Note the unchecked box is FILLED at rest, faintly and with black. Drawing it as a bare outline
// over the page (which this did) loses the recessed look the real control has.

Item {
    id: cb

    property bool checked: false
    property string text: ""
    property bool enabled: true

    signal toggled()

    // CheckBoxSize / CheckBoxGlyphSize.
    property int boxSize: 20
    property int glyphSize: 12

    width: cb.boxSize + (cb.text === "" ? 0 : Fluent.gutter + label.implicitWidth)
    height: Fluent.rowHeight

    Rectangle {
        id: box
        // Explicit size, not anchors.fill: an Item root is laid out after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this
        // 0x0 -- which renders nothing AND makes the MouseArea below unhittable.
        x: 0
        y: (cb.height - cb.boxSize) / 2
        width: cb.boxSize
        height: cb.boxSize
        radius: Fluent.radiusControl
        color: cb.checked
             ? (!cb.enabled ? Fluent.accentFillDisabled : Fluent.accent)
             : (!cb.enabled ? Fluent.controlAltFillDisabled
               : hit.pressed ? Fluent.controlAltFillQuarternary
               : hit.containsMouse ? Fluent.controlAltFillTertiary
               : Fluent.controlAltFillSecondary)
        // The opacity ramp applies to the ACCENT fill only. Fading the whole control (which this
        // did) also faded the stroke, and when unchecked the stroke IS the control.
        opacity: cb.checked && cb.enabled
               ? (hit.pressed ? Fluent.accentOpacityPressed
                 : hit.containsMouse ? Fluent.accentOpacityHover
                 : 1.0)
               : 1.0
        // The accent fill carries its own edge, so the outline is only needed when unchecked.
        border.width: cb.checked ? 0 : 1
        // Pressed uses the DISABLED strong stroke: WinUI dims an unchecked box's outline as it
        // goes down rather than brightening it, matching the plate behaviour elsewhere.
        border.color: !cb.enabled || hit.pressed
                    ? Fluent.controlStrokeStrongDisabled
                    : Fluent.controlStrokeStrong
    }

    Text {
        id: check
        x: (cb.boxSize - check.implicitWidth) / 2
        y: box.y + ((cb.boxSize - cb.glyphSize) / 2) - 2
        // A plain character rather than an icon font: Segoe Fluent Icons cannot be redistributed,
        // so a glyph that renders in any font is the portable choice -- same reasoning as
        // MenuItem's leading glyphs.
        text: cb.checked ? "✓" : ""
        fontSize: cb.glyphSize
        // CheckBoxCheckGlyphForegroundCheckedPressed is the SECONDARY on-accent colour, so the
        // mark fades with the plate under the pointer instead of staying full black.
        color: !cb.enabled ? Fluent.textOnAccentDisabled
             : hit.pressed ? Fluent.textOnAccentSecondary
             : Fluent.textOnAccent
    }

    Text {
        id: label
        x: cb.boxSize + Fluent.gutter
        y: (cb.height - Fluent.fontBody) / 2 - 2
        text: cb.text
        fontSize: Fluent.fontBody
        color: cb.enabled ? Fluent.textPrimary : Fluent.textDisabled
    }

    MouseArea {
        id: hit
        x: 0
        y: 0
        // Spans the label too: the label is part of the control's target, not decoration beside it.
        width: cb.width
        height: cb.height
        hoverEnabled: true
        onClicked: {
            if (!cb.enabled) {
                return
            }
            cb.checked = !cb.checked
            cb.toggled()
        }
    }
}
