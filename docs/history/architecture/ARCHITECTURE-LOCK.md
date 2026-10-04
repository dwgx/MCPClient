---
doc: architecture-lock
title: 架构固定锁 — 冻结骨架 + 变更闸门
layer: reference
status: locked
updated: 2026-07-13
parent: ../README.md
read_if: 你打算改动模块布局、包结构、核心契约或安全脊柱前——先读本篇,确认是否触碰冻结项;若触碰,走 ADR + 用户确认闸门。
---

# ARCHITECTURE-LOCK — 冻结的架构骨架

> 本篇是**架构层的锁**:列出"以后必须保持、不许随手改"的骨架。
> 定位类比 `../../../CLAUDE.md`(项目铁律入口)——CLAUDE.md 锁流程与铁律,本篇锁**结构**。
> 易变数字(测试数、工具数、进度)不在这里,一律见 `../../STATUS.md`。
>
> **status: locked 的含义**:下列每一项都是不变量。要动其中任何一项,**必须**先立一份 ADR
> (`_templates/adr.md` → `adr/ADR-NNNN-*.md`)并满足 §7 的变更闸门,否则一律拒绝改动。
> 锁的是"骨架形状",不是"不能加东西"——加法式扩展(新 Chip、新工具、新测试、新子类)自由,
> 破坏或重塑下列结构才需要闸门。

---

## L0 — 模块层次(设计骨架不可增删/合并/越界;地基与辅助分列)

> 分类判据:**"删了它,其余骨架还能否独立编译运行"**——删地基→client 编不了;删辅助→骨架照跑。
> 变更来由见 `adr/ADR-0002-lwjgl2-shim-platform-substrate.md`(2026-07-14,accepted)。

| 层 | 模块 | 职责(冻结) | 边界铁律 |
|---|---|---|---|
| 平台地基 | `lwjgl2-shim/` | LWJGL2→LWJGL3 ABI 兼容垫片 | 只做 ABI 兼容,不含业务/安全权;与 client 共生(删它 client 编不了) |
| 设计骨架 | `core/` | NT 内核:MCP server、7 层安全内核、能力包 C1-C8、JVMTI 调试器 | 内核本体,唯一可持有安全决策权 |
| 设计骨架 | `board/` | 客户端功能框架(PCB 隐喻):事件总线 + 功能单元 + 管理器 | 与 core **零硬依赖**(见 L3) |
| 设计骨架 | `client/` | MC 1.8.9 **vanilla 映射**(反射/GUI 字段名的唯一真相源) | 不被 core/board 污染;保持 vanilla |

**不变量 L0**:**设计骨架恒为 3(core/board/client)**,不可增删/合并/职责越界;`lwjgl2-shim` 是
client 的 **ABI 地基垫片**(层次低于骨架、与 client 共生,不是第 4 个骨架、也不是可拆卸辅助);
`client/` 保持 vanilla 映射(改它=改事实基准,禁止);core/board 绝不 import `client/` 的映射类到
自身逻辑里(需要游戏访问走反射/seam)。此三骨架 + shim 之外的一切皆"可拆卸辅助"(下述),增删不触 L0。

> **设计骨架之外的辅助工具/库不计入那 3 个,可自由增减。** 设计骨架永远是上面 3 个(core/board/client),
> shim 是 client 的地基垫片;这四者**之外**可以挂任意多个独立小工具/库(build 持件、加固器、外部工具等),它们:
> **① 不进骨架、不持安全决策权;② 与主项目解耦、可独立外抽;③ 增删/外抽它们不触碰 L0 骨架不变量**(设计骨架仍是 3),因此**无需走 §7 闸门**。
>
> **当前挂着的辅助模块(可拆卸,删了 3 设计骨架仍独立编译运行):**
> - **`pg/`** —— 辅助加固库(标注驱动的字节码加固,`pg-api`/`pg-engine`/`pg-maven-plugin`,包名 `net.marcloud.pg.*`)。列在根 `pom.xml <modules>` 里只为一键构建/测试;将来大概率外抽独立发布。
> - **`dwm/`** —— UI 子系统(DWM 隐喻):可切换 `RenderBackend`/`ContentBackend` SPI + compositor + MD3 组件。零安全决策权,靠 board 的反射 Backplane 发现;删掉它 3 设计骨架照编照跑。
> - **`dwm-gl/`** —— dwm 的纯 Java 手写即时模式 GL 后端(zero Kotlin/native)。实现 `RenderBackend`/`ContentBackend` + `DrawContext`;含 `GlStateGuard`(MC GlStateManager 影子写回,三后端共用的黑/白/不可见修复)+ `GameBackendHost`/`GameInput`(反射解析窗口/输入)。core 反射发现入口 `GlUiEntry`/`GlOverlayEntry`。可拆卸辅助。
> - **`dwm-imgui/`** —— dwm 的 Dear ImGui 后端(imgui-java,native DLL)。imgui 类型只在此模块;复用 dwm-gl 的 GlStateGuard(编译依赖 dwm-gl)。入口 `ImGuiUiEntry`/`ImGuiOverlayEntry`。可拆卸辅助。
> - **`dwm-skiko/`** —— dwm 的 Skia/Skiko 后端(纯 Java 调 `org.jetbrains.skia.*`,native DLL,最高保真)。复用 dwm-gl 的 GlStateGuard。入口 `SkikoUiEntry`。可拆卸辅助。
> - **`dwm-compose/` —— 已于 2026-07-14 git rm 删除**(dwgx"零 Kotlin 源码"约束;`@Composable` 无法 javac 编译)。被上面三个纯 Java DrawContext 后端取代。归档 `docs/project/archive/dwm-compose-removed-2026-07-14.md`(含从 HEAD `6468b83` 复活指引)。
> - (以后新增的这类工具往此列表加即可,不改 L0 骨架。)

---

## L1 — core 的 NT Executive 分层包(包名 + 职责冻结)

根包 `net.marcloud.mcp.core` 保留。下列分层包名与职责是**结构契约**,重命名/合并/拆分需走 ADR。
映射来历见 `04-NT-EXECUTIVE-RENAME-MAP.md`。

| 包 | NT 角色 | 职责一句话 |
|---|---|---|
| `se` | Security Reference Monitor | 策略引擎 / Ring / Integrity / Privilege / SID —— 安全决策核心 |
| `ob` | Object Manager | 对象句柄层(L6) |
| `io` (+`io.transport`/`io.http`) | I/O Manager | supervise/dispatch/breaker = IRP 分发;socket 前门 + REST facade |
| `alpc` | ALPC (P-SECURE) | 独立决策进程 + RPC 端口 |
| `ke` (+`ke.event`) | Kernel dispatcher | 游戏线程 dispatcher;EventBus = 内核事件分发 |
| `boot` | 系统引导 | premain/agentmain + Instrumentation |
| `ldr` | 映像加载器 | 编译 / redefine 活类(热加载) |
| `mm` | Memory Manager | 读写活对象字段(deep-access) |
| `flt` (+`flt.seam`) | Filter Manager | ByteBuddy retransform = minifilter;Netty/GLFW/tick 注入 |
| `kd` | Kernel Debugger | JVMTI 原生调试器 |
| `ps` | Process/Thread Mgr | hidden-class 生成执行体(synth);包名硬约束 `...core.ps` |
| `cm` | Configuration Mgr | 类 / 自模型查询(introspect) |
| `compat` | AppCompat / Shim Engine | 启动期给 vanilla 类套**已签名**兼容补丁,修已确认的移植 bug(不改 client、不走热加载);对标 Windows AppCompat + .sdb。详见 `07-COMPAT-SHIM.md` |
| `drivers.*` | 设备驱动 | gui/video/world/store/narrative/action + act(PHASE A 流式操控)/observe(PHASE P 抓包观测)= 8 个感知·作动驱动 |

**不变量 L1**:上述 14 个分层包 + `drivers` 的 8 个子包构成 core 的骨架;`compat` 补丁的应用是内核启动期自动的、靠 Ed25519 签名验签而非 ring 门控(验签失败即拒),每个补丁须挂已证实 KI + 证据 + 签名方可进 `CompatDatabase`;
`ps` 的载重包名恒为 `net.marcloud.mcp.core.ps`(工具描述、javadoc、`REQUIRED_PACKAGE` 必须一致——
历史上这里漂移过一次 `synth`/`ps`,是审计发现的硬伤,已锁死)。

> **`link/` 是 seam,不算骨架分层包(所以骨架计数仍是 14)。** core 根包下另有 `link/`(`BoardTraceLink`/`BoardClockBridge`/`BoardWorldEventBridge`),是 **core→board 的纯反射发布桥**——core 靠它给 board 发信号(聊天否决 / 发包否决 / 时钟·世界事件 fan-out)而**零编译依赖 board**。它是 **L3「core↔board 零硬依赖」不变量的 core 半边**,与 board 侧 `board.link/McpLink`(board→core 反射探测)对称。二者都是 seam 而非 NT 内核骨架层,加法式扩展自由(对称的 board 侧 `McpLink` javadoc 自陈"a seam under board.link,not part of the frozen skeleton")。

---

## L2 — board 的 PCB 冻结契约(公共签名不许改)

`board/` 顶层 8 个类的**公共签名**冻结(细节见 `06-PLATFORM-SPI.md` §7)。新功能只能 `extends`/新增类,
不许改这些类的既有公共/protected 方法签名或 `final` 修饰:

| 类 | 角色 |
|---|---|
| `Trace` | 事件总线(Clock 优先级 + Cancellable + 异常隔离 + 防泄漏 `Subscription` 句柄) |
| `Signal` | 事件基类(带 PRE/POST 相位的 `Cancellable`) |
| `Clock` | 优先级枚举 HIGHEST..LOWEST |
| `Chip` | 中性功能单元(onLoad/onEnable/onDisable/onUnload;自动订阅袋) |
| `Matrix<T>` | 功能管理器 |
| `Manager<T>` | 管理器接口 |
| `Board` | 静态门面 |
| `Backplane` | 服务注册表 |

**不变量 L2**:这 8 个类的公共 API 是被 mcp-core 反射依赖的稳定面,冻结;加法式扩展自由。

---

## L3 — core ↔ board 零硬依赖(架构级隔离)

**不变量 L3**:`core/` 与 `board/` **互不 import 对方的具体类**。二者通过反射桥
(`board/link/McpLink` + `BoardPort`)连接,桥的公共签名只穿 **JDK 类型**或 board 自己的接口/抽象类型,
**绝不泄漏**对方子系统的具体 impl 类。删掉任一模块,另一方仍能独立编译。
(board 已有 `BoundaryDisciplineTest` 反射守卫此不变量。)

---

## L4 — 7 层权限模型 + 安全脊柱(最高等级锁 · 呼应 CLAUDE.md 铁律③)

**不变量 L4(不可动区)**:
1. **7 层权限内核 L1-L7** 的分层语义冻结;`se` 是唯一安全决策权持有者。
2. **R-1 门控**:任何 AI 生成 / eval 的代码(`eval_java`、`redefine_class`、C5 字段写、C6 JVMTI、C7 synth)
   一律顶格 gate 到 R-1 + SYSTEM + 相应 capability——generated 工具**不查按名侧表**,无条件顶格。
3. **fail-safe 默认**:未登记 ring 的危险工具回退 R3(有 `PolicySideTableDriftTest` 守卫);
   非 loopback bind 无 token 时拒绝启动 facade;无法解析的 host 当作暴露。
4. **ProtectedClasses / `SeProtectedObjects`** 清单(`se.`/`ob.`/`compat.`/`alpc.` 四前缀 + FQN)是安全边界,见 `../../SECURITY.md`。
5. **常量时间比较**:权限恢复等 kill-switch 的 token 比较用 `MessageDigest.isEqual`,不用 `String.equals`。

动 L4 任何一条 = 触碰安全脊柱,**闸门最严**(见 §7,等同 CLAUDE.md 铁律③的"安全类不可动")。

---

## 5 — 什么不算"改骨架"(加法式扩展,自由)

以下不需要 ADR,放手做:新增 `Chip`/`Signal` 子类、新增内建工具(登记 ring)、新增测试、
新增 `drivers.*` 下的具体驱动实现、新增 `board/persist` 之类的**新子包**(不碰既有契约)、
文档更新、bug 修复(不改公共签名)。**锁的是"重塑结构",不是"往结构里填东西"。**

---

## 6 — 载重字符串同步清单(改包名时必须一起改的地方)

历史教训:包名/契约字符串散落多处,漂移即出 bug。若经闸门批准要改上述任何包/契约名,
下列载重必须同步(否则运行时崩或误导 LLM):
- `SeProtectedObjects` 的 `se.`/`ob.`/`compat.`/`alpc.` 四前缀 + FQN 清单(exact 集含 IoProbe/HttpFacade/DebugTools/MutateStateTools 等 gate 包装类)
- `PsSynthesizer.REQUIRED_PACKAGE` + `SynthTools` 工具描述 + `PsSynthesizer` javadoc(三者必须一致)
- `pom.xml` 的 `Premain-Class`/`Agent-Class`(`boot.CoreAgent`)
- board 反射桥 `McpLink` 里的 `CORE_CLASS`/`BOOTSTRAP_CLASS` 常量

---

## 7 — 变更闸门(要动上面任何冻结项,必须满足)

1. **先立 ADR**:用 `_templates/adr.md` 建 `adr/ADR-NNNN-<slug>.md`,写清背景/决策/后果/替代方案,
   并在 ADR 里标注"触及 ARCHITECTURE-LOCK 的哪一项"。
2. **用户确认**:
   - 触碰 **L0/L1/L2/L3**(结构骨架):需用户明确确认。
   - 触碰 **L4**(安全脊柱):等同 CLAUDE.md 铁律③,需用户**明确确认 3 次**,ADR 未 `accepted` 前停在 `proposed`,不得实施。
3. **同步 §6 载重清单** + 更新受影响的 architecture 文档 + STATUS.md。
4. **配非空转回归测试**(铁律②):结构变更要有测试锁住新不变量。

未走闸门而改动冻结项的 diff,应在 review 中被拒。

---

## 指向

- `../../../CLAUDE.md`(项目铁律入口)· `../../STATUS.md`(单一真相/易变数字)· `../../SECURITY.md`(安全边界)
- `04-NT-EXECUTIVE-RENAME-MAP.md`(L1 包名来历)· `06-PLATFORM-SPI.md`(L2 board 契约细节)
- `../../_templates/adr.md`(变更闸门用的 ADR 母版)

