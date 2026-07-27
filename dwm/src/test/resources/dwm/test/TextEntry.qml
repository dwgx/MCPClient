import QtQuick

// A focusable text field, for KeyDispatchLiveIT. Test-only: it lives under src/test/resources
// because no shipped scene has a text field, which is precisely why the dead key path went
// unnoticed. Deliberately minimal — the assertion is about characters arriving, not layout.

Item {
    width: 400
    height: 200

    TextField {
        objectName: "entry"
        x: 10
        y: 10
        width: 300
        height: 40
    }
}
