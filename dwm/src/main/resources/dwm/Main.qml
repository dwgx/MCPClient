import QtQuick
import "."

// DWM's menu, in the Windows 11 Fluent idiom — the NT metaphor the project uses throughout
// (core = NT kernel, board = PCB, compat = AppCompat, dwm = Desktop Window Manager).
//
// Metrics and colours come from dwm/Fluent.qml; see docs/dwm/fluent-spec.md for which of them
// are Microsoft's published values and which are approximations.

Item {
    id: root

    MenuPanel {
        objectName: "menuPanel"
        x: 48
        y: 48
        width: 320
        title: "DWM"

        MenuItem {
            objectName: "menuKernelState"
            glyph: "▦"
            label: "Kernel state"
            shortcut: "F6"
        }

        MenuItem {
            objectName: "menuBoardChips"
            glyph: "⌗"
            label: "Board chips"
        }

        MenuItem {
            objectName: "menuCoordinates"
            glyph: "◳"
            label: "Coordinates"
        }

        MenuSeparator { }

        MenuItem {
            objectName: "menuSettings"
            glyph: "⚙"
            label: "Settings"
        }

        MenuItem {
            objectName: "menuAbout"
            glyph: "ⓘ"
            label: "About"
        }

        MenuSeparator { }

        MenuItem {
            objectName: "menuClose"
            glyph: "✕"
            label: "Close menu"
            shortcut: "Esc"
            danger: true
        }
    }
}
