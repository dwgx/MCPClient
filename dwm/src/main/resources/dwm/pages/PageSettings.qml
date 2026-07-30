import QtQuick
// Registered in dwm/qmldir, so a Loader resolves this page to its already-compiled class and
// never recompiles the document -- which is what makes the import prefix below irrelevant to that
// path. It matters for the OTHER path: an UNREGISTERED page compiled fresh by
// Loader.createFromSource is handed the resolved file's own directory as its baseDir (dwm/pages),
// not the qmldir's, so "." would probe a dwm/pages/qmldir that does not exist and every control
// here would be an unknown type. Measured both ways against qml4j 0.2.24. ".." is therefore the
// prefix that holds regardless of how the page is reached.
import ".."

// The settings page, in the Windows Settings idiom: titled groups of cards, each card carrying an
// icon, a header, a description and its control.
//
// WHAT CHANGED AND WHY. The previous version stacked bare label+control rows. Every control in it
// was metrically accurate -- and it still did not look like Windows Settings, because the page
// idiom is the CARD: a plate with an edge, an icon column, and a two-line text block. Card
// metrics and provenance are in FluentSettingsCard; the page's job is only to group them.
//
// EVERY CONTROL HOLDS ITS OWN VALUE AND APPLIES NOTHING. Gamma really is a client setting, but
// writing it means reaching through a Backplane into the game, which is out of scope here -- so
// dragging the slider moves the slider. The gamma readout is computed from the slider's own value
// rather than stored beside it, so there is no second copy to fall out of step.
//
// The objectNames are unchanged from the row-based version on purpose: the live probe drives this
// page through them, so a rewrite that renamed them would silently stop being verified.

Item {
    id: settings

    // The page's own margin. 16 matches a card's internal padding, so a card's text column and the
    // page title sit on one grid rather than two.
    property int pad: 16

    // Sized to whatever box the Loader gives it, with a fallback for standalone instantiation.
    // Explicit, never anchors.fill -- see the trap documented in MenuItem.qml.
    width: parent ? parent.width : 420
    height: parent ? parent.height : 340

    Text {
        id: pageTitle
        x: settings.pad
        y: settings.pad
        text: "设置"
        fontSize: Fluent.fontSubtitle
        color: Fluent.textPrimary
    }

    // Scrolling is not optional here: the content area is 419 logical px wide and 380 tall, while
    // two groups of 68px cards plus their headings exceed that. A page that silently clipped its
    // last card would be worse than one that scrolls.
    Flickable {
        id: scroller
        x: 0
        y: pageTitle.y + pageTitle.implicitHeight + Fluent.gutter
        width: settings.width
        height: Math.max(0, settings.height - scroller.y)
        // Vertical only: nothing here is wider than the page, and a horizontal drag would just
        // shift the cards sideways for no reason.
        flickableDirection: "VerticalFlick"
        contentWidth: settings.width
        // Driven by the stacked groups, so adding a card cannot leave content unreachable.
        contentHeight: groups.height + (settings.pad * 2)
        clip: true

        Column {
            id: groups
            x: settings.pad
            y: 0
            width: Math.max(0, settings.width - (settings.pad * 2))
            // Groups sit further apart than the cards inside them -- the spacing is what separates
            // one block of settings from the next.
            spacing: 20
            // No height: Column.layout() computes it from its children, and assigning it would
            // fight that.

            FluentSettingsGroup {
                title: "外观"
                width: groups.width

                FluentSettingsCard {
                    icon: "brightness"
                    header: "全亮"
                    description: "渲染方块时忽略世界光照"
                    width: groups.width

                    FluentToggleSwitch {
                        objectName: "settingsFullbright"
                        checked: false
                    }
                }

                FluentSettingsCard {
                    id: gammaCard
                    icon: "contrast"
                    header: "伽马"
                    description: "提亮阴影区域"
                    width: groups.width

                    // The slider and its readout travel together, so they go in as one item -- a
                    // card takes a single content node.
                    Item {
                        id: gammaContent
                        // Wide enough to be draggable, narrow enough to leave the header its
                        // column. A card's content is right-aligned, so this width IS the slider's
                        // extent and a zero one would render fine while never receiving a click --
                        // the trap FluentButton records.
                        width: 160
                        height: Fluent.rowHeight

                        FluentSlider {
                            id: gamma
                            objectName: "settingsGamma"
                            x: 0
                            y: 0
                            width: 110
                            value: 0.5
                        }

                        Text {
                            // Reads the slider directly, so there is nothing to keep in sync as it
                            // moves. Rendered as a percentage via Math.round rather than
                            // value.toFixed(2): bindings evaluate in Rhino, and Math is the numeric
                            // surface the rest of dwm already relies on.
                            x: gamma.width + Fluent.gutter
                            y: (Fluent.rowHeight - Fluent.fontCaption) / 2 - 1
                            text: Math.round(gamma.value * 100) + "%"
                            fontSize: Fluent.fontCaption
                            color: Fluent.textSecondary
                        }
                    }
                }

                FluentSettingsCard {
                    icon: "person"
                    header: "显示名称"
                    description: "覆盖界面中显示的名字"
                    width: groups.width

                    FluentTextBox {
                        objectName: "settingsName"
                        // Explicit, because FluentTextBox binds its width to parent.width by
                        // default and its parent here is the card's content holder, which measures
                        // ITS width from this node -- a circular binding that resolves to zero, and
                        // a zero-width field can never be clicked into focus.
                        width: 160
                        placeholder: "显示名称"
                    }
                }
            }

            FluentSettingsGroup {
                title: "高级"
                width: groups.width

                FluentSettingsExpander {
                    objectName: "settingsAdvanced"
                    icon: "overlay"
                    header: "覆盖层行为"
                    description: "界面何时保持打开"
                    width: groups.width

                    FluentSettingsItem {
                        label: "世界切换时保持打开"
                        width: groups.width

                        FluentCheckBox {
                            objectName: "settingsTelemetry"
                            checked: true
                        }
                    }

                    FluentSettingsItem {
                        label: "暂停菜单时保持打开"
                        width: groups.width

                        FluentCheckBox {
                            objectName: "settingsKeepOnPause"
                            checked: false
                        }
                    }
                }
            }

            // ---- visual effects ---------------------------------------------------------
            //
            // Windows' Performance Options dialog, in the shape Windows actually gives it: ONE
            // master switch (SPI_SETUIEFFECTS, the "Adjust for best performance" radio) over a set
            // of subordinate toggles, some of which are themselves subordinate to each other
            // (SPI_GETMENUFADE means nothing unless SPI_GETMENUANIMATION is set).
            //
            // The dependency graph lives in Motion, not here. This page WRITES policy and reads
            // back the effective values to decide what to grey out; it does not restate the
            // precedence, because a second copy of that logic is a second thing to get wrong.
            FluentSettingsGroup {
                title: "视觉效果"
                width: groups.width

                FluentSettingsCard {
                    objectName: "fxMaster"
                    icon: "gauge"
                    header: "界面动画与效果"
                    description: "关闭后所有动画立即完成,而不是停在中途"
                    width: groups.width

                    FluentToggleSwitch {
                        objectName: "fxMasterToggle"
                        checked: Motion.uiEffects
                        // Writes the master. Everything below reads its effect through Motion, so
                        // one assignment greys out the whole group.
                        onToggled: Motion.uiEffects = checked
                    }
                }

                FluentSettingsExpander {
                    objectName: "fxDetails"
                    icon: "overlay"
                    header: "逐项设置"
                    description: "总开关关闭时这些项不起作用"
                    width: groups.width
                    // Subordinate to the master, exactly as the dialog's checkbox list is: with
                    // effects off the individual rows are disabled rather than merely ineffective,
                    // so the UI does not offer a choice that cannot take effect.
                    enabled: Motion.uiEffects

                    FluentSettingsItem {
                        label: "控件状态动画"
                        width: groups.width
                        enabled: Motion.uiEffects

                        FluentCheckBox {
                            objectName: "fxClientArea"
                            checked: Motion.clientAreaAnimation
                            enabled: Motion.uiEffects
                            onToggled: Motion.clientAreaAnimation = checked
                        }
                    }

                    FluentSettingsItem {
                        label: "展开与收缩动画"
                        width: groups.width
                        enabled: Motion.uiEffects

                        FluentCheckBox {
                            objectName: "fxExpand"
                            checked: Motion.comboBoxAnimation
                            enabled: Motion.uiEffects
                            onToggled: Motion.comboBoxAnimation = checked
                        }
                    }

                    // 平滑滚动 / 菜单动画 / 菜单渐隐 USED TO BE OFFERED HERE AND ARE GONE ON
                    // PURPOSE. Each wrote a Motion flag that NOTHING in this scene reads:
                    // animateScrolling and menuFadesRatherThanSlides have no consumer at all, and
                    // animateMenus is read only by the row that greyed out its own child. The
                    // Flickable scrolls through qml4j's own smoothing, which does not consult the
                    // policy, and this scene has no menu -- MenuPanel/MenuItem live in Main.qml,
                    // which the shell does not load.
                    //
                    // So all three were switches a user could flip with no observable effect. That
                    // is the exact defect the `enabled:` bindings above exist to prevent: this file
                    // already refuses to OFFER a choice that cannot take effect, and offering one
                    // that silently does nothing is worse, because a disabled control at least
                    // tells the truth. Windows has the same rule -- the Performance Options list
                    // shows the effects that machine can actually apply.
                    //
                    // They go back in when something reads them: a menu in this scene for the two
                    // menu flags, and a scroll path that asks the policy for animateScrolling.
                    // Motion.qml deliberately KEEPS all of them, because it models
                    // SystemParametersInfo rather than this page's current contents.
                }
            }
        }
    }
}
