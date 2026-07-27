import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// The board's feature chips, one toggle per chip.
//
// THE TOGGLES ARE SELF-HELD AND DRIVE NOTHING. A real chip is enabled through board's ChipBridge,
// which needs a Backplane and is out of scope here, so flipping one of these changes this page and
// nothing else. Named after the chips that exist so the widths are honest.

Item {
    id: chips

    property int pad: Fluent.itemPaddingH
    // The switch column, right-aligned against the page's padding and expressed relative to a ROW's
    // origin, since that is where it gets used. A switch is measured from its own left edge, so the
    // column sits one 40px track width in from the row's right end. Clamped at 0 because the page's
    // width arrives from the Loader, and an unclamped expression is negative until it does.
    property real switchX: Math.max(0, chips.width - (chips.pad * 2) - 40)

    // Sized to whatever box the Loader gives it, with a fallback for standalone instantiation.
    // Explicit, never anchors.fill -- see the trap documented in MenuItem.qml.
    width: parent ? parent.width : 420
    height: parent ? parent.height : 340

    Text {
        x: chips.pad
        y: chips.pad
        text: "Chips"
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    Column {
        id: rows
        x: chips.pad
        y: chips.pad + 34
        width: Math.max(0, chips.width - (chips.pad * 2))
        // No height: Column.layout() sets its own from the stacked rows.

        // Each row is an Item holding a label and a switch at a shared x, so the switches line up
        // down the page. The switch carries no text of its own for the same reason -- its own label
        // would sit to its right, off the alignment.

        Item {
            id: fullbrightRow
            width: rows.width
            height: Fluent.rowHeight

            Text {
                y: (fullbrightRow.height - Fluent.fontBody) / 2 - 2
                text: "Fullbright"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }

            FluentToggleSwitch {
                objectName: "chipFullbright"
                x: chips.switchX
                checked: true
            }
        }

        Item {
            id: coordsRow
            width: rows.width
            height: Fluent.rowHeight

            Text {
                y: (coordsRow.height - Fluent.fontBody) / 2 - 2
                text: "Coordinates"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }

            FluentToggleSwitch {
                objectName: "chipCoordinates"
                x: chips.switchX
                checked: true
            }
        }

        Item {
            id: fpsRow
            width: rows.width
            height: Fluent.rowHeight

            Text {
                y: (fpsRow.height - Fluent.fontBody) / 2 - 2
                text: "FPS meter"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }

            FluentToggleSwitch {
                objectName: "chipFpsMeter"
                x: chips.switchX
                checked: false
            }
        }

        Item {
            id: hitboxRow
            width: rows.width
            height: Fluent.rowHeight

            Text {
                y: (hitboxRow.height - Fluent.fontBody) / 2 - 2
                text: "Entity hitboxes"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }

            FluentToggleSwitch {
                objectName: "chipHitboxes"
                x: chips.switchX
                checked: false
            }
        }
    }
}
