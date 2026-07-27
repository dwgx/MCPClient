import QtQuick
import "."

// A Fluent menu surface: 8px rounded, tonal fill, hairline stroke, 4px padding.
//
// Items go in as children and are stacked by the inner Column, so a caller writes
// MenuPanel { MenuItem { ... } MenuSeparator { } MenuItem { ... } } with no layout arithmetic.

Item {
    id: panel

    // Optional heading above the items. Empty hides it and its divider.
    property string title: ""
    default property alias items: body.children

    width: 300
    height: shell.height

    Rectangle {
        id: shell
        width: parent.width
        // Panel padding top and bottom, plus the title block when present.
        height: body.height + (Fluent.panelPadding * 2) + (panel.title === "" ? 0 : 44)
        radius: Fluent.radiusOverlay
        color: Fluent.panelFill
        border.width: 1
        border.color: Fluent.panelStroke

        Text {
            x: Fluent.itemPaddingH
            y: 12
            text: panel.title
            fontSize: Fluent.fontSubtitle
            color: Fluent.textPrimary
        }

        Rectangle {
            x: Fluent.itemPaddingH
            y: 43
            width: shell.width - (Fluent.itemPaddingH * 2)
            height: panel.title === "" ? 0 : 1
            color: Fluent.divider
        }

        Column {
            id: body
            x: Fluent.panelPadding
            y: Fluent.panelPadding + (panel.title === "" ? 0 : 44)
            width: shell.width - (Fluent.panelPadding * 2)
        }
    }
}
