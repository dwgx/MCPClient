import QtQuick
import "."

// A Windows 11 Settings card: icon, header, description, and a control on the right.
//
// THIS IS THE PIECE THAT MAKES A PAGE LOOK LIKE WINDOWS SETTINGS. The controls inside it were
// already accurate; a page that stacks bare label+control rows is not a Settings page regardless
// of how correct those controls are. The card -- its plate, its edge, its icon column, its
// two-line text block -- is the actual idiom.
//
// Metrics from CommunityToolkit/Windows, components/SettingsControls/src/SettingsCard/
// SettingsCard.xaml (the control Windows Settings itself is built from):
//
//   SettingsCardMinHeight          68
//   SettingsCardPadding            16,16,16,16
//   SettingsCardBorderThickness    1
//   SettingsCardHeaderIconMaxSize  20
//   SettingsCardHeaderIconMargin   2,0,20,0
//   SettingsCardDescriptionFontSize 12
//   SettingsCardActionIconMaxSize  13
//   HeaderPanel margin             0,0,24,0
//
// Colours (x:Key="Default", the dark dictionary):
//   background  CardBackgroundFillColorDefault  #0DFFFFFF
//     hover     ControlFillColorSecondary       #15FFFFFF
//     pressed   ControlFillColorTertiary        #08FFFFFF
//   border      CardStrokeColorDefault          #19000000
//     HOVER     ControlElevationBorderBrush  <- the lit edge, not a flat colour
//
// That hover border is why FluentElevation exists as its own component: the card swaps a flat
// stroke for the elevation ramp under the pointer, which is the same brush a button wears all
// the time.

Item {
    id: card

    // ---- content ----
    property string header: ""
    property string description: ""
    /** Icon resource under dwm/icons, without the .svg. Empty collapses the icon column. */
    property string icon: ""
    property bool enabled: true

    /**
     * Clickable variant. A Settings card can be a button that navigates; when it is, it grows an
     * action chevron on the right and reports clicks.
     */
    property bool clickable: false
    signal clicked()

    /**
     * Whether to paint the plate and outline.
     *
     * <p>False when the card is the header of a {@code FluentSettingsExpander}, which draws one
     * surface around header and body together — an expanded expander is a single card with
     * divisions inside it, so a second plate nested in the first would show as a seam.
     */
    property bool showSurface: true

    // The control on the right. Declared as the default property so a caller writes the control
    // directly inside the card, the way SettingsCard takes its Content.
    default property alias content: contentHolder.children

    // ---- metrics ----
    property int minHeight: 68
    property int padding: 16
    property int iconSize: 20
    // SettingsCardHeaderIconMargin 2,0,20,0 -- 2 leading, 20 trailing.
    property int iconLead: 2
    property int iconGap: 20
    // HeaderPanel's own 0,0,24,0: the gap between the text block and the control.
    property int headerGap: 24
    property int actionIconSize: 13

    // The icon column's total width, or zero when there is no icon. Both the text block and the
    // wrap threshold below are measured from this, so it lives in one place.
    readonly property int iconColumn: card.icon === ""
        ? 0 : card.iconLead + card.iconSize + card.iconGap

    // SettingsCardWrapThreshold. Below this width WinUI moves the content BELOW the text rather
    // than beside it, because a control squeezed against a long header is worse than a taller card.
    //
    // 320, NOT WinUI's 476 -- and this is a deliberate deviation from a value that is otherwise
    // quoted verbatim throughout this module. dwm's content area is 419 logical px (a 560 window
    // less the 140 rail and its divider), so at 476 EVERY card would wrap: measured, that makes a
    // 68px card 110px tall and fits three on screen where Windows fits five. Wrapping is the
    // right BEHAVIOUR at some width; 476 is the right threshold for a desktop window, not for a
    // 419px in-game panel. WinUI itself exposes the threshold as an overridable resource for
    // exactly this reason, so overriding it is the supported move rather than a workaround.
    property int wrapThreshold: 320
    readonly property bool wrapped: card.width > 0 && card.width < card.wrapThreshold

    // Text is measured, so the card grows for a long description rather than clipping it.
    //
    // No gap between the two lines. Measured on a live scene, this sums to 30 for a header plus a
    // description -- qml4j's implicitHeight tracks the FONT SIZE (14 + 12 + rounding), not the type
    // ramp's line height (20 + 16), so 16 + 30 + 16 = 62 and the 68px minimum is what actually
    // sets a two-line card's height. A 4px gap between the lines would still leave 66, i.e. under
    // the floor, so it is invisible in the common case and only shows up on a card whose
    // description wraps to a second line. Omitted anyway: the ramp's leading is meant to supply
    // that spacing, and adding more would drift from the spec exactly where the floor stops hiding
    // it. (An earlier version of this comment claimed 68 = 16+20+16+16 and that the gap broke the
    // floor; both were wrong -- the measurement above is what the scene reports.)
    readonly property int textHeight: headerText.implicitHeight
        + (card.description === "" ? 0 : descriptionText.implicitHeight)

    readonly property int contentHeight: contentHolder.childrenRect.height

    width: parent ? parent.width : 400
    // Tallest of: the minimum, the text block, or -- when wrapped -- text plus content stacked.
    height: Math.max(card.minHeight,
        (card.padding * 2) + card.textHeight
        + (card.wrapped && card.contentHeight > 0 ? Fluent.gutter + card.contentHeight : 0))

    // ---- surface ----
    //
    // Two layers so the hover state can swap a flat stroke for the elevation ramp. The elevation
    // is only instantiated as visible under the pointer; at rest the flat card stroke shows.
    FluentElevation {
        id: outline
        x: 0
        y: 0
        width: card.width
        height: card.height
        radius: Fluent.radiusCard
        visible: card.showSurface
        // At rest both stops are the flat card stroke, which makes this a plain 1px edge. Under
        // the pointer the top stop lifts to the control stroke and it becomes the ramp.
        litColor: hit.containsMouse && card.enabled && card.clickable
                ? Fluent.controlStrokeSecondary : Fluent.cardStroke
        baseColor: Fluent.cardStroke
    }

    Rectangle {
        id: face
        visible: card.showSurface
        // Inset by the outline's width, which is what leaves it showing as a 1px edge. Explicit
        // geometry, never anchors.fill -- see the trap documented in MenuItem.qml.
        x: outline.borderWidth
        y: outline.borderWidth
        width: card.width - (outline.borderWidth * 2)
        height: card.height - (outline.borderWidth * 2)
        radius: Fluent.radiusCard - outline.borderWidth
        // The hover/pressed steps only apply to a CLICKABLE card. A card that merely holds a
        // toggle must not light up when the pointer crosses it -- the toggle is the target, and a
        // reacting plate would advertise a click the card does not accept.
        color: !card.enabled ? Fluent.controlFillDisabled
             : !card.clickable ? Fluent.cardFill
             : hit.pressed ? Fluent.controlFillTertiary
             : hit.containsMouse ? Fluent.controlFillSecondary
             : Fluent.cardFill
    }

    // ---- icon ----
    Image {
        id: iconImage
        // The tint travels in the source string: qml4j's ResourceLoader is handed a path and
        // nothing else, so the colour has to ride along with it. ClasspathResources splits it off
        // and SvgRaster substitutes it into the SVG's currentColor.
        source: card.icon === "" ? ""
              : "icons/" + card.icon + ".svg?" + (card.enabled ? "ffffff" : "5dffffff")
        visible: card.icon !== ""
        x: card.padding + card.iconLead
        // Aligned to the first text line rather than the card's centre, so a card with a long
        // wrapped description keeps its icon beside the header instead of drifting down.
        y: card.padding + Math.max(0, (headerText.implicitHeight - card.iconSize) / 2)
        width: card.iconSize
        height: card.iconSize
    }

    // ---- text block ----
    Text {
        id: headerText
        x: card.padding + card.iconColumn
        y: card.padding
        width: Math.max(0, card.width - x - card.headerGap
            - (card.wrapped ? card.padding : contentHolder.width + card.padding))
        text: card.header
        fontSize: Fluent.fontBody
        color: card.enabled ? Fluent.textPrimary : Fluent.textDisabled
        wrapMode: Text.WordWrap
    }

    Text {
        id: descriptionText
        x: headerText.x
        // Flush under the header: the two line heights supply the leading, per the height
        // arithmetic documented on textHeight.
        y: headerText.y + headerText.implicitHeight
        width: headerText.width
        visible: card.description !== ""
        text: card.description
        // SettingsCardDescriptionFontSize 12, at the secondary text colour.
        fontSize: 12
        color: card.enabled ? Fluent.textSecondary : Fluent.textDisabled
        wrapMode: Text.WordWrap
    }

    // ---- the caller's control ----
    Item {
        id: contentHolder
        // Right-aligned at rest; below the text when wrapped, indented to the text column so it
        // lines up with the header rather than with the card edge.
        x: card.wrapped
         ? headerText.x
         : Math.max(headerText.x,
             card.width - card.padding - contentHolder.childrenRect.width
             - (card.clickable ? card.actionIconSize + 14 : 0))
        y: card.wrapped
         ? card.padding + card.textHeight + Fluent.gutter
         : (card.height - contentHolder.childrenRect.height) / 2
        width: contentHolder.childrenRect.width
        height: contentHolder.childrenRect.height
    }

    // ---- action chevron ----
    //
    // Sized to zero rather than merely hidden when the card is not clickable. `visible: false`
    // stops a node being drawn but leaves its geometry, and this node's box is what the content
    // holder measures its right edge against -- so a hidden-but-sized chevron silently pushed
    // every control 27px left on cards that have no chevron at all.
    Image {
        id: actionIcon
        visible: card.clickable
        source: card.clickable
              ? "icons/chevron-right.svg?" + (card.enabled ? "c5ffffff" : "5dffffff") : ""
        // SettingsCardActionIconMargin 14,0,0,0 measured from the card's trailing padding.
        x: card.width - card.padding - actionIcon.width
        y: (card.height - actionIcon.height) / 2
        width: card.clickable ? card.actionIconSize : 0
        height: card.clickable ? card.actionIconSize : 0
    }

    // The card's own hit area, for the clickable variant ONLY.
    //
    // DISABLED rather than resized when the card is not clickable, and the distinction matters.
    // qml4j hit-tests a subtree by walking children in REVERSE z order and returning the first
    // hit, so the last-declared child wins -- and this MouseArea is declared last. A live
    // full-size area here therefore intercepts every press before the control inside the card
    // sees it: measured on a real client, the toggle reported `down=true` (the card had consumed
    // it) while its own MouseArea reported containsMouse=false, and nothing on the page could be
    // operated. Gating `onClicked` does not help -- consuming the press is what breaks it.
    //
    // `enabled: false` is the right lever because hitTestMouseArea checks it FIRST and returns
    // null, so the press falls through to the control. Sizing it to zero also works, but it means
    // "an interactive node that can never be reached", which is the exact defect
    // NavigationShellLiveIT scans the scene for -- and it flagged this. A disabled area is
    // non-zero and honestly declares that it does not want input.
    MouseArea {
        id: hit
        x: 0
        y: 0
        width: card.width
        height: card.height
        enabled: card.clickable
        hoverEnabled: card.clickable
        onClicked: if (card.clickable && card.enabled) card.clicked()
    }
}
