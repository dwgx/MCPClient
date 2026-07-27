import QtQuick
import "."

// The Fluent elevation border: a control's outline, brighter along its TOP edge.
//
// This is WinUI's ControlElevationBorderBrush, from microsoft-ui-xaml release/2.8
// dev/CommonStyles/Common_themeresources_any.xaml:
//
//   <LinearGradientBrush x:Key="ControlElevationBorderBrush"
//                        MappingMode="Absolute" StartPoint="0,0" EndPoint="0,3">
//     <GradientStop Offset="0.33" Color="ControlStrokeColorSecondary"/>  #18FFFFFF
//     <GradientStop Offset="1.0"  Color="ControlStrokeColorDefault"/>    #12FFFFFF
//   </LinearGradientBrush>
//
// Two details decide whether this reads correctly, and both are easy to lose:
//
//  * MappingMode="Absolute" with EndPoint 0,3 means the ramp spans THREE PIXELS, not the
//    control's height. Past y=3 the brush holds its end colour, so the effect is a lit 1px top
//    edge over a uniform darker outline -- not a control that fades out top to bottom.
//  * It is a BORDER brush, so it paints the outline only. qml4j cannot express that directly:
//    Border.color takes a single colour string and a Gradient can only fill. So this draws the
//    gradient as a filled rectangle and lets the caller lay an opaque face over its middle,
//    leaving `width` of it showing as the outline.
//
// Usage -- the face must be inset by borderWidth on all sides:
//
//   FluentElevation { x: 0; y: 0; width: btn.width; height: btn.height; radius: 4 }
//   Rectangle { x: 1; y: 1; width: btn.width - 2; height: btn.height - 2; radius: 3 }
//
// A caller whose face is translucent will show the gradient through it. That is why the standard
// button composites its plate ON TOP of this rather than relying on it as a background.

Item {
    id: elevation

    // The outline thickness. 1 everywhere in WinUI's control styles; exposed because the caller
    // has to inset its face by exactly this much and should read the number from one place.
    property int borderWidth: 1
    property int radius: Fluent.radiusControl

    // The two stops. Overridable so the accent variant can pass its own pair -- WinUI has a
    // separate AccentControlElevationBorderBrush that is the same shape with the ramp flipped.
    property string litColor: Fluent.controlStrokeSecondary
    property string baseColor: Fluent.panelStroke

    // Where the ramp ends. From EndPoint="0,3" under MappingMode="Absolute".
    //
    // A LOGICAL 3, deliberately, and worth stating because the obvious reading is wrong:
    // "Absolute" sounds like device pixels, but the documented meaning is "not relative to a
    // bounding box -- values are interpreted directly in local space", i.e. the element's own
    // DIP coordinate space. So 3 here scales with the display exactly as WinUI's does, and
    // dividing by the DPI scale (which this file briefly did) would make the band too thin on a
    // Retina panel rather than correcting it.
    property int rampHeight: 3

    Rectangle {
        id: ramp
        // Explicit geometry, never anchors.fill: an Item root is sized after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this 0x0.
        x: 0
        y: 0
        width: elevation.width
        height: elevation.height
        radius: elevation.radius
        // The end colour, held below the ramp. Drawn as the base layer so the gradient strip
        // above only has to cover the first few pixels.
        color: elevation.baseColor
    }

    // The ramp itself, clipped to the top rampHeight pixels by being that tall.
    //
    // Two rectangles rather than one gradient over the full height, because the gradient's stops
    // are positions within ITS OWN box: a full-height gradient would stretch a 3px ramp across
    // the whole control and wash the outline out. Height is min'd with the control so a control
    // shorter than the ramp (there are none today, but a 2px separator would be) cannot overflow.
    Rectangle {
        x: 0
        y: 0
        width: elevation.width
        height: Math.min(elevation.rampHeight, elevation.height)
        // Only the top corners are round: the bottom of this strip is an interior edge, and
        // rounding it would cut a notch out of the lit band.
        topLeftRadius: elevation.radius
        topRightRadius: elevation.radius
        bottomLeftRadius: 0
        bottomRightRadius: 0
        // Vertical by default in qml4j 0.2.24, measured: a two-stop gradient runs top to bottom
        // and is constant horizontally.
        //
        // Offset 0.33 in a 3px box is pixel 1 -- the second row. Stated as 0.33 rather than
        // rounded to 0 so the value stays traceable to the WinUI stop it came from.
        gradient: Gradient {
            GradientStop { position: 0.0; color: elevation.litColor }
            GradientStop { position: 0.33; color: elevation.litColor }
            GradientStop { position: 1.0; color: elevation.baseColor }
        }
    }
}
