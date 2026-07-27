import QtQuick
// Registered in dwm/qmldir as controls/<name>.qml, so qml4j hands this document the qmldir's
// PREFIX as its baseDir -- "dwm" -- not the file's own directory. That is why the token singleton
// resolves through "." and not "..". Verified by instantiating all three from a dwm/ document.
import "."

// A Fluent horizontal slider: a bar-shaped track, the part left of the thumb accent-filled, and a
// dark thumb with an accent core (the dark-theme form -- on a light theme the disc is the bright
// one).
//
// Dimensions from microsoft-ui-xaml release/2.8 dev/CommonStyles/Slider_themeresources.xaml.
// Three of the four numbers this file used to carry were wrong:
//
//   SliderHorizontalThumbWidth/Height   18   (was 20)
//   SliderTrackThemeHeight               4   (correct)
//   SliderTrackCornerRadius              2   (was Fluent.radiusControl, i.e. 4)
//   SliderInnerThumbWidth/Height        12   base, scaled per state -- see thumb.core
//
// The track radius is the interesting one: the published Fluent geometry page lists bar-shaped
// elements under the 4px rule, and this file cited it. But the shipped resource is 2 -- the 4px
// rule is about the CONTROL's corners, and the inner track is a separate, tighter radius. A
// published rule that sounds like it covers a metric is not the same as the metric.
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

    // SliderHorizontalThumbWidth/Height, SliderTrackThemeHeight, SliderTrackCornerRadius.
    property int thumbSize: 18
    property int trackHeight: 4
    property int trackRadius: 2

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
        radius: slider.trackRadius
        color: Fluent.divider
    }

    Rectangle {
        id: trackFill
        x: slider.thumbSize / 2
        y: trackRest.y
        width: slider.travel * slider.fraction
        height: slider.trackHeight
        radius: slider.trackRadius
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
        // A round control gets CircleElevationBorderBrush, whose stops are 0.70 lit / 0.50 base
        // in RELATIVE (bounding-box) space -- unlike the 3px absolute ramp on rectangular
        // controls. On an 18px disc that ramp is wide enough that a single stroke is
        // indistinguishable from it at this size, so the disc keeps a flat outline and the
        // gradient is not worth a second node inside a moving thumb. Stated rather than silently
        // simplified: this is the one place dwm knowingly flattens an elevation border.
        border.width: 1
        border.color: Fluent.controlStrokeSecondary

        // The core grows on hover and shrinks on press. WinUI does this by SCALING the 12px
        // SliderInnerThumb rather than by giving each state a size: ScaleX/ScaleY are 0.86 at
        // rest, 1.167 on hover, 0.71 pressed (and 1.167 disabled, oddly -- a disabled slider
        // shows the large core, so the shrink is specifically a press affordance).
        //
        // 12 * those factors is 10.3 / 14.0 / 8.5. Rounded to int because a fractional radius on
        // a small disc renders as a soft edge rather than a crisp one; the rounding costs at most
        // half a pixel and buys a clean circle.
        property int core: !slider.enabled ? 14
                         : hit.pressed ? 9
                         : hit.containsMouse ? 14
                         : 10

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
