import QtQuick
import "."

// One row of a Fluent menu: a 40px-tall hover backplate with a glyph, a label and an optional
// shortcut hint.
//
// The 8px panel / 4px backplate nesting is the core of the Windows 11 look, so the backplate
// is inset by the panel's padding rather than filling the row edge to edge.

Item {
    id: row

    property string label: ""
    // Leading glyph. A short string rather than an icon font: Segoe Fluent Icons cannot be
    // redistributed, so a glyph that renders in any font is the portable choice.
    property string glyph: ""
    // Right-aligned hint, e.g. a key binding. Empty hides it.
    property string shortcut: ""
    property bool enabled: true
    property bool danger: false

    signal triggered()

    width: parent ? parent.width : 280
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
        color: !row.enabled ? "#00000000"
             : hit.pressed ? Fluent.subtlePressed
             : hit.containsMouse ? Fluent.subtleHover
             : "#00000000"
    }

    Text {
        x: Fluent.itemPaddingH
        y: (row.height - Fluent.fontBody) / 2 - 2
        text: row.glyph
        fontSize: Fluent.fontBody
        color: !row.enabled ? Fluent.textDisabled
             : row.danger ? "#ff99a3"
             : Fluent.textSecondary
    }

    Text {
        x: Fluent.itemPaddingH + 26
        y: (row.height - Fluent.fontBody) / 2 - 2
        text: row.label
        fontSize: Fluent.fontBody
        color: !row.enabled ? Fluent.textDisabled
             : row.danger ? "#ff99a3"
             : Fluent.textPrimary
    }

    Text {
        x: row.width - Fluent.itemPaddingH - 30
        y: (row.height - Fluent.fontCaption) / 2 - 1
        text: row.shortcut
        fontSize: Fluent.fontCaption
        color: Fluent.textTertiary
    }

    MouseArea {
        id: hit
        x: 0
        y: 0
        width: row.width
        height: row.height
        hoverEnabled: true
        onClicked: if (row.enabled) row.triggered()
    }
}
