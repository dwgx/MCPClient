# dwm 真机验证:为什么必须有,以及怎么自动化

headless 测试证明逻辑,**真机证明它真的出现在屏幕上**。这两件事在 dwm 上差得很远 ——
2026-07-27 进游戏跑第一次,抓到三个 bug,**headless 断言原理上一个都抓不到**。

自动化入口:`scripts/live-dwm-probe.py`(先 `./scripts/run-mcp.sh` 起客户端)。

---

## 0. 真机抓到的三个 bug(记录下来,别再犯)

| # | 症状 | 根因 | 为什么 headless 测不到 |
|---|---|---|---|
| 1 | 开界面瞬间 SIGSEGV | Skija 留下 `ARRAY_BUFFER` 绑定;MC 用**客户端顶点数组**,`glVertexPointer` 传的 Java 堆指针在有绑定时被当成缓冲区**偏移**解引用 | 原生崩溃,不是 Java 异常。裸 GLFW 窗口不画世界,所以没有 `glDrawArrays` 去踩它 |
| 2 | JVM 直接 abort | 我修 #1 时顺手还原 VAO,但 `glBindVertexArray` 是 GL 3.0,Apple 的 2.1 兼容 profile 没有该入口 → LWJGL `jni_FatalError` | `jni_FatalError` 越过所有 Java handler,任何 try/catch 都拦不住 |
| 3 | 界面**完全不可见**,而所有状态都报健康 | `compositeLayer` 只把 `drawImage` 排进队列,从不 flush。`present()` 才 flush,而 qml4j 在 `renderFrame` **内部**调它 —— 那在 blit **之前**,且空闲帧根本不跑 | `isOpen=true`、`inert=false`、`lastError=null`、`fboId` 正确。**没有任何状态字段能暴露它**;读离屏层也不行(层里内容是对的),只有读**目标 framebuffer** 的像素才行 |

第 3 条是最重要的教训:**「所有字段都正常」和「用户能看见」是两回事。**

---

## 1. 为什么不能用 `gui_click_element`

内核已经有一套 GUI 自动化工具(`gui_snapshot` / `gui_click_element` / `gui_type_text`),
但它们**驱动不了 dwm**。原因具体:

`GuiSnapshotService` 反射读的是 vanilla 的 `GuiScreen.buttonList`(`:129`),
而 QML 场景**从不往那个 list 里放东西** —— dwm 的控件是 qml4j 的 Item 树,不是 `GuiButton`。
所以 snapshot 看不到任何 dwm 控件,`gui_click_element` 也就无从下手。

**探针因此走 dwm 自己的 `UiInput` SPI**(`pointerDown`/`pointerUp`/`pointerMove`/`key`)。
这不是权宜之计,反而更好:那**正是游戏自己走的那条路**(`QmlGuiScreen.handleMouseInput` /
`keyTyped` 调的就是它),所以探针测的是生产代码,不是测试专用的旁路。

---

## 2. 探针断言什么

当前 28 条(数字会随新检查增长,以探针自己的输出为准),全部在真实游戏帧内:

- 进世界 → `DwmEntry` 构造并显示 `QmlGuiScreen` → surface 健康(`isOpen`/`inert`/`lastError`)
- **窗口在 MC 的 framebuffer 里真的有像素** —— `glReadPixels` 读目标,不经 Skia
  (经 Skia 就等于问那个正在丢帧的队列"你还好吗")
- 点击三个导航行 → 每次断言 `currentPage` 真的变成对应页面
- 往聚焦的输入框打 `abc` → **读回来必须等于 `abc`**
- **清掉焦点 → 点击输入框 → 断言它拿到焦点 → 再打字读回**(§5 那条 bug 的守卫)
- resize 到 1280x720 再回来 → surface 存活、无错误(历史"resize 后世界变黑"那条路)
- 关闭界面 → 客户端**仍然存活**

每一步之后都检查进程还活着 —— 因为这里最要紧的失败不是断言失败,而是**硬崩溃**:
三个 bug 里两个直接杀掉 JVM,而死进程和挂住的调用从外面看一模一样。

退出码沿用 `smoke-live-gl.sh` 的约定:`0` PASS · `1` FAIL · `2` TIMEOUT · `3` SETUP。

---

## 3. 探针自己的一个 bug(值得记)

第一版把导航行的 y 坐标**硬编码**成 152/192/232,结果每次点击都落在**下面一行**,
探针报了两条"导航失败" —— 而那是探针自己的错,UI 是对的。

实测真实几何是 y=64/104/144/184(高 40,中心 84/124/164/204)。

改成 `row_centre()` 从活场景读绝对坐标(沿 parent 链累加)。
**布局拥有的几何必须问布局要**,否则测试harness 会自己造出 bug 来。

---

## 4. 控件交互:为什么点击不能替代拖动

探针驱动设置页的四个控件,断言的是**控件自己的属性变了**,不是"输入调用返回了" ——
一个被消费却什么都没做的 dispatch 从外面看一模一样,那正是之前按键 bug 的形状。

实测结果(真机):

| 交互 | 断言 | 结果 |
|---|---|---|
| 点 ToggleSwitch | `checked` 翻转 | `false -> true` |
| 点 CheckBox | `checked` 翻转 | `true -> false` |
| **拖** Slider 向右 | `value` 显著上升 | `0.5 -> 0.994` |
| **拖**回向左 | `value` 显著下降 | `0.994 -> 0.006` |
| 移入/移出 | `containsMouse` 置位再清零 | `over=true off=false` |

**拖动必须是真的按下-移动-释放序列**,不能用点击代替:qml4j 只把 `positionChanged`
投给**被捕获**的 MouseArea,所以"按下再松开"只走了 click 路径,drag 路径一次都没碰。
探针因此中间插 8 步移动 —— 这同时证明捕获在移动中存活,而不是每次重新命中测试。

### 这两条断言证伪过

删掉 `FluentSlider` 的 `onPositionChanged`(**只**删拖动处理,保留按下定位),两条断言都正确失败,
而且失败方式恰好揭示差别:

```
拖右: 0.5 -> 0.234    <- 跳到按下点,没跟到终点
拖左: 0.234 -> 0.994  <- 反向"升",因为按下点在右侧
```

这就是 click-only 滑块的行为,断言分辨出来了。**不是"跑了没崩"那种断言。**

反向为什么这么重要:只断言"值上升"的话,一个无论输入都跳到右端的坏滑块也会通过。
两个方向一起断言,那种实现就被排除了。

---

## 5. 鼠标按钮编号:两套词汇撞在同一个整数上

2026-07-28 补的第四个真 bug,来源正是上一轮列在"未覆盖"里的那条——**点击输入框拿焦点**。

dwm 的 SPI 传的是 LWJGL2/GLFW 的**零基索引**(左 0、右 1、中 2);
qml4j 说的是 Qt 的 **`MouseButton` 位掩码**:`NoButton` 0、`LeftButton` **1**、`RightButton` 2、
`MiddleButton` 4(`MouseEvent.LEFT_BUTTON` 和 QML 侧 `Qt.LeftButton` 都能证)。
原来把索引**原样**递给 qml4j,于是:

| 用户按的 | qml4j 收到 |
|---|---|
| 左键 | `Qt.NoButton` —— 根本不算按了哪个键 |
| 右键 | `Qt.LeftButton` —— **冒充左键** |

**为什么整套探针全绿还是漏了。** `EventDispatcher.hitTestMouseArea` 在 button 为 0 时
**整个跳过** `acceptedButtons` 掩码判断(把 0 当通配),而 Fluent 控件底下全是 `MouseArea` ——
所以导航、开关、勾选、滑块拖动**全都照常工作**。真正断掉的是**按值等于 `LeftButton`** 的那几条路:
文本框聚焦与光标定位、`AbstractButton` 的按下/释放、`Flickable` 的拖动滚动。

实测症状(真机,把修复回退后):点输入框 → `focused=null`,接着打字 → 读回空串。
反过来右键点输入框会**拿到焦点并开始选择**,因为它冒充了左键。

修复:`QmlButtonMap`(在 qml/ 适配包内,与其它后端词汇同处),`toQml(0)=1`、`1=2`、`2=4`,
其余(含 LWJGL 用 -1 表示的移动/滚轮事件)一律 `NoButton` —— Qt 更高位继续是 2 的幂,
但 LWJGL2 第三键以上的顺序依平台而异,**没量过的映射不写**。

**这一条只影响 dwm 自己的适配层**:`client/` 的 `Mouse` 语义没动,shim 没动。

### 探针差点又写出一条空转断言

新加的"点击聚焦"检查第一版**在回退后依然通过**。原因:它前面那条 `focus_and_read_field`
用 `setFocus` 已经把焦点放上去了,于是"点完之后是聚焦的"根本没在测点击。
加 `clearFocus()` 当前置条件后才正确变红。

**只是"很可能成立"的前置条件不算前置条件** —— 断言前必须把状态**摆到**已知位置,而不是假设它在。

---

## 6. 又一条探针自己的空转/竞态(2026-07-28)

打磨控件那轮,真机跑出一条 headless 抓不到的失败:

```
FAIL  hovering a control sets containsMouse, and leaving clears it
      over=false off=false
```

**但重跑三次全过。** 间歇性,所以第一反应"是不是我改坏了 hover"是错的方向 ——
逐项查下来几何全对(40x40、trackRadius=7、knob=12),直接读 `containsMouse` 也是 `true`。

真因是**探针自己的竞态**:`hover()` 和 `hovered()` 是**两次独立的 `eval_java`**,
中间游戏线程会推进帧。移动落地了、帧过了,读到的是另一个时刻。
这个竞态**一直都在**,是我给 knob 加了 83ms 动画才把它暴露出来。

改法与 `click_and_focused` 一致:**移动与读取放进同一次 eval**(`hover_and_read`)。
改完连跑 4 次稳定 26/26,并反向验证过(把 `hoverEnabled` 改成 false → 正确变红)。

**教训**:跨两次 eval 断言一个"只维持到下一帧"的状态,本身就是错的形状 ——
和 §5 那条"前置条件必须摆到已知位置"是同一类错误。

## 7. elevation border 的断言改了三次才真正能红

这轮唯一的**新增视觉结构**(Windows 11 的亮顶边),验证过程值得记全,因为前两版都是空转:

| 版本 | 断言 | 为什么是空转 |
|---|---|---|
| 1 | 顶边 **luminance** > 底边 | 两停是**同一个白**,只差 alpha ⇒ luminance 恒为 255,**永远不可能失败** |
| 2 | 顶边 **alpha** > 底边(采样 y=20 / y=51) | y=51 落在 **face** 上不是描边上;而且"顶>底"由两层叠加结构**天然成立** —— 压平、倒置、两停设为相同,三种改坏全都照样通过 |
| 3 | 底边 **必须恰为 0x12**,且顶底 **gap ≥ 20** | ✅ 正常 gap=22 通过;压平 gap=17 变红;ramp 归零 gap=0 变红 |

阈值是算出来的,不是试出来的(源 alpha 叠同色:`a + a(1-a)`):

```
lit  0x18 over base 0x12 -> 0x28   gap 22   正确
base 0x12 over base 0x12 -> 0x23   gap 17   litColor 被压平
```

所以 bar 落在 20。**先试的 16 会放压平的过去** —— 因为两层相同底色叠起来确实比一层亮。

顺带查出一个**实现 bug**:我一度把 `MappingMode="Absolute"` 当设备像素,给 rampHeight 除了 DPI。
[官方定义](https://learn.microsoft.com/en-us/dotnet/api/system.windows.media.brushmappingmode)
是"直接在**局部空间**解释",即 DIP —— 除 DPI 会让 Retina 上的亮带**偏细**。已撤回。

## 8. 已知未覆盖

诚实列出来,别当成已验证:

- **KI-11 热键**没验过 —— 它带占位符签名,内核原话 `SKIP unverified patch MCP-KI0011 —
  signature not trusted`,所以打开界面目前走 `eval_java`。签名 ceremony(`scripts/sign-patch.sh`)
  需要私钥,**这台机器不做**。
- **长时间运行**没验 —— 探针跑完就关。帧率影响、显存增长、几十分钟后的稳定性都未知。
- **Windows 侧完全没验** —— 这批修复全部只在 macOS / Apple GL 2.1 上跑过。
  尤其 buffer 绑定和 vertex attrib array 那两条,不同驱动的行为可能不同。
- **`AbstractButton` 与 `Flickable` 的按钮路径**仍未验 —— §5 的修复让它们**能**收到
  `LeftButton` 了,但当前场景里一个都没用(全是 `MouseArea` + 自绘),所以没有真机证据。
  将来引入 qml4j 的 stock `Button` 或滚动容器时,按 §5 的方式补一条断言。
- **中键**没验 —— `toQml(2)=MiddleButton` 有单测,但真机上没有消费中键的控件可断言。

### 一个操作教训(不是代码 bug)

证伪过程中我在**游戏运行时**改 QML 并 `mvnw package`,结果 `target/classes` 被短暂清空、
而运行中的 JVM 正好在那一刻读 qmldir,报了 `unknown QML type: NavItem`。
那不是代码问题,是我的时序问题 —— **改资源要先停客户端**,因为场景是按需从 classpath 读的。
