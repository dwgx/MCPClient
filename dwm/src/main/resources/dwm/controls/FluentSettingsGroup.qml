import QtQuick
import "."

// A titled group of Settings cards, which is how a Windows Settings page is organised: a short
// heading in Body Strong, then its cards stacked 4px apart.
//
// The 4px spacing is the value Microsoft's own samples use -- every SettingsCard example is a
// `<StackPanel Spacing="4">`. It is small on purpose: cards in a group read as one block, and the
// group heading is what separates blocks, not whitespace between their cards.
//
// The heading is Body Strong (14px Semibold) rather than Subtitle. A Settings page reserves the
// larger sizes for the PAGE title; a group heading that competes with it flattens the hierarchy
// the grouping exists to create.

Item {
    id: group

    property string title: ""

    // The cards. Default property so a caller writes them straight inside the group.
    default property alias cards: stack.children

    // Cards in a group sit 4px apart -- Microsoft's own sample spacing.
    property int cardSpacing: 4
    // Gap between the heading and the first card.
    property int titleGap: 8
    // Height of the heading block, collapsing to nothing when untitled so an unnamed group
    // stacks flush with whatever is above it.
    readonly property int titleBlock: group.title === ""
        ? 0 : titleText.implicitHeight + group.titleGap

    width: parent ? parent.width : 400
    // Driven by the stacked cards. Column sets its own height from its children, so reading it
    // back here is what lets a page's outer Column size this group correctly.
    height: group.titleBlock + stack.height

    Text {
        id: titleText
        x: 0
        y: 0
        visible: group.title !== ""
        text: group.title
        fontSize: Fluent.fontBody
        // Body Strong, i.e. Semibold. 63 is Qt's DemiBold on the 0..99 weight scale qml4j uses --
        // NOT CSS's 600. Measured: Font's default is 50 (Normal) and TextLayout treats >= 63 as
        // bold. A CSS 600 happens to render bold here only because it clears that threshold, so
        // it would be right by accident and wrong the moment the scale is respected.
        font.weight: 63
        color: Fluent.textPrimary
    }

    Column {
        id: stack
        x: 0
        y: group.titleBlock
        width: group.width
        spacing: group.cardSpacing
        // No height: Column.layout() computes it from the stacked cards, and assigning it here
        // would fight that -- the trap NavigationView's rail records.
    }
}
