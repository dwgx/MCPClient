import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// The settings page from the approved mockup: a toggle, a labelled slider, a text box and a check
// box, in that order.
//
// EVERY CONTROL HOLDS ITS OWN VALUE AND APPLIES NOTHING. Gamma really is a client setting, but
// writing it means reaching through a Backplane into the game, which is out of scope here -- so
// dragging the slider moves the slider. The gamma readout is computed from the slider's own value
// rather than stored beside it, so there is no second copy to fall out of step.

Item {
    id: settings

    property int pad: Fluent.itemPaddingH
    // Shared left edge for the value column, so the toggle and the readout line up.
    property real valueX: Math.max(0, settings.width - settings.pad - 60)

    // Sized to whatever box the Loader gives it, with a fallback for standalone instantiation.
    // Explicit, never anchors.fill -- see the trap documented in MenuItem.qml.
    width: parent ? parent.width : 420
    height: parent ? parent.height : 340

    Text {
        x: settings.pad
        y: settings.pad
        text: "Settings"
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    Item {
        id: fullbrightRow
        x: settings.pad
        y: settings.pad + 34
        width: Math.max(0, settings.width - (settings.pad * 2))
        height: Fluent.rowHeight

        Text {
            y: (fullbrightRow.height - Fluent.fontBody) / 2 - 2
            text: "Fullbright"
            fontSize: Fluent.fontBody
            color: Fluent.textPrimary
        }

        FluentToggleSwitch {
            objectName: "settingsFullbright"
            // Measured from the row's origin, which the page padding already inset.
            x: fullbrightRow.width - 40
            checked: false
        }
    }

    Text {
        x: settings.pad
        y: settings.pad + 34 + Fluent.rowHeight + 4
        text: "Gamma"
        fontSize: Fluent.fontCaption
        color: Fluent.textTertiary
    }

    FluentSlider {
        id: gamma
        objectName: "settingsGamma"
        x: settings.pad
        y: settings.pad + 34 + Fluent.rowHeight + 20
        // Leaves room for the readout to the right of the track. The rowHeight floor is not
        // cosmetic: this control's MouseArea is bound to its width, and a zero-width one renders
        // perfectly while never receiving a click -- the trap FluentButton records. It bites here
        // because the page's width comes from the Loader, which leaves the fallback binding in
        // place for any pass where its own box is still zero.
        width: Math.max(Fluent.rowHeight, settings.width - (settings.pad * 2) - 50)
        value: 0.5
    }

    Text {
        // Reads the slider directly, so there is nothing to keep in sync as it moves. Rendered as a
        // percentage via Math.round rather than value.toFixed(2): bindings evaluate in Rhino, and
        // Math is the numeric surface the rest of dwm already relies on.
        x: gamma.x + gamma.width + Fluent.gutter
        y: gamma.y + (Fluent.rowHeight - Fluent.fontCaption) / 2 - 1
        text: Math.round(gamma.value * 100) + "%"
        fontSize: Fluent.fontCaption
        color: Fluent.textSecondary
    }

    Text {
        x: settings.pad
        y: gamma.y + Fluent.rowHeight + 8
        text: "Name"
        fontSize: Fluent.fontCaption
        color: Fluent.textTertiary
    }

    FluentTextBox {
        objectName: "settingsName"
        x: settings.pad
        y: gamma.y + Fluent.rowHeight + 24
        // Floored for the same reason as the slider, and it matters more here: qml4j hit-tests a
        // text field by its own geometry, so a zero-width one can never be clicked into focus and
        // never sees a keystroke.
        width: Math.max(Fluent.rowHeight, settings.width - (settings.pad * 2))
        placeholder: "display name"
    }

    FluentCheckBox {
        objectName: "settingsTelemetry"
        x: settings.pad
        // Below the text box's own 32px height.
        y: gamma.y + Fluent.rowHeight + 24 + 32 + Fluent.gutter
        text: "Keep the overlay open on world change"
        checked: true
    }
}
