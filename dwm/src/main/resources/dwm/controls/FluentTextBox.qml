import QtQuick
// Registered in dwm/qmldir as controls/<name>.qml, so qml4j hands this document the qmldir's
// PREFIX as its baseDir -- "dwm" -- not the file's own directory. That is why the token singleton
// resolves through "." and not "..". Verified by instantiating all three from a dwm/ document.
import "."

// A Fluent text box: 4px rounded, with the accent underline that appears while it is focused.
//
// The chrome is mostly the TextField's OWN -- backgroundColor / borderColor / focusBorderColor /
// radius / padding / placeholderText, all painted by qml4j's drawTextField -- rather than a
// Rectangle with the field laid on top. Two reasons, and the second is the one that matters: qml4j
// already swaps borderColor for focusBorderColor when activeFocus is set, so a hand-rolled border
// would restate working logic; and this module spent a session on text input being entirely dead,
// so the structure here stays as close to the shape KeyDispatchLiveIT proves works as it can.
//
// Colours from microsoft-ui-xaml release/2.8 dev/CommonStyles/TextBox_themeresources.xaml
// (x:Key="Default"):
//
//   TextControlBackground             ControlFillColorDefault      #0FFFFFFF
//   TextControlBackgroundPointerOver  ControlFillColorSecondary    #15FFFFFF
//   TextControlBackgroundFocused      ControlFillColorInputActive  #B31E1E1E  <- near-opaque dark
//   TextControlPlaceholderForeground  TextFillColorSecondary, TERTIARY when focused
//   TextControlBorderBrush            TextControlElevationBorderBrush
//
// The focused background is the surprise: it does not brighten on focus, it goes to a nearly
// opaque dark (#B31E1E1E) so the text being edited sits on a controlled surface instead of on
// whatever is behind the panel. This file previously used the opaque solidFill for every state,
// which is that focused value applied all the time.
//
// THE UNDERLINE. TextControlElevationBorderBrush is a 2px absolute ramp with ScaleY="-1", i.e.
// flipped -- so unlike every other control the lit edge is at the BOTTOM: strong stroke along the
// bottom, default stroke elsewhere. Focused, the whole bottom stop becomes the accent. That
// bottom rule is the signature of a Fluent text field and qml4j cannot express it, because
// TextField.borderColor is a single colour for all four sides. So the underline is a separate
// Rectangle laid over the field's own bottom edge.
//
// The placeholder needs no binding: drawTextField draws it only when text is empty.

Item {
    id: box

    // Aliased straight through, so a caller reads and writes the field's real text with no copy
    // to keep in sync in either direction.
    property alias text: field.text
    property string placeholder: ""
    property bool enabled: true

    // WinUI's shipped default control height inside the documented 40x40 epx target, same as
    // FluentButton. Microsoft publishes the target, not this number.
    property int boxHeight: 32
    // The focus underline's thickness, from TextControlElevationBorderBrush's EndPoint="0,2".
    property int underlineHeight: 2

    // True while the field holds keyboard focus. Read off qml4j's own activeFocus rather than
    // tracked here, so the underline cannot disagree with the border qml4j draws itself.
    readonly property bool isFocused: field.activeFocus

    width: parent ? parent.width : 240
    height: box.boxHeight

    // A hover-only area, below the field so it never intercepts the press that focuses it: qml4j
    // hit-tests text fields before MouseAreas (EventDispatcher checks hitTestTextEditable first),
    // but keeping this underneath makes the ordering irrelevant rather than relying on it.
    MouseArea {
        id: hover
        x: 0
        y: 0
        width: box.width
        height: box.height
        hoverEnabled: true
    }

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
        // The three non-bottom sides. WinUI's ramp puts the strong stroke only along the bottom,
        // so the sides carry the ordinary control stroke and the underline below supplies the rest.
        borderColor: Fluent.panelStroke
        // Left as the accent so qml4j's own focus swap agrees with the underline rather than
        // fighting it -- the field outlines in accent and the underline thickens it at the bottom.
        focusBorderColor: Fluent.accent
        // TextControlBackground / PointerOver / Focused. Focused is the near-opaque
        // ControlFillColorInputActive, which is why it darkens rather than brightens on focus.
        backgroundColor: !box.enabled ? Fluent.controlFillDisabled
                       : box.isFocused ? Fluent.controlFillInputActive
                       : hover.containsMouse ? Fluent.controlFillSecondary
                       : Fluent.controlFill
        padding: Fluent.itemPaddingH

        fontSize: Fluent.fontBody
        color: box.enabled ? Fluent.textPrimary : Fluent.textDisabled
        // TextControlPlaceholderForegroundFocused steps down to TERTIARY: the hint recedes once
        // the caret is in the field, since at that point it is describing something being replaced.
        placeholderTextColor: !box.enabled ? Fluent.textDisabled
                            : box.isFocused ? Fluent.textTertiary
                            : Fluent.textSecondary

        // readOnly, not the Item's enabled flag: qml4j's dispatcher hit-tests text fields by
        // visibility alone, so a merely-disabled field would still take focus and accept typing.
        // readOnly is what its editing path actually checks.
        readOnly: !box.enabled

        // Reachable by Tab as well as by click, so a keyboard user can get into it.
        activeFocusOnTab: box.enabled
    }

    // The bottom rule, over the field's own bottom edge. Drawn last so it wins the overlap.
    //
    // Inset horizontally by the field's border width so it stops where the rounded corners begin
    // instead of poking out past them -- a square strip under a 4px-rounded box would show two
    // small ears at the bottom corners.
    Rectangle {
        id: underline
        x: field.borderWidth
        y: box.height - box.underlineHeight
        width: box.width - (field.borderWidth * 2)
        height: box.underlineHeight
        // Only the bottom corners are round, matching the box it sits inside.
        topLeftRadius: 0
        topRightRadius: 0
        bottomLeftRadius: Fluent.radiusControl - field.borderWidth
        bottomRightRadius: Fluent.radiusControl - field.borderWidth
        // Unfocused, the bottom edge is the STRONG stroke: visible, but not accent. Focused, the
        // whole rule becomes the accent, which is the affordance.
        color: !box.enabled ? Fluent.controlStrokeStrongDisabled
             : box.isFocused ? Fluent.accent
             : Fluent.controlStrokeStrong
    }
}
