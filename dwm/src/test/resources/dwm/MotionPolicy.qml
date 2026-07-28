import QtQuick
import "."

// A window onto the Motion singleton, for MotionPolicyLiveIT.
//
// Test-only. The singleton is not an Item, so findByObjectName cannot reach it; these bindings put
// its live values somewhere a test can read them.
//
// BINDINGS, NOT ALIASES. qml4j 0.2.24 rejects an alias whose target is a nested object -- measured:
// "property alias target resolves to no Property field (v0 allows builtin or root-declared targets
// only)". Bindings re-evaluate when the singleton changes, which is what makes the dependency-graph
// assertions meaningful: the test writes the FLAG on the singleton and reads the EFFECT here.
//
// The writable flags are therefore driven through Motion itself rather than through this scene. That
// is the honest arrangement anyway: policy lives in one place and everything else observes it.

Item {
    id: root
    objectName: "root"
    width: 200
    height: 100

    property int durationFaster: Motion.durationFaster
    property int durationFast: Motion.durationFast
    property int durationNormal: Motion.durationNormal
    property int durationChevron: Motion.durationChevron
    property int easeEnter: Motion.easeEnter
    property int easeExit: Motion.easeExit

    property bool animateControls: Motion.animateControls
    property bool animateMenus: Motion.animateMenus
    property bool animateExpand: Motion.animateExpand
    property bool animateScrolling: Motion.animateScrolling
    property bool animatePages: Motion.animatePages
    property bool menuFadesRatherThanSlides: Motion.menuFadesRatherThanSlides
    property bool showShadows: Motion.showShadows

    // Writable drivers. A test sets these; the handlers push the value INTO the singleton, which
    // is exactly the path a real settings page takes -- a toggle writes policy, everything else
    // observes it. If a scene could not write the singleton, the settings page would be impossible.
    property bool setUiEffects: true
    property bool setMenuAnimation: true
    onSetUiEffectsChanged: Motion.uiEffects = root.setUiEffects
    onSetMenuAnimationChanged: Motion.menuAnimation = root.setMenuAnimation

    property int controlDuration: Motion.controlDuration
    property int expandDuration: Motion.expandDuration
    property int chevronDuration: Motion.chevronDuration
    property int pageDuration: Motion.pageDuration
}
