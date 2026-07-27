# Windows 11 / Fluent 2 规格摘录(dwm UI 依据)

dwm 的 UI 按 Windows 11 Fluent 设计,契合项目的 NT 隐喻(core=NT kernel, board=PCB,
compat=AppCompat, dwm=Desktop Window Manager)。

**本文区分两类数字**:标 [官方] 的来自 Microsoft Learn 文档,可直接引用;
标 [近似] 的是从公开截图/Fluent 2 观察推得,WinUI 的权威色值只存在于 Windows App SDK
随附的 `Common_themeresources` XAML 字典里,不在公开文档中枚举。改这些值时不要假装它们精确。

---

## 圆角 [官方]

来源:[Geometry in Windows 11](https://learn.microsoft.com/en-us/windows/apps/design/signature-experiences/geometry)

| 半径 | 用于 |
|---|---|
| **8px** | 顶层容器:应用窗口、**flyout**、dialog、MenuFlyout、TeachingTip |
| **4px** | 页内元素:Button、CheckBox、ComboBox、TextBox、ListView、以及"背板"(backplate) |
| **4px** | 条状元素:ProgressBar、ScrollBar、Slider;ToolTip 因尺寸小也用 4px |
| **0px** | 直边相交处;窗口贴边/最大化时 |

两个全局资源控制默认值:`ControlCornerRadius`=4px、`OverlayCornerRadius`=8px。

**推论**:菜单面板本体 8px,菜单项的悬停背板 4px。这个 8/4 嵌套关系是 Windows 11 观感的核心。

## 排版 [官方]

来源:[Typography in Windows](https://learn.microsoft.com/en-us/windows/apps/design/signature-experiences/typography)

字体 **Segoe UI Variable**(变量字体,`wght` 100-700 + `opsz` 光学尺寸轴自动)。
Type ramp(单位 epx,**尺寸/行高**):

| 样式 | 字重 | 尺寸/行高 |
|---|---|---|
| Caption | Small | 12/16 |
| **Body** | Text (400) | **14/20** |
| **Body Strong** | Semibold (600) | **14/20** |
| Body Large | Text | 18/24 |
| Body Large Strong | Semibold | 18/24 |
| **Subtitle** | Display Semibold | **20/28** |
| Title | Display Semibold | 28/36 |
| Title Large | Display Semibold | 40/52 |
| Display | Display Semibold | 68/92 |

规则 [官方]:
- 正文用 Regular,标题用 **Semibold**;**不用 Bold**(用 Semibold 表强调),不用 Italic(降低可读性,对阅读障碍者尤甚)。
- 最小可读值:**14px Semibold / 12px Regular**,再小在部分语言下不可读。
- 大小写:**句首大写**(sentence case),标题也一样 —— 不要全大写。
- 对齐默认左对齐;仅图标下方文字等少数情况居中。
- 每行 50-60 字符;截断优先用省略号。

## 触控目标 [官方]

来源:[Targeting](https://learn.microsoft.com/nl-NL/windows/apps/design/input/guidelines-for-targeting)
Fluent Standard:所有项对齐 **40x40 epx** 目标。菜单项高度取 40px 由此而来。

## 颜色

`TextFillColorPrimary` 暗色 = **`#FFFFFFFF`** [官方,见
[XAML theme resources](https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/xaml-theme-resources)
的 runtime value 表;同表亮色为 `#E4000000`]。

其余暗色 token [近似]:

| Token | 值 | 用途 |
|---|---|---|
| `TextFillColorPrimary` | `#FFFFFFFF` [官方] | 主文本 |
| `TextFillColorSecondary` | `#C5FFFFFF` | 次要文本、说明 |
| `TextFillColorTertiary` | `#87FFFFFF` | 快捷键提示、弱化文本 |
| `TextFillColorDisabled` | `#5DFFFFFF` | 禁用项 |
| `SolidBackgroundFillColorBase` | `#FF202020` | 不透明面板底 |
| `AcrylicBackgroundFillColorDefault` | `#CC2C2C2C` | 亚克力面板(带透明度) |
| `SubtleFillColorSecondary` | `#0FFFFFFF` | **悬停**背板 |
| `SubtleFillColorTertiary` | `#0AFFFFFF` | **按下**背板(比悬停更淡) |
| `CardStrokeColorDefault` | `#19000000` | 卡片描边 |
| `ControlStrokeColorDefault` | `#12FFFFFF` | 控件描边、面板边框 |
| `DividerStrokeColorDefault` | `#15FFFFFF` | 分隔线 |
| `AccentFillColorDefault` | `#FF4CC2FF` | 强调色(暗色下偏亮) |

注意:悬停比按下**更亮**,这与直觉相反但是 Fluent 的实际行为(按下时背板收缩变淡)。

## 亚克力(Acrylic)

真正的 Acrylic 是**五层**配方(官方原文:background → blur → exclusion blend → color/tint → noise)。
其中 **exclusion blend** 那层容易被忽略,它的职责是保证叠在 acrylic 上的 UI 文字有足够对比度
——可读性靠它,不是靠调不透明度。详见 `dwm-deep-dive.md` §6。

dwm 的实现目前只做**半透明色调层**,不做模糊:
- Skija 的高斯模糊要对 MC 已渲染的帧做离屏采样,每帧成本高;
- 菜单叠在游戏画面上,不模糊反而让玩家能看清背后的战况。

这一简化踩在 Acrylic 自己的降级路径上:官方明确 acrylic 是 GPU 密集的,**省电模式下自动禁用**,
用户关掉"透明效果"、高对比度模式、低端硬件时都退化为纯色。所以"无模糊的 acrylic"是它支持的形态之一。

待办:补 **exclusion blend** 层 —— 它不需要模糊就能改善可读性,是性价比最高的一项。

若日后要真模糊:Skija 有 `ImageFilter.makeBlur`,但需要先把 MC 的帧读进一张 Image,
属于额外一次全屏拷贝,应先测量再决定。

## 控件库度量(2026-07-27 新增)

`dwm/controls/` 下六个控件用到的数字。**每一条都标了出处** —— 这一批里
[官方] 的比例很低,因为 Microsoft 公布的是圆角、字号、触控目标,**不是各控件的具体尺寸**。

| 控件 | 度量 | 值 | 出处 |
|---|---|---|---|
| 全部 | 圆角(页内元素) | 4px | [官方] `ControlCornerRadius` |
| 全部 | 行高/触控目标 | 40px | [官方] Fluent Standard 40x40 epx |
| Button | 控件高度 | 32px | **[近似]** —— 官方只公布 40x40 触控目标,不含按钮高度;32 是 WinUI 默认外观在该目标内的形状 |
| ToggleSwitch | 轨道 40x20、圆角 10、滑块 14、内缩 3 | — | **[近似],全部** —— 已核实 Microsoft **在 Fluent 与 WinUI 文档中均未公布 ToggleSwitch 的任何像素尺寸**。这些值读自实际控件比例 |
| ToggleSwitch | 滑块动画 150ms / OutCubic | — | **[近似]** —— 官方未公布时长 |
| CheckBox | 方框边长 | 20px | **[近似]** —— 官方公布 4px 圆角与 40x40 目标,不含方框尺寸 |
| Slider | 轨道圆角 | 4px | [官方] bar 类元素(ProgressBar / ScrollBar / Slider) |
| Slider | 轨道厚度 4、滑块直径 20 | — | **[近似]** —— 规范钉的是**半径**,不是厚度 |
| ProgressBar | 轨道圆角 4px | 4px | [官方] bar 类元素 |
| ProgressBar | 轨道厚度 | 4px | **[近似]** |
| TextBox | 控件高度 | 32px | **[近似]**,同 Button |

两个新增色 token,都是 [近似]:

- `controlStrokeStrong` `#9AFFFFFF` —— WinUI 的**强**控件描边。给"整个外观就是一条轮廓"的控件用
  (未勾选的 checkbox、关闭的 toggle 轨道)。`panelStroke` 是**弱**描边,`#12FFFFFF` 在这类控件上几乎看不见,
  两者不可互换。
- `textOnAccent` `#FF000000` —— 画在 accent 填充**上面**的文字。暗色主题下是黑色,
  因为此时 accent 才是那个亮面 —— 全项目唯一一处极性反转的地方。

### 一条 qml4j 0.2.24 的实测约束(写进代码注释了)

`Behavior` 的 `duration` 与 `easing.type` **必须写字面量**:

- `Behavior` 在绑定求值前就把 duration 从模板读进私有字段(`Behavior.java:13,67`),
  绑定表达式会静默退回默认 250ms;
- `Easing.OutCubic` 这个具名形式在此处解析不出来,`easingType` 留在 0(= Linear)。
  **6 才是 OutCubic**(`Easings.java:14`)。

两条都是读源码 + 真机比对确认的,不是推测。

## 尺寸推导(用于本项目)

由上述 [官方] 数字推出的实现值:

| 元素 | 值 | 依据 |
|---|---|---|
| 菜单面板圆角 | 8px | flyout [官方] |
| 菜单项背板圆角 | 4px | 页内元素 [官方] |
| 菜单项高度 | 40px | 40x40 epx 触控目标 [官方] |
| 菜单项文字 | 14px Regular | Body [官方] |
| 菜单标题 | 20px Semibold | Subtitle [官方] |
| 面板内边距 | 4px | 背板与面板边缘留白,使 4px 背板圆角嵌在 8px 面板内 |
| 项内左边距 | 12px | 图标/文字起始位 |
| 分隔线 | 1px | `DividerStrokeColorDefault` |

## 字体落地

Segoe UI Variable 是 Windows 私有字体,**不可分发**。dwm 通过 qml4j 的
`QmlView.uiTypefaces(byte[], byte[])` / `cjkTypeface(byte[])` 装载字体:
- Windows 上可尝试读系统 `SegoeUIVariable`;
- 其他平台退回 qml4j 自带默认字体。
两种情况下 type ramp 的**尺寸与字重**照 [官方] 表执行 —— 度量比字形更影响观感一致性。
