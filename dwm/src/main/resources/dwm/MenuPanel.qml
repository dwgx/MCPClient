import QtQuick
import "."

// A Fluent menu surface: 8px rounded, tonal fill, hairline stroke, 4px padding.
//
// Items go in as children and are stacked by the inner Column, so a caller writes
// MenuPanel { MenuItem { ... } MenuSeparator { } MenuItem { ... } } with no layout arithmetic.
//
// The height follows the Column's own laid-out height. An earlier version made the caller
// declare itemCount/separatorCount by hand, on the belief that binding to body.height evaluates
// before the Column has laid out and collapses the panel to its title block. Measured against a
// real eight-child menu, that does not happen: the renderer settles layout up to
// MAX_LAYOUT_PASSES times per frame, flushing the dirty queue between passes, and Column.layout()
// publishes its height into that loop -- so the binding resolves within the FIRST frame. Both
// forms produced an identical 320x310 panel with the bottom row hittable. Declaring the counts
// was therefore a per-panel tax with nothing bought, and a silent-wrong-height trap whenever a
// caller added a row and forgot to bump the number. PanelSizingLiveIT holds this down.

Item {
    id: panel

    // Optional heading above the items. Empty hides it and its divider.
    property string title: ""

    default property alias items: body.children

    property int titleBlock: title === "" ? 0 : 44
    property real contentHeight: body.height

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
        // No height here: Column.layout() sets its own from the stacked children, and the panel
        // reads it back through contentHeight. Assigning it would close that into a cycle.
    }
}
