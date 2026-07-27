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

    Loader {
        id: page
        // The page gets the content area edge to edge and applies its own padding. Handing it a
        // pre-padded box instead would put the page's margins in two files.
        x: nav.contentX
        y: nav.titleBlock
        width: nav.contentWidth
        height: Math.max(0, nav.height - nav.titleBlock)
        source: nav.currentPage
    }
}
