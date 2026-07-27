import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// The kernel page: the REAL 7-layer security posture, read live from the running kernel.
//
// Rows come from core's KernelStatePort across the Backplane -- clearance, integrity, disabled
// privileges, revoked capabilities, armed patches, MCP facade. Core recomputes the whole snapshot on
// every read, so a runtime disable_privilege or revoke_capability shows up the next time this page is
// laid out.
//
// This page previously displayed invented values ("R-2 (operator)", "11 of 14", "00:42:18"). The old
// comment was at least honest that they were fabricated, but plausible-looking fake state is worse
// than an empty panel: nobody double-checks a number that already looks right, and a security
// posture is the last place to practise on.
//
// The rows are DERIVED, not declared. A Repeater over Dwm.kernelRows() cannot fall out of step with
// what the kernel publishes, and a row added on the core side appears here with no edit -- whereas
// four hand-written rows are four things to forget. Verified against qml4j 0.2.24 that a Repeater
// delegate does participate in the Column's stacking, so the Column's height still follows content.

Item {
    id: kernel

    property int pad: Fluent.itemPaddingH
    // Where the value column starts. One property rather than a number per row: the rows have to
    // align, and aligning them in several places is how one of them ends up not. Fixed rather than
    // measured off the labels, because Text only reports an implicitWidth after a measure pass and a
    // value column bound to it would shift on the first frame.
    property int valueX: 190

    // Sized to whatever box the Loader gives it, with a fallback for standalone instantiation.
    // Explicit, never anchors.fill -- see the trap documented in MenuItem.qml.
    width: parent ? parent.width : 420
    height: parent ? parent.height : 340

    Text {
        x: kernel.pad
        y: 16
        text: "Kernel"
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    // Says which it is rather than leaving an empty panel to be interpreted: running without core is
    // an ordinary state for a detachable module, not a fault.
    Text {
        x: kernel.pad
        y: 50
        text: Dwm.hasKernel()
            ? "Live posture, recomputed on every read."
            : "No kernel state published - running without core."
        fontSize: Fluent.fontCaption
        color: Fluent.textSecondary
    }

    Column {
        id: rows
        x: kernel.pad
        y: 80
        width: kernel.width - (kernel.pad * 2)

        Repeater {
            model: Dwm.kernelRows()

            Item {
                width: rows.width
                height: 28

                Text {
                    x: 0
                    y: 4
                    text: modelData.label
                    fontSize: Fluent.fontBody
                    color: Fluent.textSecondary
                }

                Text {
                    x: kernel.valueX
                    y: 4
                    text: modelData.value
                    fontSize: Fluent.fontBody
                    color: Fluent.textPrimary
                }
            }
        }
    }
}
