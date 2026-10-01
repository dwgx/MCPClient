import QtQuick
import "."

// A Windows 11 window shell: an 8px rounded surface, a caption bar carrying the title on the left
// and the caption buttons on the right, and a content region below it that callers fill.
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

    // ---- window state ----------------------------------------------------------------
    //
    // Windows models these as WM_SYSCOMMAND verbs (SC_MAXIMIZE / SC_RESTORE / SC_CLOSE) that the
    // non-client area SENDS and the window manager carries out. The frame does not implement them
    // itself, and that division is worth copying: this file decides what each button MEANS and
    // asks; something above it decides what happens to the window.
    //
    // "normal" and "maximized" are the two states a caption bar can produce here. There is no
    // minimize. It was a third button, and the only answer it could be given -- dismiss the screen
    // -- is what the close button already does, because a game has no taskbar to restore a hidden
    // window from. Its old implementation kept the Skia surface alive for a reopen that could never
    // happen, which stranded a whole DirectContext; see UiWindowHost for the full account.
    property string windowState: "normal"
    readonly property bool isMaximized: win.windowState === "maximized"

    /**
     * Maximize/restore, i.e. SC_MAXIMIZE and SC_RESTORE.
     *
     * <p>Handled locally because the geometry is ours: the shell fills the available area and, on
     * restore, returns to the size the caller asked for. That is what "restore" means in
     * Windows too — WINDOWPLACEMENT keeps the normal-state rect and restore puts it back — so the
     * pre-maximize extent is remembered rather than recomputed.
     */
    property int availableWidth: parent ? parent.width : win.contentWidth
    property int availableHeight: parent ? parent.height : (win.titleBarHeight + win.contentHeight)

    // The normal-state extent, captured on the way into maximized. WINDOWPLACEMENT's rcNormalPosition.
    property int restoreWidth: 0
    property int restoreHeight: 0

    function toggleMaximize() {
        if (win.isMaximized) {
            // SC_RESTORE.
            if (win.restoreWidth > 0) {
                win.contentWidth = win.restoreWidth;
                win.contentHeight = win.restoreHeight;
            }
            win.windowState = "normal";
        } else {
            // SC_MAXIMIZE. Remember the normal extent FIRST, or restore has nothing to go back to.
            win.restoreWidth = win.contentWidth;
            win.restoreHeight = win.contentHeight;
            win.contentWidth = win.availableWidth;
            win.contentHeight = Math.max(0, win.availableHeight - win.titleBarHeight);
            win.windowState = "maximized";
        }
    }

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
        // Square when maximized. Windows does this too, and it is not decoration: a rounded corner
        // on a window flush with the screen edge would show the desktop through the arc. The
        // published geometry guidance states 0px "where the window is snapped or maximized".
        radius: win.isMaximized ? 0 : Fluent.radiusOverlay
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
    // BOTH ACT, and that is the standard this file is held to. An earlier version shipped all of
    // them inert on the grounds that dwm has no OS window to act on -- wrong of maximize, since the
    // shell owns its own geometry, and no longer applicable to minimize, which is gone rather than
    // shipped as a second verb that could only do what close already does. A caption button that
    // does nothing is the same lie as an inert switch; see UiWindowHost.
    //
    // The division copied from Windows: the caption bar SENDS a verb and does not implement policy.
    // Maximize is geometry the shell owns, so it is handled here. Close changes what the HOST does
    // with the screen, so it goes through the host.

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
            // The glyph CHANGES with state, as Windows' does: a maximized window offers restore,
            // not maximize. Missing that is the usual tell of a hand-built caption bar -- the
            // button keeps claiming an action it no longer performs.
            text: win.isMaximized ? "❐" : "□"
            fontSize: Fluent.fontCaption
            color: Fluent.textSecondary
        }

        MouseArea {
            id: maxHit
            // Named because what the panel publishes is THIS MouseArea, not the plate above it.
            // Left anonymous it resolved to the window's own name, so the two caption buttons
            // were published as two rows both called "window" and neither could be told from the
            // other. Measured on a live client, on the two-button caption bar.
            objectName: "windowMaximize"
            x: 0
            y: 0
            width: maxButton.width
            height: maxButton.height
            hoverEnabled: true
            // SC_MAXIMIZE / SC_RESTORE, resolved by the shell because the geometry is its own.
            onClicked: win.toggleMaximize()
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
        // Follows the shell to 0 when maximized, or the red would round inside a square corner.
        // Independent per-corner radii paint as of qml4j 0.2.27 (upstream PR #15). Before that
        // the property compiled and the plate stayed square — a silent no-op, not a load error.
        topRightRadius: win.isMaximized ? 0 : Fluent.radiusOverlay
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
            // Same reason as maxHit, and the pair is why the rule is a pair: a window with one
            // caption button could have left both anonymous.
            objectName: "windowClose"
            id: closeHit
            x: 0
            y: 0
            width: closeButton.width
            height: closeButton.height
            hoverEnabled: true
            // SC_CLOSE, direct to the host: the caption bar states the intent and the host decides
            // what closing this screen means.
            onClicked: WindowHost.close()
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
