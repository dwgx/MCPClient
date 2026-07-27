import QtQuick
import "."

// A Fluent menu surface: 8px rounded, tonal fill, hairline stroke, 4px padding.
//
// Items go in as children and are stacked by the inner Column, so a caller writes
// MenuPanel { MenuItem { ... } MenuSeparator { } MenuItem { ... } } with no layout arithmetic.
//
// Height is computed from the declared item counts rather than read off the Column. A binding to
// body.height evaluates before the Column has laid out, so the panel would collapse to just its
// title block -- and a collapsed panel makes every row below it unhittable, since hit testing
// rejects any point outside a node before it ever reaches the children.

Item {
    id: panel

    // Optional heading above the items. Empty hides it and its divider.
    property string title: ""
    // How many MenuItem and MenuSeparator children this panel holds. Declared, because the
    // Column's own height is not available at binding time.
    property int itemCount: 0
    property int separatorCount: 0

    default property alias items: body.children

    property int titleBlock: title === "" ? 0 : 44
    property int contentHeight: (itemCount * Fluent.rowHeight) + (separatorCount * 9)

    width: 300
    height: contentHeight + (Fluent.panelPadding * 2) + titleBlock

    Rectangle {
        id: shell
        x: 0
        y: 0
        width: panel.width
        height: panel.height
        radius: Fluent.radiusOverlay
        color: Fluent.panelFill
        border.width: 1
        border.color: Fluent.panelStroke
    }

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
        width: panel.width - (Fluent.itemPaddingH * 2)
        height: panel.title === "" ? 0 : 1
        color: Fluent.divider
    }

    Column {
        id: body
        x: Fluent.panelPadding
        y: Fluent.panelPadding + panel.titleBlock
        width: panel.width - (Fluent.panelPadding * 2)
        height: panel.contentHeight
    }
}
