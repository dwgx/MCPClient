import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// The board's feature chips: the REAL roster, and the toggles really enable them.
//
// The list comes from board's ChipBridgePort across the Backplane, and a toggle calls board's own
// toggle-by-id command. Board marshals that onto the game thread inside its port, which is why the
// call is made directly from the handler here: a chip's enable can touch live game state, and doing
// the marshalling on this side would duplicate logic owned by the side that owns the chips.
//
// The switches used to be self-held and drive nothing, named after chips that happen to exist. That
// is a worse failure than an empty page, because a switch that moves looks like a switch that works.
//
// Rows are DERIVED from the roster, so the page shows what the board actually has -- including chips
// added later, and including none at all when board is absent. Hand-written rows would drift the
// moment the roster changed.

Item {
    id: chips

    property int pad: Fluent.itemPaddingH
    // The switch column, expressed relative to a ROW's origin since that is where it is used. A
    // switch is measured from its own left edge, so the column sits one 40px track width in from the
    // row's right end. Clamped at 0 because the page's width arrives from the Loader and the
    // expression is negative until it does.
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

    Text {
        x: chips.pad
        y: 50
        text: Dwm.hasChips()
            ? "Live roster - a switch enables the real chip."
            : "No roster published - running without board."
        fontSize: Fluent.fontCaption
        color: Fluent.textSecondary
    }

    Column {
        id: rows
        x: chips.pad
        y: 80
        width: chips.width - (chips.pad * 2)

        Repeater {
            model: Dwm.chips()

            Item {
                id: row
                width: rows.width
                height: Fluent.rowHeight

                Text {
                    x: 0
                    y: (row.height - Fluent.fontBody) / 2 - 2
                    text: modelData.name
                    fontSize: Fluent.fontBody
                    color: Fluent.textPrimary
                }

                FluentToggleSwitch {
                    x: chips.switchX
                    y: 0
                    // The board's own state, as a string, is the source of truth for the initial
                    // position -- not a local default that would disagree with reality on first paint.
                    checked: modelData.enabled === "true"
                    // The switch has already flipped itself by the time this runs, so the board is
                    // asked to match. Its answer is authoritative: a refused or failed toggle snaps
                    // the switch back rather than leaving the UI claiming something untrue.
                    onToggled: checked = Dwm.toggleChip(modelData.id)
                }
            }
        }
    }
}
