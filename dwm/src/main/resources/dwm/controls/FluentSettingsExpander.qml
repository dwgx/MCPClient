import QtQuick
import "."

// A collapsible Settings group: a card that opens to reveal further settings under it.
//
// Metrics from CommunityToolkit/Windows, components/SettingsControls/src/SettingsExpander/
// SettingsExpander.xaml:
//
//   SettingsExpanderHeaderPadding       16,16,4,16
//   SettingsExpanderItemPadding         58,8,44,8
//   SettingsExpanderItemBorderThickness 0,1,0,0   <- top edge only
//   SettingsExpanderChevronButtonWidth  32
//   SettingsExpanderChevronButtonHeight 32
//
// The 58px left padding on an item is the number worth understanding: it lines a child row's text
// up with the PARENT's text column, which is 16 padding + 2 icon lead + 20 icon + 20 icon gap = 58.
// Indenting by an arbitrary amount instead is what makes a hand-rolled expander look off -- the
// child text would not sit under the parent's.
//
// Only the top border is drawn on an item, so consecutive items separate with one hairline each
// rather than doubling up between them.

Item {
    id: expander

    property string header: ""
    property string description: ""
    property string icon: ""
    property bool expanded: false
    property bool enabled: true

    /** The child rows, revealed when expanded. */
    default property alias items: itemStack.children

    // ---- metrics ----
    property int chevronButton: 32
    property int chevronIcon: 13
    // SettingsExpanderItemPadding 58,8,44,8.
    property int itemIndent: 58
    property int itemPaddingV: 8
    property int itemPaddingRight: 44

    readonly property int headerHeight: head.height

    /** Where the body is heading: its full height when open, zero when closed. */
    readonly property int targetBodyHeight: expander.expanded ? itemStack.height : 0

    /**
     * The animated body height. Follows {@link targetBodyHeight} through the Behavior below.
     *
     * <p>A plain property rather than a readonly derived one, because a derived value cannot carry a
     * Behavior — and the height IS the animation. WinUI's Expander reveals its content by growing
     * the container and clipping, which is what this reproduces; fading alone would let the rows
     * below jump instead of being pushed down.
     */
    property int animatedBodyHeight: expander.targetBodyHeight

    /**
     * The height actually used, which is the animated value only when the effect is enabled.
     *
     * <p>Two properties rather than one because {@code Behavior} has no {@code enabled} in qml4j
     * 0.2.24 — verified against its class, which exposes only attach/write/tick — so an animation
     * cannot be switched off at runtime. Selecting between the animated value and the target is how
     * policy is honoured, and it honours it in the documented direction: with the effect off the
     * height is the TARGET, i.e. the expander arrives instantly in the correct end state rather
     * than freezing part-open.
     */
    readonly property int bodyHeight: Motion.animateExpand
        ? expander.animatedBodyHeight : expander.targetBodyHeight

    Behavior on animatedBodyHeight {
        NumberAnimation {
            // 250 is ControlNormalAnimationDuration: an expander is a LARGER move than a control
            // state change, which is what the 83ms Faster tier is for. A literal because qml4j
            // 0.2.24 reads a Behavior's duration off the template before bindings resolve, so
            // Motion.expandDuration would silently revert to the 250ms default. It happens to equal
            // that default here, which is exactly why the trap is worth naming: the next value that
            // differs would fail silently.
            duration: 250
            // 2 is OutQuad, the closest qml4j easing to WinUI's entrance curve
            // cubic-bezier(0,0,0,1). See Motion.easeEnter.
            easing.type: 2
        }
    }

    width: parent ? parent.width : 400
    height: expander.headerHeight + expander.bodyHeight

    // ---- surface ----
    //
    // One outline around the whole thing, header and body together: an expanded SettingsExpander is
    // a single card with divisions inside it, not a card with more cards below.
    FluentElevation {
        id: outline
        x: 0
        y: 0
        width: expander.width
        height: expander.height
        radius: Fluent.radiusCard
        litColor: hit.containsMouse && expander.enabled
                ? Fluent.controlStrokeSecondary : Fluent.cardStroke
        baseColor: Fluent.cardStroke
    }

    Rectangle {
        id: face
        x: outline.borderWidth
        y: outline.borderWidth
        width: expander.width - (outline.borderWidth * 2)
        height: expander.height - (outline.borderWidth * 2)
        radius: Fluent.radiusCard - outline.borderWidth
        color: !expander.enabled ? Fluent.controlFillDisabled : Fluent.cardFill
    }

    // ---- header ----
    //
    // The header reuses FluentSettingsCard: it IS a settings card, and this is the same
    // composition SettingsExpander uses (its header is a SettingsCard template). Reimplementing
    // the icon column and text block here would be a second place to keep the metrics right.
    // Its own surface is suppressed, because the expander drew one above.
    FluentSettingsCard {
        id: head
        x: 0
        y: 0
        width: expander.width
        icon: expander.icon
        header: expander.header
        description: expander.description
        enabled: expander.enabled
        // The expander owns the plate and the outline; the header must not paint a second pair
        // inside them.
        showSurface: false

        // The chevron sits where a card's content would, so it goes in as the card's content.
        Item {
            id: chevronSlot
            width: expander.chevronButton
            height: expander.chevronButton

            Rectangle {
                // The chevron's own hover backplate, as WinUI gives it: it is a button inside the
                // header, distinct from the header's own hover.
                x: 0
                y: 0
                width: expander.chevronButton
                height: expander.chevronButton
                radius: Fluent.radiusControl
                color: chevronHit.pressed ? Fluent.subtlePressed
                     : chevronHit.containsMouse ? Fluent.subtleHover
                     : "#00000000"
            }

            Image {
                id: chevron
                source: "icons/chevron-down.svg?"
                      + (expander.enabled ? "ffffff" : "5dffffff")
                x: (expander.chevronButton - expander.chevronIcon) / 2
                y: (expander.chevronButton - expander.chevronIcon) / 2
                width: expander.chevronIcon
                height: expander.chevronIcon
                // Points down when closed, up when open. Rotation rather than two icons, so the
                // turn is one asset and can animate.
                // The animated angle, and the one actually applied. Split for the same reason as
                // the body height: qml4j's Behavior cannot be disabled at runtime, so policy is
                // honoured by choosing between the animated value and the destination.
                property real turn: expander.expanded ? 180 : 0
                rotation: Motion.animateExpand ? chevron.turn : (expander.expanded ? 180 : 0)

                // 100ms, NOT the 83ms Faster tier: Expander_themeresources.xaml animates its
                // chevron over Duration="0:0:0.1", which is its own value. Using 83 here because
                // "that is the control-state duration" would be a plausible wrong answer.
                // 2 is OutQuad, the closest qml4j easing to WinUI's 0,0,0,1 spline.
                //
                // Both LITERALS because qml4j 0.2.24 reads a Behavior's duration off the template
                // before bindings resolve -- a bound value silently reverts to 250ms. See
                // FluentToggleSwitch for the full measurement.
                Behavior on turn {
                    NumberAnimation {
                        duration: 100
                        easing.type: 2
                    }
                }
            }

            MouseArea {
                id: chevronHit
                x: 0
                y: 0
                width: expander.chevronButton
                height: expander.chevronButton
                hoverEnabled: true
                onClicked: if (expander.enabled) expander.expanded = !expander.expanded
            }
        }
    }

    // ---- body ----
    Item {
        id: body
        x: outline.borderWidth
        y: expander.headerHeight
        width: expander.width - (outline.borderWidth * 2)
        height: expander.bodyHeight
        visible: expander.expanded
        // Clipped so a row cannot draw outside the card while the height animates.
        clip: true

        Column {
            id: itemStack
            x: 0
            y: 0
            width: body.width
            // No spacing: each item carries its own top hairline, and a gap would separate the
            // rows from the line that is supposed to divide them.
            spacing: 0
        }
    }

    // Header hit area. The whole header toggles, not just the chevron -- WinUI makes the entire
    // header the target, and a 32px chevron would otherwise be the only way to open it.
    //
    // DECLARED LAST AND SIZED TO THE HEADER ONLY, both deliberately. qml4j walks a subtree's
    // children in reverse z order and returns the first hit, so a last-declared area wins over
    // everything above it. Spanning the full height would therefore swallow every press meant for
    // a row in the body -- the same defect that made FluentSettingsCard eat its own control's
    // clicks. The chevron's own area is nested inside the header card, which is declared EARLIER,
    // so it would lose to this one; that is why the chevron does not rely on winning, and both
    // paths simply toggle.
    MouseArea {
        id: hit
        x: 0
        y: 0
        width: expander.width
        height: expander.headerHeight
        hoverEnabled: true
        onClicked: if (expander.enabled) expander.expanded = !expander.expanded
    }
}
