# mcp-core 分支 dwm 模块调查(macOS arm64 移植)

调查对象:`origin/mcp-core` 分支上的 `dwm/`(未检出,全部内容通过 `git show` 读取)。
最后一次触及该目录的提交:`1dbf475 dwm: strip UI implementation to an empty concept module; drop GL/skiko/imgui backends`。

## 这个模块是什么

- README 明确写着 **"Status: intentionally empty. This module holds no code today."**(dwm/README.md:3)。dwm 是游戏内 UI 层的"占位/概念锚点",按项目的 NT/硬件隐喻,代号取自 Windows 的 Desktop Window Manager(dwm/README.md:5-15)。
- 树里只有两个文件:`dwm/README.md` 和 `dwm/pom.xml`,**没有 `dwm/src/`**(`git ls-tree -r origin/mcp-core -- dwm` 的完整输出就是这两个文件)。
- pom 声明 **零依赖**,并用注释确认这是有意为之:"No dependencies: an empty concept module compiles to an empty jar."(dwm/pom.xml:36)。父 pom 仍把 dwm 列在 modules 里(mcp-core 分支根 pom.xml:19),所以它每次构建都产出一个空 jar。
- 从 README 和 pom 得到的、被删掉的渲染后端(即"曾经尝试过的方案"):
  1. `dwm-gl` — 纯 OpenGL 后端(dwm/README.md:23;dwm/pom.xml:26)
  2. `dwm-skiko` — Skia(Skiko)后端(dwm/README.md:23;dwm/pom.xml:26)
  3. `dwm-imgui` — Dear ImGui 后端(dwm/README.md:23;dwm/pom.xml:26)
  4. `qml4j` — Skija/QML 的 "always-on desktop shell"(常驻桌面外壳,含 taskbar、窗口、System Info 面板)(dwm/README.md:24,28)
  此外还删了 "DWM MD3 component tree + theme"(dwm/README.md:22)。
- 这些实现可在 git 找回:`origin/backup/qml4j-desktop` 和 `origin/backup/overlay-guiscreen` 两个分支确实存在于远端(README dwm/README.md:28-29 所述,已用 `git branch -r` 核实)。
- README 写死了未来重实现的契约(dwm/README.md:33-41):dwm 是可拆卸的辅助层、不 import 任何 `core` 类、由 Board 经反射 Backplane 发现;渲染放在 `RenderBackend` / `DrawContext` SPI 后面;菜单本体必须是真正的 MC `GuiScreen`(由 MC 的 screen 生命周期管理渲染/输入/resize/焦点),而不是字节码注入的并行管线。这与本次调查"不要设计替代方案"的边界一致——契约是模块自己声明的,不是本文的建议。

## 文件清单

- `dwm/README.md`(origin/mcp-core)— 41 行;说明模块定位(UI 占位)、为何清空、被删的四个后端、备份分支、重实现契约。
- `dwm/pom.xml`(origin/mcp-core)— 41 行;空 jar 模块,零依赖,`<maven.compiler.release>25</maven.compiler.release>`(dwm/pom.xml:17),description 复述 README 的内容。
- mcp-core 分支根 `pom.xml`(仅读了 modules 段)— 确认 dwm 仍是 reactor 成员(第 19 行)。

## macOS 移植阻碍

模块本身:**无。** 空模块、零依赖、无源码,不含任何平台相关内容,在 macOS arm64 上按原样构建即可。

对未来重实现的约束(依据仅为 README/pom 中对被删后端的描述,非本文设计):

| 被删后端 | macOS 上原本会有的问题 | 依据 |
|---|---|---|
| `qml4j` 常驻桌面外壳 | 最麻烦。"always-on desktop shell"(taskbar、独立窗口)意味着自己的窗口体系;macOS 的 AppKit 要求所有窗口/事件循环在主线程,而主线程已被 `-XstartOnFirstThread` 交给 GLFW/MC,第二套窗口体系没有可用的主线程事件循环 | dwm/README.md:24,28 |
| `dwm-skiko` | Skiko 默认走自己的渲染层(macOS 上是 Metal/AWT 集成),需要自己的 surface/上下文,且通常假设非 GLFW 的宿主;与 MC 已占用的 GL 上下文和主线程约束冲突 | dwm/README.md:23 |
| `dwm-imgui` | 若按 imgui-java 常见用法自建 GLFW 窗口/上下文则撞主线程约束;若共享 MC 的 GLFW 窗口和 GL 上下文则问题小得多,但要处理 Retina 坐标缩放(现有移植中光标坐标已按 framebuffer 比例缩放) | dwm/README.md:23 |
| `dwm-gl` | 约束最少:在 MC 自己的 GL 上下文、主线程、`GuiScreen` 生命周期内画,正是 README 契约点名的模式(dwm/README.md:39-41),与已验证可行的 macOS 运行方式一致 | dwm/README.md:23,39-41 |

另注(非阻碍,提醒一致性):dwm/pom.xml:17 设 `maven.compiler.release=25`,与任务背景所述"bytecode target 是 Java 8"不一致。当前模块无源码,该值不产生任何字节码,无实际影响;若日后往里放代码需按项目整体的 target 决定。

## 不确定的地方

- 提交 `8dda691` 提到 "skiko/imgui/gl black-screen fixes"——三个后端当时都出现过黑屏,具体根因(上下文、线程、还是驱动)只能从 backup 分支的代码和真机复现判断,README/pom 没有记录。
- `backup/qml4j-desktop` / `backup/overlay-guiscreen` 上的代码本次未读(超出模块边界);上表对各后端的窗口/线程模型判断基于 README 的一句话描述,若日后要恢复某个后端,需先读备份分支确认其实际的窗口与上下文用法。
- Board 经反射 Backplane 发现 dwm 的机制在 `board/` 模块里,本次未读;dwm 为空时 Board 是否静默跳过(README 声称 "Deleting dwm must leave everything else compiling",dwm/README.md:34)需在 macOS 真机启动验证。
