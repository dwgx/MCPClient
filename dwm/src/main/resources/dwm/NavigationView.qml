import QtQuick
import "."

// The NavigationView body: a nav rail on the left, a hairline divider, and a content area on the
// right whose Loader swaps pages as the rail selection changes.
//
// SELECTION IS DERIVED, NEVER STORED. currentPage is the single piece of state; each row's
// `selected` is a comparison against it and each row's click assigns it. An earlier shape for this
// would have been a bool per row, which has a failure mode that only shows up later: two rows lit
// at once, or none, because one assignment was missed. With a comparison there is no second copy to
// fall out of step -- setting currentPage IS deselecting everything else.
//
// The page paths live in properties rather than being repeated in each row's binding and handler,
// so a path is written once. A typo in a duplicated literal would produce a row that lights up but
// loads nothing, and Loader makes that quiet: a FAILED load leaves the previously loaded item in
// place, so the symptom is a selected row still showing the old page rather than an error.
//
// Loader resolves `source` against the DECLARING document's directory -- this file's, i.e. dwm --
// so the paths are "pages/<name>.qml". It caches by source string and swaps only when that string
// changes, which means re-clicking the current row costs nothing.
//
// The loaded page then gets the RESOLVED path's directory as its own baseDir, i.e. dwm/pages, not
// dwm. That is why each page imports both "." and ".."; the page files carry the measurement.

Item {
    id: nav

    property int railWidth: 140
    // The page currently shown, as a Loader source path. Writing it navigates.
    property string currentPage: nav.pageHome
    // Optional heading above the content area. Empty collapses its block, which is the useful
    // default here: every page already carries its own heading, so a title set at this level would
    // print a second one above it.
    property string title: ""

    // ---- page paths ----
    property string pageHome: "pages/PageHome.qml"
    property string pageKernel: "pages/PageKernel.qml"
    property string pageChips: "pages/PageChips.qml"
    property string pageSettings: "pages/PageSettings.qml"

    // Same 44px title block as MenuPanel, so a panel heading and a content heading sit on one grid.
    property int titleBlock: nav.title === "" ? 0 : 44
    // The content area starts past the rail and its 1px divider.
    property int contentX: nav.railWidth + 1
    property real contentWidth: Math.max(0, nav.width - nav.contentX)

    width: parent ? parent.width : 560
    height: parent ? parent.height : 380

    Column {
        id: rail
        // Inset by the panel padding, so each row's 4px backplate nests inside the shell's 8px
        // corner instead of running into it -- the nesting MenuItem documents.
        x: Fluent.panelPadding
        y: Fluent.panelPadding
        width: nav.railWidth - (Fluent.panelPadding * 2)
        // No height: Column.layout() sets its own from the stacked rows. Nothing here reads it
        // back, and assigning it would fight that.

        // 关于 is deliberately absent. There is no About page in this scene, and an unloadable
        // source would light the row while leaving the previous page on screen -- a nav item that
        // looks like it works and does nothing. It goes in when the page does.

        NavItem {
            objectName: "navHome"
            glyph: "⌂"
            label: "主页"
            selected: nav.currentPage === nav.pageHome
            onClicked: nav.currentPage = nav.pageHome
        }

        NavItem {
            objectName: "navKernel"
            glyph: "▦"
            label: "内核"
            selected: nav.currentPage === nav.pageKernel
            onClicked: nav.currentPage = nav.pageKernel
        }

        NavItem {
            objectName: "navChips"
            glyph: "⌗"
            label: "芯片"
            selected: nav.currentPage === nav.pageChips
            onClicked: nav.currentPage = nav.pageChips
        }

        NavItem {
            objectName: "navSettings"
            glyph: "⚙"
            label: "设置"
            selected: nav.currentPage === nav.pageSettings
            onClicked: nav.currentPage = nav.pageSettings
        }
    }

    Rectangle {
        // Full height rather than inset: this line separates two regions of the window, so it runs
        // the whole way like a pane split, unlike MenuSeparator's inset line between two rows.
        x: nav.railWidth
        y: 0
        width: 1
        height: nav.height
        color: Fluent.divider
    }

    Text {
        x: nav.contentX + Fluent.itemPaddingH
        y: 12
        text: nav.title
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    // ---- page transition ----------------------------------------------------------------
    //
    // WinUI calls this "page refresh" (EntranceNavigationTransitionInfo) and describes it as a
    // slide up combined with a fade in for the INCOMING content. It is the transition a Frame
    // plays by default and the one NavigationView uses for left-nav items, because the intended
    // feeling is that the user has started over -- not travelled sideways or drilled deeper, which
    // are the other two documented transitions and would be the wrong message for a rail.
    //
    // Only the incoming page is animated. There is no outgoing half: Loader swaps its item
    // synchronously, so the old page is already gone by the time this runs, and pretending to
    // cross-fade would mean holding two pages alive for a frame.
    //
    // Driven from a progress value rather than by animating x and opacity separately, so the slide
    // and the fade cannot drift out of step. 0 = just arrived (offset and transparent), 1 = settled.
    // Counts navigations. The animation is driven from its PARITY rather than by resetting a
    // progress value, and that is not a trick -- it is the only shape that works here. qml4j's
    // Behavior decides whether to tween by comparing against the value it last DISPLAYED, so
    // writing 0 and then 1 inside one handler leaves lastDisplayed at 1, the tween is skipped, and
    // the transition silently never plays. Measured: the handler ran (a counter proved it) while
    // the progress value never left 1.0.
    //
    // A monotonic counter has no such collapse: every navigation moves it to a value it has never
    // held, so the Behavior always has a real interval to interpolate.
    property int navCount: 0
    onCurrentPageChanged: nav.navCount = nav.navCount + 1

    // The animated follower. It lags navCount by exactly one step immediately after a navigation
    // and catches up over the transition, so `navCount - animatedNav` IS the remaining progress.
    property real animatedNav: 0

    onNavCountChanged: nav.animatedNav = nav.navCount

    // 0 just after a navigation, rising to 1 as the follower catches up. Clamped because a second
    // navigation mid-flight leaves the follower more than one behind, and a progress below zero
    // would push the incoming page further away than its slide distance.
    readonly property real pageProgress:
        Math.max(0, Math.min(1, 1 - (nav.navCount - nav.animatedNav)))

    Behavior on animatedNav {
        NumberAnimation {
            // 250 is ControlNormalAnimationDuration. A page arriving is a large move, not a control
            // state change, so the Faster tier would read as a flinch. A literal because qml4j
            // 0.2.24 samples a Behavior's duration off the template before bindings resolve.
            duration: 250
            // 2 is OutQuad, standing in for WinUI's entrance curve cubic-bezier(0,0,0,1) -- content
            // ENTERING decelerates to rest. The exit curve is the mirror of this and is not used
            // here, because nothing exits.
            easing.type: 2
        }
    }

    Loader {
        id: page
        // The page gets the content area edge to edge and applies its own padding. Handing it a
        // pre-padded box instead would put the page's margins in two files.
        x: nav.contentX
        // Slid up into place: offset DOWN by the remaining progress, so it rises as it settles.
        // The distance is [approximate] -- Microsoft documents the composition (slide plus fade,
        // upward) but not the offset, so this is a value that reads correctly rather than a spec
        // number. With the effect disabled the offset is zero and the page simply appears.
        y: nav.titleBlock + (Motion.animatePages
            ? (1.0 - nav.pageProgress) * Motion.pageSlideDistance : 0)
        width: nav.contentWidth
        height: Math.max(0, nav.height - nav.titleBlock)
        // The fade half of the same transition.
        opacity: Motion.animatePages ? nav.pageProgress : 1.0
        source: nav.currentPage
    }
}
