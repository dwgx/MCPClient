# 真 DWM 架构 vs 我们的 dwm

项目用 NT 隐喻命名(core=NT kernel, board=PCB, compat=AppCompat, **dwm=Desktop Window Manager**)。
本文查清真 Windows DWM 的架构,逐条对比我们的实现,并区分:**哪些该学、哪些不适用、哪些是我们真的缺**。

来源(全部 Microsoft Learn):
[The Desktop Window Manager](https://learn.microsoft.com/en-us/windows/win32/learnwin32/the-desktop-window-manager) ·
[DirectComposition Architecture and components](https://learn.microsoft.com/en-us/windows/win32/directcomp/architecture-and-components) ·
[DWM best practices](https://learn.microsoft.com/en-us/windows/win32/dwm/bestpractices-ovw) ·
[High-Performance Window Layering](https://learn.microsoft.com/en-us/archive/msdn-magazine/2014/june/windows-with-c-high-performance-window-layering-using-the-windows-composition-engine)

---

## 一、真 DWM 是什么

**它的本质不是"画 UI",是合成器(compositor)。** Vista 之前每个窗口直接写显存,所以拖窗会拖出残影
——两个窗口画同一块内存。DWM 的根本改变是:

> When the DWM is enabled, a window no longer draws directly to the display buffer.
> Instead, each window draws to an offscreen memory buffer, also called an offscreen surface.
> The DWM then composites these surfaces to the screen.

组件构成:

| 组件 | 位置 | 职责 |
|---|---|---|
| `dcomp.dll` | 应用进程内 | COM API,应用用它建 visual tree |
| `dwmcore.dll` | **`dwm.exe` 进程内** | 真正的合成引擎,一个会话一个实例 |
| `win32k.sys` 内核对象库 | 内核 | 把应用的命令 marshal 给合成引擎 |

**关键机制:**

1. **Redirection surface(重定向表面)** —— 每个**顶层**窗口独占一个离屏缓冲;该窗口下所有子窗口
   画到同一个表面。GDI 绘制命令甚至 D3D swap chain 的 present 都被"重定向"到它。

2. **单一合成引擎服务所有应用** —— 一个 `dwm.exe` 把所有应用的 visual tree + DWM 自己的 tree
   合成为**每个桌面一棵大 visual tree**。带来五个好处(文档原文列举):跨进程窗口互操作、
   低权限应用可安全合成受保护内容、**可检测完全遮挡的窗口并跳过**、可直接合成到屏幕后缓冲省一次拷贝、
   所有应用共享单个 D3D device 省显存。

3. **Retained(保留式)visual tree + 原子提交** —— tree 是保留结构,改动**批量**累积,
   调 `Commit()` 才作为一个事务生效。不是 immediate mode。

4. **frame 与 batch 解耦** —— *frame* = 合成引擎的一次迭代;*batch* = 应用两次 `Commit` 之间的区间。
   因为引擎异步运行,**两次 Commit 之间它可能已经 present 了好几帧**。

5. **合成循环钉在 vblank 上**(文档给了完整 9 步):
   ```
   1. 估算下一次 vblank 时刻
   2. 取出所有 pending batch
   3. 处理这些 batch
   4. 用步骤 1 估算的时间更新所有动画   ← 注意:用"预计呈现时刻",不是"现在"
   5. 判定屏幕上需要重新合成的区域       ← 脏区域
   6. 只重新合成脏区域
   7. present:翻转前后缓冲
   8. 若 6/7 什么都没做,睡到有新 batch 提交
   9. 等下一次 vblank
   ```

6. **属性只写不可读** —— DirectComposition 的属性全是 setter 无 getter。理由:异步引擎返回的任何值
   可能立刻失效(比如属性上绑了 independent animation)。

7. **遮挡剔除** —— 被完全遮挡的窗口收不到 `WM_PAINT`,内容已在表面里,不必重绘。

8. **发布 frame 统计** —— 引擎公布呈现时刻与当前帧率,应用据此选择自己动画的采样时刻。

---

## 二、逐条对比

| 真 DWM | 我们的 dwm | 判定 |
|---|---|---|
| 每个顶层窗口一个 redirection surface | **没有**。qml4j 直接画进 MC 的 framebuffer,叠在游戏已完成的帧上 | **不适用**(见 §3.1) |
| 独立 `dwm.exe` 进程,信任边界 | 与游戏**同进程**,`provided` 依赖 client | **不适用**,但安全含义要讲清(§3.2) |
| 单引擎服务多应用,共享 D3D device | 单一 Skija `DirectContext`,只服务一个场景 | 规模不同,机制相同 |
| Retained visual tree | qml4j 的 `Item` 树就是保留式,带 `DirtyQueue` | **已具备** |
| 批量改动 + `Commit()` 原子生效 | qml4j 的 `Property.changeVersion()` + `dirty.flush()` | **已具备**,机制等价 |
| vblank 驱动合成循环 | 跟随 MC 的游戏循环(`drawScreen` 每帧) | **不适用**(§3.3) |
| 动画采样用**预计呈现时刻** | qml4j 内部用 `System.nanoTime()`(即"现在") | **真差距**,但在 qml4j 侧(§3.4) |
| 只重新合成脏区域 | 每帧全量重绘;qml4j 有 `skipLayout` 快路径但仍重绘 | **真差距**(§3.5) |
| 遮挡剔除,跳过不可见窗口 | 菜单关闭时不渲染;无部分遮挡判断 | 单场景下等价 |
| 属性只写(因异步) | 同步在帧内,可读可写 | **不适用**(§3.6) |

---

## 三、逐条说清"为什么"

### 3.1 我们没有 redirection surface —— 而且不该有

真 DWM 需要它,因为它要合成**任意多个互不知情的进程**的窗口。我们只有一个场景要画在游戏之上,
引入"先画到离屏纹理再合成"意味着每帧多一次全屏拷贝,换不来任何东西。

**但更重要的是 macOS 逼死了另一条路**:`-XstartOnFirstThread` 下 GLFW 占着进程主线程,
AppKit 要求窗口事件循环必须在主线程 —— **第二套窗口体系根本没有线程可跑**。所以
"画进宿主的 framebuffer"不是优化选择,是唯一可行解。

这也解释了为什么 `backup/qml4j-desktop` 那个常驻桌面外壳(taskbar + 独立窗口)注定难做:
它在向真 DWM 的形态靠拢,而我们缺少真 DWM 赖以存在的前提(自己的进程 + 自己的主线程)。

### 3.2 同进程 = 没有信任边界,这一点必须承认

真 DWM 把引擎放在**受信任的系统进程**里,与应用进程分离,所以低权限应用能安全合成受保护内容。

我们的 dwm 与游戏同进程,拿的是同一个 GL 上下文和同一个堆。所以模块契约里那句
"**零安全决策权、不 import 任何 core 类**"不是洁癖,**它是我们唯一的边界**:
既然拿不到进程级隔离,就只能靠"dwm 结构上无从参与安全决策"来替代。
`DwmEntryTest` 扫全模块源码断言无 `import net.marcloud.mcp.core` 正是在守这条线。

### 3.3 我们没有也不需要 vblank 循环

真 DWM 自己是显示的最终所有者,必须对齐 vblank。我们是**寄生在 MC 帧内**的:
`QmlGuiScreen.drawScreen` 由 MC 的 screen 生命周期调用,vsync 由 MC 的 `Display.sync()` 管。
自己再起一个 vblank 循环会与 MC 打架。

代价是:我们的帧率完全由游戏决定。游戏卡到 10fps,菜单动画也 10fps。真 DWM 不会——
它的合成循环独立于应用是否交付了新 batch。**这是寄生架构的固有代价,不是缺陷。**

### 3.4 动画采样时刻:真差距,但不在我们这层

真 DWM 第 4 步用**步骤 1 估算的 vblank 时刻**更新动画,而不是"现在"。差别是判断力级别的:
用"现在"采样,则动画进度落后于它实际被看到的时刻一整帧,表现为轻微 judder。

qml4j 的 `renderFrame` 内部用 `System.nanoTime()`,即"现在"。**这在 qml4j 侧,不该在
MCPClient 里 fork 修改**(会毁掉跟上游的能力)。若要改,走 `dwgx/qml4j` 的 topic stack 提 PR。

顺带:我们的 `UiSurface.frame(w, h, nanoTime)` 保留了 nanoTime 参数正是为这一天预留 ——
将来若 qml4j 支持外部指定采样时刻,宿主可以传"预计呈现时刻"。

### 3.5 脏区域:真差距,也是最值得做的一项

真 DWM 第 5/6 步只重新合成**脏区域**,第 8 步若无变化则**完全不合成**直接睡。

我们每帧无条件 `renderFrame`。qml4j 已经有一半机制:

```java
boolean skipLayout = Property.changeVersion() == renderedVersion;
```

它跳过**布局**,但仍然重绘。也就是说一个完全静止的菜单,每帧照样付一次全场景 paint。

**可做的改进(在我们这层,不碰 qml4j)**:`QmlUiSurface.frame()` 里比较
`Property.changeVersion()`,若与上一帧相同且无输入事件,则**跳过整个 renderFrame**。
风险:菜单是叠在**动态游戏画面**之上的 —— 跳过绘制不会留下上一帧的菜单像素,因为
MC 每帧重画整个世界,菜单会直接消失。所以**这条不能照搬**,除非先把菜单画到离屏纹理再合成
(即 §3.1 那个 redirection surface)。

**结论:脏区域优化在我们的架构下需要先引入离屏表面,两者是一揽子的。** 现在不做,记在这里。

### 3.6 只写属性:不适用

真 DWM 的只写属性是**异步架构的必然结果**。我们同步在帧内跑,读回来的值就是当前值,
强行照搬只写属性只会让代码更难写而无收益。

---

## 四、这次对比直接抓出的一个 bug

对比第 4/5 步时发现:qml4j 的 `renderFrame` **内部已经**调用 `tickAnimations(root, now)`
(`QmlView.java:237`),而我在 `QmlUiSurface.frame()` 里又调了一次 `view.tickAnimations(nanoTime)`。

**同一帧内动画被推进两次 —— 所有动画双倍速。** 当前场景没有动画,所以看不出来,
会由"第一个加过渡效果的人"踩到。已删除多余调用,并在 `UiScaleContractTest` 加了源码级断言防回归。

---

## 五、我们比真 DWM 少的、和不需要的

**真的缺(记录待办)**
- 脏区域/空闲帧跳过 —— 需与离屏表面一揽子做(§3.5)
- 动画采样用呈现时刻而非"现在" —— 属 qml4j 上游(§3.4)

**结构上不适用(不是缺陷)**
- redirection surface / 多窗口合成 —— 我们只有一个场景,且 macOS 主线程约束封死了多窗口路径
- 独立合成进程与信任边界 —— 用"零安全权 + 不 import core"的结构约束替代
- vblank 循环 —— 寄生在 MC 帧内,代价是帧率随游戏
- 只写属性 —— 同步架构无此必要

**我们有而真 DWM 没有的**
- 声明式场景描述(QML),真 DWM 的 visual tree 是命令式 API 建起来的
- 宿主 GL 状态影子同步(`GlStateGuard`)—— 真 DWM 拥有整个显示,不必迁就别人的状态机

---

## 六、命名是否恰当

诚实说:**我们的 dwm 承担的是真 DWM 的"绘制与呈现 UI"部分,不是它的"合成多个独立表面"本质。**

但这个命名仍然站得住,理由是它在**项目内部的隐喻体系**里位置正确 —— 它是唯一负责
"屏幕上看到的东西"的层,与 core(内核)、board(硬件功能)、compat(兼容补丁)构成完整的 NT 类比。
若哪天真的引入离屏表面 + 多面板合成(§3.5 那条路),它会向真 DWM 的本质靠近一步。
