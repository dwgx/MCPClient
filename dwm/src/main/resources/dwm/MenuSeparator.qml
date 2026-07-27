import QtQuick
import "."

// A 1px divider between groups of menu items.
//
// Inset horizontally rather than run edge to edge: a full-bleed line would collide visually
// with the panel's 8px rounded corners.

Item {
    width: parent ? parent.width : 280
    height: 9

    Rectangle {
        x: Fluent.itemPaddingH
        anchors.verticalCenter: parent.verticalCenter
        width: parent.width - (Fluent.itemPaddingH * 2)
        height: 1
        color: Fluent.divider
    }
}
