---
doc: nt-executive-rename-map
title: 04 — NT Executive 重命名映射表(实施前定稿闸口)
layer: reference
status: authoritative
updated: 2026-07-11
parent: ../README.md
read_if: 你要审阅/执行把 core 重构成 NT Executive 分层结构这件事;这是动手前必须定稿的"旧→新"全表。
---
# 04 — NT Executive 重命名映射(草案,待用户定稿)

> 目标:把 `core/` 的包与类名重构成 **Windows NT Executive** 分层体系,变得高级、自洽
> ——项目本就是 DepthARK(NT)的 JVM 移植。**只改我们自己写的文件**;绝不动
> `net.minecraft.*`(vanilla 映射)与 `lwjgl2-shim`(ABI 靠名字)。根包
> `net.marcloud.mcp.core` 保留(= kernel image / ntoskrnl 根)。

## NT Executive 前缀(Windows Internals)
`Ke` 内核 dispatcher · `Mm` 内存 · `Ob` 对象管理器 · `Se` 安全引用监视器 ·
`Io` I/O 管理器(IRP+设备栈) · `Ps` 进程/线程 · `Cm` 配置/注册表 ·
`Alpc` 内核 IPC 端口 · `Ldr` 映像加载器 · `Flt` 过滤器管理器 · `Kd` 内核调试器。

## 类命名策略(定稿点之一)
- **子系统骨干类**:改成 NT 前缀名(如 `CapabilityRegistry`→`IoManager`)。
- **领域/数据类(record、DTO、纯值)**:保留原名,只随包搬家(如 `Bounds`/`Point`/
  `FieldInfo`/`DebugEvent`)——强行 NT 化只会降低可读性。
- **镜像 vanilla 的类**(`GameAccess`/`GuiReflect` 等):语义保留,不改字段/映射名。

---

## A. 包映射(23 子包 → NT 子系统)

| 现包 | → 新包 | NT 子系统 | 依据 |
|---|---|---|---|
| `security`(去掉 Ob 类) | `se` | Security Reference Monitor | PolicyEngine/Ring/Integrity/Privilege/SID |
| `security`(Ob 类) | `ob` | Object Manager | 句柄层(已叫 ObjectManager) |
| `secure` | `alpc` | ALPC | 独立决策进程 + RPC 端口 |
| `registry` | `io` | I/O Manager | supervise/dispatch/breaker = IRP 分发 |
| `mcp` | `io.transport` | Io(设备栈入口) | socket 前门 |
| `http` | `io.http` | Io(第二前门) | REST facade |
| `thread` | `ke` | Kernel dispatcher | 游戏线程 dispatcher |
| `agent` | `boot` | 系统引导 | premain/agentmain + Instrumentation |
| `hotload` | `ldr` | 映像加载器 | 编译/redefine 活类 |
| `deepaccess` | `mm` | Memory Manager | 读写活对象字段(MmProbeAndLockPages) |
| `hook` | `flt` | Filter Manager | ByteBuddy retransform = minifilter |
| `seam` | `flt.seam` | Filter(注入) | Netty/GLFW/tick MITM |
| `debug` | `kd` | Kernel Debugger | JVMTI 调试器 |
| `synth` | `ps` | Process/Thread Mgr | hidden-class 生成执行体 |
| `introspect` | `cm` | Configuration Mgr | 类/自模型查询(备选 `ob.query`) |
| `gui` | `drivers.gui` | 设备驱动 | 输入/显示设备 |
| `vision` | `drivers.video` | 设备驱动 | framebuffer 抓帧 |
| `state` | `drivers.world` | 设备驱动 | world/player/packet 读 |
| `memory` | `drivers.store` | 设备驱动 | 持久知识库(避免与 `mm` 撞名) |
| `narrative` | `drivers.narrative` | 设备驱动 | 目标栈/故事 |
| `action` | `drivers.action` | 设备驱动 | 高层作动 |
| `event` | `ke.event` | Ke 通知 | EventBus = 内核事件分发 |
| `core`(根:McpCore/GameAccess/GameBridge) | 保留 | kernel image 根 | 入口 + 游戏门面 |

> **待定稿点**:`introspect`→`cm` vs `ob.query`;`seam` 是否保留原名 vs `flt.seam`;
> `event` 独立 vs 归 `ke`;`drivers.*` 嵌套 vs 平铺。请指出偏好。

---

## B. 骨干类重命名(其余类只搬包不改名)

`se` / `ob` / `io` 三个子系统的骨干类改名对如下(其余列出的类只搬包、保留原名):

| 子系统 | 旧名 | 新名 |
|---|---|---|
| se | `PolicyEngine` | `SeReferenceMonitor` |
| se | `InProcessPolicyEngine` | `SeLocalMonitor` |
| se | `RemotePolicyEngine` | `SeRemoteMonitor` |
| se | `AccessDecision` | `SeAccessCheck` |
| se | `ProtectedClasses` | `SeProtectedObjects` |
| se | `SecurityContext` | `SeToken` |
| se | `PermissionPolicy` | `SeClearancePolicy` |
| se | `ToolPolicy` | `SeToolRequirement` |
| se | `ToolRequest` | `IoRequestPacket`(移到 io) |
| ob | `ObjectManager` | `ObManager` |
| ob | `ObjectHandle` | `ObHandle` |
| ob | `ResourceRef` | `ObRef` |
| ob | `AccessRight` | `ObAccessMask` |
| io | `CapabilityRegistry` | `IoManager` |
| io | `SafeToolExecutor` | `IoSupervisor` |
| io | `BoundaryGuard` | `IoProbe` |

> **保原名只搬包**:se 的 `Ring`/`IntegrityLevel`/`Privilege`/`PrivilegeToken`/`CapabilitySid`/`CapabilityCatalog`/`AccessGate`/`AllowAllGate`/`PermissionTools`/`PrivilegeControlTools`;io 的 `Capability`/`ToolStats`/`DynamicToolFactory`/`MetaTools`,以及 `SocketTransportServer`→`io.transport`、`HttpFacade`/`Json`→`io.http`(均保名)。

### alpc / ke / boot / ldr / mm / flt / kd / ps / cm
- `PSecureServer/Protocol/Main`→`AlpcServer/AlpcProtocol/AlpcMain`。
- `MainThreadExecutor`→`KeGameDispatcher`;`EventBus`→保留(`ke.event`)。
- `CoreAgent`→`boot.CoreAgent`(**pom Premain/Agent-Class 同步改**);`AgentAccess`/
  `CoreBootstrap`/`StartupAdvice` 保名搬 `boot`。
- `hotload/*`→`ldr`(保名,如 `HotLoadEngine`/`Redefiner`)。
- `deepaccess/*`→`mm`(`DeepAccess`→`MmAccess`?待定;其余保名)。
- `hook/*`→`flt`(`HookManager`→`FltManager`?待定);`seam/*`→`flt.seam`(保名)。
- `debug/*`→`kd`(`DebuggerBridge`→`KdBridge`;其余保名)。
- `synth/*`→`ps`(`EphemeralSynthesizer`→`PsSynthesizer`?待定;**REQUIRED_PACKAGE 字符串同步改**)。
- `introspect/*`→`cm`(`IntrospectionService`→`CmQuery`?待定)。

> **待定稿点**:上面带 `?` 的骨干类是否改名,还是保留原名只搬包?

---

## C. 非 import 引用(必须与包同步改,编译器抓不到的靠测试兜底)
1. **`se.SeProtectedObjects`(原 ProtectedClasses)**:包前缀字符串 + 19 个 FQN 字面量
   ——**最高危**,改错 = 自我脑叶切除守卫静默失效。第 1 步先加强 `ProtectedClassesTest`
   断言每个被保护类改名后仍被命中。
2. **`ps.PsSynthesizer` 的 `REQUIRED_PACKAGE`**:`"net.marcloud.mcp.core.synth"`→新包串。
3. **`core/pom.xml`** 的 `Premain-Class`/`Agent-Class`:`...agent.CoreAgent`→`...boot.CoreAgent`(4 处)。
4. **测试里的 FQN 字符串**:ProtectedClassesTest / RedefineGuardTest /
   EphemeralSynthesizerTest / IntrospectionServiceTest / DynamicHookManagerTest。
5. `MetaTools`/`ToolRegistry` 里指向 `GameBridge` 的字符串:**根包不变,无需改**。

## D. 执行顺序(每步编译,可回退)
1. 加强 `ProtectedClassesTest`(保险)。
2. 叶子包先练手(drivers.*)→ 核心包最后(se/io/boot)。每包 `git mv` + 改包声明 +
   改 import,`./mvnw -pl core test-compile`。
3. 改 C 节非 import 引用 + pom。
4. 全量 `./mvnw -pl core test`(应全绿,测试数见 STATUS.md)+ `package -DskipTests` 验 agent jar。
5. 更新 `00-OVERVIEW`/`01-SECURITY-KERNEL`/`STATUS.md`/`SECURITY.md` 的包名。CLAUDE.md 骨架不改。
