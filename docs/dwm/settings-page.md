# Settings 页:卡片、动画、以及 dwm 该怎么分层

2026-07-28。本文记录三件事:Windows Settings 页的真实结构、动画为什么必须是**策略层**而不是散落的 tween、
以及从 Windows 的分层里学到的边界。

---

## 0. 上一轮做错了什么

上一轮做的是**控件级** token 保真(滑块 18px、toggle 圆角 7、elevation border),
每个值都对、都有断言守住。**但那没有回答需求。**

Settings 页的观感主体不是控件,是**卡片布局** —— 有背景、描边、图标列、标题+副标题两行的
SettingsCard,加上分组标题、4px 卡片间距、可折叠分组。控件只是嵌在里面的零件。

当时的页面是"标签 + 控件"的裸行堆叠。控件再准,那也不是 Settings 页。

---

## 1. 权威结构在开源仓库里

上一版 `fluent-spec.md` 写"WinUI 的权威值只存在于 Windows App SDK 随附的字典里,
不在公开文档中枚举"。**那句话对了一半:它不在文档里,但仓库是开源的。**

- 控件度量:`microsoft/microsoft-ui-xaml`,分支 `release/2.8`
- Settings 页那套:`CommunityToolkit/Windows`,`components/SettingsControls/src/`
  —— **这正是 Windows Settings 自己用的控件**

### SettingsCard(`SettingsCard/SettingsCard.xaml`)

| 资源 key | 值 |
|---|---|
| `SettingsCardMinHeight` | **68** |
| `SettingsCardPadding` | **16,16,16,16** |
| `SettingsCardBorderThickness` | 1 |
| `SettingsCardHeaderIconMaxSize` | **20** |
| `SettingsCardHeaderIconMargin` | **2,0,20,0** |
| `SettingsCardDescriptionFontSize` | **12** |
| `SettingsCardActionIconMaxSize` | 13 |
| HeaderPanel margin | `0,0,24,0` |
| 背景过渡 | `BrushTransition Duration="0:0:0.083"` |

状态色(`x:Key="Default"` 暗色):背景 `CardBackgroundFillColorDefault` = **`#0DFFFFFF`**、
悬停 `ControlFillColorSecondary`、按下 `ControlFillColorTertiary`、
描边 `CardStrokeColorDefault` = **`#19000000`**,而**悬停时描边换成 `ControlElevationBorderBrush`**
—— 正好用上一轮建的 `FluentElevation`。

### SettingsExpander(`SettingsExpander/SettingsExpander.xaml`)

| 资源 key | 值 |
|---|---|
| `SettingsExpanderHeaderPadding` | `16,16,4,16` |
| `SettingsExpanderItemPadding` | **`58,8,44,8`** |
| `SettingsExpanderItemBorderThickness` | `0,1,0,0`(**只有上边框**) |
| `SettingsExpanderChevronButtonWidth/Height` | 32 |

**58 是这里最值得理解的数字**:它让子行的文字与**父卡片的文字列**对齐
(16 padding + 2 icon lead + 20 icon + 20 gap = 58)。随手取一个缩进,
就是手工 expander 最典型的破绽 —— 子行文字不在父行文字下面。

只画上边框,所以连续两行之间是一条线而不是两条。

### 页面级

官方示例是 `<StackPanel Spacing="4">` —— **卡片间距 4px**。小是故意的:
一组内的卡片读作一个块,分隔靠**分组标题**,不靠留白。

来源:[SettingsCard](https://learn.microsoft.com/en-us/dotnet/communitytoolkit/windows/settingscontrols/settingscard) ·
[SettingsExpander](https://learn.microsoft.com/en-us/dotnet/communitytoolkit/windows/settingscontrols/settingsexpander)

---

## 2. 两处刻意偏离官方值,都写进了文件

**卡片换行阈值 476 → 320。** `SettingsCardWrapThreshold` 官方是 476,
而 dwm 内容区只有 419 逻辑像素(560 窗口 − 140 导航栏 − 1 分隔线)。按官方值**每张卡片都会换行**:
实测 68px 的卡变成 110px,一屏放三张而 Windows 放五张。换行**行为**在某个宽度上是对的;
476 是桌面窗口的阈值,不是 419px 游戏内面板的。WinUI 自己把它做成可覆盖的资源,正是为此。

**标题与副标题之间没有间距。** 实测两行 `implicitHeight` 合计 30
(qml4j 跟的是**字号**,不是 type ramp 的行高),所以 16+30+16=62,
**68px 下限才是两行卡片高度的决定者**。加 4px 间距会得到 66 —— 仍在下限之下,
所以在常见情况下不可见,只在副标题折行时才冒出来。不加,因为行高本身就该提供那个间距。

(这条注释我写错过一次:先写成"68 = 16+20+16+16,加 gap 会顶破下限",两句都不对。
实测数据才是准的。)

---

## 3. SVG 图标:通路不在明显的地方

Segoe Fluent Icons 是 Windows 私有字体、不可分发,所以图标自己画,20px viewBox、
1.5 描边、`currentColor`。

Skija **自带 SVG 模块**(`skija.svg.SVGDOM`,已在依赖里),但 qml4j 的 `Image.source`
走 `Image.makeDeferredFromEncodedBytes` —— Skia 的**位图**解码器,不认 SVG。

三条路里选了第三条:

| 方案 | 为什么不行 / 行 |
|---|---|
| 自定义 `Item` 注册进 `TypeRegistry` | **拿不到 Canvas** —— `Painter.canvas()` 是包私有 |
| 直接写 `Image.skiaImage` | 要与 qml4j 的 `loadedSource`/`decodeGen`/`adoptedGen` 状态机保持同步,那是内部实现 |
| **在 `ClasspathResources.load()` 拦截 `.svg`,返回 PNG 字节** | **选这条** —— 那是 qml4j 自己的资源通道,而它是我们实现的 |

光栅化**不需要 GL 上下文**(实测,`Surface.makeRaster`),所以在加载时做,不在帧里。
2 倍过采样,因为场景按 DPI 合成,20px 光栅在 Retina 上会被放大。

### 两个实测出来的坑

**tint 必须带 `#`。** 颜色跟着资源路径走(`icons/x.svg?4cc2ff`),因为 `ResourceLoader`
只收一个字符串。而裸的 `4cc2ff` 不是合法 SVG 颜色,**Skia 对非法 paint 的回答是什么都不画、
同时报告成功** —— 产出 101 字节、全透明的 PNG,而它的 `Image` 报 `status=Ready`、
`intrinsic=40x40` 一切正常。

**图片路径到达时未解析。** qml4j 解析 QML **文档**的 import,但 `Image.source` 原样传下来:
`dwm/Main.qml` 里写 `icons/gamma.svg` 会请求**恰好** `icons/gamma.svg`,而资源在
`dwm/icons/gamma.svg`。第一版图标什么都没画就是这个原因。所以相对路径找不到时回退到场景目录。

---

## 4. 命中测试:卡片会吃掉自己控件的点击

这条破坏了整个页面,值得单独记。

qml4j 的 `hitTestMouseArea` **逆 z 序**遍历子节点、返回第一个命中,所以**最后声明的胜出**。
`FluentSettingsCard` 自己的 MouseArea 声明在最后,于是它拦下了本该给卡内控件的每一次按下:
真机上 toggle 报 `down=true`(卡片消费了它)而它自己的 area 报 `containsMouse=false`,
页面上任何东西都动不了。

**门控 `onClicked` 没用** —— 坏的是消费按下,不是消费后做了什么。

修法:非 clickable 时 `enabled: false`。`hitTestMouseArea` **先查 enabled 并返回 null**,
按下就落到控件上。尺寸归零也能用,但那等于"一个永远碰不到的交互节点",
正是 `NavigationShellLiveIT` 扫的那个缺陷 —— 它也确实报了。

---

## 5. 动画必须是策略层

Windows 把动画当**系统策略**,不是每个控件的私事:`SystemParametersInfo` 有一个总开关
`SPI_GETUIEFFECTS`,下面挂一组独立项(`SPI_GETMENUANIMATION`、`SPI_GETCOMBOBOXANIMATION`、
`SPI_GETLISTBOXSMOOTHSCROLLING`、`SPI_GETCLIENTAREAANIMATION`、`SPI_GETDROPSHADOW` …),
而"高级系统设置 → 性能选项"就是那一层的界面。

**控件不决定自己是否动画,它去问。**

dwm 原来是每个控件写死时长 —— 那让"关掉动画"这件事无法实现,除非改每个文件。
`Motion.qml`(单例)就是那一层。

### 依赖图是真的

`SPI_SETUIEFFECTS(FALSE)` 压制**全部**效果,无论各项自身如何;
而从属项在其 master 关闭时**被忽略** —— `SPI_GETMENUFADE` 在菜单动画关掉时毫无意义。

`Motion` 用 `effective*` 只读属性把这个优先级编码一次,调用方读**一个**值,不可能弄错顺序:

| 项 | 门控于 |
|---|---|
| `animateControls` | `uiEffects && clientAreaAnimation` |
| `animateExpand` | `uiEffects && comboBoxAnimation && clientAreaAnimation` |
| `animateScrolling` | `uiEffects && listBoxSmoothScrolling` |
| `animatePages` | `uiEffects && clientAreaAnimation` |
| `menuFadesRatherThanSlides` | `animateMenus && menuFade` ← **两级** |

### 关闭 = 立即到终态,不是冻结

`SPI_GETCLIENTAREAANIMATION` 的文档预期是应用**跳到终态**。时长 0 表达的就是这个:
长度为零的过渡**仍然会到达**。一个停在中途的控件是坏了,而不是没有动画。

### 时长与缓动(全部实测)

| 值 | 来源 |
|---|---|
| 83ms | `ControlFasterAnimationDuration`,控件状态切换 |
| 167ms | `ControlFastAnimationDuration` |
| 250ms | `ControlNormalAnimationDuration`,较大位移 |
| **100ms** | Expander 的 chevron —— `Duration="0:0:0.1"`,**它自己的值** |
| 进场 | `cubic-bezier(0,0,0,1)` "Fast Out, Slow In" → qml4j 索引 **2**(OutQuad) |
| 退场 | `cubic-bezier(1,0,1,1)` "Slow Out, Fast In" → qml4j 索引 **1**(InQuad) |

chevron 用 100 而不是 83:"那是控件状态时长"是一个**看起来合理的错答案**。

**别用索引 3**(InOutQuad)—— 它起步加速,给出慢-快-慢,两条 Fluent 曲线都不是这样。

来源:[Timing and easing](https://learn.microsoft.com/en-us/windows/apps/design/motion/timing-and-easing) ·
[Page transitions](https://learn.microsoft.com/en-us/windows/apps/develop/motion/page-transitions)

---

## 6. 三个 qml4j 0.2.24 的实测约束

这些决定了动画能写成什么形状,不是风格选择。

**① `Behavior` 不能运行时禁用。** 它的类只暴露 attach/write/tick,没有 `enabled`。
所以策略是靠**在动画值与目标值之间选择**来实现的,而不是关掉动画 —— 这也刚好落在正确方向:
效果关闭时高度就是**目标**,即立即到达终态。

**② `Behavior` 的 duration 在绑定求值前就从模板读走。** 绑定值会静默退回默认 250ms。
所以时长必须是字面量,`Motion` 是"那个字面量该是多少"的唯一真相来源,也是断言的对象。

**③ 页面切换不能靠重置进度值。** `Behavior` 是拿新值与它**上次显示的值**比较来决定要不要 tween。
在一个 handler 里写 0 再写 1,`lastDisplayed` 还是 1,tween 被跳过,**过渡静默地永不播放**。
实测:handler 跑了(计数器证明了),而进度值从未离开 1.0。

改用**单调递增的导航计数器** + 一个滞后的跟随值,`navCount - animatedNav` 就是剩余进度。
计数器每次都走到一个它从未持有过的值,所以永远有真实区间可插值。

---

## 7. 跨文件自定义信号连不上

`FluentWindow` 的 `onCloseRequested` 在 `Shell.qml` 里写了、**从未触发**。

根因:qml4j 0.2.24 里组件的生成类**不实现 `SignalRelay`**,所以在外层文档写的
`on<CustomSignal>` handler 无处可连,被**静默丢弃**。场景编译干净,按钮什么都不做。

所以标题栏按钮**直接调 `WindowHost`**。这也更接近 Windows 的做法:
非客户区把 `WM_SYSCOMMAND` 发给**窗口管理器**,而不是发给布局它的人。

(注:`NavItem` 的 `onClicked` 跨文件是**工作**的 —— 那是内置信号名,走的是另一条路径。)

---

## 8. 分层:从 Windows 学到的边界

Windows 把 DWM 拆成合成器、窗口框架/主题、动画、设置策略几层,各层不知道彼此的内部。
本轮按同样的思路划了三道线:

| 层 | 一句话职责 | 不该知道 |
|---|---|---|
| `Motion.qml` | 什么可以动、动多久 | 谁在动、怎么画 |
| `Fluent.qml` | 颜色与几何 token | 动画、输入 |
| `FluentWindow` | 窗口框架:标题栏、按钮、状态 | 内容是什么 |
| `UiWindowHost` | 宿主能被请求什么 | 谁请求的、为什么 |

`WindowCommands` 单独成一个 context 对象,**没有塞进 `DwmContext`**:
后者是"UI 能观察到的内核/board 状态",而"请把我最小化"不是关于内核的知识,是关于窗口的请求。
Windows 也是这条缝 —— 非客户区发 verb,窗口管理器决定含义。

同理,最大化/还原由 shell **自己**处理(几何是它的),最小化/关闭**离开场景**(那改变宿主对屏幕的处置)。

### 仍然违反这个划分的地方

`QmlUiSurface` 455 行,同时管合成决策、输入坐标转换、DPI、按钮编号映射、动画驱动。
按 Windows 的分法,合成与输入路由应该是两层。**本轮没动它** —— 那是一次独立重构,
而不是顺手做的事。

---

## 9. 标题栏三个按钮

此前三个都只有 hover、没有行为,理由写的是"dwm 没有 OS 窗口可操作"。
最小化确实如此;**最大化不是** —— shell 拥有自己的几何;而关闭从来不是:
`closeRequested` 声明了却没接线,点 X 什么都不发生。

现在:

- **最小化**(`SC_MINIMIZE`)→ 宿主:关屏幕但**保留 surface**,所以重开是瞬时的、
  页面/滚动/展开状态都还在。与关闭**故意不是同义** —— "窗口还存在" vs "不存在"就是两个按钮的全部区别。
- **最大化/还原**(`SC_MAXIMIZE`/`SC_RESTORE`)→ shell 自己:填满可用区域;
  还原回到**当时的**尺寸(所以进入时要记住,如 Windows 的 `WINDOWPLACEMENT.rcNormalPosition`)。
- **关闭**(`SC_CLOSE`)→ 宿主:关屏幕并释放 surface。

最大化时**圆角归零**(官方 geometry 指南:snapped 或 maximized 时 0px —— 贴边的圆角会漏出背后),
且**字形变成还原图标**(最大化的窗口提供还原;保持一个字形等于按钮在宣传它已不执行的动作)。

---

## 10. ~~未解决:卡片面在真机上不可见~~ **已修复(2026-07-31)**

> **[2026-07-31 结论] 已修复,根因是 MC 的 `GL_ALPHA_TEST`。**
> MC 画 GUI 时开着 alpha test(`GL_GREATER ref=0.1`),外来渲染器继承它,而 `0.1×255=25`
> —— GPU 丢弃所有 alpha ≤ 25 的 Skia 片元,而卡片面是 alpha 13。文字(alpha 255/197)不受影响,
> 这就是「文字在、面不在」的由来。`GlStateGuard` 现在在 `glPushAttrib` 之后禁用它。
> 详见 `handoff-2026-07-29.md` §3 与 commit `5669a04`。**本节以下的推测均已作废。**

> **本节结论是错的,保留是为了记住错在哪。** 真机实测卡片面**是画出来的**
> (`screen=313131` vs 间隙 `2a2a2a`,离屏层逐点一致)。不是回归,不是代码缺陷 ——
> 是**测量方法错**:`item_box` 返回的是命中坐标(已减 Flickable 滚动),
> 拿它当绘制坐标采样会采到窗口外;而那张卡默认在视口外(`contentHeight=689` vs 视口 `328`)。
> 完整三个坑与正确采样姿势见 `handoff-2026-07-29.md` §3。
>
> 下面这段"嫌疑最大的是 `composite()` 改成每帧 `renderFrame`"**已排除**。

**已提交但未修**,下一个上下文可以从这条线索接。

测量互相矛盾:

- 场景图正确:`Rectangle 385x66 color=#0dffffff visible=true`,卡片在屏幕 (177,140) 387x68
- **离屏层测试通过**(`aCardsPlateIsBrighterThanThePanelBehindIt`)
- 但**屏幕**像素在卡片范围内全是 `2a2a2a`(面板底色),不是预期的 `313131`

早前明确测到过 `313131` vs `2a2a2a` 的 +7 对比,所以这是**回归**。

嫌疑最大的是把 `composite()` 改成每帧 `renderFrame`(为修滚动动画所必需),
可能与离屏层的 begin/end 时序有交互。

同一现象我失败了三次(先怀疑透明度、再怀疑采样点、再怀疑 clip),所以停手记录而不是继续猜。
**下一次从"离屏层通过而屏幕不通过"这个差异入手** —— 那正是上一轮抓到
"合成结果从未提交"那个 bug 的同一类线索。
