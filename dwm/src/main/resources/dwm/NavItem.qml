import QtQuick
import "."

// One row of the navigation rail: a 40px hover backplate with a leading glyph and a label, plus the
// vertical accent pill that marks the selected item.
//
// Structurally this is MenuItem with selection added, and deliberately so -- same 40px row, same
// 4px backplate inset inside the 8px panel, same hover-brighter-than-pressed chain. It is a
// separate component rather than a flag on MenuItem because the pill and the selected backplate
// only mean anything inside a rail, and a menu row that could draw a selection indicator would
// invite drawing one where nothing is selected.
//
// The pill IS the pattern. Fluent marks the current page with a short accent bar on the leading
// edge, not by colouring the label; without it a selected row is just a row with a slightly
// brighter plate, which does not read as "you are here".

Item {
    id: row

    property string label: ""
    // Leading glyph. A short string rather than an icon font: Segoe Fluent Icons cannot be
    // redistributed, so a glyph that renders in any font is the portable choice.
    property string glyph: ""
    property bool selected: false
    property bool enabled: true

    signal clicked()

    // ---- approximations ----
    // Microsoft publishes no pixel geometry for the NavigationView selection indicator. 3x16 with
    // a 1.5 radius is what the shipped control's proportions give inside a 40px row: fully rounded
    // ends, and short enough to read as a marker beside the row rather than a border on it.
    property int pillWidth: 3
    property int pillHeight: 16

    width: parent ? parent.width : 140
    height: Fluent.rowHeight

    Rectangle {
        id: backplate
        // Explicit size, not anchors.fill: an Item root is laid out after its children are
        // constructed, so a fill anchor resolves against a still-zero parent and leaves this
        // 0x0 -- which renders nothing AND makes the MouseArea below unhittable.
        x: 0
        y: 0
        width: row.width
        height: row.height
        radius: Fluent.radiusControl
        // A selected row keeps a resting plate so it stays marked once the pointer leaves; an
        // unselected one is transparent at rest. Within each case hover is BRIGHTER than pressed --
        // counter-intuitive, but it is what Fluent does, the plate dims as it goes down.
        color: !row.enabled ? "#00000000"
             : row.selected ? (hit.pressed ? Fluent.subtlePressed : Fluent.subtleHover)
             : hit.pressed ? Fluent.subtlePressed
             : hit.containsMouse ? Fluent.subtleHover
             : "#00000000"
    }

    Rectangle {
        id: pill
        // Sits in the backplate's left margin, not inside the text column, so selecting a row
        // never moves its glyph or label sideways.
        x: 0
        y: (row.height - row.pillHeight) / 2
        // Zero width when unselected rather than visible:false, so exactly one property carries
        // the state and there is nothing to keep in sync.
        width: row.selected ? row.pillWidth : 0
        height: row.pillHeight
        radius: row.pillWidth / 2
        color: row.enabled ? Fluent.accent : Fluent.textDisabled
    }

    Text {
        x: Fluent.itemPaddingH
        y: (row.height - Fluent.fontBody) / 2 - 2
        text: row.glyph
        fontSize: Fluent.fontBody
        // The glyph goes accent-coloured with the pill: in Fluent the selected row's icon is the
        // second half of the indicator, and the label alone carries no accent.
        color: !row.enabled ? Fluent.textDisabled
             : row.selected ? Fluent.accent
             : Fluent.textSecondary
    }

    Text {
        // Same 26px glyph column as MenuItem, so a rail row and a menu row align to one grid.
        x: Fluent.itemPaddingH + 26
        y: (row.height - Fluent.fontBody) / 2 - 2
        text: row.label
        fontSize: Fluent.fontBody
        color: row.enabled ? Fluent.textPrimary : Fluent.textDisabled
    }

    MouseArea {
        id: hit
        x: 0
        y: 0
        width: row.width
        height: row.height
        hoverEnabled: true
        onClicked: if (row.enabled) row.clicked()
    }
}
