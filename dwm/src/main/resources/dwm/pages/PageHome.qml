import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// The landing page: a heading, one line of orientation, and two buttons.
//
// Every page in this set holds its OWN state and reads nothing real. Reaching live kernel or board
// data means reflecting through a Backplane, which is out of scope here -- so these are shapes for
// the layout to be judged against, not a view of anything.

Item {
    id: home

    // Padding is the page's own, because the Loader hands it the content area edge to edge.
    property int pad: Fluent.itemPaddingH

    // Sized to whatever box the Loader gives it, with a fallback for standalone instantiation.
    // Explicit, never anchors.fill -- see the trap documented in MenuItem.qml.
    width: parent ? parent.width : 420
    height: parent ? parent.height : 340

    Text {
        x: home.pad
        y: home.pad
        text: "Home"
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    Text {
        x: home.pad
        y: home.pad + 34
        text: "DWM composites this window inside Minecraft's own frame."
        fontSize: Fluent.fontBody
        color: Fluent.textSecondary
    }

    Row {
        x: home.pad
        y: home.pad + 70
        spacing: Fluent.gutter

        // Buttons size themselves to their labels, so the Row's own width follows from the content
        // and nothing here does layout arithmetic.
        FluentButton {
            objectName: "homePrimary"
            text: "Open kernel"
            accent: true
        }

        FluentButton {
            objectName: "homeSecondary"
            text: "Reload scene"
        }
    }
}
