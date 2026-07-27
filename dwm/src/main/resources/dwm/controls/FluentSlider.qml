import QtQuick
// Registered in dwm/qmldir as controls/<name>.qml, so qml4j hands this document the qmldir's
// PREFIX as its baseDir -- "dwm" -- not the file's own directory. That is why the token singleton
// resolves through "." and not "..". Verified by instantiating all three from a dwm/ document.
import "."

// A Fluent horizontal slider: a 4px bar-shaped track, the part left of the thumb accent-filled,
// and a dark thumb with an accent core (the dark-theme form -- on a light theme the disc is the
// bright one).
//
// The 4px track radius is the published value for bar-shaped elements (ProgressBar / ScrollBar /
// Slider), so it is Fluent.radiusControl rather than a local number. The row is Fluent.rowHeight
// because the whole control is the touch target, not just the 4px bar.
//
// Dragging is real, not click-to-position: qml4j's MouseArea does emit positionChanged while a
// press is captured (EventDispatcher.dispatchPointerMove forwards to the captured area), and
// dwm's host feeds it -- QmlGuiScreen calls pointerMove for every LWJGL move event, which is what
// a button-held drag produces. Verified against qml4j 0.2.24, the version this module pins.

Item {
    id: slider

    // Normalised position, 0..1. Callers map it to their own range; a from/to pair would be a
    // second thing to keep consistent for no gain here.
    property real value: 0.0
    property bool enabled: true

    signal moved()

    // Both approximations. Microsoft publishes the 4px bar RADIUS, not the bar height or the
    // thumb diameter; 20/4 is what reads correctly next to a 40px row.
    property int thumbSize: 20
    property int trackHeight: 4

    // The track is inset by the thumb's radius at each end so the thumb never overhangs the
    // control, which makes the usable travel shorter than the width.
    property real travel: Math.max(0, slider.width - slider.thumbSize)
    property real fraction: Math.max(0, Math.min(1, slider.value))

    width: parent ? parent.width : 200
    height: Fluent.rowHeight

    // Position from a pointer x in slider coordinates. Guarded on travel because a control that
    // has not been laid out yet would divide by zero and write NaN into value.
    function setFromX(mx) {
        if (slider.travel > 0) {
            var next = Math.max(0, Math.min(1, (mx - slider.thumbSize / 2) / slider.travel));
            if (next !== slider.value) {
                slider.value = next;
                slider.moved();
            }
        }
    }

    Rectangle {
        id: trackRest
        // Explicit size, never anchors.fill -- see the trap documented in MenuItem.qml.
        x: slider.thumbSize / 2
        y: (slider.height - slider.trackHeight) / 2
        width: slider.travel
        height: slider.trackHeight
        radius: Fluent.radiusControl
        color: Fluent.divider
    }

    Rectangle {
        id: trackFill
        x: slider.thumbSize / 2
        y: trackRest.y
        width: slider.travel * slider.fraction
        height: slider.trackHeight
        radius: Fluent.radiusControl
        color: slider.enabled ? Fluent.accent : Fluent.textDisabled
    }

    Rectangle {
        id: thumb
        x: slider.travel * slider.fraction
        y: (slider.height - slider.thumbSize) / 2
        width: slider.thumbSize
        height: slider.thumbSize
        radius: slider.thumbSize / 2
        color: Fluent.solidFill
        border.width: 1
        border.color: Fluent.panelStroke

        // The core grows on hover and shrinks on press -- the same "dims/contracts on the way
        // down" behaviour as the menu backplate, expressed as size instead of alpha. Plain
        // conditional bindings rather than a Behavior: a transition here would repaint the scene
        // for its whole duration, and the compositor's idle fast path is worth more than the ease.
        property int core: hit.pressed ? 10 : hit.containsMouse ? 16 : 12

        Rectangle {
            x: (thumb.width - thumb.core) / 2
            y: (thumb.height - thumb.core) / 2
            width: thumb.core
            height: thumb.core
            radius: thumb.core / 2
            color: slider.enabled ? Fluent.accent : Fluent.textDisabled
        }
    }

    MouseArea {
        id: hit
        // Covers the whole control, including the row's slack above and below the 4px bar: a
        // hit box the size of the bar would be a 4px target. Non-zero by construction, which
        // CompositorLiveIT enforces across the scene.
        x: 0
        y: 0
        width: slider.width
        height: slider.height
        hoverEnabled: true
        // Press jumps to the pointer, then the drag tracks it. Both are guarded on enabled here
        // rather than by disabling the area, so a disabled slider still reads as one control
        // instead of letting presses fall through to whatever is behind it.
        onPressed: (mouse) => { if (slider.enabled) slider.setFromX(mouse.x) }
        onPositionChanged: (mouse) => { if (slider.enabled) slider.setFromX(mouse.x) }
    }
}
