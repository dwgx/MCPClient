import QtQuick
import "."

// A Fluent push button: 4px corners, a subtle plate plus hairline stroke for the standard
// variant, an accent-filled one for the primary variant.
//
// The 32px height is an APPROXIMATION. Microsoft publishes the 40x40 epx interactive target, not
// a button height; WinUI's shipped default draws a shorter control inside that target, and 32 is
// that shape. Do not treat it as a spec value.

Item {
    id: btn

    property string text: ""
    // The filled/primary variant. Opt-in because Fluent wants at most one accent button per
    // view -- making it the default would flatten that hierarchy.
    property bool accent: false
    property bool enabled: true

    signal clicked()

    // Sizes to the measured label. The rowHeight floor is not cosmetic -- the label only reports
    // an implicitWidth after the first measure pass, and the floor keeps the MouseArea below
    // non-zero until then. A zero-sized MouseArea renders perfectly and never receives a click.
    width: Math.max(Fluent.rowHeight, label.implicitWidth + (Fluent.itemPaddingH * 2))
    height: 32

    Rectangle {
        id: face
        // Explicit size, not anchors.fill: an Item root is laid out after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this
        // 0x0 -- which renders nothing AND makes the MouseArea below unhittable.
        x: 0
        y: 0
        width: btn.width
        height: btn.height
        radius: Fluent.radiusControl
        // The plate keeps dimming on the way down: hover brighter than rest, rest brighter than
        // pressed. Counter-intuitive but it is what Fluent does. The standard variant bottoms out
        // at transparent because dwm has no token for WinUI's third subtle fill; the hairline
        // stroke is what keeps the button readable at that step.
        color: !btn.enabled ? Fluent.subtlePressed
             : btn.accent ? Fluent.accent
             : hit.pressed ? "#00000000"
             : hit.containsMouse ? Fluent.subtleHover
             : Fluent.subtlePressed
        // WinUI derives the accent hover/pressed fills from the base accent at 90% / 80%, which
        // is exactly what opacity does -- so the accent state chain costs no extra colour token.
        opacity: !btn.accent ? 1.0
               : !btn.enabled ? 0.4
               : hit.pressed ? 0.8
               : hit.containsMouse ? 0.9
               : 1.0
        // The accent fill carries its own edge; a stroke on top would only muddy it.
        border.width: btn.accent && btn.enabled ? 0 : 1
        border.color: Fluent.panelStroke
    }

    Text {
        id: label
        x: (btn.width - label.implicitWidth) / 2
        y: (btn.height - Fluent.fontBody) / 2 - 2
        text: btn.text
        fontSize: Fluent.fontBody
        color: !btn.enabled ? Fluent.textDisabled
             : btn.accent ? Fluent.textOnAccent
             : Fluent.textPrimary
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
