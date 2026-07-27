import QtQuick
import "."

// A Fluent check box: 4px-cornered square plus a label, accent-filled when checked.
//
// The 20px box is an APPROXIMATION -- Microsoft publishes the 4px corner radius for in-page
// elements and the 40x40 epx target, but no box size. 20px is what the shipped control's
// proportions give inside that target. The root height and the corner radius are spec values;
// the box side and the checkmark's size are not.

Item {
    id: cb

    property bool checked: false
    property string text: ""
    property bool enabled: true

    signal toggled()

    // ---- approximation ----
    property int boxSize: 20

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
        color: cb.checked ? Fluent.accent
             : hit.containsMouse ? Fluent.subtleHover
             : "#00000000"
        // Hover brighter than pressed. Counter-intuitive but it is what Fluent does: the surface
        // dims as it goes down.
        opacity: !cb.enabled ? 0.4
               : hit.pressed ? 0.8
               : hit.containsMouse ? 0.9
               : 1.0
        // The accent fill carries its own edge, so the outline is only needed when unchecked.
        border.width: cb.checked ? 0 : 1
        border.color: Fluent.controlStrokeStrong
    }

    Text {
        id: check
        x: (cb.boxSize - check.implicitWidth) / 2
        y: box.y + ((cb.boxSize - Fluent.fontCaption) / 2) - 2
        // A plain character rather than an icon font: Segoe Fluent Icons cannot be redistributed,
        // so a glyph that renders in any font is the portable choice -- same reasoning as
        // MenuItem's leading glyphs.
        text: cb.checked ? "✓" : ""
        fontSize: Fluent.fontCaption
        color: cb.enabled ? Fluent.textOnAccent : Fluent.textDisabled
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
