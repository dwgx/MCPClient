import QtQuick
import "."

// A Fluent push button: 4px corners, an elevation border, a plate that dims on the way down, and
// an accent-filled primary variant.
//
// Colours are WinUI's own, from microsoft-ui-xaml release/2.8
// dev/CommonStyles/Button_themeresources.xaml (x:Key="Default", the dark dictionary):
//
//   ButtonBackground            ControlFillColorDefault     #0FFFFFFF
//   ButtonBackgroundPointerOver ControlFillColorSecondary   #15FFFFFF
//   ButtonBackgroundPressed     ControlFillColorTertiary    #08FFFFFF
//   ButtonBackgroundDisabled    ControlFillColorDisabled    #0BFFFFFF
//   ButtonForegroundPressed     TextFillColorSecondary   -- the LABEL dims too
//   ButtonBorderBrush           ControlElevationBorderBrush -- see FluentElevation
//
// The rest plate is BRIGHTER than the pressed one. Counter-intuitive, and it is what Fluent does:
// the surface recedes as it goes down. An earlier version of this file dropped the pressed plate
// to fully transparent with a comment claiming dwm had no token for it -- ControlFillColorTertiary
// is that token, and it was in the theme dictionary all along.
//
// The 32px height and the 11,5,11,6 padding are WinUI's ButtonPadding and its shipped default
// height inside the documented 40x40 epx target.

Item {
    id: btn

    property string text: ""
    // The filled/primary variant. Opt-in because Fluent wants at most one accent button per
    // view -- making it the default would flatten that hierarchy.
    property bool accent: false
    property bool enabled: true

    signal clicked()

    // ButtonPadding is 11,5,11,6 -- asymmetric vertically, which is why the label sits one pixel
    // above centre rather than on it.
    property int paddingH: 11

    // Sizes to the measured label. The rowHeight floor is not cosmetic -- the label only reports
    // an implicitWidth after the first measure pass, and the floor keeps the MouseArea below
    // non-zero until then. A zero-sized MouseArea renders perfectly and never receives a click.
    width: Math.max(Fluent.rowHeight, label.implicitWidth + (btn.paddingH * 2))
    height: 32

    // The outline, under everything. For the accent variant WinUI uses
    // AccentControlElevationBorderBrush, which is the same 3px ramp flipped upside down (its
    // RelativeTransform scales Y by -1) so the lit edge sits at the BOTTOM.
    FluentElevation {
        id: outline
        x: 0
        y: 0
        width: btn.width
        height: btn.height
        radius: Fluent.radiusControl
        litColor: btn.accent ? Fluent.panelStroke : Fluent.controlStrokeSecondary
        baseColor: btn.accent ? Fluent.controlStrokeSecondary : Fluent.panelStroke
    }

    Rectangle {
        id: face
        // Inset by the outline's width on all sides, which is what leaves the elevation border
        // showing as a 1px edge. Explicit geometry, not anchors.fill -- an Item root is laid out
        // after its children are constructed, so a fill anchor resolves against a still-zero
        // parent and leaves this 0x0, which renders nothing AND makes the MouseArea unhittable.
        x: outline.borderWidth
        y: outline.borderWidth
        width: btn.width - (outline.borderWidth * 2)
        height: btn.height - (outline.borderWidth * 2)
        // One less than the outline's, so the inner corner sits concentric inside it rather than
        // leaving a thicker border at the corners than along the edges.
        radius: Fluent.radiusControl - outline.borderWidth

        color: btn.accent
             ? (!btn.enabled ? Fluent.accentFillDisabled : Fluent.accent)
             : (!btn.enabled ? Fluent.controlFillDisabled
               : hit.pressed ? Fluent.controlFillTertiary
               : hit.containsMouse ? Fluent.controlFillSecondary
               : Fluent.controlFill)

        // AccentFillColorSecondary/Tertiary are the base accent at 90%/80% opacity, not separate
        // colours -- so the accent state chain costs no extra token. Applied to the FACE only:
        // fading the whole control would fade the elevation border with it.
        opacity: !btn.accent || !btn.enabled ? 1.0
               : hit.pressed ? Fluent.accentOpacityPressed
               : hit.containsMouse ? Fluent.accentOpacityHover
               : 1.0
    }

    Text {
        id: label
        x: (btn.width - label.implicitWidth) / 2
        y: (btn.height - Fluent.fontBody) / 2 - 2
        text: btn.text
        fontSize: Fluent.fontBody
        // ButtonForegroundPressed drops to the SECONDARY text colour: the label dims along with
        // the plate, so a pressed button recedes as a whole rather than keeping bright text on a
        // darkened background.
        color: !btn.enabled
             ? (btn.accent ? Fluent.textOnAccentDisabled : Fluent.textDisabled)
             : btn.accent
               ? (hit.pressed ? Fluent.textOnAccentSecondary : Fluent.textOnAccent)
               : (hit.pressed ? Fluent.textSecondary : Fluent.textPrimary)
    }

    MouseArea {
        id: hit
        x: 0
        y: 0
        width: btn.width
        height: btn.height
        hoverEnabled: true
        onClicked: if (btn.enabled) btn.clicked()
    }
}
