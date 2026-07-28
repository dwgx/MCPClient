import QtQuick
import "."

// A row inside a FluentSettingsExpander: label on the left, control on the right, one hairline
// along its top edge.
//
// Padding is SettingsExpanderItemPadding, 58,8,44,8. The 58 is the load-bearing number: it lines
// this row's text up with the PARENT card's text column (16 padding + 2 icon lead + 20 icon +
// 20 icon gap), which is what makes a child row read as belonging to the card above it. An
// arbitrary indent is the usual tell of a hand-rolled expander.
//
// SettingsExpanderItemBorderThickness is 0,1,0,0 -- the TOP edge only, so consecutive rows
// separate with one hairline each instead of doubling up between them.

Item {
    id: item

    property string label: ""
    property bool enabled: true

    default property alias content: contentHolder.children

    // SettingsExpanderItemPadding 58,8,44,8.
    property int indent: 58
    property int paddingV: 8
    property int paddingRight: 44

    readonly property int contentHeight: contentHolder.childrenRect.height

    width: parent ? parent.width : 400
    // Tall enough for whichever is bigger, the label or the control, plus the vertical padding.
    // Floored at the interactive row height so a row holding a bare label still reads as a row.
    height: Math.max(Fluent.rowHeight,
        (item.paddingV * 2) + Math.max(labelText.implicitHeight, item.contentHeight))

    // The dividing hairline, along the TOP edge only.
    Rectangle {
        x: 0
        y: 0
        width: item.width
        height: 1
        color: Fluent.divider
    }

    Text {
        id: labelText
        x: item.indent
        y: (item.height - labelText.implicitHeight) / 2
        width: Math.max(0, item.width - item.indent - item.paddingRight
            - contentHolder.childrenRect.width)
        text: item.label
        fontSize: Fluent.fontBody
        color: item.enabled ? Fluent.textPrimary : Fluent.textDisabled
        wrapMode: Text.WordWrap
    }

    Item {
        id: contentHolder
        // Right-aligned within the row's trailing padding.
        x: Math.max(labelText.x,
            item.width - item.paddingRight - contentHolder.childrenRect.width)
        y: (item.height - contentHolder.childrenRect.height) / 2
        width: contentHolder.childrenRect.width
        height: contentHolder.childrenRect.height
    }
}
