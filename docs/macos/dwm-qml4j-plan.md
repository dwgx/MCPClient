# dwm 引入 qml4j — 落地方案

目标:把 qml4j(Skija/QML)作为 dwm 的 UI 实现引入,**并且保证 qml4j 上游更新后我们能持续跟上**。
本文只立方案与约束,不含实现。

前置:`client` + `lwjgl2-shim` 已在 macOS 跑通;`dwm/` 目前是空概念模块(`origin/mcp-core`)。

---

## 0. 已验证的事实(不是推测)

| 事实 | 证据 |
|---|---|
| **qml4j 已发布 Maven Central** | `io.github.timer-err:qml4j-core:0.2.24` 返回 HTTP 200,`maven-metadata.xml` 的 `<release>` 即 0.2.24 |
| qml4j-core **不创建窗口、不依赖 GLFW** | `grep GLFW qml4j-core/src/main/java` 只命中 `EventDispatcher` 的注释(滚轮方向约定),无 API 调用 |
| 嵌入接缝是 `SurfaceBackend` | `qml4j-core/.../render/SurfaceBackend.java`:`init/acquireCanvas/present/resize/dispose/width/height` + `recordingContext()` |
| Skija 是 `provided` scope | `qml4j-core/pom.xml` — natives 由宿主选择,正合我们双平台需要 |
| **Skija 有 macos-arm64 natives** | `~/.m2/.../skija-macos-arm64/0.143.16/skija-macos-arm64-0.143.16.jar` 已在本地 |
| 上游已修过 macOS 那批坑 | `dwgx/qml4j`:`3b0dc65` first-thread 启动、`c91b02d` 高 DPI 逻辑坐标、`6ca28e9` 非 Linux 宿主干净退出、`9e110c0`/`442d771` 剪贴板安全 |
| 已有可复用的 FBO backend | `backup/qml4j-desktop:qml/.../McpFboSurfaceBackend.java`(221 行)——已解决 per-frame retarget 防 resize 黑屏、GL 状态隔离、native 调用 fault-isolation |
| **Skija 在 Apple 的 GL 2.1 兼容上下文上可用** | 真机探针(macOS 26.5 / M2 / Temurin 25):`GL_VERSION = 2.1 Metal - 90.5`、`GL_RENDERER = Apple M2`;`DirectContext.makeGL()` → `BackendRenderTarget.makeGL(w,h,0,8,fbo,GR_GL_RGBA8)` → `Surface.wrapBackendRenderTarget(..., ColorType.RGBA_8888, sRGB)` → 画矩形 → `flush()` → 干净退出,**全链路 OK**。所以 FBO 路线不需要改 qml4j 本体 |

---

## 1. 硬约束

**A. 绝不 vendor qml4j 源码。** 只声明 Central 坐标 + 版本属性:

```xml
<qml4j.version>0.2.24</qml4j.version>
```

上游发版 → 改这一行 → 跑测试。若必须改 qml4j 本体,走 `dwgx/qml4j` 的 topic stack → PR 上游
(那边已有 `upstream` remote + ff-only 同步纪律,`CONTROL_STATE.yaml` 记录三方同步状态),
**不在 MCPClient 里分叉**。这是"持续更新"唯一站得住的做法。

**B. macOS 决定了架构:必须渲进 MC 自己的 FBO。**
`-XstartOnFirstThread` 把主线程交给 GLFW/MC,AppKit 要求所有窗口/事件循环在主线程,
**第二套窗口体系没有可用的主线程事件循环**。所以 qml4j 的 `GlfwSurfaceBackend`(自建窗口)
在 macOS 上不可用,只能用 FBO backend 把 qml4j 画进 MC 当前绑定的 framebuffer。
这也正是 `dwm/README.md` 契约第 4 条要的东西 —— 平台约束与既有架构意图在此重合。

**C. 遵守 `dwm/README.md` 的四条契约**(原文在 `origin/mcp-core:dwm/README.md`):

1. 可拆卸辅助层,零安全决策权,**不 import 任何 `core` 类**
2. 由 **Board 经反射 Backplane** 发现加载;删掉 dwm,其余必须照常编译
3. 渲染走 `RenderBackend`/`DrawContext` SPI,**native 类型只出现在唯一一个 adapter 包**
4. 菜单本身是**真正的 `GuiScreen`**(由 MC 的 screen 生命周期管 render/input/resize/focus),
   不是字节码注入的平行管线

**D. 不碰 `client/` 基线。** 见本目录 `known-issues.md` MK-1 的教训:
追加功能走 board 芯片 / core 织入 / pg 构建期改写,不改 vanilla 源码。

---

## 2. 必须由你拍板的一件事

`backup/qml4j-desktop` 那次实现是 **always-on 桌面外壳**(taskbar、常驻窗口、System Info 面板),
被 owner 决定移除;而 `dwm/README.md` 契约第 4 条明确写"**应该是真正的 GuiScreen,不是平行管线**"。
两者不是同一形态。这次要哪个:

- **(甲) `GuiScreen` 形态** — 按 F-something 打开一个全屏 qml4j 场景,关闭即销毁。
  完全符合契约第 4 条,macOS 上最稳(生命周期、输入、resize 全由 MC 管),实现量最小。
  代价:不能常驻在游戏画面上叠加。
- **(乙) 常驻叠加形态** — 恢复 backup 里的桌面外壳。视觉效果最好,但与契约第 4 条冲突,
  且需要自己管 input 抢占/焦点/resize,是上次 black-screen 那批 bug 的来源。

我的建议是**甲**:先用 GuiScreen 形态把 qml4j 在 macOS 上真正点亮(有明确成败信号),
常驻叠加作为后续增量。但这条影响整体形态,不该我替你定。

---

## 3. 模块与包结构(甲方案)

```
dwm/
  pom.xml                        qml4j-core(compile) + skija-shared(provided)
                                 + skija-macos-arm64 / skija-windows-x64(runtime,并存)
  src/main/java/net/marcloud/mcp/dwm/
    DwmEntry.java                Board 经反射 Backplane 找的入口;不 import core
    spi/RenderBackend.java       契约③的 SPI
    qml/                         ← native 类型只准出现在这个包(契约③)
      McpFboSurfaceBackend.java  从 backup 恢复,实现 qml4j 的 SurfaceBackend
      QmlGuiScreen.java          extends GuiScreen(契约④)
      GlStateGuard.java          进出 qml4j 渲染时隔离 MC 的 GlStateManager 影子状态
      QmlInputBridge.java        LWJGL2 scancode → qml4j EventDispatcher
```

Skija natives 双平台并存,与 client 处理 LWJGL natives 的做法一致 —— 一个 jar 两端通用,
不用 os-activated profile(那会让产物依构建机而变)。

---

## 4. 依赖顺序与成败信号

| 阶段 | 做什么 | 成败信号 |
|---|---|---|
| 1 | dwm/pom 声明 qml4j + skija 双平台 natives | `./mvnw -pl dwm test` 过,jar 里同时有 `.dylib` 与 `.dll` |
| 2 | `QmlProbe` 级最小验证:加载 Skija native、建 `DirectContext` | 真机 headless 跑通,拿到非 null context |
| 3 | 恢复 `McpFboSurfaceBackend`,在 MC 的 FBO 上 wrap Skia surface | 游戏里画一个纯色矩形能看见,且**resize 后不黑屏** |
| 4 | `QmlGuiScreen` + 输入桥接,跑一个真 `.qml` 场景 | 按键打开,鼠标能点,ESC 关闭后游戏照常 |
| 5 | Board 经反射 Backplane 发现 dwm | 删掉 dwm jar,其余照常启动(契约②) |

第 3 阶段是真正的风险点:Skija 的裸 GL 调用会打乱 MC `GlStateManager` 的影子状态。
backup 里的做法是 driver 用 `GlStateGuard enter/leave` + `resetGLAll` 括起每帧 —— 
这个纪律必须照搬,它就是上次"resize 后世界变黑"的解药。

---

## 5. 待验证的问题

**最大的未知已经验掉了(见 §0 末行):Skija 在 Apple 的 GL 2.1 兼容上下文上可用。** 剩下这些:

- MC 的 `framebufferMc` 无 stencil attachment(backup 注释已记录:请求它会让 wrap 返回 null),
  macOS 上是否同样。上面的探针用 `stencil=8` 对**默认 framebuffer(fbo=0)** 成功了,
  但 MC 的自建 FBO 是另一回事,需要在游戏里对 `framebufferMc` 实测。
- qml4j 的 `EventDispatcher` 期望的坐标系与 Retina 缩放:我们已在 shim 侧把光标缩放到
  framebuffer 像素,而 qml4j 上游 `c91b02d` 改用"逻辑坐标",两者需要对齐,别缩放两次。
