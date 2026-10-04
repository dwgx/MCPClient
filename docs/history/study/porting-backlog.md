---
doc: porting-backlog
title: 1.8.9 → 现代运行时 移植 bug backlog(compat 补丁候选)
layer: reference
status: authoritative
updated: 2026-07-13
parent: README.md
next:
  - path: ../architecture/07-COMPAT-SHIM.md
    when: 你要把某个候选做成签名 compat 补丁
  - path: ../project/known-issues.md
    when: 你要看已登记的 KI(KI-1..9)
read_if: 你要挑一个移植 bug 来修、或想知道 vanilla 1.8.9 在 LWJGL3/JDK25/Netty4.2 下还有哪些坑没修。
---

# 1.8.9 → 现代运行时 移植 bug backlog

> **一句话**:vanilla MC 1.8.9(Java8/LWJGL2/Netty4.0)跑在我们的 JDK25/LWJGL3/Netty4.2 上会踩一批移植坑。
> 本文是这些坑的**候选清单** —— 三份 2026-07-13 研究(本地 133 仓广度 + 联网 lwjgl3ify/CleanroomMC/legacy-lwjgl3 + 本地深读 commandblock2 完整移植 patch)合并去重。每条标了别人怎么修、以及**别人修错的地方(别抄)**。
> 落地方式(直接改 client 源 vs compat 签名补丁)**逐条由 dwgx 拍板** —— KI-1/KI-4 当初为"client 纯 vanilla"回滚过。

##  铁则:不许盲抄 corpus 修法

研究实证:别人的手修**有真 bug**。抄之前必须理解 + 验证 + 改进:
- **commandblock2 移植到 Netty 5.0-alpha,不是我们的 Netty 4.2** —— 它把 `channelRead0` 改名 `messageReceived`(Netty5 名),照抄会**编译失败或静默丢包**。我们该用 `channelRead0`。
- **commandblock2 自己的 shader 修复有 bug**:`ShaderLoader` 把 `ByteBuffer.toString()` 喂给 `glShaderSource(CharSequence)` → 拿到 `"java.nio.HeapByteBuffer[...]"` 而非 GLSL → shader 永不编译。我们要 `StandardCharsets.UTF_8` 解码。
- **KotlinizedMCP 的 Kotlin 重写**:`PotionEffect.combine()` 用 `with(other){}` 把合并方向搞反。
- **KotlinizedMCP 的 `PacketBuffer.touch()` 返回 null**(错)—— 该委托给 wrapped buf(commandblock2 对)。
- Gradle-MCP-1.8.9-Base 家族**根本不是移植**(pin LWJGL2.9.4/Netty4.0/Java8)—— 是"我们要替换的旧构造"的反面教材,不是修法来源。

## Tier 1 — 崩溃级(不修就连不上/进不去/黑屏)

| ID | 问题 | vanilla 类 | 症状 | 修法(来源) |
|---|---|---|---|---|
| NEW-1 | PacketBuffer 缺 `touch()/touch(Object)` | `network.PacketBuffer` | Netty4.2 调 touch() → AbstractMethodError 硬崩 | 委托给 wrapped buf(commandblock2 对;KotlinizedMCP 返回 null 错) |
| NEW-2 | `Class.newInstance()` 5 处 | EnumConnectionState/TileEntity/MapGenStructureIO | JDK25 强封装 → 断封包/方块实体/结构生成 | `getDeclaredConstructor().newInstance()` |
| NEW-6 | Netty `LocalEventLoopGroup` 移除(扩展 KI-4) | NetworkSystem + NetworkManager | ClassNotFound;单人世界进不去 | `DefaultEventLoopGroup`/`MultiThreadIoEventLoopGroup(LocalIoHandler.newFactory())`,server+client 两侧 |
| N6 | `org.lwjgl.opengl.Display`/`DisplayMode` 移除 | `client.Minecraft`(~20 处) | 窗口初始化/帧循环/resize/全屏/vsync/游戏时钟全断 | 迁到 GLFW(我们有 lwjgl2-shim,验覆盖) |
| N5 | `org.lwjgl.util.glu`(GLU+Project)删除 | EntityRenderer/ActiveRenderInfo/GuiMainMenu | 投影/全景/鼠标拾取/GL错误串失败 | 矩阵数学或 `org.joml` 重写 |
| N7 | `org.lwjgl.Sys` 移除 | `Minecraft.getSystemTime()` | 游戏时钟断 → 时序/物理漂移 | `System.nanoTime()/1_000_000L`(单调 ms) |
| NEW-3 | SoundManager `LibraryLWJGLOpenAL` | `client.audio.SoundManager` | LWJGL3 无 `AL.create()` → 全无声 | LWJGL3 ALC/AL 自定义 Library(先验我们 shim 是否已覆盖) |
| N9 | GuiConnecting epoll 被 `&& false` 硬禁 + 废弃 Unsafe | `multiplayer.GuiConnecting` | native transport 静默失效;Unsafe 块重启会 InaccessibleObjectException | 用正确的 epoll 门控,删死代码 |

## Tier 1 — 已知 KI(我们独有 or 已处理)

| ID | 状态 |
|---|---|
| KI-1 mipmap 蓝斑 | **我们独有发现**(corpus 无人修 —— 没人真跑 LWJGL3 到那步)。zeroFill 修复已 live 证明后回滚,列 compat MCP-KI0001 |
| KI-4 LocalServerChannel | 我们发现;= NEW-6 的子集;修复已回滚,列 MCP-KI0004 |
| NEW-4/5 GL_CLAMP → GL_CLAMP_TO_EDGE | **我们已顺手修**(TextureUtil);EntityRenderer 光照贴图待查是否也要 |

## Tier 2 — 视觉/GL API(NoSuchMethodError 或渲染错)

| ID | 问题 | 来源 |
|---|---|---|
| N8 | LWJGL3 去了隐式向量重载:`glGetInteger`→`glGetIntegerv`、`glFog`→`glFogfv`、`glLight*`→`*fv`、`glMultMatrix`→`glMultMatrixf`、`glUniform*`→`*iv/*fv` 等**一整族** | commandblock2 patch(多处) |
| N1 | ShaderLoader `glShaderSource` 签名变 `(int,CharSequence)`,调用方仍传 `ByteBuffer.toString()` → shader 永不编译 | commandblock2(它自己的 bug,我们要 UTF_8 解码) |
| NEW-7 | `OpenGlHelper.initializeTextures` 用 `GLContext.getCapabilities()`(LWJGL3 无)→ FBO/VBO/shader 检测断 | `GL.getCapabilities()` |
| web | GLFW framebuffer 启动报 0x0 → 黑屏竞态;色彩/gamma 回归 | lwjgl3ify |
| web | GLU `gluBuild2DMipmaps` 移除(KI-1 相邻) | legacy-lwjgl3 |

## Tier 2 — 输入/窗口(GLFW 迁移,喂 lwjgl2-shim)

| ID | 问题 | 来源 |
|---|---|---|
| NEW-8 | `org.lwjgl.input.Mouse/Keyboard`(getDWheel 等)不存在 | 验 shim 覆盖 |
| web | MouseHelper.deltaX/Y 是 int,GLFW 是 float → 精度丢 | lwjgl3ify MixinMouseHelper |
| web | 剪贴板用 java.awt.Toolkit(GLFW下崩);Open link 用 java.awt.Desktop(挂) | lwjgl3ify |
| web | 键盘字符/IME 输入模型变了(比 NEW-8 深);特殊键 GLFW keycode 越界崩 | lwjgl3ify + legacy-lwjgl3 |
| web | macOS 必须 `-XstartOnFirstThread`(GLFW 窗口在主线程) | 多源 |
| N11 | Mouse/Keyboard EventQueue 自拷贝 bug(`lastxEvents[nextPos]=xEvents[nextPos]`) | commandblock2(手修 bug,别抄) |

## Tier 3 — JDK 现代化(启动配置/反射)

| ID | 问题 | 来源 |
|---|---|---|
| web | 反射写 static final 字段在 JDK12+ 被封(≠ NEW-2) | CleanroomMC + lwjgl3ify |
| web | 系统 ClassLoader 不再是 URLClassLoader(JDK9+) | 多源 |
| web | 需一批 `--add-opens`(JPMS 强封装)—— lwjgl3ify 有现成 java9args.txt | lwjgl3ify |
| web | JAXB/Nashorn/Pack200/SecurityManager 移除(部分对 vanilla 相关性低) | CleanroomMC |

## 优先级建议(dwgx 定夺)

1. **先修 Tier 1 崩溃级**(NEW-1/2/6 + Display/GLU/Sys)—— 这些是"一连服务器/进世界就崩",优先级高于 KI-1(视觉瑕疵)。
2. **输入/窗口喂 lwjgl2-shim** —— 我们已有 shim,很多是补覆盖缺口,收益高。
3. **JDK 配置类**(--add-opens 等)—— 大多是启动参数,不是代码改,最省力。
4. KI-1/视觉类最后(不影响能不能跑,只影响好不好看)。

## 来源(完整细节)
- 本地广度扫(8 候选):workflow `wf_ce22eff8-0d1`
- 联网 lwjgl3ify/CleanroomMC/legacy-lwjgl3(~20 候选):workflow `wf_35c9541d-902`(synth 失败,4 research 结果在 journal)
- 本地深读 commandblock2 完整 patch(~17 候选 + corpus 陷阱):workflow `wf_80ef6e02-435`
- 修复候选(进行中):workflow `wf_2f449f1a-6db`

