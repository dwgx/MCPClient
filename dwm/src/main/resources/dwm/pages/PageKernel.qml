import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// Kernel state as a label/value list plus a load bar.
//
// THE NUMBERS ARE FABRICATED. Reading the real ring, patch set or uptime means reflecting through a
// Backplane into core, which is out of scope here -- so these are plausible literals whose only job
// is to give the rows realistic widths to be judged at. Nothing on this page observes anything.

Item {
    id: kernel

    property int pad: Fluent.itemPaddingH
    // Where the value column starts. One property rather than a repeated number per row: the rows
    // have to align, and aligning them in four places is how one of them ends up not.
    property int valueX: 150

    // Sized to whatever box the Loader gives it, with a fallback for standalone instantiation.
    // Explicit, never anchors.fill -- see the trap documented in MenuItem.qml.
    width: parent ? parent.width : 420
    height: parent ? parent.height : 340

    Text {
        x: kernel.pad
        y: kernel.pad
        text: "Kernel"
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    // Rows are stacked by a Column so adding one needs no y arithmetic. Each row is a full-width
    // Item carrying two Texts, rather than a Row, because the value column has to line up across
    // rows at a fixed x -- a Row would place each value right after its own label instead.
    Column {
        id: rows
        x: kernel.pad
        y: kernel.pad + 34
        width: Math.max(0, kernel.width - (kernel.pad * 2))
        // No height: Column.layout() sets its own from the stacked children.

        Item {
            id: ringRow
            width: rows.width
            height: 28

            Text {
                y: (ringRow.height - Fluent.fontBody) / 2 - 2
                text: "Privilege ring"
                fontSize: Fluent.fontBody
                color: Fluent.textSecondary
            }

            Text {
                x: kernel.valueX
                y: (ringRow.height - Fluent.fontBody) / 2 - 2
                text: "R-2 (operator)"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }
        }

        Item {
            id: patchRow
            width: rows.width
            height: 28

            Text {
                y: (patchRow.height - Fluent.fontBody) / 2 - 2
                text: "Armed patches"
                fontSize: Fluent.fontBody
                color: Fluent.textSecondary
            }

            Text {
                x: kernel.valueX
                y: (patchRow.height - Fluent.fontBody) / 2 - 2
                text: "11 of 14"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }
        }

        Item {
            id: uptimeRow
            width: rows.width
            height: 28

            Text {
                y: (uptimeRow.height - Fluent.fontBody) / 2 - 2
                text: "Uptime"
                fontSize: Fluent.fontBody
                color: Fluent.textSecondary
            }

            Text {
                x: kernel.valueX
                y: (uptimeRow.height - Fluent.fontBody) / 2 - 2
                text: "00:42:18"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }
        }

        Item {
            id: capsRow
            width: rows.width
            height: 28

            Text {
                y: (capsRow.height - Fluent.fontBody) / 2 - 2
                text: "Capability packs"
                fontSize: Fluent.fontBody
                color: Fluent.textSecondary
            }

            Text {
                x: kernel.valueX
                y: (capsRow.height - Fluent.fontBody) / 2 - 2
                text: "C1-C6 unlocked"
                fontSize: Fluent.fontBody
                color: Fluent.textPrimary
            }
        }
    }

    Text {
        x: kernel.pad
        y: kernel.pad + 34 + 112 + Fluent.gutter
        text: "Patch scan"
        fontSize: Fluent.fontCaption
        color: Fluent.textTertiary
    }

    FluentProgressBar {
        objectName: "kernelProgress"
        x: kernel.pad
        // Below the label, positioned off the same stack base as it. Not bound to rows.height:
        // a determinate bar is 4px tall and would visibly jump if the Column published its height a
        // pass later, and the row count here is fixed anyway.
        y: kernel.pad + 34 + 112 + Fluent.gutter + 20
        // Floored rather than clamped at 0: the bar has no MouseArea, but a zero-width one also
        // divides its own segment width by nothing useful, and the page's width arrives from the
        // Loader a pass later.
        width: Math.max(Fluent.rowHeight, kernel.width - (kernel.pad * 2))
        // Determinate on purpose: indeterminate writes a property every tick, which defeats the
        // compositor's idle fast path and re-paints the whole scene per frame for as long as it
        // runs. FluentProgressBar documents this.
        value: 0.78
    }
}
