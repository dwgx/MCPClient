import QtQuick
// Registered in dwm/qmldir as controls/<name>.qml, so qml4j hands this document the qmldir's
// PREFIX as its baseDir -- "dwm" -- not the file's own directory. That is why the token singleton
// resolves through "." and not "..". Verified by instantiating all three from a dwm/ document.
import "."

// A Fluent text box: 4px rounded, hairline stroke that goes accent while focused, wrapping a
// qml4j TextField.
//
// The chrome is the TextField's OWN -- backgroundColor / borderColor / focusBorderColor / radius /
// padding / placeholderText, all painted by qml4j's drawTextField -- rather than a Rectangle with
// the field laid on top. Two reasons, and the second is the one that matters: qml4j already swaps
// borderColor for focusBorderColor when activeFocus is set, so a hand-rolled border would restate
// working logic; and this module just spent a session on text input being entirely dead, so the
// structure here stays as close to the shape KeyDispatchLiveIT proves works as it can. One node,
// explicitly sized, nothing overlapping it.
//
// The placeholder needs no binding for the same reason: drawTextField draws it only when text is
// empty.

Item {
    id: box

    // Aliased straight through, so a caller reads and writes the field's real text with no copy
    // to keep in sync in either direction.
    property alias text: field.text
    property string placeholder: ""
    property bool enabled: true

    // Approximation. Microsoft publishes the 4px corner radius for in-page controls, not the box
    // height; 32 is what sits correctly beside a 40px row without matching it.
    property int boxHeight: 32

    width: parent ? parent.width : 240
    height: box.boxHeight

    TextField {
        id: field
        // Explicit size, never anchors.fill -- see the trap documented in MenuItem.qml. Here it
        // also decides whether the control works at all: qml4j hit-tests a text field by its own
        // geometry, so a 0x0 field can never be clicked into focus and never sees a keystroke.
        x: 0
        y: 0
        width: box.width
        height: box.height

        placeholderText: box.placeholder

        radius: Fluent.radiusControl
        borderWidth: 1
        borderColor: Fluent.panelStroke
        focusBorderColor: Fluent.accent
        // No dedicated control-fill token exists yet, and solidFill is the opaque control base;
        // inventing a colour here instead would put a metric outside Fluent.qml.
        backgroundColor: Fluent.solidFill
        padding: Fluent.itemPaddingH

        fontSize: Fluent.fontBody
        color: box.enabled ? Fluent.textPrimary : Fluent.textDisabled
        placeholderTextColor: Fluent.textSecondary

        // readOnly, not the Item's enabled flag: qml4j's dispatcher hit-tests text fields by
        // visibility alone, so a merely-disabled field would still take focus and accept typing.
        // readOnly is what its editing path actually checks.
        readOnly: !box.enabled

        // Reachable by Tab as well as by click, so a keyboard user can get into it.
        activeFocusOnTab: box.enabled
    }
}
