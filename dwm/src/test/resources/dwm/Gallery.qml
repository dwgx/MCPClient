import QtQuick
import "."

// Every control at once, for ControlGalleryLiveIT. Test-only: it is the fixture the pixel
// assertions sample, and keeping it out of src/main means the shipped jar carries no scene that
// exists purely to be measured.
//
// It sits at dwm/ rather than dwm/test/ because `import "."` resolves against the directory that
// owns the qmldir naming these components, and that qmldir is dwm/qmldir. From a subdirectory the
// prefix would resolve to nothing and every control would be an unknown type. Test resources merge
// over main ones on the classpath, so this loads beside the real scenes without shipping in the jar.
//
// Positions are deliberately fixed and generously spaced. The IT samples absolute coordinates, so
// a control that moves silently changes what a "the fill is accent here" assertion is looking at;
// spacing keeps a sample well inside its own control rather than near a neighbour's edge.

Item {
    id: gallery

    width: 420
    height: 380

    FluentButton {
        objectName: "standardButton"
        x: 20
        y: 20
        text: "Standard"
    }

    FluentButton {
        objectName: "accentButton"
        x: 160
        y: 20
        text: "Accent"
        accent: true
    }

    FluentToggleSwitch {
        objectName: "toggleOn"
        x: 20
        y: 70
        checked: true
        text: "On"
    }

    FluentToggleSwitch {
        objectName: "toggleOff"
        x: 160
        y: 70
        checked: false
        text: "Off"
    }

    FluentCheckBox {
        objectName: "checkOn"
        x: 20
        y: 120
        checked: true
        text: "Checked"
    }

    FluentCheckBox {
        objectName: "checkOff"
        x: 160
        y: 120
        checked: false
        text: "Unchecked"
    }

    FluentSlider {
        objectName: "slider"
        x: 20
        y: 170
        width: 200
        value: 0.5
    }

    FluentProgressBar {
        objectName: "progress"
        x: 20
        y: 230
        width: 200
        value: 0.25
    }

    FluentTextBox {
        objectName: "textBox"
        x: 20
        y: 270
        width: 200
        placeholder: "type here"
    }
}
