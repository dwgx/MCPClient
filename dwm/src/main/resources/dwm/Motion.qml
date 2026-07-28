pragma Singleton
import QtQuick

// The animation POLICY layer: what may animate, and for how long.
//
// This exists because Windows treats animation as a system-wide policy rather than as each
// control's private business. `SystemParametersInfo` exposes a master switch (SPI_GETUIEFFECTS)
// plus per-effect toggles (SPI_GETMENUANIMATION, SPI_GETCOMBOBOXANIMATION,
// SPI_GETLISTBOXSMOOTHSCROLLING, SPI_GETCLIENTAREAANIMATION, SPI_GETDROPSHADOW, …), and the
// Performance Options dialog in Advanced System Settings is a view onto exactly that. A control
// does not decide whether it animates; it asks.
//
// Copying that division is the point. The alternative -- a duration literal in each control -- is
// what dwm had, and it makes "turn animations off" unimplementable without editing every file.
//
// THE DEPENDENCY GRAPH IS REAL. In Windows, SPI_SETUIEFFECTS(FALSE) suppresses every effect
// regardless of the individual flags, and subordinate flags are ignored when their master is off:
// SPI_GETMENUFADE means nothing unless SPI_GETMENUANIMATION is set. The `effective*` readonly
// properties below encode that, so a caller reads ONE value and cannot get the precedence wrong.
//
// WHEN ANIMATION IS OFF, JUMP TO THE END STATE. That is the documented expectation for
// SPI_GETCLIENTAREAANIMATION -- the accessibility flag a well-behaved app honours -- and it is not
// the same as "freeze": a control that stops mid-transition is broken, whereas one that arrives
// instantly is merely not animated. Duration 0 is how that is expressed here.

QtObject {
    // ---- durations (WinUI ControlAnimationDuration values) ----
    //
    // Read from microsoft-ui-xaml release/2.8 Common_themeresources_any.xaml, where they are
    // timespans: 00:00:00.083 / .167 / .250. Faster is what every control STATE transition uses
    // (a knob resizing, a colour crossfade); Normal is for larger moves.
    readonly property int durationFaster: 83
    readonly property int durationFast: 167
    readonly property int durationNormal: 250

    // The Expander's own chevron duration, which is its own value rather than one of the three
    // above: Expander_themeresources.xaml animates the chevron over 0:0:0.1.
    readonly property int durationChevron: 100

    // ---- easing (qml4j easing-type numbers) ----
    //
    // WinUI names two curves and uses them by DIRECTION, not by taste:
    //   entering  cubic-bezier(0, 0, 0, 1)  "Fast Out, Slow In"  -- decelerate to rest
    //   exiting   cubic-bezier(1, 0, 1, 1)  "Slow Out, Fast In"  -- accelerate away
    //
    // qml4j takes an easing NUMBER, and the enum names do not resolve in this position, so these
    // are its indices, read from its own Easings.apply jump table:
    //   1 InQuad, 2 OutQuad, 3 InOutQuad, 6 OutCubic.
    // OutQuad (2) is the closest available approximation to the decelerating entrance curve, and
    // InQuad (1) to the accelerating exit. NOT 3 -- InOutQuad accelerates out of the start, which
    // gives a slow-fast-slow motion neither Fluent curve has.
    readonly property int easeEnter: 2
    readonly property int easeExit: 1

    // ---- policy: the master switch (SPI_GETUIEFFECTS) ----
    //
    // False here suppresses everything below, exactly as SPI_SETUIEFFECTS(FALSE) does. This is the
    // "Adjust for best performance" radio button.
    property bool uiEffects: true

    // ---- policy: per-effect toggles ----
    //
    // Named after the SPI flags they stand in for, so the mapping is checkable rather than
    // asserted. Each is subordinate to uiEffects; some are subordinate to each other.

    /** SPI_GETCLIENTAREAANIMATION. The accessibility flag: transient in-content motion. */
    property bool clientAreaAnimation: true

    /** SPI_GETMENUANIMATION. Master for menu/flyout motion. */
    property bool menuAnimation: true

    /** SPI_GETMENUFADE. Fade (true) versus slide (false); IGNORED unless menuAnimation. */
    property bool menuFade: true

    /** SPI_GETCOMBOBOXANIMATION. Stands here for expand/collapse of a disclosure control. */
    property bool comboBoxAnimation: true

    /** SPI_GETLISTBOXSMOOTHSCROLLING. Smooth scrolling versus jumping by a step. */
    property bool listBoxSmoothScrolling: true

    /** SPI_GETDROPSHADOW. Not motion, but the same dialog and the same master. */
    property bool dropShadow: false

    // ---- effective values ----
    //
    // What a control should actually read. The precedence lives here so no caller repeats it, and
    // so a subordinate flag left true under a false master cannot leak through.

    readonly property bool animateControls: uiEffects && clientAreaAnimation
    readonly property bool animateMenus: uiEffects && menuAnimation
    readonly property bool animateExpand: uiEffects && comboBoxAnimation && clientAreaAnimation
    readonly property bool animateScrolling: uiEffects && listBoxSmoothScrolling
    readonly property bool animatePages: uiEffects && clientAreaAnimation
    readonly property bool menuFadesRatherThanSlides: animateMenus && menuFade
    readonly property bool showShadows: uiEffects && dropShadow

    // ---- effective durations ----
    //
    // Zero when the corresponding effect is off, which is what makes "disabled" mean ARRIVE
    // INSTANTLY rather than "do not move": a transition of length zero still ends in the target
    // state. Controls bind their Behavior duration to one of these.
    //
    // NOTE these cannot be read by a Behavior on qml4j 0.2.24 -- it samples duration off the
    // template before bindings resolve, so a bound duration silently reverts to its 250ms default.
    // A control must therefore gate on the boolean and keep its duration a literal. These are the
    // single source of truth for what that literal should be, and the values a test asserts.
    readonly property int controlDuration: animateControls ? durationFaster : 0
    readonly property int expandDuration: animateExpand ? durationNormal : 0
    readonly property int chevronDuration: animateExpand ? durationChevron : 0
    readonly property int pageDuration: animatePages ? durationNormal : 0

    // ---- page transition geometry ----
    //
    // WinUI's "page refresh" (EntranceNavigationTransitionInfo) is a slide up combined with a fade
    // in for the incoming content. Microsoft documents the COMPOSITION -- slide plus fade, upward,
    // for navigation to the top of a stack -- but does not publish the offset, so this value is
    // [approximate] and marked as such rather than presented as a spec number.
    readonly property int pageSlideDistance: 24
}
