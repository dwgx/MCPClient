import QtQuick
import "."

// DWM's window shell: the Fluent window frame wrapping the navigation view and its pages.
//
// This is the SHIPPED scene NavigationShellLiveIT drives, deliberately rather than a fixture copy
// of it -- a test that exercises a duplicate proves the duplicate works. Sizing lives in
// FluentWindow and NavigationView, so there are no numbers to keep in step here.

Item {
    id: shell

    // A fallback only. QmlUiSurface.sizeRoot assigns the live viewport in logical units every time
    // the framebuffer extent changes, so these values apply solely to a scene instantiated with no
    // surface behind it.
    width: 640
    height: 500

    FluentWindow {
        id: frame
        objectName: "window"
        // The window's inset from the screen edge, and the same value the maximized extent has to
        // subtract twice -- so it is named rather than repeated.
        property int inset: 20

        x: frame.inset
        y: frame.inset
        title: "DWM"

        // What "maximized" means here: the screen less the inset on each side. Windows maximizes to
        // the work area rather than the raw display -- the desktop keeps room for the taskbar -- and
        // the inset is this scene's equivalent of that reserved margin.
        availableWidth: Math.max(0, shell.width - (frame.inset * 2))
        availableHeight: Math.max(0, shell.height - (frame.inset * 2))

        NavigationView {
            objectName: "nav"
            x: 0
            y: 0
        }
    }
}
