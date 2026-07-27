import QtQuick
import "."

// A Fluent toggle switch: pill track, circular knob that slides between two rest positions.
//
// EVERY DIMENSION IN THIS FILE IS AN APPROXIMATION. Microsoft publishes no pixel geometry for
// ToggleSwitch anywhere in the Fluent or WinUI documentation -- verified against the official
// docs, not assumed. The 40x20 track, its 10px radius, the 14px knob, the 3px inset and the
// 150ms travel are all read off the shipped control's proportions. Only the 40px root height
// (the documented 40x40 epx interactive target) and the type ramp size are spec values.
//
// The geometry is exposed as properties so a caller can rescale the pill in one place, and so
// nobody mistakes them for values a test could pin to a published source. The animation numbers
// are the exception and have to stay literals -- see the Behavior below.

Item {
    id: sw

    property bool checked: false
    // Optional trailing label. Empty collapses the gap and the switch stands alone.
    property string text: ""
    property bool enabled: true

    signal toggled()

    // ---- approximations ----
    property int trackWidth: 40
    property int trackHeight: 20
    property int knobSize: 14
    // Gap between knob and track edge at either rest position.
    property int knobInset: 3

    width: sw.trackWidth + (sw.text === "" ? 0 : Fluent.gutter + label.implicitWidth)
    height: Fluent.rowHeight

    Rectangle {
        id: track
        // Explicit size, not anchors.fill: an Item root is laid out after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this
        // 0x0 -- which renders nothing AND makes the MouseArea below unhittable.
        x: 0
        y: (sw.height - sw.trackHeight) / 2
        width: sw.trackWidth
        height: sw.trackHeight
        radius: sw.trackHeight / 2
        color: sw.checked ? Fluent.accent
             : hit.containsMouse ? Fluent.subtleHover
             : "#00000000"
        // Hover brighter than pressed, in both states. Counter-intuitive but it is what Fluent
        // does: the surface dims as it goes down.
        opacity: !sw.enabled ? 0.4
               : hit.pressed ? 0.8
               : hit.containsMouse ? 0.9
               : 1.0
        // The accent fill carries its own edge, so the outline is only needed when off.
        border.width: sw.checked ? 0 : 1
        border.color: Fluent.controlStrokeStrong
    }

    Rectangle {
        id: knob
        x: sw.checked ? sw.trackWidth - sw.knobSize - sw.knobInset : sw.knobInset
        y: track.y + ((sw.trackHeight - sw.knobSize) / 2)
        width: sw.knobSize
        height: sw.knobSize
        radius: sw.knobSize / 2
        color: !sw.enabled ? Fluent.textDisabled
             : sw.checked ? Fluent.textOnAccent
             : Fluent.textSecondary

        // The travel is the whole affordance: without it the knob teleports and the control reads
        // as two unrelated pictures rather than one thing being moved. ~150ms is an approximation
        // too -- Microsoft documents no duration for this control.
        //
        // Both values are LITERALS on purpose, and qml4j 0.2.24 is why:
        //  - Behavior reads duration ONCE, off its template, before bindings resolve. A bound
        //    duration (`sw.travelMs`, or any expression) silently reverts to the 250ms default.
        //  - easing.type does not resolve the Easing enum here either; `Easing.OutCubic` leaves
        //    easingType at 0, i.e. Linear. 6 IS Easing.OutCubic, read from qml4j's own table.
        // Measured both ways against a real scene: with the literals the knob eases across in
        // ~150ms, with the named forms it slides linearly over 250ms.
        Behavior on x {
            NumberAnimation {
                duration: 150
                easing.type: 6
            }
        }
    }

    Text {
        id: label
        x: sw.trackWidth + Fluent.gutter
        y: (sw.height - Fluent.fontBody) / 2 - 2
        text: sw.text
        fontSize: Fluent.fontBody
        color: sw.enabled ? Fluent.textPrimary : Fluent.textDisabled
    }

    MouseArea {
        id: hit
        x: 0
        y: 0
        // Spans the label too: the label is part of the control's target, not decoration beside it.
        width: sw.width
        height: sw.height
        hoverEnabled: true
        onClicked: {
            if (!sw.enabled) {
                return
            }
            sw.checked = !sw.checked
            sw.toggled()
        }
    }
}
