import QtQuick
// Registered in dwm/qmldir as controls/<name>.qml, so qml4j hands this document the qmldir's
// PREFIX as its baseDir -- "dwm" -- not the file's own directory. That is why the token singleton
// resolves through "." and not "..". Verified by instantiating all three from a dwm/ document.
import "."

// A Fluent progress bar: a 4px bar-shaped track with an accent-filled portion, plus an
// indeterminate mode that slides a short segment across.
//
// 4px is the published radius for bar-shaped elements (ProgressBar / ScrollBar / Slider), so it
// is Fluent.radiusControl. The 4px HEIGHT is an approximation -- the spec pins the radius, not
// the thickness -- but a 4px-tall bar with a 4px radius gives the fully rounded ends Fluent shows.
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

    // Approximation, as above.
    property int trackHeight: 4

    property real fraction: Math.max(0, Math.min(1, bar.value))
    // A third of the width, which is the proportion Fluent's indeterminate bar reads as.
    property real segmentWidth: bar.width / 3

    width: parent ? parent.width : 200
    height: bar.trackHeight

    Rectangle {
        id: track
        // Explicit size, never anchors.fill -- see the trap documented in MenuItem.qml.
        x: 0
        y: 0
        width: bar.width
        height: bar.height
        radius: Fluent.radiusControl
        color: Fluent.divider
        // Clip so the sliding segment disappears at the ends instead of drawing past them.
        clip: true

        Rectangle {
            id: progressFill
            x: 0
            y: 0
            width: bar.indeterminate ? 0 : track.width * bar.fraction
            height: track.height
            radius: Fluent.radiusControl
            color: Fluent.accent
        }

        Rectangle {
            id: segment
            y: 0
            width: bar.indeterminate ? bar.segmentWidth : 0
            height: track.height
            radius: Fluent.radiusControl
            color: Fluent.accent

            // One NumberAnimation rather than the two-bar Fluent choreography: the second bar
            // would double the per-frame write cost for a detail nobody reads at 4px tall.
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
