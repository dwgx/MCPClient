import QtQuick
import "."

// DWM's window shell: the Fluent window frame wrapping the navigation view and its pages.
//
// This is the SHIPPED scene NavigationShellLiveIT drives, deliberately rather than a fixture copy
// of it -- a test that exercises a duplicate proves the duplicate works. Sizing lives in
// FluentWindow and NavigationView, so there are no numbers to keep in step here.

Item {
    id: shell

    width: 640
    height: 500

    FluentWindow {
        objectName: "window"
        x: 20
        y: 20
        title: "DWM"

        NavigationView {
            objectName: "nav"
            x: 0
            y: 0
        }
    }
}
