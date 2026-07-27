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
        anchors.fill: parent
        radius: Fluent.radiusControl
        color: !row.enabled ? "#00000000"
             : hit.pressed ? Fluent.subtlePressed
             : hit.containsMouse ? Fluent.subtleHover
             : "#00000000"
    }

    Text {
        x: Fluent.itemPaddingH
        anchors.verticalCenter: parent.verticalCenter
        text: row.glyph
        fontSize: Fluent.fontBody
        color: !row.enabled ? Fluent.textDisabled
             : row.danger ? "#ff99a3"
             : Fluent.textSecondary
    }

    Text {
        x: Fluent.itemPaddingH + 26
        anchors.verticalCenter: parent.verticalCenter
        text: row.label
        fontSize: Fluent.fontBody
        color: !row.enabled ? Fluent.textDisabled
             : row.danger ? "#ff99a3"
             : Fluent.textPrimary
    }

    Text {
        anchors.right: parent.right
        anchors.rightMargin: Fluent.itemPaddingH
        anchors.verticalCenter: parent.verticalCenter
        text: row.shortcut
        fontSize: Fluent.fontCaption
        color: Fluent.textTertiary
    }

    MouseArea {
        id: hit
        anchors.fill: parent
        hoverEnabled: true
        onClicked: if (row.enabled) row.triggered()
    }
}
