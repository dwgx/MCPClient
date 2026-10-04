---
doc: arch-security-kernel
title: MCPClient 安全内核:7 层引用监视器详解
layer: reference
status: authoritative
updated: 2026-07-13
parent: 00-OVERVIEW.md
next:
  - path: 02-CAPABILITIES.md
    when: 你想知道这套门禁具体门控哪些能力工具(C1-C9)
read_if: 只在你要改安全内核、权限门控、引用监视器(L1-L7 任一层)或新增受门控工具时读。
---
# MCPClient 安全内核:7 层引用监视器详解

> 适用范围:分支 `mcp-core`,模块 `core/`(`net.marcloud.mcp.core`,Java `--release 25`)。
> 本文所有论断均对照真实源码,标注 `file:line`。设计但未落地的部分明确标记为 **designed-not-built**。

## 0. 总览:这是什么

MCPClient 把一个运行中的 Minecraft 1.8.9 客户端暴露给 AI(通过 MCP)。AI 能力从"观察"一直到"在运行的 JVM 里跑任意代码 / 热替换字节码 / 原生 JVMTI 调试器"。这种能力密度要求一个**引用监视器(reference monitor)**:每一次工具调用都必须先过同一道门。

这道门参照 Windows NT 内核特权体系设计,共 **7 层**,全部 **AND 组合**——任一层拒绝即整体拒绝,并短路返回一条指明"哪层拒的"的原因。单一决策权威是接口 `SeReferenceMonitor.evaluate()`(`core/src/main/java/net/marcloud/mcp/core/se/SeReferenceMonitor.java:28`),唯一的调用点是 `IoManager.supervise()`(`.../io/IoManager.java:111`)。

7 层现状一览:

| 层 | NT 对标 | 实现类 | 现状 |
|---|---|---|---|
| L1 VTL | 虚拟信任级 / 独立地址空间 | `SeRemoteMonitor` + `alpc/AlpcServer` | **opt-in**(`-Dmcp.core.psecure=true`) |
| L2 环 | CPU Ring (R-1/0/3) | `Ring` + `SeClearancePolicy` | **live** |
| L3 完整性 | Mandatory Integrity Control | `IntegrityLevel` | **live** |
| L4 特权 | Access-token privileges | `Privilege` + `PrivilegeToken` | **live** |
| L5 能力 SID | AppContainer capability SID | `CapabilitySid` + `CapabilityCatalog` | **live**(默认 wildcard,strict 可选) |
| L6 对象句柄 | Object handle + granted mask | `ob/ObManager` + `ob/ObHandle` | **built, opt-in**(`-Dmcp.core.handles=true`,默认关) |
| L7 边界校验 | `ProbeForRead` + 参数捕获 | `io/IoProbe` | **live** |

L1-L5 在 `SeLocalMonitor.evaluate()` 里按序 AND 组合(`.../se/SeLocalMonitor.java:123-172`;本地引擎里 L1 是 no-op,实跑 L2-L5);L7 在 `supervise()` 里紧接决策之后拼接(`IoManager.java:130-144`);L6 由独立的 `ob/ObManager.checkRequest` 门(接线可选,默认关),不在 `SeLocalMonitor` 的 AND 链内。

---

## 1. 单一门:`IoManager.supervise()`

所有工具——内建游戏工具、meta 工具(create_tool/redefine_class)、C1-C8 能力工具、4 个结构化 GUI 工具、甚至 AI 用 `create_tool` 运行时造出来的工具——注册时都被 `supervise()` 包一层(`IoManager.java:111-147`)。它返回的 `SyncToolSpecification` 才是真正注册到 MCP server 的东西。HTTP REST 前门也走同一条 `invoke()` → 同一个 supervised handler(`IoManager.java:276-285`),所以 HTTP 无法绕过安全模型。

`supervise()` 内每次调用的执行顺序(`IoManager.java:115-146`):

1. **引用监视器优先**:`engine.evaluate(engine.currentSubject(), toolReq)` 跑完 L1-L5(远程模式下是 L1;本地模式下 L2-L5)。
2. **deny 快速失败**:决策为拒时,直接返回 `isError=true` 的 `CallToolResult`,**不触碰熔断器**(权限拒绝不是工具故障,不应让 `IoSupervisor` 的 circuit breaker 计数)。
3. **L7 边界**:`IoProbe.validate()` 校验参数符合 schema;失败是 domain error(`isError`),同样不碰熔断器。
4. **冻结**:`IoProbe.freezeArgs()` 深拷贝 + 冻结参数,构造新的 `CallToolRequest frozen`(防 TOCTOU)。
5. **交执行器**:`executor.run(stats, rawHandler, exchange, frozen, 0)` —— 此时才进入超时 + 熔断 + `catch(Throwable)` 的执行沙箱。

关键设计:门在执行器**之前**。权限判定与工具健康(熔断)是两个正交维度,拒绝不污染健康统计。

---

## 2. `SeToolRequirement.forTool` 的安全默认:未列出的工具 = 仅环门控

每个工具的需求**不存在**工具对象上,而是运行时按名字从旁表拼装(`se/SeToolRequirement.java:44-61`):

```
SeToolRequirement.forTool(name, builtIn) =
    // 生成工具(!builtIn)无条件走最强门,不查旁表(见 CRITICAL#1)
    if (!builtIn) → (R-1, SYSTEM, SE_RUN_GENERATED, {CAP_TOOL_CREATE})
    // 内建工具:按名字从旁表拼装
    L2 ring   ← Ring.forBuiltin(name, R3)               // 内建缺省 R3
    L3 writes ← L3_WRITES.get(name)                     // 缺省 null → 不设 L3 门
    L4 priv   ← L4_PRIVILEGE.get(name)                  // 缺省 null → 不设 L4 门
    L5 caps   ← CapabilityCatalog.requiredFor(name, builtIn)  // 缺省空集
```

**CRITICAL#1 —— 生成工具无条件封顶(`SeToolRequirement.java:52-55`)。** AI 用 `create_tool`/`eval` 造的工具是运行在本 JVM 里的**任意 Java**,能经反射 / `Instrumentation` / `Unsafe` 够到任何 R-1 能力,危险度等同 `eval_java`。因此它**不**从按名字的旁表推导需求(旁表只登记已知内建名,一个未登记的生成名会漏成"仅环门控"——审计发现的环模型坍塌),而是**无条件**盖上最强门:`Ring.R_MINUS_1` + `IntegrityLevel.SYSTEM` + `Privilege.SE_RUN_GENERATED` + `CapabilitySid.CAP_TOOL_CREATE`。配套 `Ring.DEFAULT_GENERATED` 已从 R2 改为 **R_MINUS_1**(`Ring.java:69`),`create_tool` 注册生成工具时也用它(`MetaTools.java:169-170`)。于是一次降权就能真正把生成工具全锁死。

**为什么这保住了测试全绿(向后兼容)。** Phase 2 之前,系统只有"环"这一个维度。引入 L3/L4/L5 时若对每个已有工具都强制新需求,行为会变。`forTool` 的"安全默认"让一个**未登记额外需求**的工具只受它的环约束——L3 无写(`writesResourceAt==null`)、无特权要求、能力集为空(内建工具 `Set.of()`)。于是每个老工具的判定路径与 Phase 2 之前逐字节等价(`SeToolRequirement.java:38-43` 注释明确说明这一点)。这就是 keystone commit `efa04a2` 能"零回归"接入完整 7 层门的原因:`SeLocalMonitor` 的 L2 检查就是老 `policy.allows(ring)` 的原样(`SeLocalMonitor.java:126-134`)。

配套的 dev 默认主体是 `SeToken.wideOpen()`:R-1 环、SYSTEM 完整性、全特权 enabled、wildcard 能力(`SeToken.java:44-47`)。所以开发默认下只有环维度真正咬人,其余层恒过——与项目一贯的 wide-open R-1 一致。

---

## 3. 逐层详解

### L1 VTL —— 独立进程决策权威(opt-in)

**NT 对标**:Virtual Trust Level / VTL——把安全决策搬进游戏 JVM **够不到**的另一个地址空间。这是全模型里**唯一的真墙**:JDK 24 起 Security Manager 永久禁用(JEP 486),JVM 内没有硬件级隔离,所以一个被攻陷的 in-JVM hook 在同进程里理论上能绕过任何纯 Java 门;跨地址空间它做不到。

**实现**:
- `se/SeRemoteMonitor`(`SeRemoteMonitor.java:41`):游戏 JVM 侧的 `SeReferenceMonitor`,每次决策通过 127.0.0.1 socket(newline JSON,复用 dep-free 的 `io/Json` 编解码,不耦合 MCP SDK)问远端。
- `alpc/AlpcServer`(`AlpcServer.java:37`):P-SECURE 进程侧,持有真正的 `SeLocalMonitor` 权威 + 权威主体,单线程 accept 循环应答。
- `alpc/AlpcMain`(`AlpcMain.java:27`):独立 JVM 入口(`java ... net.marcloud.mcp.core.alpc.AlpcMain`)。
- `alpc/AlpcProtocol`(`AlpcProtocol.java:28`):双方共享的 wire 常量,防协议漂移。

**只发身份,不发主体**。`SeRemoteMonitor.evaluate()` 故意**不发送** `subject` 参数——只发工具名 + `builtIn`(`SeRemoteMonitor.java:64-70`;注释 `AlpcProtocol.java:9-15`)。P-SECURE 进程用它自己持有的权威主体判定。这样即便游戏 JVM 被攻陷,它也无法"伪造一个 wide-open 主体"混过门,因为它根本没机会陈述主体。

**Fail-closed**。任何传输失败(进程宕、超时、坏帧、半关)一律解析为 **deny**——绝不 allow、绝不 hang(`SeRemoteMonitor.java:72-76`,`call()` 异常 → 返回 null → deny,`.java:176-194`)。`dropTo` 也**同样 fail-closed**:L1 模式下权威主体的真实 clearance 归 P-SECURE 进程所有,`evaluate()` 每次都问远端、从不读本地缓存——所以远端不可达时**绝不**就地降本地缓存(那会是一次"幻影降权":本地显示已降,权威其实没变,连通性恢复后高权工具又被放行,kill-switch 形同虚设)。改为抛 `AuthorityUnreachableException`,让 handler 边界渲染成"drop FAILED — 权威不可达,clearance 未变"(`SeRemoteMonitor.java:103-118`);`tryRestore` 同样返回 false 而不动缓存(`.java:149`)。握手先验共享密钥,不符即关连接(`AlpcServer.java:116-125`)。

**启用**:`-Dmcp.core.psecure=true`,默认关(`AlpcProtocol.java:40`)。两侧须同 `-Dmcp.core.psecureToken`,端口默认 25601(`AlpcProtocol.java:34`)。接线在 `McpCore.buildEngine()`:启用则 L1-L5 决策交给远端 `SeRemoteMonitor`(超时 2000ms)。**但当 P-SECURE 同时开 L6(`objects != null`)时,引擎是 `SeHandleGatedMonitor(remote, objects)`**(KI-8 修复,`McpCore.java:~357`):在远端权威前拼一个**本地** L6 句柄门——先问远端要 L1-L5(fail-closed),仅当放行才跑本地 `ObManager.checkRequest`(L6-last,镜像 `SeLocalMonitor` 顺序);其余(clearance/dropTo/…)全委托过墙。L6 句柄冻结的是**本进程内对象**,远端进程无法解析,故 L6 必须留在本地。`SeHandleGatedMonitor` 属 `se` 包(受保护)。

### L2 环 —— CPU 特权环(live)

**NT 对标**:CPU 保护环(R-1 hypervisor … R3 user),**环号越小 = 特权越高 = 越危险**。

**实现**:`se/Ring`(`Ring.java:31-37`)枚举 R_MINUS_1(HYPERVISOR)/R0(KERNEL)/R1(SYSTEM)/R2(OBSERVE)/R3(USER)。当前 clearance 由 `se/SeClearancePolicy`(`SeClearancePolicy.java:20`)持有,判定规则 `clearance.level() <= tool.level()`(`SeClearancePolicy.java:42-45`)。

**判定点**:`SeLocalMonitor.evaluate()` L2 分支(`SeLocalMonitor.java:126-134`)——与老 `policy.allows(ring)` 逐字节等价。drop/restore 通过引擎的 `dropTo`/`tryRestore` 委托给 `SeClearancePolicy`(`SeLocalMonitor.java:179-187`),每次评估用 `currentSubject()` 把实时 clearance 重新盖到主体上(`.java:194-197`),所以一次 `drop_privilege` 立即生效。

**降权是真 kill-switch**:降权自由(`SeClearancePolicy.dropTo` 只降不升,`.java:52-55`),升权需出示启动时的 restore token(`tryRestore`,`.java:61-67`)。token 未设则随机生成并打印一次(`McpCore.java:307-312`),看不到日志的人无法再升权。

### L3 完整性 —— 强制完整性控制(live)

**NT 对标**:Windows Mandatory Integrity Control(MIC),规则 **no-write-up**:主体完整性 ≥ 资源完整性才能写。

**实现**:`se/IntegrityLevel`(`IntegrityLevel.java:23-31`)7 级:UNTRUSTED(0) … PROTECTED(6)。谓词 `canWriteTo(resource)` = `this.rank >= resource.rank`(`IntegrityLevel.java:54-56`)。

**判定点**:`SeLocalMonitor.java:136-141`——仅当 `tp.writesResourceAt() != null`(即该工具是写操作)才设门。每个写工具写哪一级资源在 `SeToolRequirement.L3_WRITES` 表(`SeToolRequirement.java:74-114`):如 `eval_java`/`open_module`/`eval_ephemeral`/`debug_*` 写 SYSTEM,`redefine_class`/`send_chat`/`send_raw_packet`/`install_hook`/`write_field`/`invoke_method`/`seam_*`/3 个 `gui_*` 交互工具写 HIGH,`create_tool`/`rollback_tool` 写 MEDIUM_PLUS,`memory_write`/`memory_delete`/`set_goal`/`narrate` 等写 LOW。读/记账类工具不在表内 → 无 L3 门。dev 默认主体是 SYSTEM,可写除 PROTECTED 外的一切。

### L4 特权 —— 访问令牌特权(live)

**NT 对标**:NT 访问令牌特权(如 `SeDebugPrivilege`),**两态**:granted(令牌里有)与 enabled(当前激活)。危险操作要求"既 granted 又 enabled",对标 `AdjustTokenPrivileges`。

**实现**:`se/Privilege`(`Privilege.java:16-27`)枚举 **10** 个动词特权:SE_DEBUG_CLASS / SE_LOAD_AGENT / SE_NET_RAW / SE_WORLD_WRITE / SE_SCREEN_CAP / SE_CREATE_TOOL / **SE_RUN_GENERATED**(执行 AI 生成 / eval 的任意 in-proc Java)/ **SE_GUI_INTERACT**(驱动实时 GUI:点击元素、输入、按键)/ SE_DEBUG_CONTROL / SE_SEAM_INJECT。`se/PrivilegeToken`(`PrivilegeToken.java:27`)用 `EnumMap<Privilege, AtomicBoolean>`:键存在 = granted,布尔 = enabled;`isEnabled()` 只在两者都真时为真(`.java:59-62`)。主体不能给自己新增 grant(防自我提权),只能 enable/disable 已有的(`.java:68-85`)。

**判定点**:`SeLocalMonitor.java:144-152`——仅当 `tp.requiredPrivilege() != null`。哪个动词需要哪个特权 enabled 见 `SeToolRequirement.L4_PRIVILEGE` 表(`SeToolRequirement.java:117-151`):`redefine_class`/`write_field`/`invoke_method`/`open_module` 需 SE_DEBUG_CLASS;`send_chat`/`send_raw_packet` 需 SE_NET_RAW;`capture_screen` 需 SE_SCREEN_CAP;`create_tool`/`rollback_tool`/`eval_java`/`eval_ephemeral` 需 SE_CREATE_TOOL;3 个 GUI 交互工具(`gui_click_element`/`gui_type_text`/`gui_press_key`)需 **SE_GUI_INTERACT**;所有 `debug_*` 需 SE_DEBUG_CONTROL;所有 `seam_*`/`install_hook`/`uninstall_hook` 需 SE_SEAM_INJECT。此外生成工具(`!builtIn`)在 `forTool` 里无条件要求 **SE_RUN_GENERATED**(见 §2 CRITICAL#1),不经此旁表。

**L4 vs L5**:L4 门"危险动词",L5 门"资源名词"——见下。

### L5 能力 SID —— AppContainer 能力(live,默认 wildcard)

**NT 对标**:Windows AppContainer capability SID——回答"该主体到底能不能碰资源类 X"。模型是 **default-deny**:主体的 granted 集不含工具所需 SID 即拒,与环无关。

**实现**:`se/CapabilitySid`(`CapabilitySid.java:17-32`)枚举 14 个资源类 SID,并各自标注归属的 C1-C8 能力(如 `CAP_CLASS_REDEFINE` [C4]、`CAP_DEBUG_CONTROL` [C6]、`CAP_SEAM_INJECT` [C8]、`CAP_WORLD_READ` [C2])。

**判定点**:`SeLocalMonitor.java:155-159`,`subject.holdsAll(tp.requiredCaps())`。工具所需 SID 集在 `CapabilityCatalog`(`CapabilityCatalog.java:29-80`)——它是 `Ring.BUILTIN_RINGS` 的直系对照表。

**默认 wildcard,strict 可选**。dev 默认主体能力为 `null` = wildcard = 持有全部(`SeToken.holdsAll` 对 null 直接返回 true,`.java:73-78`),所以 L5 默认恒过。`-Dmcp.core.caps=strict` 切成真 default-deny:主体起始空能力集(`SeLocalMonitor.strictSubject`,`.java:84-88`;接线 `McpCore.java:358-364`),此时每个碰 gated 资源的工具都要先被显式授予 SID。未登记的内建工具需求空集;未登记的 AI 工具默认落 observe 档(`CAP_WORLD_READ` + `CAP_MEMORY_READ`,`CapabilityCatalog.java:82-95`)。

### L6 对象句柄 —— **built, opt-in(默认关)**

**NT 对标**:`NtCreateFile` 式的 open-time 权限冻结——open 时定下 desiredAccess 掩码,后续操作只做子集校验,不再重判。

**现状**:**已建并接线**。`ob/ObManager`(`checkRequest` 单门 + `HANDLE_OPS` 表 + owner-scope + idle reap)是实现类;`McpCore.buildObjectManager` 在 `-Dmcp.core.handles=true` 时接线(默认关 → 引擎里 L6 纯 no-op,现有工具判定不变)。`ObManager` 还有 `strictHandles` 姿态(`-Dmcp.core.hardened=true` 开):`HANDLE_OPS` 工具无 `handle` 参数时**拒绝**而非回退名字解析,闭合 jthread 名复用 TOCTOU(见 `../project/known-issues.md` KI-3 + `adr/ADR-0001-l6-strict-handle-posture.md`)。回归测试 `ob/L6ObjectHandleTest`、`ob/L6DebugGateThroughRegistryTest`。

### L7 边界校验 —— `ProbeForRead` + 参数捕获(live)

**NT 对标**:系统调用边界的 `ProbeForRead` + 参数捕获——dispatch 前把用户态参数拷进内核态快照并校验,杜绝 TOCTOU。

**实现**:`io/IoProbe`(`IoProbe.java:16`),纯函数、无状态,两件事:
1. **深拷贝 + 冻结**(`deepFreeze`/`freezeArgs`,`.java:39-70`):Map→不可变 `LinkedHashMap`、List→不可变 list、String/Number/Boolean/null 直通,其他可变引用防御性字符串化,确保没有活对象能在校验后被换掉(TOCTOU)。
2. **轻量 JSON-schema 校验**(`validate`,`.java:75-162`):检查 required 键非空、已声明属性的类型(string/boolean/number/integer/array/object)、enum 约束;未知键放行(`additionalProperties` 默认 true,让 AI 工具的空 schema 接受任意参数)。

**判定点**:在决策**之后**拼进 `supervise()`(`IoManager.java:135-144`)——先校验 schema(失败为 domain error,不碰熔断器),再冻结成 `frozen` 请求交执行器。之所以放这里而非引擎:L7 依赖具体参数与工具 schema,而引擎的 `evaluate` 只拿工具身份。schema 从 `Tool.inputSchema()` 尽力还原为 Map(`IoManager.schemaMapOf`,`.java:92-99`;还原不到则 null → 校验宽松但冻结仍跑)。

---

## 4. 四张门表与交叉一致性

7 层门的判定数据落在四张按工具名索引的表里。它们各管一层,但**必须互相对齐**,否则会出现"某工具在一层被登记、在另一层漏登记"的裂缝。

| 表 | 位置 | 管的层 | 缺省行为 |
|---|---|---|---|
| `Ring.BUILTIN_RINGS` | `Ring.java:86-167` | L2 环 | 未列出 → 内建 R3 / AI 造 R-1(`DEFAULT_GENERATED`) |
| `CapabilityCatalog.REQUIRED` | `CapabilityCatalog.java:29-80` | L5 能力 SID | 未列出 → 内建空集 / AI 造 observe 档 |
| `SeToolRequirement.L3_WRITES` + `L4_PRIVILEGE` | `SeToolRequirement.java:74-151` | L3 完整性 / L4 特权 | 未列出 → 无该层门 |
| `MetaTools.isReserved`(注册表派生) | `MetaTools.java:246-248` | 反自我覆盖(create_tool 守卫) | 未列出 → 可被 AI 工具替换 |

**交叉一致性**:同一个危险工具应在四张表里给出**同档**的严格度。以 `redefine_class` 为例——L2:R_MINUS_1(`Ring.java:89`);L3:写 HIGH(`SeToolRequirement.java:75`);L4:SE_DEBUG_CLASS(`.java:118`);L5:CAP_CLASS_REDEFINE(`CapabilityCatalog.java:44`);reserved:是(任一内建都保留,见下)。`debug_*` 九个工具同样在四表里齐刷刷 R-1 / SYSTEM / SE_DEBUG_CONTROL / CAP_DEBUG_CONTROL / reserved。前三表(L2/L3/L4/L5)的跨表对齐靠约定与评审维持,而非编译期强制——`forTool` 的安全默认意味着某表漏登记不会报错、只会"少一层门"(整体仍受其余层约束,不会误放开更危险)。集成 C1/C3/C5/C7/C8 时(commit `aff7597`)"把所有新工具接进 Ring/CapabilityCatalog/SeToolRequirement"正是这条一致性工序;对抗审计(`a674ea9`)的 H3 补的就是几个漏档(`seam_*_uninstall`/`send_chat`/`rollback_tool` 的 L3/L4)。**第四张"reserved"表已不再是手维护名单**——见下。

`MetaTools.isReserved`(`MetaTools.java:246-248`)是防"自我脑叶切除"的一环:AI 用 `create_tool` 造工具时,不允许覆盖它赖以运作的核心工具名。**CRITICAL#2 修复后,保留集不再是一份手写名单,而是直接从活注册表派生**——`isReserved(name)` 即 `registry.isBuiltin(name)`(`IoManager.java:224-227`),于是"保留集"恒等于"当前已注册的内建集",永不漏档、永不与真实内建集漂移。`create_tool` 先查一次(`MetaTools.java:154-156`),而 `IoManager.register()` 又作硬后盾:任何生成工具(`!builtIn`)试图替换已注册的内建工具会直接抛异常(`IoManager.java:161-164`),即便前置检查被绕过也拦得住。

---

## 5. `SeProtectedObjects` 与 `AgentAccess`:守住内核自身

7 层门管"谁能调哪个工具",但还有一类更根本的威胁:AI 用 `redefine_class`(R-1)去**重写实施特权模型的类本身**,或抓 `Instrumentation` 绕过一切。两个专门守卫堵这两个洞(Phase 0,commit `8112e43`)。

### `SeProtectedObjects` —— 不可重定义集合

`se/SeProtectedObjects`(`SeProtectedObjects.java:34`)是一组"永不可被 redefine/retransform/hot-swap"的载荷类。两条规则(`isProtected`,`.java:129-137`):
1. **整包前缀(四个)**:`net.marcloud.mcp.core.se.`(安全参考监视器)、`ob.`(对象管理器,持 L6 句柄)、`compat.`(补丁信任脊柱)、`alpc.`(P-SECURE 传输+crypto)下所有类全保护(`.java:40/48/61/77`),所以以后新增的内核/信任类型自动覆盖。`compat.`/`alpc.` 是 2026-07-16 审计补的——所有 Ed25519 裁决最终汇聚到 `alpc.CompatCrypto.ed25519Verify` 一个原语,只护 compat 调用方而漏 alpc 委托曾是覆盖缺口。
2. **精确名单**:安全包外的载荷机器——`CoreAgent`/`AgentAccess`、`IoManager`/`IoSupervisor`/`ToolStats`/`IoProbe`(L7 deep-freeze)/`MetaTools`/`Capability`/`DynamicToolFactory`、`LdrRedefiner`/`LdrEngine`、hook 装机(`FltManager`/`HookBridge`/`FltDynamicManager`/`HookTools`/`GenericEntryAdvice`)、`MmAccess`/`SeamController`/`NettyTap`/`TickInjector`、`KdBridge`,以及**上一层的 gate 包装类**(redefine 掉门方法即可绕过检查而不碰受保护核)`io.http.HttpFacade`/`kd.DebugTools`/`mm.MutateStateTools`(`.java:84-123`)。

**名字规范化防绕过**(对抗审计 H1,commit `a674ea9`):`normalize()`(`.java:147-163`)剥掉 JVM 数组描述符(`[Lnet...Ring;` → `net...Ring`)、源码数组形式(`Foo[]`)、内部类后缀(`Foo$Bar` → `Foo`),使调用者无法用数组类型名或内部类名把受保护类偷渡过门。

**多个choke point都查这张表**(单一事实源):`LdrRedefiner.redefine()`(`LdrRedefiner.java:54`)、`MetaTools.redefine_class`(`MetaTools.java:222-225`,清晰报错、不强制解析,LdrRedefiner 再兜一次)、ByteBuddy 的 `.type()` matcher(`FltManager.java:120-123`、`FltDynamicManager.java:143`)、以及 `MmAccess` 的每条反射读/写/调用路径(`guardProtected`,`MmAccess.java:336-338`)。**读路径也守(对抗审计):**`getField`/`getStaticField` 不仅守目标运行时类,还守**声明类**(`MmAccess.java:90`、`.108`)——否则一个非受保护子类可读出声明在受保护安全类上的标量/String 字段(仅看返回值运行时类型的 `isProtectedValue` 会漏),与写/调用路径对称。

**诚实边界**(`SeProtectedObjects.java:19-24` 明说):这只挡"具名、schema 驱动"的 redefine/retransform 路径,**不是**对抗任意 R-1 代码的墙——`eval_java` 跑无限制 Java,理论上能经 self-attach 等别的路子够到 `Instrumentation`。真正的跨地址空间墙是 P-SECURE(L1)。这张表是纵深防御:移除掉"工具中介"这条最省事的作恶路径。

### `AgentAccess` —— `Instrumentation` 的门

原先 `CoreAgent.instrumentation()` 是 `public static`,任何代码(含 eval_java 片段)都能抓全权 `Instrumentation` 绕过模型。Phase 0 把它降为 package-private,跨包唯一正道是 `boot/AgentAccess`(`AgentAccess.java:29`)——同 `agent` 包,只把句柄再暴露给需要它的具体内部调用者(LdrRedefiner、FltManager、introspection、MmAccess 等经 `AgentAccess::instrumentation` 注入,见 `McpCore.java:183-184`)。

**诚实边界**(`AgentAccess.java:21-27`):这是可见性门,不是能力沙箱——任何能引用此类的 in-process 代码都能调它。价值是消除全局裸抓、给出一个可审计的 choke point;`SeProtectedObjects` 防它被 redefine 掉,P-SECURE 才是真墙。

---

## 6. 一次工具调用穿过全部 7 层(决策流)

以 AI 调 `redefine_class`(重写一个已加载的 `net.minecraft.*` 类)为例,dev 默认(wide-open R-1)+ **strict 能力** + **P-SECURE 关闭**:

```
AI/MCP client ─→ SocketTransportServer(或 HTTP /v1/tools/{name})
                      │
                      ▼
        IoManager.supervise() 的 supervised handler        [唯一的门]
                      │
   ┌── engine.evaluate(currentSubject(), IoRequestPacket{redefine_class}) ──┐
   │                                                                     │
   │  L1 VTL   本地引擎无 L1(远程模式下:socket 问 P-SECURE,fail-closed)│
   │  L2 环    clearance(R-1) <= tool.ring(R-1)?          过           │  SeLocalMonitor
   │  L3 完整性 subject(SYSTEM) canWriteTo HIGH?           过           │  .evaluate():123-172
   │  L4 特权  SE_DEBUG_CLASS granted && enabled?          过           │  (任一  → 短路 deny,
   │  L5 能力  holdsAll({CAP_CLASS_REDEFINE})?                           │   附 "L{n}" 层标签)
   │           strict 下起始空集 →  deny "L5 capability"                │
   └─────────────────────────────────────────────────────────────────┘
                      │  decision.allow()==false
                      ▼
        返回 CallToolResult{isError=true, "permission denied [L5 capability]: ..."}
        —— 不碰熔断器(IoManager.java:124-129)
```

若 L1-L5 全过(如 wildcard 能力),继续:

```
                      │  allow
                      ▼
   L7 IoProbe.validate(schema, args)     校验 className/source 齐备、类型对
                      │  失败 → isError domain error(不碰熔断器)
                      │  通过
                      ▼
   frozen = new CallToolRequest(name, IoProbe.freezeArgs(args))   深拷贝+冻结
                      │
                      ▼
   executor.run(stats, rawHandler, exchange, frozen, 0)     进入超时+熔断+catch(Throwable)沙箱
                      │
                      ▼
   redefine_class handler:
     • SeProtectedObjects.isProtected(className)?  是 → 拒(内核自保,MetaTools.java:222)
     • Class.forName(className, false, ...) 仅取已加载类(不强制加载)
     • hotLoad.redefineExisting(target, source)  经 LdrRedefiner(再查一次 SeProtectedObjects)
                      │
                      ▼
              成功/失败的 CallToolResult
```

要点回顾:门在执行器**之前**;7 层 AND、首拒短路并带层标签(`SeAccessCheck.message()`,`SeAccessCheck.java:28-30`);权限拒绝与 schema 拒绝都**不**污染熔断器;`SeProtectedObjects` 是执行阶段的第二道内核自保闸,与 L1-L7 正交。

---

## 7. 现状与诚实的空缺

- **live**:L2/L3/L4/L5(默认 wildcard)、L7、`SeProtectedObjects`、`AgentAccess`、单门 `supervise()`。core 测试全绿(方法数以 STATUS.md 为准;含 `SecurityKernelTest`/`SupervisedGateIntegrationTest`/`PSecureRpcTest`/`DebugToolsTest`/`GuiToolGateTest`)。
- **built, opt-in**:L1 P-SECURE(`-Dmcp.core.psecure=true`,fail-closed,commit `7cdbb66`);L5 strict default-deny(`-Dmcp.core.caps=strict`);L6 对象句柄(`ob/ObManager`,`-Dmcp.core.handles=true`,strict 姿态见 ADR-0001)。
- **诚实边界**(源码自述):环/`SeProtectedObjects`/`AgentAccess` 都不是对抗任意 R-1 代码的沙箱;`eval_java` 是设计上的任意代码执行,唯一真正的跨地址空间墙是 opt-in 的 P-SECURE 进程。
- **已 live 验证**:C6 原生 JVMTI 调试器已在**运行中的 MC 客户端**里实测——`debug_set_breakpoint`(如 `Minecraft.runTick`)、线程 suspend 查找、single-step 切换均执行真实 JVMTI。原生库不再受 MSVC 阻塞:用 LLVM/clang 构建(`core/src/main/native/core-jvmti/build-clang.sh`,`clang -shared`,无需 Visual Studio / Windows SDK);一处 `JNI_OnLoad` 绑定修复(在 app-classloader 上下文而非仅 VMInit 绑定 natives)使其可用。编译出的 `core-jvmti.dll` 被 gitignore。
- **仍待 live 验证**:其余 Phase 2 能力(hook 触发、结构化 redefine、seam、deep-access)在运行中的 JBR+DCEVM 游戏里的端到端验证目前仍以 headless 为主(见 project-phase2-kernel.md 的 REMAINING)。
