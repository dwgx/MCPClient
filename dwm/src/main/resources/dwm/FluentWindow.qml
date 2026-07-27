import QtQuick
import "."

// A Windows 11 window shell: an 8px rounded surface, a caption bar carrying the title on the left
// and the three caption buttons on the right, and a content region below it that callers fill.
//
// Children go straight into the content region -- FluentWindow { NavigationView { ... } } -- so no
// caller ever adds the caption bar's offset by hand. That region is an explicitly sized Item,
// which is what lets a child bind its own width to parent.width and receive a real box.
//
// Sizing runs content-first: the caller states how much room the CONTENT needs and the shell adds
// its own chrome on top, rather than being handed an outer size and having to subtract chrome it
// cannot see.

Item {
    id: win

    property string title: ""

    // The content region, chrome excluded.
    property int contentWidth: 560
    property int contentHeight: 380

    // Children land in the content region rather than on the root, so nothing a caller writes can
    // end up under the caption bar. Declared here, resolved against the body Item below.
    default property alias content: body.children

    signal closeRequested()

    // ---- geometry ----
    // Fluent's plain caption bar is 32px, but a NavigationView shell reads at the documented 40px
    // interactive row, and that is a token rather than a local number.
    property int titleBarHeight: Fluent.rowHeight
    // Windows draws a 46x32 caption button. The 46 is that value; the height is the whole bar,
    // which is what makes the very corner of the window hittable instead of a dead 8px strip.
    // [approximation, height only]
    property int captionWidth: 46
    // WinUI's close-button hover is SystemFillColorCritical. dwm has no destructive-colour token,
    // so this is local and approximate; it is the one raw colour in the file for that reason.
    // [approximation]
    property string closeHoverFill: "#c42b1c"

    width: win.contentWidth
    height: win.titleBarHeight + win.contentHeight

    Rectangle {
        id: shell
        // Explicit size, not anchors.fill: an Item root is laid out after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this
        // 0x0 -- which renders nothing AND makes every MouseArea below unhittable.
        x: 0
        y: 0
        width: win.width
        height: win.height
        radius: Fluent.radiusOverlay
        color: Fluent.panelFill
        border.width: 1
        border.color: Fluent.panelStroke
    }

    Text {
        x: Fluent.itemPaddingH
        y: (win.titleBarHeight - Fluent.fontBody) / 2 - 2
        text: win.title
        fontSize: Fluent.fontBody
        color: Fluent.textPrimary
    }

    // Inset by the border on both sides so the line stops where the 1px stroke starts instead of
    // crossing it at the rounded corners.
    Rectangle {
        x: 1
        y: win.titleBarHeight
        width: win.width - 2
        height: 1
        color: Fluent.divider
    }

    // ---- caption buttons -------------------------------------------------------------
    //
    // Placed from the right edge inward, so the group stays put when the window resizes.
    //
    // Minimise and maximise are DELIBERATELY INERT: they carry hover feedback and nothing else.
    // dwm composites inside Minecraft's own frame and has no OS window to minimise or maximise, so
    // a signal here would be one no host could implement. They exist because a caption bar missing
    // them does not read as a window. Close is the one that means something.

    Rectangle {
        id: minButton
        x: win.width - (win.captionWidth * 3)
        y: 0
        width: win.captionWidth
        height: win.titleBarHeight
        // Hover brighter than pressed, as everywhere else in dwm: the backplate dims on the way
        // down. Counter-intuitive, but it is what Fluent does.
        color: minHit.pressed ? Fluent.subtlePressed
             : minHit.containsMouse ? Fluent.subtleHover
             : "#00000000"

        Text {
            id: minGlyph
            x: (minButton.width - minGlyph.implicitWidth) / 2
            y: (minButton.height - Fluent.fontCaption) / 2 - 2
            // Box-drawing and geometric characters rather than an icon font: Segoe Fluent Icons
            // cannot be redistributed, so glyphs that render in any font are the portable choice --
            // the same reasoning MenuItem records for its leading glyphs.
            text: "─"
            fontSize: Fluent.fontCaption
            color: Fluent.textSecondary
        }

        MouseArea {
            id: minHit
            x: 0
            y: 0
            width: minButton.width
            height: minButton.height
            hoverEnabled: true
            // No onClicked. The press is still consumed here rather than falling through to the
            // game, which is what a caption bar should do with a click.
        }
    }

    Rectangle {
        id: maxButton
        x: win.width - (win.captionWidth * 2)
        y: 0
        width: win.captionWidth
        height: win.titleBarHeight
        color: maxHit.pressed ? Fluent.subtlePressed
             : maxHit.containsMouse ? Fluent.subtleHover
             : "#00000000"

        Text {
            id: maxGlyph
            x: (maxButton.width - maxGlyph.implicitWidth) / 2
            y: (maxButton.height - Fluent.fontCaption) / 2 - 2
            text: "□"
            fontSize: Fluent.fontCaption
            color: Fluent.textSecondary
        }

        MouseArea {
            id: maxHit
            x: 0
            y: 0
            width: maxButton.width
            height: maxButton.height
            hoverEnabled: true
        }
    }

    Rectangle {
        id: closeButton
        x: win.width - win.captionWidth
        y: 0
        width: win.captionWidth
        height: win.titleBarHeight
        // Only the top-right corner is rounded, and to the WINDOW's radius: this button reaches
        // the shell's corner, and a square plate there would paint red outside the surface.
        topRightRadius: Fluent.radiusOverlay
        color: closeHit.containsMouse ? win.closeHoverFill : "#00000000"
        // The red fill darkens on press the way WinUI derives its pressed variant from the base.
        opacity: closeHit.pressed ? 0.8 : 1.0

        Text {
            id: closeGlyph
            x: (closeButton.width - closeGlyph.implicitWidth) / 2
            y: (closeButton.height - Fluent.fontCaption) / 2 - 2
            text: "✕"
            fontSize: Fluent.fontCaption
            // White over the red plate, secondary over the bare surface.
            color: closeHit.containsMouse ? Fluent.textPrimary : Fluent.textSecondary
        }

        MouseArea {
            id: closeHit
            x: 0
            y: 0
            width: closeButton.width
            height: closeButton.height
            hoverEnabled: true
            onClicked: win.closeRequested()
        }
    }

    // ---- content ---------------------------------------------------------------------

    Item {
        id: body
        x: 0
        y: win.titleBarHeight
        width: win.contentWidth
        height: win.contentHeight
        // Clipped so a content page cannot paint over the shell's rounded bottom corners or its
        // hairline stroke.
        clip: true
    }
}
