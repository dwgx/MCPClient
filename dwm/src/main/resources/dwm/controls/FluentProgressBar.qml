import QtQuick
// Registered in dwm/qmldir as controls/<name>.qml, so qml4j hands this document the qmldir's
// PREFIX as its baseDir -- "dwm" -- not the file's own directory. That is why the token singleton
// resolves through "." and not "..". Verified by instantiating all three from a dwm/ document.
import "."

// A Fluent progress bar: a thin bar-shaped track with an accent-filled portion, plus an
// indeterminate mode that slides a short segment across.
//
// Dimensions from microsoft-ui-xaml release/2.8 dev/ProgressBar/ProgressBar_themeresources.xaml.
// This control was the furthest off of the six -- the published "bar-shaped elements use a 4px
// radius" rule was applied to both its thickness and its radius, and neither is 4:
//
//   ProgressBarMinHeight        3     (was 4)
//   ProgressBarCornerRadius     1.5   (was 4 -- exactly half the height, i.e. fully round ends)
//   ProgressBarTrackHeight      1     the unfilled track is THINNER than the fill
//   ProgressBarTrackCornerRadius 0.5
//   ProgressBarBackground       ControlStrongStrokeColorDefault  (was Fluent.divider)
//
// The track being 1px against a 3px fill is the detail that makes the real control read as a
// hairline that thickens where progress has reached, rather than as a groove being filled in.
//
// INDETERMINATE COSTS A REPAINT EVERY FRAME IT RUNS. dwm composites inside MC's frame and skips
// the scene repaint whenever qml4j's global property change counter has not moved (QmlUiSurface's
// composite()). An animation writes a property every tick, so the counter never settles and the
// idle fast path is defeated for as long as the bar spins -- the whole scene re-lays-out and
// re-paints per frame, not just this bar. That is acceptable while something is genuinely
// pending and wasteful otherwise, so `indeterminate` defaults false and callers should clear it
// the moment the work finishes rather than leaving a hidden bar animating.

Item {
    id: bar

    // Normalised progress, 0..1. Ignored while indeterminate, since there is nothing to show.
    property real value: 0.0
    property bool indeterminate: false

    // ProgressBarMinHeight: the height of the FILL, and of the control.
    property int fillHeight: 3
    // ProgressBarTrackHeight: the unfilled remainder, deliberately thinner than the fill.
    property int trackHeight: 1
    // Real numbers, not ints: ProgressBarCornerRadius is 1.5 and rounding it to 2 would over-round
    // a 3px bar into a lozenge, while 1 would square the ends the spec makes round.
    property real fillRadius: 1.5
    property real trackRadius: 0.5

    property real fraction: Math.max(0, Math.min(1, bar.value))
    // A third of the width, which is the proportion Fluent's indeterminate bar reads as.
    property real segmentWidth: bar.width / 3

    width: parent ? parent.width : 200
    height: bar.fillHeight

    // The unfilled track: 1px, vertically centred in the 3px control so the thicker fill grows
    // symmetrically out of it rather than sitting on top of it.
    Rectangle {
        id: track
        // Explicit size, never anchors.fill -- see the trap documented in MenuItem.qml.
        x: 0
        y: (bar.height - bar.trackHeight) / 2
        width: bar.width
        height: bar.trackHeight
        radius: bar.trackRadius
        // ProgressBarBackground is the STRONG stroke colour, not a divider: the unfilled remainder
        // has to stay visible as a hairline, and at #15FFFFFF a 1px line all but disappears.
        color: Fluent.controlStrokeStrong
    }

    // The filled portion and the indeterminate segment, both at full fill height. Clipped by their
    // own container so the sliding segment vanishes at the ends instead of drawing past them.
    Item {
        id: fillArea
        x: 0
        y: 0
        width: bar.width
        height: bar.fillHeight
        clip: true

        Rectangle {
            id: progressFill
            x: 0
            y: 0
            width: bar.indeterminate ? 0 : fillArea.width * bar.fraction
            height: bar.fillHeight
            radius: bar.fillRadius
            color: Fluent.accent
        }

        Rectangle {
            id: segment
            y: 0
            width: bar.indeterminate ? bar.segmentWidth : 0
            height: bar.fillHeight
            radius: bar.fillRadius
            color: Fluent.accent

            // One NumberAnimation rather than the two-bar Fluent choreography: the second bar
            // would double the per-frame write cost for a detail nobody reads at 3px tall.
            // `running` is bound explicitly so the animation is gated on the flag -- qml4j only
            // auto-starts a property value-source animation when the QML did NOT bind running,
            // and an auto-started one would spin forever and hold the scene dirty for good.
            NumberAnimation on x {
                running: bar.indeterminate
                loops: Animation.Infinite
                from: -bar.segmentWidth
                to: bar.width
                duration: 1600
            }
        }
    }
}
