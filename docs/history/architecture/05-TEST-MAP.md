---
doc: arch-test-map
title: 05 — 测试地图 (Test Map)
layer: reference
status: authoritative
updated: 2026-07-11
parent: 03-NATIVE-C6-AND-BUILD.md
next:
  - path: ../README.md
    when: 读完架构链、要回到文档索引
read_if: 只在你要加/改测试、确认某条关键路径或不变量是否被测试守护、或排查覆盖缺口时读。
---
# 05 — 测试地图 (Test Map)

本文枚举 `core/` 模块下的**全部测试类**，逐一说明:它验证什么、是 headless(无游戏/无原生库)还是需要 live-JBR、以及它锁死的**关键不变量 (invariant)**。所有断言均对照 `core/src/test/java/` 真实源码,并给出 `file:line`。文末给出**总数**与**覆盖缺口 (coverage gaps)**——今天没有任何测试守护的关键路径。另有一小节映射**与 core 平级的 Board 模块**测试(见 §8)。

> **易变数字以 `../../STATUS.md` 为准。** 本文的测试总数是编写时的快照,如与 STATUS.md 冲突以 STATUS.md 为准(它是单一真相)。这里维护的是"每个测试类守护什么不变量"的结构映射,不是权威计数。

> 说明:测试根目录 `core/src/test/java/` 采用 default package(测试类无 `package` 声明,类名即文件名),通过 `import net.marcloud.mcp.core.*` 引用被测代码。

---

## 0. 总数 (Total count)

- **core 测试类:56 个文件**(逐文件 `grep -c "@Test"` 实测汇总)。常规 `./mvnw -pl core test` 跑通的方法数**以 STATUS.md 为准**——3 个 `*LiveIT` 类(`GuiClickLiveIT`、`NativeDebugOpLiveIT`、`SeamOnLiveConnectionLiveIT`)默认 `Assume`/skip、只在 `-Dmcp.it.live=true` + 真游戏下跑,不计入常规 run。**权威计数以 STATUS.md 为准。**
  - 注:测试根目录另有 `net/marcloud/mcp/core/se/DeepAccessProtectedBase.java`,它是 `DeepAccessReadGuardTest`/`DeepAccessDeclaringGuardTest` 共用的受保护基类夹具,**不含 `@Test`,不计入测试类总数**(权威计数见 `../../STATUS.md`)。
- 另有 **client 模块 13 个黄金协议测试**(`CompressionFramingTest`=3 + `NbtRoundTripTest`=2 + `PacketBufferCodecTest`=6 + `PacketIdRegistryTest`=2 = 13),自 v1.0.0 黄金基线以来从未回归,与内核无耦合,本文不展开。
- **另有与 core 平级的 Board 模块测试**(`net.marcloud.mcp.board`,独立模块,`./mvnw -pl board test` 单独跑),映射见下方 §8。
- 历史增量:18 → 54 → 108 → 111 → 120 → 178,并入 C6、结构化 GUI 层、审计/诚实性回归、L6 句柄层、NT Executive 重构与追加测试后到当前值(见 STATUS.md)。

| # | 测试类 | `@Test` 数 | 模式 | 被测层/能力 |
|---|--------|-----------|------|------------|
| 1 | `ProtectedClassesTest` | 6 | headless | Phase 0 受保护类集合 (守护内核) |
| 2 | `RedefineGuardTest` | 3 | headless | Phase 0 redefine 门禁 + instrumentation 收回 |
| 3 | `PermissionPolicyTest` | 6 | headless | L2 CPU 环 (clearance/drop/restore) |
| 4 | `SecurityKernelTest` | 13 | headless | L2-L5 内核数据模型 + AND 组合 |
| 5 | `BoundaryGuardTest` | 8 | headless | L7 边界校验 (深冻结 + schema) |
| 6 | `GeneratedToolGateTest` | 6 | headless | 生成型工具门控 (CRITICAL#1/#2 回归) |
| 7 | `GuiToolGateTest` | 2 | headless | 结构化 GUI 工具的环/完整性/特权门控 |
| 8 | `L4DenyMessageHonestyTest` | 2 | headless | L4 拒绝消息诚实性 (GAP-5) |
| 9 | `PSecureRpcTest` | 5 | headless(内嵌 loopback server) | L1 VTL P-SECURE fail-closed |
| 10 | `PSecureServerAuditTest` | 4 | headless(内嵌 loopback server) | L1 P-SECURE server 帧/握手/断连审计 |
| 11 | `RemotePolicyEngineDropFailClosedTest` | 1 | headless | L1 VTL drop 在权威不可达时 fail-closed (GAP-1) |
| 12 | `SupervisedGateIntegrationTest` | 4 | headless | 7 层门禁经 `supervise()` 端到端不可绕过 |
| 13 | `CapabilityRegistryTest` | 8 | headless | 自愈:熔断器 + 版本/回滚 |
| 14 | `SafeToolExecutorAuditTest` | 1 | headless | 忽略中断的失控工具仍跳闸,恢复道畅通 |
| 15 | `StaleServerRebindTest` | 2 | headless | 断连后 rebind(null)注册契约 (GAP-3) |
| 16 | `HotLoadEngineTest` | 4 | headless | 热加载 cap-1 (编译+载入新类) |
| 17 | `IntrospectionServiceTest` | 17 | headless | C1 内省 (list/describe/find/hooks) |
| 18 | `DynamicHookManagerTest` | 8 | 混合(1 个 live-attach 自跳过) | C3 运行时装/卸 hook |
| 19 | `HookInstallValidationTest` | 4 | headless | C3 install 拒绝空/不存在方法 (MEDIUM#11) |
| 20 | `DeepAccessTest` | 10 | headless | C5 深访问 (读写字段/私有调用) |
| 21 | `DeepAccessReadGuardTest` | 4 | headless | C5 读路径也守护声明类 (GAP-9) |
| 22 | `DeepAccessDeclaringGuardTest` | 4 | headless | C5 守护解析出的声明超类 (LOW#17) |
| 23 | `EphemeralSynthesizerTest` | 7 | headless | C7 hidden-class 合成 |
| 24 | `SeamControllerTest` | 14 | headless(seam 状态机) | C8 接缝层 (Netty/GLFW/tick) |
| 25 | `NettyTapAuditTest` | 3 | headless(seam 包内) | C8 NettyTap 审计回归 |
| 26 | `SeamLifecycleAuditTest` | 5 | headless(seam 包内) | C8 seam 生命周期 (MEDIUM#10 / HIGH#7) |
| 27 | `GuiSnapshotTest` | 8 | headless(vanilla client 类) | 结构化 GUI 基座 (快照/反射/服务) |
| 28 | `DebuggerBridgeFallbackTest` | 2 | headless | C6 无 DLL 时干净降级 |
| 29 | `DebugToolsTest` | 3 | headless | C6 9 个调试工具接线 + 门控 |
| 30 | `DebugEventQueueTest` | 3 | headless | C6 事件队列(纯 Java) |
| 31 | `DisconnectSensorHonestyTest` | 3 | headless | 断连传感器缺席 vs 真负 (GAP-4) |
| 32 | `MemoryDurabilityTest` | 3 | headless | 持久化失败不谎报成功 (MEDIUM#12) |
| 33 | `MissingObservationSourceTest` | 5 | headless | 观测源缺失 ≠ 真实负观测 (MEDIUM#13) |
| | **上表小计** | **178** | | |

> **上表逐一映射的是编写时纳入的那批 core 测试类(小计见上表小计行 178)。** 此后又新增了一批测试类(NT Executive 重构后的 L6 对象句柄层 `L6ObjectHandleTest`/`L6DebugGateThroughRegistryTest`、`ob` 门控、L3/L4/L5 真生效的 `PrivilegeControlTest`/`SupervisedGateL4L5DenyTest`、HTTP facade 鉴权 `HttpFacadeTest`/`McpCoreBindGuardTest`/`McpCoreHardenedPostureTest`、`drivers.gui` 的 `GuiSnapshotImageAssemblyTest`/`GuiTrajectoryTest`/`SoMOverlayTest`、`drivers.video` 的 `ScreenCaptureTest`、`flt.seam` 的 `NettyTapLifecycleTest`、`kd` 的 native 契约测试、策略侧表漂移守卫 `PolicySideTableDriftTest`、`PSecureHardenedDenyTest`、`HookLifecycleThroughRegistryTest` 等),使 core 增长到 **56 个测试文件**(方法总数与常规 run 数**见 STATUS.md**)。这些新类尚未逐条并入上表;**权威计数见 STATUS.md**,新类的不变量分散在各自 §1-§6c 主题里。

**live-JBR 现状:** 常规 `./mvnw -pl core test` 跑的 **258** 个测试**全部 headless**——不需要运行中的游戏、不需要 `-javaagent`、不需要 `-agentpath` 原生 DLL(`DynamicHookManagerTest` 的一个自附加 self-attach 用例在不满足时 `Assume` 自跳过,非假失败;`*LiveIT` 里的 live-only 用例默认 skip、只在 `-Dmcp.it.live=true` + 真游戏/服务器下跑,不计入这 258;方法数以 STATUS.md 为准)。这是刻意设计:门禁/降级逻辑在 `inst==null` / `isAvailable()==false` 时依然可判定,因此可在普通 `mvn test`(JBR 25 工具链)里证明。整套内核栈在**真实运行游戏里**的 live 验证(hook 真正触发、DCEVM 结构性 redefine、seam 挂载、深访问)仍属未做项(见缺口 §G4);C6 原生调试器的真机 JVMTI 操作已在 live 客户端**验证过**(DLL 现由 clang 构建,见 §6 与 §G3)。

---

## 1. Phase 0 守护层 — 内核不可被内部改写

### `ProtectedClassesTest` (6 tests, headless)
验证 `se/SeProtectedObjects.isProtected(String)` ——redefine/retransform 路径查询的规范受保护类集合,防止特权模型从内部被重写。
关键不变量:
- **前缀规则**:`net.marcloud.mcp.core.se.` 下任意类(含日后新增的 `SeToken`)自动受保护,无需改集合 (`ProtectedClassesTest.java:40-47`)。
- **集合外的承重类**也受保护:`boot.CoreAgent`/`AgentAccess`、`io.IoManager`/`IoSupervisor`、`ldr.LdrRedefiner`、`flt.FltManager` (`ProtectedClassesTest.java:50-57`);以及 retransform 机器 `FltDynamicManager`/`HookTools`/`mm.MmAccess`/`flt.seam.NettyTap`/`flt.seam.TickInjector` (`ProtectedClassesTest.java:74-83`)。
- **数组/内部类不能绕过**(审计 H1 修复):数组描述符 `[Lnet...Ring;`、多维 `[[L...`、源码形式 `...Ring[]`、内部类 `...IoManager$Inner` 全部归一化后判为受保护;而游戏数组 `[Lnet.minecraft.network.Packet;` 不受保护 (`ProtectedClassesTest.java:85-99`)。
- 游戏类与 AI 生成类**不**受保护,合法 `redefine_class` 用例不被误伤 (`ProtectedClassesTest.java:59-65`);`null`/空串/纯空白不受保护 (`ProtectedClassesTest.java:67-72`)。

### `RedefineGuardTest` (3 tests, headless)
验证 Phase 0 封堵的两个自改写漏洞。
关键不变量:
- **守护先于 agent 检查**:`LdrRedefiner.redefine(SeClearancePolicy.class, …)` 即使无 agent 也抛 `IllegalStateException`(消息含 "protected"),证明门禁与 agent 存在与否无关 (`RedefineGuardTest.java:22-37`, `50-65`)。
- **`CoreAgent.instrumentation()` 已收回为非 public**:反射检查该方法 `!Modifier.isPublic(...)`,仅经 `agent` 包的 `AgentAccess` seam 可达 (`RedefineGuardTest.java:39-48`)。

---

## 2. 权限内核 L2-L5 与 L7

### `PermissionPolicyTest` (6 tests, headless)
验证 L2 CPU 环模型:分级门控、只降不升的自沙盒、令牌门控的 restore。
关键不变量:
- clearance 按环级门控:R2 许可 R2/R3、拒绝更高特权环 (`PermissionPolicyTest.java:13-21`);R-1 wide-open 许可所有环 (`:24-29`)。
- **`dropTo` 只降不升**:向更高特权环的 "drop" 被忽略 (`PermissionPolicyTest.java:32-39`)——反自提权。
- **restore 需正确令牌**:错令牌拒绝、对令牌恢复 (`:42-49`);无令牌构造 (`null`) → `restorable()==false`,drop 为永久 (`:52-58`)。
- 内置环表把危险工具映射到低环:`eval_java`/`redefine_class`→R-1、`create_tool`→R0、`send_raw_packet`→R1、`capture_screen`→R2、`memory_write`→R3,未知工具回退 R3 (`PermissionPolicyTest.java:61-69`)。

### `SecurityKernelTest` (13 tests, headless)
验证 7 层内核的数据模型 (L2-L5) 与 `SeLocalMonitor` 的组合语义——纯逻辑,无游戏。
关键不变量:
- **L3 完整性 (MIC no-write-up)**:高标签可写低标签、平级可写、低不能写高;`null` 目标 = 无 L3 门 (`SecurityKernelTest.java:31-38`)。
- **L4 特权两态 (granted vs enabled)**:已授予但未启用 → 不通过;`enable()` 只能启用已授予的特权,不能启用未授予的 `SE_NET_RAW` (`:42-51`);`wideOpen()` 令牌启用全部 (`:53-59`)。
- **L5 能力 SID 默认拒绝**:`CapabilityCatalog.requiredFor` 把 `redefine_class`→`CAP_CLASS_REDEFINE`、`capture_screen`→`CAP_SCREEN_CAP`;未列内置工具无需能力;未列 AI 工具默认落 observe 档 (`:63-74`)。
- **SeToolRequirement 安全默认**:未列工具只强制环(无 L3/L4 门,向后兼容)(`:78-84`);`redefine_class` 全门控(R-1 + HIGH + `SE_DEBUG_CLASS` + `CAP_CLASS_REDEFINE`)(`:87-93`)。
- **逐层 AND 组合 + 首个失败层短路**:R-1 wide-open 放行全部危险工具 (`:106-113`);降到 R2 后 `eval_java` 于 **"L2 ring"** 拒绝而 `scan_surroundings` 放行 (`:116-124`);MEDIUM 完整性主体即便 R-1+全特权也在 **"L3 integrity"** 被拒 (`:127-137`);`SE_DEBUG_CLASS` 禁用 → **"L4 privilege"** 拒 (`:140-151`);只持 `CAP_WORLD_READ` 的严格主体 → `capture_screen` 于 **"L5 capability"** 拒 (`:154-167`);R2 下 `redefine_class` 报告的首个失败层是 **"L2 ring"** 而非 L3/L4,证明顺序与短路 (`:190-197`)。
- **审计回归**:`seam_netty_uninstall`/`seam_tick_disable`/`install_hook`/`write_field`/`open_module` 等所有变异型接缝/hook 工具都必须同时声明 L3 写完整性与 L4 特权 (`SecurityKernelTest.java:170-187`)。

### `BoundaryGuardTest` (8 tests, headless)
验证 L7 系统调用边界:参数深拷贝+冻结 (防 TOCTOU) + 轻量 JSON-schema 校验。
关键不变量:
- **深冻结**:`freezeArgs` 后修改原始嵌套结构不影响快照;冻结后的 list/map 拒绝变更(抛 `UnsupportedOperationException`)(`BoundaryGuardTest.java:19-46`);`null`/空 → 空 map (`:48-52`)。
- **schema 校验**:缺必填项失败且消息含字段名 (`:60-68`);类型错失败 (`:70-77`);**整数容忍整值 double**(JSON 常把 5 解码为 5.0,须接受;5.5 拒绝)(`:79-86`);`additionalProperties` 默认 true,让空 schema 的 AI 工具接受任意参数 (`:88-94`);`enum` 强制 (`:96-103`);`null` schema 宽松放行 (`:105-108`)。

---

## 3. L1 VTL 与不可绕过的门禁

### `PSecureRpcTest` (5 tests, headless + 内嵌 loopback server)
验证 L1 VTL——P-SECURE 独立进程决策权威。测试内在临时 loopback 端口 (`port 0`) 起真实 `AlpcServer` 并驱动真实 `SeRemoteMonitor` 客户端。
关键不变量:
- 往返 allow/deny:R-1 权威放行 `eval_java` (`PSecureRpcTest.java:44-53`);**权威在 R2 时,游戏 JVM(客户端)无法覆盖**——客户端从不陈述自己的 subject,故 R-1 工具被权威拒、observe 工具仍放行 (`:56-69`)。
- clearance/drop 穿墙:`dropTo(R2)` 被权威侧反映,之后 R-1 工具穿墙被拒 (`:72-83`)。
- **fail-closed(核心)**:
  - 错认证令牌 → 握手被拒 → 无决策通道 → 拒绝,`decision.layer()=="L1 VTL"` (`PSecureRpcTest.java:86-96`)。
  - **不可达进程 → 快速失败关闭**:目标端口无 server 时,客户端在 ~2×timeout 内返回拒绝(实测 `elapsedMs < 2500`),绝不放行、绝不挂起;`restorable()` 亦 fail-closed (`:99-114`)。

### `SupervisedGateIntegrationTest` (4 tests, headless)
验证 keystone 迁移后的**端到端**:参考监视器的决策就是 `IoManager.supervise()` 里真正执行的那一个(不只是独立 engine),且 REST/`invoke()` 路径无法绕过门禁。
关键不变量:
- R-1 clearance 经受监视门跑通 R-1 工具 (`SupervisedGateIntegrationTest.java:38-49`)。
- **drop 后经受监视门拒绝**:`engine.dropTo(R2)` 后 `reg.invoke("eval_java")` 返回 `isError` 且消息含 "L2 ring"/"permission denied",而 R2 的 `scan_surroundings` 放行;对令牌 restore 后 `eval_java` 又通 (`:51-79`)——**门禁无法经 `invoke()`(REST 前门走的同一路径)绕过**。
- **L7 端到端 live**:SDK `Tool.inputSchema()` 被恢复为 Map,缺必填 `name` 在 handler 运行前即被拒(handler 本会返回 "ran"),补齐后通过 (`:81-105`)。
- `isAllowed()` 反映完整决策:R2 clearance 下 R-1 工具不允许、R3 工具允许 (`:107-122`)。

---

## 4. 自愈注册表与热加载

### `CapabilityRegistryTest` (8 tests, headless)
验证自愈核心:受监视执行器的熔断器 + 注册表的版本/回滚。无游戏、无 live MCP server(server 为 null → 本地模式)。守护 "一个坏工具不能拖垮系统"。
关键不变量:
- **熔断器 3 次失败跳闸**:2 次仍 CLOSED、第 3 次 OPEN 且阻断调用 (`CapabilityRegistryTest.java:30-40`);成功重置连续失败计数 (`:42-52`)。
- **抛异常算 fault(会跳闸),runaway 拒绝快速失败**:抛异常的工具 3 次即跳闸 (`:54-65`);IoSupervisor 把抛异常工具变成 error 结果而非崩溃 (`:77-87`);挂起工具超时成 error 且计入 `timeouts()` (`:89-100`)。
- **域错误 (`isError=true`) 不跳闸(关键)**:5 次 validation/compile 拒绝后 `failures()==0`、熔断器保持 CLOSED——否则 AI 自扩展环会在几次编译错后把 `create_tool` 隔离 (`CapabilityRegistryTest.java:102-120`)。
- 负数 n 不抛(审计 #6):`PacketLog.recent(-5)` 返回空而非异常 (`:67-75`)。
- **版本+回滚**:注册两版后 `version()==2`、`source()=="src-v2"`,`rollback()` 回到 v1 源 (`:122-138`)。

### `HotLoadEngineTest` (4 tests, headless)
验证热加载 cap-1 路径(编译+载入全新类),普通 JDK 无 agent 即可。cap-2/3(redefine / DCEVM 结构性改)需 live `-javaagent`,由启动探针验证,**不在此**。
关键不变量:
- 编译并跑通全新类 (`HotLoadEngineTest.java:24-34`),载入类可反射调用 (`:36-49`)。
- **编译错被报告而非抛出**:`InMemoryCompiler.compile` 失败时 `success()==false`、有 diagnostics、bytecode 为空 (`:51-60`)。
- 新类可引用游戏 classpath(编译器继承 `java.class.path`)(`:62-74`)。

---

## 5. 能力包 C1/C3/C5/C7/C8

### `IntrospectionServiceTest` (17 tests, headless)
验证 C1 内省:`list_classes`/`describe_class`/`find_method`/`list_hooks` 及描述符生成。经 `AgentAccess` seam 注入,无需 live agent。
关键不变量:
- **确定性反射回退**(审计:去掉旧的 "instrumentation OR fallback" 恒真断言):headless 无 agent → `AgentAccess.isLoaded()==false`,`listing.source()` 必为 `"reflection-fallback"` 且仍非空枚举 (`IntrospectionServiceTest.java:26-43`)。
- 包/名过滤、limit、大小写不敏感名过滤 (`:45-81`)。
- **受保护标记**:`SeClearancePolicy` 在 listing/describe 里被标 `protectedClass()==true`,`ArrayList` 不是 (`:83-139`)。
- 未解析类返回 `<unresolved>` + 含 `ClassNotFoundException` 的 note (`:141-153`)。
- `find_method` 按名/owner 过滤,空名返回空 (`:155-194`);描述符与类型名工具:`substring(II)` → `(II)Ljava/lang/String;`、`int`、`int[]`、`java.lang.String[]` (`:196-224`)。
- **`list_hooks` 聚合 `HookSource` SPI**:多源聚合,单源抛异常时跳过坏源保留好源 (`:226-269`)。

### `DynamicHookManagerTest` (7 tests, 混合 — 1 个 live-attach 用例自跳过)
验证 C3 INTERCEPT:`FltDynamicManager`、hook 事件、路由簿记、能力/denylist 门。多数 agent-less;live retransform 用例在 `ByteBuddyAgent.install()` 失败时 `Assume` 自跳过(无假失败)。
关键不变量:
- **denylist 检查先于 instrumentation 检查**:`inst==null` 时对受保护类 (`SeClearancePolicy`/`CoreAgent`) `install()` 仍抛 `SecurityException`(消息含类名)——证明 denylist 在 `inst==null` 门之前 (`DynamicHookManagerTest.java:43-65`)。
- 无 instrumentation 时 `canInstall()==false`、`install()` 抛 `IllegalStateException`(消息提 `-javaagent`)(`:73-86`)。
- 纵深防御 `AccessGate` 与外层门 AND 组合:deny-all gate 抛 `SecurityException` 含能力名 (`:95-124`)。
- **`HookFiredEvent.argTypes()` 只暴露类型名不泄露值**(L7 边界):`[int[], "secret", null]` → `["int[]","String","null"]`,不含 "secret"、不含数组内容 (`:149-164`)。
- 路由簿记:`registerRoute`+`dispatch` 发事件、`unregisterRoute` 后不再发 (`:172-191`);`list()` 是防御性拷贝 (`:311-325`)。
- **live 往返(自跳过)**:自附加取 Instrumentation → 在 `Sample.probe` 装 hook → 调用触发 `HookFiredEvent` → `uninstall` 后再调用不再触发,走真实 `ResettableClassFileTransformer.reset(...RETRANSFORMATION)` 路径 (`DynamicHookManagerTest.java:204-260`)。

### `DeepAccessTest` (10 tests, headless)
验证 C5:读写私有字段(含 final)、调私有方法、层级遍历、受保护类守护、缓存失效。用普通 POJO,无游戏对象。**只测公开 API**,不直接触 package-private 的 `RootResolver`/`ValueCodec`(见缺口 §G2)。
关键不变量:
- 读私有实例字段、写私有非 final、**经 Unsafe 写私有 final**、读写 static、写非常量 static final (`DeepAccessTest.java:60-104`)。
- 调私有方法(含 int 参数强制转换)(`:106-123`);字段解析走超类 (`:125-131`)。
- **拒绝受保护类**:对 `SeClearancePolicy.class` 的 `setStaticField` 抛 `MmAccessException`(消息含 "protected")(`:133-144`)。
- `invalidate(clazz)` 清缓存后仍工作(重新缓存)(`:146-160`)。

### `DeepAccessDeclaringGuardTest` (4 tests, headless)
验证 **LOW#17**:MmAccess 必须守护字段/方法解析出的**声明(超)类**,而非只看 runtime/start 类。`UnprotectedSub` 自身 FQN 不受保护(过初始 `guardProtected(t.getClass())`),但其继承成员声明在 `DeepAccessProtectedBase`(位于 `net.marcloud.mcp.core.se.*`,受保护)。每条变异路径(写/静态写/调用)现在都在声明类上拒绝(消息含 "protected");gate 用 `AllowAllGate`,故唯一受测的就是 SeProtectedObjects 守护。

### `DeepAccessReadGuardTest` (4 tests, headless)
验证 **GAP-9**:C5 **读**路径(`getField`/`getStaticField`)也须在 start 类**和**解析出的声明超类上施加 `SeProtectedObjects` 守护,与写/调用路径对称。修复前读路径**完全没有** `guardProtected`,唯一保护是基于**返回值 runtime 类型**的 `MutateStateTools.isProtectedValue`——所以声明在受保护安全类上的标量/String 字段(值类型看着无害)直接穿过。`UnprotectedReadSub` 的 FQN 不受保护但继承字段声明在 `DeepAccessProtectedBase`,`secret` 是纯 `int`(值 7 无害),正是 GAP-9 描述的非对称漏洞。

### `EphemeralSynthesizerTest` (7 tests, headless)
验证 C7:编译+定义可 GC 的 hidden class + 调用、错误处理、包约束。
关键不变量:
- 从参数计算 (`EphemeralSynthesizerTest.java:26-43`);缺 `handle` 方法被报告 (`:45-56`);**包约束**:非 `net.marcloud.mcp.core.synth` 包被拒 (`:58-70`);编译错报告 "compile failed" (`:72-83`)。
- 定义出的是 hidden class (`isHidden()==true`)(`:85-98`);**GC 可回收**(弱引用,GC 时序非确定 → 非失败式收敛)(`:100-137`);eval 内抛异常表面化为 "eval threw ... boom" (`:139-154`)。

### `SeamControllerTest` (14 tests, headless)
验证 C8 接缝层:`NettyTap` 状态机、`TickBridge` 转发、`SeamController` 编排。全部 headless(无 live game、无 GLFW、无 MC 字节码 retransform)。真实 Netty 挂载/`runTick` retransform/GLFW 回调链需 live-JBR(见缺口 §G4)。
关键不变量:
- `TickBridge` 转发到 EventBus、计数自增、吞订阅者异常、容忍 null bus (`SeamControllerTest.java:49-78`)。
- `NettyTap.PacketTapHandler` 用 `EmbeddedChannel` 测:入站/出站事件转发、消息穿透、吞订阅者异常仍投递 (`:80-133`)。
- **headless 守护为真实行为断言**(审计:去掉旧 `assertNotNull(Boolean)` 恒真):无 agent → `AgentAccess.isLoaded()==false` 且 `canInstall()==false` (`:135-143`);无 live channel 无法装 NettyTap (`:145-156`);无 GLFW 无法装 key/mouse hook (`:169-188`);无 agent 装 tick injector 抛 `IllegalStateException`(消息含 "Instrumentation")(`:190-203`)。
- **`seam_tick_disable` 是诚实的非占位**(审计:曾伪造成功):未安装时 `uninstallTickInjector()` 返回 false 而非假 "done" (`:158-167`);`uninstallAll()` 空装也不抛 (`:205-210`)。

### `SeamLifecycleAuditTest` (5 tests, headless — seam 包内)
验证 seam 层两个对抗审计发现,住在 `seam` 包内以便驱动 package-private 测试 seam(`TickInjector.primeInstalledForTest`、`InputHook.resolveWindowHandle`),无需 live `-javaagent` 或 GLFW。
关键不变量:
- **MEDIUM#10**:`TickInjector.uninstall()` 曾在 `reset()` 后**无条件**清掉 installed/transformer 态,故 reset 返回 false(或抛)会遗留活 advice 却报 "uninstalled" 且无法重试。现在只在 reset 成功时清态。
- **HIGH#7**:`InputHook` 曾"取第一个非零 long 字段"当 GLFW 窗口句柄,可能锁到无关 long(如 systemTime)报出错误句柄。现在只经显式 display accessor 解析句柄,accessor 不可用时诚实失败。

### `NettyTapAuditTest` (3 tests, headless — seam 包内)
验证 C8 `NettyTap` 观测面的隔离与清理(用 `EmbeddedChannel`,住在 `seam` 包内)。
关键不变量:
- ByteBuf 观测者即便经 `unwrap()` 也不能改动出站线数据 (`byteBufObserverCannotMutateOutboundWireThroughUnwrap`)。
- 解码对象的观测者拿到的是元数据而非活消息 (`decodedObjectObserverReceivesMetadataNotLiveMessage`)。
- `removeAll` 从每个重连 channel 清掉同名 handler (`removeAllCleansSameHandlerNameFromEveryReconnectChannel`)。

---

## 6. C6 原生 JVMTI 调试器(Java 侧 + 降级)

> C6 native 链(`core/src/main/native/core-jvmti/`:`core-jvmti.c`/`.h`/`CMakeLists.txt`/`build.bat` + 新增 `build-clang.sh`)**已不再被 MSVC 阻塞**——DLL 现用 LLVM/clang 构建(`winget install LLVM.LLVM`,`clang -shared`,无需 Visual Studio / Windows SDK),编出的 `core-jvmti.dll` 已在**运行中的 MC 客户端里 live 验证**(`Minecraft.runTick` 断点、线程挂起查找、单步开关均执行真实 JVMTI;一处 `JNI_OnLoad` 绑定修复——把 native 绑定放到 app-classloader 上下文而非仅 VMInit——使其生效)。编译产物 `core-jvmti.dll` 被 gitignore。
> 下述 3 个 **headless** 测试守护的仍是 **DLL 缺席时的诚实降级与门控**(普通 `mvn test` 无 `-agentpath`);真机 JVMTI 语义的正确性验证是 live 手动完成的,尚无自动化单测覆盖(见缺口 §G3)。

### `DebuggerBridgeFallbackTest` (2 tests, headless)
C6 的承重安全证明:无原生 DLL(默认 headless 态)时,`KdBridge` 干净降级——每个公开 op 抛 `DebuggerUnavailableException`,**绝不泄露 `UnsatisfiedLinkError` 或 `NoClassDefFoundError`**。
关键不变量:
- `isAvailable()==false`,`unavailableReason()` 含缺失启动标志 `-agentpath:core-jvmti.dll` (`DebuggerBridgeFallbackTest.java:18-25`)。
- **无链接错泄露**:12 个包装器(`suspendThread`/`popFrame`/`forceReturn*`/`setBreakpoint`/`setSingleStep`/`readLocal*`/`writeLocalInt`/`watchFieldModification`)每个都以域异常失败,若捕获到 `UnsatisfiedLinkError`/`NoClassDefFoundError` 则 `fail` (`DebuggerBridgeFallbackTest.java:27-54`)。

### `DebugToolsTest` (3 tests, headless)
C6 工具**非死工具**:9 个全注册、可调用、无 DLL 时返回诚实 `isError`(点名缺失 agent)——绝不静默成功、绝不崩溃;并验证门表把它们门控在 R-1 + `SE_DEBUG_CONTROL`。
关键不变量:
- `DebugTools.TOOL_NAMES.size()==9`,全部注册可查 (`DebugToolsTest.java:34-41`)。
- **无原生 agent 时每个工具返回诚实 error**:`isAvailable()==false` 前提下,`reg.invoke(...)` 结果 `isError==true` 且内容含 `-agentpath:core-jvmti.dll` (`:43-59`)。
- 全门控在 hypervisor:每个工具 `Ring.forBuiltin==R_MINUS_1`,`SeToolRequirement` 声明 L4 特权、非空 L5 能力、L3 写完整性 (`:61-70`)。

### `DebugEventQueueTest` (3 tests, headless — 纯 Java 无原生)
验证原生事件的 Java 侧接收队列:丢弃最旧的有界、监听器隔离、投递。
关键不变量:
- 超容量丢最旧(非丢最新):塞 100 条入容量 16,`size()<=16` 且保留最新 "e99" (`DebugEventQueueTest.java:17-27`)。
- 抛异常监听器被隔离,第二个仍运行 (`:29-37`);监听器收到事件、`clear()` 后 size 0 (`:39-49`)。

---

## 6b. 结构化 GUI 层(把 MC 界面暴露成 LLM 可读/可操作的 API)

新增能力:让 AI 读取当前 GUI 结构快照并在其上点击/输入/按键。代码在 `core/.../gui/`(`GuiSnapshot`/`GuiElement`/`GuiReflect`/`GuiSnapshotService`/`GuiActions`/`GuiTools` + `Bounds`/`Point`/`State`/`Viewport`)。GUI 反射用 **vanilla 1.8.9 映射**(在 `client/` 模块:`GuiButton.xPosition`、`Slot.xDisplayPosition`、`getStack()` 空槽返回 null),**不是** 已删除的 Southside 参考仓映射。共 4 个工具:`gui_snapshot`(R2 observe 只读)、`gui_click_element`/`gui_type_text`/`gui_press_key`(R1 + `SE_GUI_INTERACT` + HIGH 完整性)。

### `GuiToolGateTest` (2 tests, headless)
锁死结构化 GUI 工具的安全姿态,防未来编辑静默解除门控。
关键不变量:
- **`gui_snapshot` 是 observe 级只读**:`SeToolRequirement.forTool` 判 `requiredRing()==R2`、`writesResourceAt()==null`、`requiredPrivilege()==null` (`GuiToolGateTest.java:snapshotIsObserveLevelReadOnly`)。
- **3 个动作工具是 R1 + HIGH + `SE_GUI_INTERACT`**:`gui_click_element`/`gui_type_text`/`gui_press_key` 均判 `requiredRing()==R1`、`writesResourceAt()==IntegrityLevel.HIGH`、`requiredPrivilege()==SE_GUI_INTERACT`(有 server-visible 效果)(`GuiToolGateTest.java:actionToolsAreR1HighIntegrityGuiPrivilege`)。

### `GuiSnapshotTest` (8 tests, headless — 走 vanilla client 类)
验证 GUI 基座(数据模型 + 反射 + 服务)在**无 live game** 下的抽取正确性:手工构造合成 `GuiScreen`/`GuiContainer` 子类(其 `buttonList`/slots 手填),断言 `GuiReflect`/`GuiSnapshotService` 从**真实 vanilla client 类**(经 reactor 上到测试 classpath)抽出正确的标签、边界与点击点。读取从不触碰 `Minecraft` 单例,故可安全离开游戏线程(`GuiSnapshotService.buildSnapshot` 是纯 seam)。

---

## 6c. 对抗审计 + 诚实性回归

这批测试是 Codex/对抗审计驱动的定点回归,守护"绝不谎报成功、门禁不可静默解除、缺席信号不当真负"。每条都是**非平凡**的(对旧代码会失败)。

### `GeneratedToolGateTest` (6 tests, headless)
守护两个 CRITICAL:**CRITICAL#1** — 生成型(非 builtIn)工具必须**无条件**带最高门(R-1 + SYSTEM 完整性 + `SE_RUN_GENERATED` 特权),使降级 clearance 真正锁死它(修复前它跑在 R2、无特权门);**CRITICAL#2** — 生成型工具绝不能替换内置工具(否则基于名字的策略会让攻击者代码套用内置门运行)。`isReserved` 从注册表派生(任何 builtIn 即 reserved),`register()` 拒绝 generated-over-builtin。

### `L4DenyMessageHonestyTest` (2 tests, headless)
GAP-5(**已演进,2026-07-15b 更正**):`enable_privilege` **现在确实存在**(R0 内建工具,`PrivilegeControlTools.java:72` + `Ring.java:117`;另有 disable/grant/revoke),所以 L4 "granted but disabled" 拒绝消息**应当**点名它作为会话内的真实补救——`L4DenyMessageHonestyTest.java:55` 现在**强制要求**消息含 `enable_privilege`。本行旧文写"项目里根本没有该工具、引用它会失败"是 GAP-5 早期快照,与现测试行为相反,勿据旧文去删消息里的 enable_privilege 引用(会弄挂该测试)。消息仍须具信息量(点名特权、说清"已授予未启用"、给真实补救)。

### `RemotePolicyEngineDropFailClosedTest` (1 test, headless)
GAP-1:L1 VTL 模式下,当 P-SECURE 权威短暂不可达时,`drop_privilege` 必须 **fail-closed**,绝不报告幻影降级。旧 `dropTo` 把"远端宕"当"本地安全应用",降 `cachedClearance` 并返回降级环——但 remote 模式 `evaluate()` 永远问权威、从不读缓存,连通恢复后 R-1 工具又被放行,kill-switch 静默失效。修复后抛 `SeRemoteMonitor.AuthorityUnreachableException`。

### `PSecureServerAuditTest` (4 tests, headless + 内嵌 loopback server)
L1 P-SECURE server 的帧/握手/断连硬化:静默握手不能独占权威 (`silentHandshakeCannotMonopolizeAuthority`);超长帧在换行前即被关闭 (`oversizedFrameIsClosedBeforeNewline`);半帧超时但不杀权威 (`partialFrameTimesOutWithoutKillingAuthority`);`close()` 断开已认证的活跃客户端 (`closeDisconnectsAuthenticatedActiveClient`)。

### `SafeToolExecutorAuditTest` (1 test, headless)
忽略中断的失控工具仍能跳限额、且恢复道仍可运行:两个自旋忽略 `Thread.interrupted()` 的工具各超时成 error,占满执行槽后普通工具被快速拒绝而非拖垮系统 (`interruptIgnoringRunawaysTripLimitAndRecoveryLaneStillRuns`)。

### `StaleServerRebindTest` (2 tests, headless)
GAP-3:socket 客户端断连后 `SocketTransportServer.closeCurrent()` 现调 `registry.bindServer(null)`。本测钉住该修复依赖的注册表契约——无绑定 server 时 `register()` 须走 in-memory-only 提交路径并仍提交,而非对已关闭的 `McpSyncServer` 调 `addTool` 抛异常、零提交。

### `HookInstallValidationTest` (4 tests, headless)
C3 INTERCEPT 的 **MEDIUM#11**:`install` 曾对空白/不存在的方法也发回 hookId,记下永不触发的 hook。现在 `install` 对此类请求 **拒绝**(抛 `IllegalArgumentException`)且不记录任何东西。

### `MemoryDurabilityTest` (3 tests, headless)
**MEDIUM#12**:持久化内存不得在写盘失败时谎报成功。旧 `MemoryStore.save()` 吞掉 IOException、内存态仍提交、工具报 "remembered"/"deleted"。测试把 store 指向不可写路径(父目录是普通文件,`Files.createDirectories` 必失败),断言 (a) 工具报 `isError=true`、(b) 内存态不变(投机 add/remove 回滚)。

### `DisconnectSensorHonestyTest` (3 tests, headless)
GAP-4:`disconnect_report` 曾把**死的**断连传感器与真实"无断连"负观测混为一谈——两者都返回 "No disconnect observed yet."。断连信号只由 `-javaagent` 在 `NetworkManager.closeChannel` 注入的 advice 喂(默认关)。新 API(`sensorInstalled`/`observedAny`/`reportResult`)让工具层能对"传感器不可用"暴露 `isError`。

### `MissingObservationSourceTest` (5 tests, headless)
**MEDIUM#13**:缺失的观测**源**不得当作真实负观测上报。headless 无 `-javaagent` → `AgentAccess.isLoaded()` 确定为 false,packet tap 死、`find_method` 只搜种子集。测试断言"源不可用"信号,而非旧的干净 "no packets" / 空 "no matches"。

---

## 7. 覆盖缺口 (Coverage gaps)

以下是**今天没有任何测试守护**的关键路径,依据源码与既往对抗审计确认。

### G1 — MmAccess 的 `invokeStatic` 与 `openModule` (C5 无测)
`DeepAccessTest` 只覆盖实例 `invoke`(`DeepAccessTest.java:106-123`)与字段读写,**从未调用**两个公开变异原语:
- `MmAccess.invokeStatic(Class<?> owner, String methodName, Class<?>[] paramTypes, Object[] args, ...)`(`mm/MmAccess.java:202`)——静态私有方法调用完全无测。
- `MmAccess.openModule(Module target, String pkg)`(`mm/MmAccess.java:224`)——`redefineModule` 开放包这一强能力(能突破模块封装)无任何断言,连 headless 冒烟都没有。
风险:这两条是 C5 里权限提升面最大的路径,却是覆盖真空。

### G2 — `ValueCodec` 强制转换无直接测试
`ValueCodec.coerce(Class<?> target, Object json, RootResolver roots)`(`mm/ValueCodec.java:27`,package-private `final class`)是 JSON 值 → Java 类型(基元/包装/枚举/引用)的转换枢纽。`DeepAccessTest` 明言 "只测公开 MmAccess API,不直接访问 package-private RootResolver/ValueCodec"(`DeepAccessTest.java:19-20`),仅通过 `invokesPrivateMethodWithCoercion` 间接碰到 int 强转 (`DeepAccessTest.java:116-123`)。**边界/失败态零覆盖**:溢出、null → 基元、非法枚举名、宽化/窄化、数组类型等强转分支均无断言。审计已点名此项为缺口。

### G3 — 原生调试器真实操作的**自动化**覆盖(DLL 已构建并 live 验证,但仍无单测)
C6 的所有真实 JVMTI 语义——`SuspendThread`/`PopFrame`/`ForceEarlyReturn`/`SetBreakpoint`/`SetSingleStep`/读写局部变量/字段监视——都是 `KdBridge` 里的 `native` 声明(`kd/KdBridge.java:174` 起,由 DLL 的 `JNI_OnLoad` 经 `RegisterNatives` 绑定)。DLL **已不再被 MSVC 阻塞**:用 clang 构建成功(`build-clang.sh`),并已在**运行中的 MC 客户端**里手动 live 验证(真机断点、线程挂起、单步)。但 headless 测试仍**只证 DLL 缺席时的降级与门控**(`DebuggerBridgeFallbackTest`/`DebugToolsTest`),`isAvailable()` 恒为 false 那条分支,故在**自动化测试里**:
- `nAgentReady()` 为 true 时的**正常路径**从未在单测中执行(只在 live 手动跑过)。
- `JvmtiError.check()` 对真实 JVMTI 错误码的映射从未被单测里的真实错误驱动。
- 真机断点命中 / 单步 / 栈帧弹出等语义正确性无自动化断言(仅手动 live 观察)。
需要在 live JBR + `-agentpath:core-jvmti.dll`(或 `-Dmcp.core.jvmtiLib`)下建自动化 live 测试,才能把这层纳入 CI。

### G4 — 整套 Phase 2 栈的 live-JBR 端到端验证
除 `DynamicHookManagerTest` 一个会自跳过的自附加用例、以及 3 个默认 skip 的 `*LiveIT` 类(其 live-only 用例)外,常规 run 的这 258 个测试全 headless。以下只在 headless 状态机层面验证,**从未在常规自动化里跑过运行中的游戏**(内存与 `SeamControllerTest` 类注释均明确标注需 live 验证;`SeamOnLiveConnectionLiveIT`/`NativeDebugOpLiveIT`/`GuiClickLiveIT` 是须手动 `-Dmcp.it.live=true` 才跑的 live 脚手架):
- C8 接缝:真实 Netty channel 获取、`Minecraft.runTick` retransform、GLFW 回调链(全藏在 `isAvailable()` 后)——`SeamControllerTest.java:24-34` 类注释即声明 "REQUIRES live-JBR verification"。
- C3 动态 hook 在真实 MC 类(如 `NetworkManager`)上的装/卸往返(仅在 `Sample` POJO 上、且自跳过时验证过)。
- C4/热加载的 DCEVM 结构性 redefine(cap-2/3)——`HotLoadEngineTest` 明言由启动探针而非单测覆盖。
- L1 P-SECURE 作为**真正独立 JVM 进程**(测试用的是同进程内嵌 loopback server,非跨进程 `AlpcMain`)。

### 缺口小结
| 缺口 | 路径 | 现状 | 阻塞 |
|------|------|------|------|
| G1 | `MmAccess.invokeStatic` (`MmAccess.java:202`) / `openModule` (`:224`) | 零测试 | 无(可立即补 headless 测) |
| G2 | `ValueCodec.coerce` (`ValueCodec.java:27`) | 仅间接 int 强转 | 需暴露/放宽包访问以直测 |
| G3 | `KdBridge` native ops (`KdBridge.java:174+`) | DLL 已 clang 构建 + live 验证;仅降级/门控有单测 | 自动化 live 测试:live JBR + `-agentpath` |
| G4 | C8 seam / C3 live hook / DCEVM / 跨进程 P-SECURE | 仅 headless 状态机 | 运行中的游戏 (live JBR) |

---

## 8. Board 模块测试(与 core 平级,独立跑)

Board(`net.marcloud.mcp.board`)是**与 core 平级、零硬依赖**的客户端功能框架(设计见 `06-PLATFORM-SPI.md`),测试独立于 core 编译与运行:`./mvnw -pl board test`。全部 **headless**(纯 JVM,无游戏、无 GLFW、无原生库)。测试根 `board/src/test/java/net/marcloud/mcp/board/`,类名即文件名(带包声明)。**权威计数(测试类数 / `@Test` 数)以 STATUS.md 为准。**

| # | 测试类 | `@Test` 数 | 子包/被测面 |
|---|--------|-----------|------------|
| 1 | `TraceTest` | 9 | `Trace` 事件总线:发布/订阅、Clock 优先级、异常隔离 |
| 2 | `TracePropagationTest` | 12 | `Trace` 传播语义:Cancellable、投递顺序、跨类型路由 |
| 3 | `TraceCacheTest` | 12 | `Trace` 按运行时类派发缓存 + `hasSubscribers(Class)` |
| 4 | `MatrixTest` | 7 | `Matrix<T>` 数据结构:增删查、快照防 CME |
| 5 | `MatrixManagementTest` | 14 | `Manager<T>` 生命周期编排 + Matrix 管理面 |
| 6 | `ChipLifecycleTest` | 8 | `Chip` 启停生命周期、enable/disable 语义 |
| 7 | `ChipSubscriptionBagTest` | 5 | `Chip` 自动订阅袋:`track()` + disable 自动 cancel,`enabled==subscribed` |
| 8 | `BoardBackplaneTest` | 7 | `Board` 静态门面 + `Backplane` 服务登记、可反射 BoardPort |
| 9 | `signals/SignalsTest` | 14 | `signals/` 各 Signal 类型的构造/字段/语义 |
| 10 | `signals/CanonicalSignalTest` | 4 | 规范 Signal 契约(合并重复 Signal 陷阱) |
| 11 | `signals/TickSignalReuseTest` | 4 | `TickSignal.END_ZERO`/`endOfTick()` 单例复用(免每 tick 分配) |
| 12 | `chips/SampleChipsTest` | 7 | `chips/` 示例中性功能单元接线 |
| 13 | `hud/HudMatrixTest` | 11 | `hud/` HUD 元素矩阵管理 |
| 14 | `hud/PanelTest` | 8 | `hud/` 面板数据模型 |
| 15 | `input/PinMatrixTest` | 9 | `input/` Pin(输入绑定)矩阵管理 |
| 16 | `input/PinTest` | 7 | `input/` 单个 Pin 语义 |
| 17 | `link/McpLinkTest` | 7 | `link/` 与 MCP 侧的桥接 |
| 18 | `link/BoundaryDisciplineTest` | 2 | `link/` 边界纪律:反射守卫桥类只暴露 JDK/冻结/抽象类型 |
| 19 | `persist/JsonTest` | 9 | `persist/` 零依赖手写 JSON 读写器 |
| 20 | `persist/StoreTest` | 8 | `persist/` 崩溃安全持久化(原子写 + ATOMIC_MOVE、坏文件备份、版本信封) |
| 21 | `persist/DataViewTest` | 8 | `persist/` 数据视图/自序列化 |
| | **合计** | **172** | 全 headless |
