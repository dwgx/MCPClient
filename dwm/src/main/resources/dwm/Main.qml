import QtQuick

// DWM default scene — the smoke target for the qml4j backend.
//
// Kept deliberately minimal: its job is to prove the pipeline end to end (Skija context over
// MC's framebuffer, per-frame retarget, GL state isolation, input routing) with a result that
// is unambiguous on screen. A translucent panel over the live game frame means a black screen
// or a missing panel are both obvious failures rather than plausible-looking ones.

Rectangle {
    x: 40
    y: 40
    width: 420
    height: 200
    radius: 12
    color: "#cc1e2430"

    Text {
        x: 24; y: 28
        text: "DWM / qml4j"
        color: "#ffffff"
        fontSize: 26
    }

    Text {
        x: 24; y: 76
        text: "Skija over Minecraft's framebuffer"
        color: "#9fb4d0"
        fontSize: 14
    }

    Text {
        x: 24; y: 104
        text: "The game keeps ticking behind this panel."
        color: "#9fb4d0"
        fontSize: 14
    }

    Text {
        x: 24; y: 148
        text: "ESC to close"
        color: "#6f8bab"
        fontSize: 13
    }
}
