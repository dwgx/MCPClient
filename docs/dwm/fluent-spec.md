# Windows 11 / Fluent 2 规格摘录(dwm UI 依据)

dwm 的 UI 按 Windows 11 Fluent 设计,契合项目的 NT 隐喻(core=NT kernel, board=PCB,
compat=AppCompat, dwm=Desktop Window Manager)。

## 出处等级(2026-07-28 起,大部分 [近似] 已消除)

三类:

- **[官方]** —— Microsoft Learn 公开文档(圆角、type ramp、触控目标)。
- **[WinUI]** —— **直接读自 WinUI 源码的主题字典**。这是新增的一级,也是本轮的关键:
  `microsoft/microsoft-ui-xaml` 分支 `release/2.8`,
  `dev/CommonStyles/Common_themeresources_any.xaml` 及各控件的 `*_themeresources.xaml`。
  上一版本文说"权威色值只存在于 Windows App SDK 随附的字典里,不在公开文档中枚举" ——
  那句话对了一半:**它不在文档里,但仓库是开源的**。所以那批值不必再靠截图猜。
  注意暗色字典的 key 是 **`x:Key="Default"`**,不是 `"Dark"`。
- **[近似]** —— 仍靠观察推得的少数几条,逐条标注。

**本轮由此纠正了 9 处错值**,详见 §控件度量。全部改动都有像素回读断言守卫
(`ControlGalleryLiveIT`),且每条断言都反向验证过。

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

## 颜色 [WinUI]

以下全部**逐字读自** `Common_themeresources_any.xaml` 的 `x:Key="Default"`(暗色)块。
上一版这些标 [近似] 的值**大部分是对的**,只有 `ControlStrongStrokeColorDefault` 错了
(写成 `#9AFFFFFF`,实际 `#8BFFFFFF`)。

> **低 alpha 曾在真机上被整体丢弃 —— 取值正确不等于画得出来(2026-07-31)。**
>
> Fluent 表达层次靠的就是这批低 alpha 白/黑,而 MC 画 GUI 时开着
> `GL_ALPHA_TEST`(`GL_GREATER ref=0.1`)。外来渲染器继承它,`0.1 × 255 = 25`,
> **GPU 直接丢弃所有 alpha ≤ 25 的片元**。`Fluent.qml` 里**有 10 个 token 落在这条线下**:
> `divider`(21)、`panelStroke`(18)、`controlStrokeSecondary`(24)、`subtlePressed`(10)、
> `controlFillDisabled`(11)、`cardFill`(13)、`controlFill`(15)、`controlFillTertiary`(8)、
> `cardStroke`(25)、`subtleTransparent`(0)。
>
> 也就是说**修复前整套微妙层次是全灭的**,而文字(alpha 255/197)照常显示 ——
> 这正是"控件度量都准、整体却不像 Windows"的真实原因,不是取值问题。
> `GlStateGuard.enter()` 现在为 Skia 禁用 alpha test(commit `5669a04`)。
>
> **给后来取值的人**:这里的 alpha 可以照 WinUI 抄,**但任何新的低 alpha 值都必须在真机上
> 用像素验证**,而不是在 headless 或离屏 raster 上 —— CPU 光栅化不过 GL,裸 GLFW 窗口
> 也从不开 alpha test,两者都看不见这类缺陷。完整案例见 `docs/debugging.md` §9。

| Token | 值 | 用途 |
|---|---|---|
| `TextFillColorPrimary` | `#FFFFFF` | 主文本 |
| `TextFillColorSecondary` | `#C5FFFFFF` | 次要文本、关闭态 toggle 滑块 |
| `TextFillColorTertiary` | `#87FFFFFF` | 弱化文本、**聚焦后**的输入框占位符 |
| `TextFillColorDisabled` | `#5DFFFFFF` | 禁用项 |
| `SolidBackgroundFillColorBase` | `#202020` | 不透明面板底 |
| `SubtleFillColorSecondary` | `#0FFFFFFF` | **悬停**背板 |
| `SubtleFillColorTertiary` | `#0AFFFFFF` | **按下**背板(比悬停更淡) |
| `SubtleFillColorTransparent` | `#00FFFFFF` | 背板静止态(完全无填充) |
| `ControlFillColorDefault` | `#0FFFFFFF` | 按钮/输入框静止底 |
| `ControlFillColorSecondary` | `#15FFFFFF` | 按钮悬停底 |
| `ControlFillColorTertiary` | `#08FFFFFF` | 按钮**按下**底 |
| `ControlFillColorDisabled` | `#0BFFFFFF` | 按钮禁用底 |
| `ControlFillColorInputActive` | `#B31E1E1E` | **聚焦的输入框底** —— 近乎不透明的暗色 |
| `ControlAltFillColorSecondary` | `#19000000` | 空心控件静止底(**黑基**,凹陷感) |
| `ControlAltFillColorTertiary` | `#0BFFFFFF` | 空心控件悬停底 |
| `ControlAltFillColorQuarternary` | `#12FFFFFF` | 空心控件按下底 |
| `ControlAltFillColorDisabled` | `#00FFFFFF` | 空心控件禁用底 |
| `ControlStrokeColorDefault` | `#12FFFFFF` | 控件描边(elevation 的**下**停) |
| `ControlStrokeColorSecondary` | `#18FFFFFF` | elevation 的**上**停(亮边) |
| `ControlStrongStrokeColorDefault` | `#8BFFFFFF` | 强描边 —— 上一版写错为 `#9AFFFFFF` |
| `ControlStrongStrokeColorDisabled` | `#28FFFFFF` | 强描边禁用**及按下**态 |
| `DividerStrokeColorDefault` | `#15FFFFFF` | 分隔线 |
| `CardStrokeColorDefault` | `#19000000` | 卡片描边 |
| `SurfaceStrokeColorFlyout` | `#33000000` | flyout/菜单边框 |
| `TextOnAccentFillColorPrimary` | `#000000` | accent 上的文字/字形 |
| `TextOnAccentFillColorSecondary` | `#80000000` | **按下时** accent 上的字形 |
| `TextOnAccentFillColorDisabled` | `#87FFFFFF` | accent 上的禁用文字 |
| `AccentFillColorDisabled` | `#28FFFFFF` | 禁用的 accent 面(**变中性灰,不是淡 accent**) |

注意:悬停比按下**更亮**,这与直觉相反但是 Fluent 的实际行为(按下时背板收缩变淡)。

### accent 阶梯不是三个色值

`AccentFillColorDefaultBrush` = `SystemAccentColorLight2`(暗色下),而
`AccentFillColorSecondaryBrush` / `TertiaryBrush` 是**同一颜色配 `Opacity="0.9"` / `"0.8"`**:

```xml
<SolidColorBrush x:Key="AccentFillColorSecondaryBrush" Color="{ThemeResource SystemAccentColorLight2}" Opacity="0.9" />
<SolidColorBrush x:Key="AccentFillColorTertiaryBrush"  Color="{ThemeResource SystemAccentColorLight2}" Opacity="0.8" />
```

所以 accent 面的状态链是**透明度斜坡**,不需要额外色 token。
但**只降那一层的透明度** —— 上一版对整个控件降 opacity,连描边一起弄淡了,
而关闭态的 toggle / 未勾选的 checkbox **描边就是控件本体**。

## Elevation border(本轮新增,Windows 11 立体感的主要来源) [WinUI]

```xml
<LinearGradientBrush x:Key="ControlElevationBorderBrush"
                     MappingMode="Absolute" StartPoint="0,0" EndPoint="0,3">
  <GradientStop Offset="0.33" Color="ControlStrokeColorSecondary"/>  <!-- #18FFFFFF -->
  <GradientStop Offset="1.0"  Color="ControlStrokeColorDefault"/>    <!-- #12FFFFFF -->
</LinearGradientBrush>
```

两处细节决定它对不对,都容易丢:

1. **`MappingMode="Absolute"` + `EndPoint="0,3"` 表示渐变只跨 3px**,不是控件全高。
   过了 y=3 就保持末色 —— 效果是"1px 亮顶边压在一条均匀暗描边上",
   **不是**一个从上到下渐隐的控件。
   另外 `Absolute` 是**局部空间(DIP)**,不是设备像素
   ([官方定义](https://learn.microsoft.com/en-us/dotnet/api/system.windows.media.brushmappingmode):
   "Values are interpreted directly in local space")—— 中途我按设备像素除过一次 DPI,是错的,已撤。
2. 它是**边框**画刷。qml4j 表达不了:`Border.color` 只吃单色字符串,`Gradient` 只能填充。
   所以 `FluentElevation.qml` 画成填充矩形,由调用方把不透明的面**内缩 1px** 盖上去,
   露出的那 1px 就是描边。

**变体**:圆形控件用 `CircleElevationBorderBrush`(0.70/0.50,**相对**映射);
accent 控件用 `AccentControlElevationBorderBrush`(同形状但 `ScaleY="-1"` 翻转,亮边在**底部**);
输入框用 `TextControlElevationBorderBrush`(2px + 翻转,所以强描边在**底边** —— 见下)。

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

## 控件度量(2026-07-28 全面改为 [WinUI] 实测值)

上一版这张表里几乎全是 [近似],因为"Microsoft 只公布圆角/字号/触控目标"。
**那个结论过早了** —— 各控件的具体尺寸都在 `*_themeresources.xaml` 里,仓库开源。
下表标出**改前 → 改后**,`≠` 的行是本轮纠正的错值。

| 控件 | 度量 | 旧值 | **WinUI 实际** | 资源 key |
|---|---|---|---|---|
| 全部 | 页内圆角 | 4 | 4 | [官方] `ControlCornerRadius` |
| 全部 | 行高/触控目标 | 40 | 40 | [官方] 40x40 epx |
| Button | 高度 | 32 | 32 | [近似] 仍是近似(官方只给触控目标) |
| Button | 内边距 | 12 | **11** ≠ | `ButtonPadding` = `11,5,11,6` |
| ToggleSwitch | 轨道 | 40x20 | 40x20 | 模板 `Width/Height` |
| ToggleSwitch | 轨道圆角 | 10 | **7** ≠ | `CornerRadius="7"`(**不是** 高/2) |
| ToggleSwitch | 滑块(静止) | 14 固定 | **12** ≠ | `SwitchKnob` Normal 关键帧 |
| ToggleSwitch | 滑块(悬停) | — | **14** ≠ 新增 | PointerOver 关键帧 |
| ToggleSwitch | 滑块(按下) | — | **17x14** ≠ 新增 | Pressed 关键帧,**压成椭圆** |
| ToggleSwitch | 动画 | 150ms/OutCubic | **83ms** ≠ | `ControlFasterAnimationDuration` |
| CheckBox | 方框 | 20 | 20 | `CheckBoxSize` |
| CheckBox | 字形 | 12 | 12 | `CheckBoxGlyphSize` |
| Slider | 滑块直径 | 20 | **18** ≠ | `SliderHorizontalThumbWidth` |
| Slider | 轨道厚度 | 4 | 4 | `SliderTrackThemeHeight` |
| Slider | 轨道圆角 | 4 | **2** ≠ | `SliderTrackCornerRadius` |
| Slider | 内滑块 | 12/16/10 | **10/14/9** ≠ | 12 基准 × 缩放 0.86/1.167/0.71 |
| ProgressBar | 高度 | 4 | **3** ≠ | `ProgressBarMinHeight` |
| ProgressBar | 圆角 | 4 | **1.5** ≠ | `ProgressBarCornerRadius` |
| ProgressBar | 轨道厚度 | 4 | **1** ≠ | `ProgressBarTrackHeight`(比填充**更细**) |
| TextBox | 高度 | 32 | 32 | [近似],同 Button |
| TextBox | 焦点下划线 | — | **2** 新增 | `TextControlElevationBorderBrush` EndPoint `0,2` |

### 三条"看起来对所以没人查"的错值

值得单独记,因为它们的共性是**渲染出来完全可信**:

1. **Slider 轨道圆角 4 → 2**。上一版引用了官方那条"bar 类元素用 4px 圆角"的规则,
   听起来完全适用。但实际资源是 2 —— **那条规则说的是控件的角,不是内部轨道的角**。
   一条听起来涵盖某度量的公开规则,不等于那个度量。
2. **ProgressBar 4px/4圆角 → 3px/1.5圆角,且轨道只有 1px**。同一条规则被套用到了厚度上。
   真实控件是"一条细线,在进度到达处变粗",而不是"一个凹槽被填满"。
3. **ToggleSwitch 滑块三态变形**。上一版是固定 14。真实控件静止 12、悬停 14、
   按下 **17x14** 压扁 —— 这是它"手感真实"的主要来源,而固定尺寸看着也没毛病。

### 状态色:换 token,不是降 opacity

上一版六个控件统一用整体 `opacity` 调暗表达 hover/pressed/disabled。WinUI 是**逐状态换 fill token**
(见 §颜色的 `ControlFill*` / `ControlAltFill*` 阶梯),只有 accent 面才用透明度。
差别在 disabled:整体降 opacity 会把**描边**一起弄淡,而空心控件的描边就是它本身。

另外两条容易漏的:

- `ButtonForegroundPressed` 降到 `TextFillColorSecondary` —— **标签跟着面一起变暗**,
  而不是亮文字压在暗面上。
- 未勾选的 checkbox / 关闭态 toggle 轨道**静止时是有填充的**(`#19000000`,黑基凹陷),
  不是全透明。画成裸描边会丢掉那个凹陷感。旧的画廊断言正好要求"必须完全空",
  与真实行为冲突,已改为 `assertFaintFill`。

### TextBox:强描边在底边

`TextControlElevationBorderBrush` 是 2px + `ScaleY="-1"`,**翻转**的 —— 所以与其他控件相反,
亮/强的那条边在**底部**:底边强描边、其余弱描边;聚焦时整条底边变 accent。
这是 Fluent 输入框的标志性细节,qml4j 的 `TextField.borderColor` 四边同色表达不了,
所以底边单独用一个 `Rectangle` 叠。

聚焦态背景也反直觉:不是变亮,而是变成近乎不透明的暗色 `#B31E1E1E`
(`ControlFillColorInputActive`),让正在编辑的文字落在受控表面上。

## 动画时长 [WinUI]

`Common_themeresources_any.xaml` 里的三个常量(原文是 timespan):

| Key | 值 |
|---|---|
| `ControlFasterAnimationDuration` | `00:00:00.083` → **83ms** |
| `ControlFastAnimationDuration` | `00:00:00.167` → 167ms |
| `ControlNormalAnimationDuration` | `00:00:00.250` → 250ms |

缓动是 `ControlFastOutSlowInKeySpline` = **`0,0,0,1`** —— 纯减速,末端斜率为零。
控件的每一次状态切换(滑块变形、颜色交叉淡入)用的都是 **Faster**。

`Fluent.qml` 里有 `durationFaster/Fast/Normal` 三个 token,但**只作文档用**,见下。

### 一条 qml4j 0.2.24 的实测约束(写进代码注释了)

`Behavior` 的 `duration` 与 `easing.type` **必须写字面量**:

- `Behavior` 在绑定求值前就把 duration 从模板读进私有字段(`Behavior.java:13,67`),
  绑定表达式(含 `Fluent.durationFaster`)会静默退回默认 250ms;
- 具名的 `Easing.*` 在此处解析不出来,`easingType` 留在 0(= Linear)。
  编号读自 qml4j 自己的 `Easings.apply` 跳转表:**1=InQuad、2=OutQuad、3=InOutQuad、6=OutCubic**。

**最接近 WinUI `0,0,0,1` 的是 `2`(OutQuad)**,本轮 toggle 用的就是它。
注意别用 `3` —— 那是 InOutQuad,起步还要加速,会让滑块变成"慢-快-慢",真实控件没有这个。
(上一版用 `6`/OutCubic 配 150ms,两个数都不是 WinUI 的。)

两条都是读源码 + 真机比对确认的,不是推测。

## Settings 控件度量(2026-07-28 新增) [Toolkit]

新增一级出处:**[Toolkit]** —— `CommunityToolkit/Windows` 分支 main,
`components/SettingsControls/src/`。这不是第三方近似,**它就是 Windows Settings 自己用的控件**。

| 控件 | 资源 key | 值 |
|---|---|---|
| SettingsCard | `SettingsCardMinHeight` | 68 |
| SettingsCard | `SettingsCardPadding` | 16,16,16,16 |
| SettingsCard | `SettingsCardHeaderIconMaxSize` | 20 |
| SettingsCard | `SettingsCardHeaderIconMargin` | 2,0,20,0 |
| SettingsCard | `SettingsCardDescriptionFontSize` | 12 |
| SettingsCard | `SettingsCardActionIconMaxSize` | 13 |
| SettingsCard | HeaderPanel margin | 0,0,24,0 |
| SettingsCard | 背景过渡 | `BrushTransition Duration="0:0:0.083"` |
| SettingsExpander | `SettingsExpanderItemPadding` | **58,8,44,8** |
| SettingsExpander | `SettingsExpanderItemBorderThickness` | 0,1,0,0(**只有上边框**) |
| SettingsExpander | `SettingsExpanderChevronButtonWidth/Height` | 32 |
| SettingsExpander | chevron 动画 | **`0:0:0.1`** = 100ms |
| 页面 | 卡片间距 | 4(官方示例的 `StackPanel Spacing="4"`) |

新增色 token(`x:Key="Default"` 暗色):

| Token | 值 | 用途 |
|---|---|---|
| `CardBackgroundFillColorDefault` | `#0DFFFFFF` | 卡片底 |
| `CardBackgroundFillColorSecondary` | `#08FFFFFF` | 次级卡片底 |
| `CardStrokeColorDefault` | `#19000000` | 卡片描边(**黑基**) |
| 卡片圆角 | 6 | 介于控件 4 与 overlay 8 之间,自成一档 |

**58 是这批里最值得理解的数字**:它让 expander 子行的文字与父卡片的文字列对齐
(16 padding + 2 icon lead + 20 icon + 20 gap)。随手取一个缩进是手工 expander 的典型破绽。

**chevron 的 100ms 不是三档时长之一。** 用 83("那是控件状态时长")是一个看起来合理的错答案。

两处刻意偏离官方值(换行阈值 476→320、标题与副标题之间无间距)的完整理由见
`settings-page.md` §2 —— 都写进了源码注释。

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
