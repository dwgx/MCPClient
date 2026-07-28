import QtQuick
import "."

// SettingsCard fixtures for SettingsCardLiveIT. Test-only: it exists to be measured, and keeping
// it out of src/main means the shipped jar carries no scene whose only purpose is assertion.
//
// Sits at dwm/ rather than dwm/test/ because `import "."` resolves against the directory owning
// the qmldir that names these components, which is dwm/qmldir. Test resources merge over main
// ones on the classpath, so this loads beside the real scenes without shipping.

Item {
    id: gallery

    width: 420
    height: 400

    // An opaque base, so a card's low-alpha plate composites against a known colour rather than
    // against transparency -- the same role Fluent.panelFill plays in the shipped shell.
    Rectangle {
        x: 0
        y: 0
        width: 420
        height: 400
        color: Fluent.panelFill
    }

    FluentSettingsCard {
        objectName: "plainCard"
        x: 10
        y: 10
        width: 400
        icon: "brightness"
        header: "Header"
        description: "Description line"

        FluentToggleSwitch { objectName: "cardToggle"; checked: true }
    }

    // No description: must still meet the 68px minimum rather than shrinking to its text.
    FluentSettingsCard {
        objectName: "headerOnlyCard"
        x: 10
        y: 90
        width: 400
        icon: "person"
        header: "Header only"
    }

    FluentSettingsCard {
        objectName: "clickableCard"
        x: 10
        y: 170
        width: 400
        icon: "gauge"
        header: "Clickable"
        description: "Has an action chevron"
        clickable: true
    }

    FluentSettingsExpander {
        objectName: "expander"
        x: 10
        y: 250
        width: 400
        icon: "overlay"
        header: "Expander"
        description: "Opens to reveal rows"

        FluentSettingsItem {
            objectName: "expanderRow"
            label: "Nested row"
            width: 400
            FluentCheckBox { objectName: "rowCheck"; checked: false }
        }
    }
}
