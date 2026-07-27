import QtQuick
import "."

// A Fluent toggle switch: pill track, knob that slides between two rest positions AND resizes
// with pointer state.
//
// EVERY DIMENSION HERE IS NOW A WinUI VALUE, read from microsoft-ui-xaml release/2.8
// dev/CommonStyles/ToggleSwitch_themeresources.xaml. The earlier version of this file was
// approximated off screenshots and was wrong in four places: the track radius (10, actually 7),
// the knob size (a fixed 14, actually three sizes), the travel duration (150ms, actually 83) and
// its easing (OutCubic, actually a 0,0,0,1 spline).
//
// The knob resize is the part that reads as "real": WinUI grows the knob on hover and SQUASHES it
// when pressed -- 17 wide by 14 tall, an ellipse, not a circle. Without it the control still
// works and still looks plausible, which is why the approximation survived.

Item {
    id: sw

    property bool checked: false
    // Optional trailing label. Empty collapses the gap and the switch stands alone.
    property string text: ""
    property bool enabled: true

    signal toggled()

    // ---- geometry, all from ToggleSwitch_themeresources.xaml ----
    property int trackWidth: 40
    property int trackHeight: 20
    // CornerRadius="7" on OuterBorder. NOT trackHeight/2 (=10): WinUI's pill is deliberately
    // less than fully round, and at 10 the track reads as a capsule rather than a switch.
    property int trackRadius: 7

    // The knob's three states, from the SwitchKnobOn/Off keyframes.
    property int knobSize: 12
    property int knobSizeHover: 14
    // Pressed is the only non-square state: 17x14, squashed along the travel axis.
    property int knobPressedWidth: 17
    property int knobPressedHeight: 14

    width: sw.trackWidth + (sw.text === "" ? 0 : Fluent.gutter + label.implicitWidth)
    height: Fluent.rowHeight

    // Live knob metrics. Derived here rather than inline so the knob's x can be expressed
    // against its CURRENT width -- a pressed knob is wider and must still rest inside the track.
    readonly property int knobWidth: !sw.enabled ? sw.knobSize
                                   : hit.pressed ? sw.knobPressedWidth
                                   : hit.containsMouse ? sw.knobSizeHover
                                   : sw.knobSize
    readonly property int knobHeight: !sw.enabled ? sw.knobSize
                                    : hit.pressed ? sw.knobPressedHeight
                                    : hit.containsMouse ? sw.knobSizeHover
                                    : sw.knobSize
    // The rest gap, measured from the 12px knob in a 20px track: (20-12)/2 = 4. Expressed as a
    // margin rather than a stored inset so a resized knob stays centred on the same axis.
    readonly property int knobMargin: (sw.trackHeight - sw.knobSize) / 2

    Rectangle {
        id: track
        // Explicit size, not anchors.fill: an Item root is laid out after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this
        // 0x0 -- which renders nothing AND makes the MouseArea below unhittable.
        x: 0
        y: (sw.height - sw.trackHeight) / 2
        width: sw.trackWidth
        height: sw.trackHeight
        radius: sw.trackRadius

        // ToggleSwitchFillOff*/On* verbatim. The off chain walks the ControlAltFill ladder, whose
        // rest value is BLACK-based (#19000000, a recess) while hover and pressed are white-based
        // -- the track lifts out of the page as you approach it. The on chain is the accent at
        // 100/90/80% (see Fluent.accentOpacity*), which is why only `opacity` changes there.
        color: sw.checked
             ? (!sw.enabled ? Fluent.accentFillDisabled : Fluent.accent)
             : (!sw.enabled ? Fluent.controlAltFillDisabled
               : hit.pressed ? Fluent.controlAltFillQuarternary
               : hit.containsMouse ? Fluent.controlAltFillTertiary
               : Fluent.controlAltFillSecondary)
        // Only the accent surface takes the opacity ramp. Applying it to the whole control (which
        // this did until the WinUI dictionary was read) also faded the STROKE, so a disabled off
        // switch lost its outline -- and the outline is the entire control when it is off.
        opacity: sw.checked && sw.enabled
               ? (hit.pressed ? Fluent.accentOpacityPressed
                 : hit.containsMouse ? Fluent.accentOpacityHover
                 : 1.0)
               : 1.0

        // ToggleSwitchStrokeOff/On: the on state's stroke is the accent itself, so it vanishes
        // into the fill; the off state is carried entirely by the strong stroke.
        border.width: sw.checked ? 0 : 1
        border.color: !sw.enabled ? Fluent.controlStrokeStrongDisabled : Fluent.controlStrokeStrong
    }

    Rectangle {
        id: knob
        // Against the knob's LIVE width, so the pressed (wider) knob does not overhang the track.
        x: sw.checked ? sw.trackWidth - sw.knobWidth - sw.knobMargin : sw.knobMargin
        y: track.y + ((sw.trackHeight - sw.knobHeight) / 2)
        width: sw.knobWidth
        height: sw.knobHeight
        // Fully round at every size, including the squashed pressed state, where half of the
        // SHORTER side is what keeps it an ellipse rather than a rounded rectangle.
        radius: Math.min(sw.knobWidth, sw.knobHeight) / 2
        // ToggleSwitchKnobFillOff/On: secondary text colour when off, on-accent when on. Constant
        // across hover and pressed -- WinUI moves the knob's SIZE, not its colour.
        color: !sw.enabled
             ? (sw.checked ? Fluent.textOnAccentDisabled : Fluent.textDisabled)
             : sw.checked ? Fluent.textOnAccent : Fluent.textSecondary

        // The travel is the whole affordance: without it the knob teleports and the control reads
        // as two unrelated pictures rather than one thing being moved.
        //
        // 83 is ControlFasterAnimationDuration (00:00:00.083), the duration WinUI uses for every
        // one of this control's state transitions -- not the 150 this file used to guess.
        //
        // 2 is OutQuad, read from qml4j's own Easings.apply table (case 2 computes
        // 1-(1-t)^2). It is the closest available curve to WinUI's
        // ControlFastOutSlowInKeySpline, which is 0,0,0,1: a pure decelerate that arrives with
        // zero slope. Do NOT use 3 -- that is InOutQuad, which accelerates out of the start and
        // gives the knob a slow-fast-slow motion the real control does not have.
        //
        // Both values are LITERALS on purpose, and qml4j 0.2.24 is why:
        //  - Behavior reads duration ONCE, off its template, before bindings resolve. A bound
        //    duration (`Fluent.durationFaster`, or any expression) silently reverts to 250ms.
        //  - easing.type does not resolve the Easing enum here either; `Easing.OutQuad` leaves
        //    easingType at 0, i.e. Linear.
        Behavior on x {
            NumberAnimation {
                duration: 83
                easing.type: 2
            }
        }
        // The resize runs on the same clock as the travel. Squashing instantly while the knob
        // glides would read as two separate effects rather than one object being pushed.
        Behavior on width {
            NumberAnimation {
                duration: 83
                easing.type: 2
            }
        }
        Behavior on height {
            NumberAnimation {
                duration: 83
                easing.type: 2
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
