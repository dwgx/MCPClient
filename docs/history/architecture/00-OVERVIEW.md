---
doc: arch-overview
title: 00 — 总览:MCPClient(the Kernel + Board)
layer: reference
status: authoritative
updated: 2026-07-13
parent: ../README.md
next:
  - path: 01-SECURITY-KERNEL.md
    when: 你要理解约束一切能力的 7 层特权内核
read_if: 你第一次接触本项目、需要建立整体心智模型(北极星、血缘、Phase-2 架构跃迁)时读;只想改某个具体子系统可跳过直接去对应章节。
---
# 00 — 总览:MCPClient(the Kernel + Board)

> AI 通过 MCP 深度驱动一个活着的 Minecraft 1.8.9 客户端,权限受一套 Windows-NT 风格的 7 层特权内核约束,底层配一个原生 JVMTI 调试器。这是整套文档的入口。

> **易变数字以 `../../STATUS.md` 为准。** 本文出现的测试数 / 内建工具数只是编写时的快照,如与 STATUS.md 冲突以 STATUS.md 为准(它是单一真相)。
>
> **范围说明:** 本文描述 the Kernel(`core/`)的 Phase-2 NT 内核架构。项目此后又长出一个与 `core/` **平级**的第二子系统 **Board**(`net.marcloud.mcp.board`,客户端功能框架,零硬依赖),它不在 7 层内核之内——见下方 §4 末的 Board 说明与 `06-PLATFORM-SPI.md`。

## 1. 北极星(项目要做成什么)

把 Minecraft 1.8.9 客户端变成**AI 可通过 Model Context Protocol(MCP)达到内核调试器级控制**的活体平台。AI 客户端能够:

- **观察(observe)**:游戏状态、进出站封包、被踢原因、周围方块/实体、屏幕视觉(PNG → `ImageContent`)、结构化 GUI 快照(`gui_snapshot`:每个按钮/物品槽/文本框/标签的 id/label/bounds/clickPoint;1.8.9 里滑条继承自 `GuiButton`,尚未单独抽取,仍以按钮 kind 呈现);
- **控制(control)**:聊天、发送任意协议封包、装卸运行时 hook、驱动活体 GUI(`gui_click_element`/`gui_type_text`/`gui_press_key`,点真实 handler);
- **热改(hot-load)**:向运行中的 JVM 编译并载入代码、redefine 活类(JBR 25 + DCEVM 可加字段/加方法);
- **深控(debugger-level)**:暂停线程、PopFrame、ForceEarlyReturn、断点、单步、读写局部变量(经原生 JVMTI agent,C6 层)——原生 DLL 现已用 clang 编成并在真实运行的 MC 客户端里 live 验证过。

关键点:一切能力都是**运行时经字节码注入挂上去的动态 seam**,追求最大灵活性,而不是工具数量。Phase 2 把这个平台从一个**工具堆(tool heap)**升级成**NT 内核架构**(代号 the Kernel / 内核)——每个能力都受 7 层特权门控,并且**没有"宣传了但其实是死的"工具**。

运行前提(地基假设):运行时是 JetBrains Runtime 25 + DCEVM(`_tools/jbrsdk-25.0.3-windows-x64-b508.16`,gitignored),`core/` 模块以 `--release 25` 编译、跑在与 Java 8 游戏同一个 JVM 里。MCP 走 socket(`127.0.0.1:25599`,newline JSON-RPC),另有一个 REST facade(`127.0.0.1:1337`,JDK 内置 `HttpServer`)。

## 2. 血缘:DepthARK → DepthMCP → MCPClient Phase 2

Phase 2 的架构不是凭空设计的,而是把用户自己的 C 语言项目搬到 JVM/MC 领域:

- **DepthARK**(`D:\Project\DepthARK`):用户用 C 写的 Windows 反 Rootkit / VT-x 调试器。已有 3 层特权(Ring 3/0/-1)+ NT 令牌 / 完整性标签 + VMCALL 门禁 + 96 个 IOCTL 能力 + bridge 进程隔离。
- **DepthMCP**(`D:\Project\DepthMCP`):把 DepthARK 暴露成 90 个 MCP 工具。
- **MCPClient Phase 2**:把这套"7 层特权 + 独立决策进程 + 能力分类 + 边界校验"的思想迁移到 JVM——用 JVMTI 换 VT-x,用 P-SECURE 独立进程换 bridge 隔离,用 MCP socket 换 IOCTL。P-SECURE ↔ 游戏 JVM 的 RPC 模式和 DepthMCP ↔ Depth.sys 的 IOCTL 是对称的。

## 3. Phase-2 的跃迁:从工具堆到 NT 内核

Phase 1(见 [[project-mcp-core]] 记忆)已经交付了完整的平台:热加载引擎、ByteBuddy 运行时 hook、MCP socket + REST 双门面、自扩展注册表(`create_tool` 运行时造工具)、自愈执行器、多模态感知(视觉/空间/记忆/叙事)、以及一个初版 CPU-ring 权限模型。已累计数十个内建工具(含 C1/C3/C5/C6/C7/C8 能力工具、GUI 交互工具、L6 句柄工具、compat 观察工具)、core 测试全绿。**易变的工具总数与测试数一律以 `STATUS.md` 为准**(注:`Ring.BUILTIN_RINGS` 条目数 ≠ 内建工具总数——如 `dev_probe` 以 `builtIn=true` 注册但不在该表内,经 `forBuiltin` 回退)。

Phase 2 的跃迁在于**把那个初版的单层 ring 权限,替换成 NT 内核式的 7 层 AND 组合特权系统**,并补上原生调试器。7 层如下(NT → MCPClient 映射):

```
L1 VTL:       P-SECURE(独立 JVM 进程,socket RPC)↔ P-NORMAL(游戏 JVM)   — 唯一真墙
L2 环:        R-1 core-jvmti.dll / R0 core-agent.jar(Instrumentation)/ R3 工具
L3 完整性:    目标资源标签(Protected/System/High/Medium/…/Untrusted),不可向上写
L4 特权:      令牌里的特权集合(granted ≠ enabled 两态)
L5 能力 SID:  CAP_CLASS_REDEFINE / CAP_NETWORK_SEND / CAP_SCREEN …  默认全拒绝
L6 对象句柄:  ObManager.open(path, desiredAccess) → 冻结掩码的 handle
L7 边界校验:  supervise() 深拷贝+冻结 args,校验 inputSchema,then dispatch
```

**当前落地状态(以源码为准,不夸大):**

- 实际的 7 层门是 `IoManager.supervise()`(`io/IoManager.java:111`)→ `engine.evaluate(subject, req)`(`:123`)→ `SeAccessCheck`。
- **L2/L3/L4/L5 已在 `SeLocalMonitor.evaluate()` 里 AND 组合实现**,任一层拒绝即 fail-fast:L2 环 `se/SeLocalMonitor.java:126`、L3 完整性 `:136`、L4 特权(granted 且 enabled)`:143`、L5 能力 SID 默认拒绝 `:154`。
- **L1 VTL 是 opt-in 的独立进程**:`alpc/AlpcServer` + `alpc/AlpcMain` + `alpc/AlpcProtocol`,Java 侧走 `se/SeRemoteMonitor`。`-Dmcp.core.psecure=true` 打开,FAIL-CLOSED(进程挂/超时/鉴权失败一律拒绝),游戏只发工具身份、从不发自己的 subject(防伪造 wide-open)。
- **L7 IoProbe 已接线**:`io/IoProbe.java`,在决策后深拷贝+冻结参数(防 TOCTOU)并按 JSON schema 校验(`IoManager.java:135`、`:144`)。
- **L6 对象句柄已建、默认关(opt-in)**:`ob/ObManager`(`checkRequest` 单门 + `HANDLE_OPS` 表 + owner-scope + idle reap)实现了 open-time 掩码冻结;`McpCore.buildObjectManager` 在 `-Dmcp.core.handles=true` 时接线(默认关 → 引擎里 L6 纯 no-op)。`strictHandles` 姿态(`-Dmcp.core.hardened=true`)让句柄工具无 handle 时拒绝而非回退名字解析(闭合 jthread 名复用 TOCTOU,见 ADR-0001 / KI-3)。
- **`SeProtectedObjects` 守护内核自身**:`se/SeProtectedObjects.java`,按精确全限定名 + `net.marcloud.mcp.core.se.` 前缀规则(`:91`)禁止 redefine/transform 内核类,堵住"用 redefine_class 改写权限系统自己"的自重写漏洞。审计后 `isProtected` 还归一化了数组描述符(`[L…;`)和内部类(`$`)名以防绕过。

## 4. 组件地图(package → 一句话职责)

| package | 角色 |
|---|---|
| `agent/` | JVM agent 入口:`CoreAgent`(premain/agentmain 捕获 `Instrumentation`)、`CoreBootstrap` + `StartupAdvice`(织入 `Minecraft.startGame` 出口点火 `McpCore`)、`AgentAccess`(受门控地把 Instrumentation 交给可信调用方,取代原来 public 的 `instrumentation()`)。 |
| `hotload/` | 热加载引擎:`InMemoryCompiler`(源码→字节码)、`DynamicClassLoader`、`LdrRedefiner`(over DCEVM)、`LdrEngine`(loadNew/redefineExisting)、`FileWatchDeployer`。 |
| `hook/` | 运行时 hook:`FltManager`(ByteBuddy 织入 NetworkManager 收发/断开)+ C3 的 `FltDynamicManager`(保留 `ResettableClassFileTransformer` 句柄 → hook 可卸载还原)、`HookSource` SPI 聚合、`HookBridge`/`HookTools`。 |
| `seam/` | C8 三大运行时缝合点:`NettyTap`(pipeline MITM,只发只读 dup 保持 wire 冻结)、`InputHook`(GLFW 输入)、`TickInjector` + `TickAdvice`(tick 注入,激活原本死掉的 `TickEvent`),由 `SeamController` 统一装/卸。 |
| `deepaccess/` | C5 深访问:`MmAccess`(cached `privateLookupIn` + `Unsafe` + `redefineModule`)读写任意字段(含 private/static final)、调私有方法、开模块;`RootResolver`/`ValueCodec`/`UnsafeAccess`/`MutateStateTools`。 |
| `synth/` | C7 合成:`PsSynthesizer`(`eval_ephemeral`,`defineHiddenClass` 造可 GC 的一次性类)、`SynthTools`。 |
| `debug/` | C6 调试器级控制:`KdBridge`(Java↔原生唯一 choke point,`isAvailable()` 探测 `core-jvmti.dll`)、`DebugTools`、`DebugEventQueue`、`JvmtiError`。DLL 现已用 clang 编成(`core/src/main/native/core-jvmti/build-clang.sh`,无需 MSVC),并在真实运行的 MC 客户端里 live 验证(断点/线程挂起/单步均执行真 JVMTI);DLL 缺失时仍优雅降级为 `isAvailable()==false` 干净拒绝。 |
| `security/` | 7 层特权内核本体:`Ring`/`IntegrityLevel`/`Privilege`+`PrivilegeToken`/`CapabilitySid`+`CapabilityCatalog`/`SeToken`/`SeToolRequirement`/`IoRequestPacket`/`SeAccessCheck`,`SeReferenceMonitor` 接口 + `SeLocalMonitor`(L2-L5 参考监视器)+ `SeRemoteMonitor`(L1)+ `SeProtectedObjects` 守护 + `PermissionTools`。 |
| `secure/` | L1 P-SECURE 独立进程:`AlpcMain`(独立 JVM 入口)、`AlpcServer`(127.0.0.1 决策服务)、`AlpcProtocol`(无依赖 io/JSON 编解码)。 |
| `registry/` | 自扩展 + 监督执行:`IoManager`(7 层门的 keystone,`supervise()`)、`IoSupervisor`(独立恢复池 + 熔断 + 超时 + catch Throwable)、`ToolStats`、`DynamicToolFactory`(AI 运行时造工具)、`MetaTools`、`IoProbe`(L7)、`Capability`。 |
| `mcp/` | MCP 服务端:`SocketTransportServer`(127.0.0.1:25599 over socket streams)、`ToolRegistry`、`ToolContext`。 |
| `http/` | REST facade:`HttpFacade`(127.0.0.1:1337,JDK 内置 `HttpServer`,`/v1/models`、`/v1/tools`、`/v1/screen` 等)、依赖无关的 `Json` 读写器。 |
| `introspect/` | C1 自省:`CmQuery`(`list_classes`/`describe_class`/`find_method` + 聚合 `list_hooks`)、`ClassInfo`/`ClassDetail`/`MethodInfo`/`FieldInfo`、`IntrospectionTools`。 |

| `gui/` | 结构化 GUI 交互(把 MC 界面当 API 给 LLM):`GuiReflect`/`GuiSnapshotService`(反射 vanilla 1.8.9 界面 → 每个 `GuiButton`/`Slot`/文本框的 id/label/bounds/clickPoint)、`GuiSnapshot`/`GuiElement`/`Bounds`/`Point`/`State`/`Viewport`、`GuiActions`(点真实 handler)、`GuiTrajectory`(操作轨迹)、`SoMOverlay`(set-of-marks 叠加)、`GuiTools`(`gui_snapshot`·`gui_snapshot_image` R2 / `gui_click_element`·`gui_type_text`·`gui_press_key` R1 + `SE_GUI_INTERACT` + HIGH 完整性 / `gui_trajectory` R3;快照带 State 校验,界面变了则拒绝 stale 点击)。用 client/ 模块的 vanilla 1.8.9 映射(`GuiButton.xPosition`、`Slot.xDisplayPosition`、`getStack()` 空槽返回 null)。 |

辅助包:`event/`(EventBus + PacketReceived/Sent/Disconnected/Tick 事件)、`thread/`(主线程 marshal)、`state/`(PlayerState / PacketLog 环)、`action/`(sendChat / sendRawPacket)、`vision/`(屏幕捕获)、`memory/`(持久 JSON 经验库)、`narrative/`(目标栈 + 叙事)。

> 注:上表包名沿用早期布局做心智地图;core 已重构为 NT Executive 分层命名(`se`/`ob`/`io`/`alpc`/`ke`/`boot`/`ldr`/`mm`/`flt`/`kd`/`ps`/`cm`/`drivers.*`),映射见 `04-NT-EXECUTIVE-RENAME-MAP.md`,不影响本文对职责的描述。

### Board — 与 core 平级的客户端功能框架

除 the Kernel(`core/`)外,项目还有一个**与 core 平级的独立模块 Board**(`net.marcloud.mcp.board`)。它不是内核的一部分,不受 7 层特权门约束,与 core **零硬依赖**(两模块互不 `import`,只经反射发现),所以内核那套 MCP/安全模型与 Board 那套客户端功能框架可以各自演进、互不牵连。

Board 是一个受 PCB(印刷电路板)命名启发、契约已冻结的客户端功能框架:`Trace`(事件总线,Clock 优先级 + Cancellable + 异常隔离 + 防泄漏 `Subscription` 句柄)、`Signal`、`Chip`(中性功能单元,带自动订阅袋)、`Matrix<T>`+`Manager<T>`、`Board`(静态门面)、`Backplane`(服务登记);子包 `signals/`·`chips/`·`hud/`·`input/`·`link/`·`persist/`(崩溃安全持久化层)。Board 有自己独立的一套测试(数目见 STATUS.md,与 core 分开跑:`./mvnw -pl board test`)。契约与设计细节见 `06-PLATFORM-SPI.md`。

## 5. 黄金意图 + 铁规则(不可违反)

**黄金意图:** 内核调试器级 AI 全控、最大灵活性、每个能力都真正接线可跑——**杜绝"宣传了但其实是死的"工具**。用户授予很大自主权("能做到什么就做到什么")。

**铁规则(带进 Phase 2,仍然生效):**

1. **wire 协议字节冻结**——不改上线字节,破坏它就上不了线(除非用户明确要离线/自定义协议时再议)。
2. **jar 里绝无 GPL**——HotSwapAgent(GPLv2)永不进产品 jar;DCEVM 是 JBR 内建的外部运行时,不入 jar。
3. **无死工具**——每个能力必须真接线可运行(黄金意图的执行面)。
4. **`SeProtectedObjects` 必须守住**——内核类不可被 redefine/transform,防自重写。
5. **v1.0.0 黄金基线不可变**;`_refs/` / `_tools` 只学习不拷贝。
6. **commit 无任何 AI 署名**,只署用户。

> **注(2026-09-30 政策反转,现行):** `client/src` 是 vendored Mojang 代码,**只读**。
> 任何行为性改动一律**回退成 vanilla,改走 compat patch**。
>
> 这条**推翻了** 2026-07-11 的解除("改了 MC 源其实没什么事情")。理由不是洁癖,是
> **可回退性**:compat patch 是一条带状态与证据串的签名 manifest 条目,撤销它就是删一条记录;
> 而 `client/src` 的改动只能靠"谁改的"找回来——通常找不回来。
>
> 判断准则不变的部分:动态注入(运行时装卸)仍优先,顺序是
> **compat patch > 直接改 vendor 源**。
>
> 首个按此政策归位的案例:`TextureUtil.java` 两处行为改动(commit `aa3f776`)回退成 vanilla,
> 其中 GL 常量那处由 `GlClampToEdgePatch`(`MCP-GL0001`,内核密钥签名)接管;
> mipmap 预扫描那处**不做替代**——它把每个 cutout 贴图挪到 alpha 加权平均分支,
> 而做出该改动的提交自己承认它并不修 KI-1,即没有缺陷需要补丁去修。
> 回归:`GlClampToEdgePatchTest` 同时钉住"vendor 是 vanilla"与"补丁会改写常量"两半,
> 缺任何一半都可以被不诚实地满足。

### 政策的唯一例外:无法对着我们的平台编译的 vendor 代码

规则要能被执行才有意义。已全仓审计(`client/src` 22 个文件与 v1.0.0 导入有差异),结果:

| 类别 | 处置 |
|---|---|
| `TextureUtil.java` 两处行为改动 | **已归位**:回退 vanilla + `GlClampToEdgePatch`;mipmap 那处不做替代(无缺陷可修) |
| Guava 17→33、LWJGL2→3 API 改名、oshi 升级、Netty | **机械改动,零行为差异**,不属于本政策范围 |
| `paulscode/sound/libraries/Library*.java` 等三个文件 | **例外**:LWJGL2 的 `AL.create/destroy` 已不存在,音频设备/上下文生命周期是在 LWJGL3 上重写的。**回退成 vanilla 就编译不过**,因此保留改动,并在文档登记为已知偏离 |

**这条例外必须写明,否则规则会被悄悄破坏**:碰到"这里只能改 vendor"的人如果没有出口,
就会自己找出口。正确做法是像上面这样登记它,而不是让它变成惯例。

除这一条外,不允许再有第二处 `client/src` 行为改动。


## 6. Git commit 表(每个交付了什么)

分支 `mcp-core`(未推 main)。Phase 2 的提交栈:

| commit | 标题 | 交付内容 |
|---|---|---|
| `8112e43` | Phase 0: protected-class set + gated Instrumentation accessor | `se/SeProtectedObjects`(精确集 + `security.` 前缀规则)在 LdrRedefiner/MetaTools/ByteBuddy matcher 处强制;`CoreAgent.instrumentation()` public→package-private,可信调用方改走 `boot/AgentAccess`。堵掉 2 个自重写漏洞。 |
| `178f9b0` | Kernel data model: 7-layer NT-style privilege types + in-process reference monitor | `IntegrityLevel`(L3)、`Privilege`+`PrivilegeToken`(L4 两态)、`CapabilitySid`+`CapabilityCatalog`(L5 默认拒绝)、`SeToken`、`SeToolRequirement`(每工具需求,`safeDefault`=仅 ring 向后兼容)、`IoRequestPacket`、`SeAccessCheck`、`SeReferenceMonitor` 接口 + `SeLocalMonitor`(L2-L5 AND 组合参考监视器)。 |
| `efa04a2` | Keystone: route every tool call through the 7-layer reference monitor | `IoManager.supervise()` 从单句 `policy.allows(ring)` 改为 `engine.evaluate(subject, req)`;HttpFacade/PermissionTools 走 `engine()`;`-Dmcp.core.caps=strict` opt-in。零回归。 |
| `734163e` | L7 boundary validation: deep-copy+freeze + schema-check tool args | `IoProbe` 深拷贝+冻结参数(防 TOCTOU)+ JSON schema 校验,拼进 `supervise()` 决策之后。live 验证 SDK `Tool.inputSchema()` 可还原为 Map。 |
| `7cdbb66` | L1 VTL: P-SECURE separate-process decision authority (opt-in) | `alpc/AlpcProtocol`+`AlpcServer`+`AlpcMain` + `se/SeRemoteMonitor`。独立 JVM 决策(无依赖 io/JSON 编解码),游戏只发工具身份、FAIL-CLOSED。`-Dmcp.core.psecure=true` + 共享 `-Dmcp.core.psecureToken`。 |
| `aff7597` | Integrate C1/C3/C5/C7/C8 capability packages behind the 7-layer gate | 4 个并行 worktree agent 造能力包,LEAD 串行集成:C1 `introspect/`、C3 `flt/FltDynamicManager`(保留 ResettableClassFileTransformer)、C5 `deepaccess/`、C7 `ps/PsSynthesizer`、C8 `seam/`(接活了死掉的 TickEvent)。集成时去重重复的安全类,全部经 7 层门 + 接入 Ring/CapabilityCatalog/SeToolRequirement/MetaTools。 |
| `a674ea9` | Fix adversarial-audit findings across the Phase 2 kernel | 6-agent 只读审计 + Opus 综合,修 8 个 HIGH + 若干 MEDIUM:SeProtectedObjects 归一化数组/内部类名防绕过、把动态能力类补进受保护集、补 L3/L4 门、DCEVM stale-VarHandle 失效钩子、NettyTap 只读 dup + 重连不泄漏 handler、把 2 个恒真测试换成真断言。诚实驳回误报。 |
| `5999fdf` | C6 CONTROL-EXEC: native JVMTI debugger (Java + scaffold) + fix seam_tick_disable dead tool | `debug/` Java 侧(`KdBridge`/`DebugTools`/事件队列)+ `core/src/main/native/core-jvmti/` 原生脚手架(`.c`/`.h`/`CMakeLists`/`build.bat`);修一个死掉的 `seam_tick_disable` 工具。DLL 当时待编,未编时干净降级。 |
| `5f9f2c2` + `de83a33` | Close audit CRITICALs + fix remaining findings | Codex ultra 18-finding 修复战:generated 工具无条件降到 R-1(`Ring.DEFAULT_GENERATED = R_MINUS_1`)+ `SE_RUN_GENERATED` + `CAP_TOOL_CREATE`(CRITICAL#1);`isReserved` 从注册表派生、`register()` 拒绝 generated-over-builtin(CRITICAL#2);补 wire 观测、生命周期、持久化、门控守卫。 |
| `1be1167` | Unblock C6 native JVMTI debugger: MSVC-free clang build + JNI_OnLoad bind fix | 用 LLVM/clang(`build-clang.sh`,`clang -shared`,无 Visual Studio / Windows SDK)编出 `core-jvmti.dll`;JNI_OnLoad 改在 app-classloader 上下文绑定原生方法(不只在 VMInit)使其真正可用;在真实运行的 MC 客户端里 live 验证 `debug_set_breakpoint`/线程挂起/单步。 |
| `643738b` | Gap fixes | 审计遗留缺口修复。 |
| `b1d9cba` | Structured GUI-as-API for LLM | `gui/` 包:`gui_snapshot`(R2)+ `gui_click_element`/`gui_type_text`/`gui_press_key`(R1 + `SE_GUI_INTERACT` + HIGH 完整性),反射 vanilla 1.8.9 界面成可点击元素,带 State 校验拒绝 stale 点击。 |

core 测试全绿(实测数见 STATUS.md;13 个 client 黄金协议测试始终未动)。此表只列 Phase-2 提交栈,更新的提交栈以 STATUS.md 为准。

## 7. 已知未建 / 阻塞项(如实标注)

- **L6 对象句柄层**:**已建**(`ob/ObManager`,`checkRequest` 单门 + `HANDLE_OPS` + `strictHandles` 姿态),经 `-Dmcp.core.handles=true` 接线,**默认关**(off ⇒ L6 纯 no-op);见 §4 与 `01-SECURITY-KERNEL.md` L6 节 + ADR-0001。
- **C6 原生 DLL**:**已解除阻塞**——不再依赖 MSVC,改用 LLVM/clang(`core/src/main/native/core-jvmti/build-clang.sh`,`clang -shared`)编出 `core-jvmti.dll`,并在真实运行的 MC 客户端里 live 验证过(断点/线程挂起/单步执行真 JVMTI)。编好的 `core-jvmti.dll` 是 gitignored;DLL 缺失时 Java 侧仍 `isAvailable()==false` 干净拒绝。
- **Phase 2 全栈 live-JBR 验证**:C6 调试器曾在跑起来的真实客户端里**手动** live 验证过(断点/线程挂起/单步),但**没有常驻的自动化回归**;GUI 交互只有一个 live 脚手架(`core/src/test/java/GuiClickLiveIT.java`,默认 `Assume` 跳过、须 `-Dmcp.it.live=true` 手跑),**未自动验证**。其余 hook/redefine/seam/deepaccess 仍多为 headless 测试,尚未全部端到端 live 验证。

## 8. 文档导航

- **[01-SECURITY-KERNEL](01-SECURITY-KERNEL.md)** — 7 层特权内核逐层拆解(L1 P-SECURE、L2-L5 `SeLocalMonitor`、L7 IoProbe、SeProtectedObjects、L6 缺口)。
- **[02-CAPABILITIES](02-CAPABILITIES.md)** — C1-C9 能力分类体系(introspect/hook/deepaccess/synth/seam 等)与其 MCP 工具。
- **[03-NATIVE-C6-AND-BUILD](03-NATIVE-C6-AND-BUILD.md)** — C6 原生 JVMTI 调试器 + 构建/运行 + 实机验证(`KdBridge`、`core-jvmti` 原生层、clang 无 MSVC 构建、live 验证、降级路径、JBR 25 + DCEVM 启动、socket 25599 / REST 1337)。
- **[04-NT-EXECUTIVE-RENAME-MAP](04-NT-EXECUTIVE-RENAME-MAP.md)** — core 的 NT Executive 分层重命名映射表(包 + 骨干类的旧名→新名)。
- **[05-TEST-MAP](05-TEST-MAP.md)** — core 测试 + client 黄金测试 + Board 测试的映射(实测数见 STATUS.md)。
- **[06-PLATFORM-SPI](06-PLATFORM-SPI.md)** — Board:与 core 平级的客户端功能框架(PCB 契约、Trace/Signal/Chip/Matrix/Board/Backplane)。
