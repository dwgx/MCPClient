---
doc: arch-capabilities
title: 02 — C1-C9 能力分类体系（Capability Taxonomy）
layer: reference
status: authoritative
updated: 2026-07-13
parent: 01-SECURITY-KERNEL.md
next:
  - path: 03-NATIVE-C6-AND-BUILD.md
    when: 你要深入 C6 原生 JVMTI 调试器层或构建/运行链路
read_if: 只在你要新增/修改某类能力工具(C1-C9)、查某个工具的 ring/privilege/capability-SID 要求或底层机制时读。
---
# 02 — C1-C9 能力分类体系（Capability Taxonomy）

本文档描述 MCPClient 的九类能力（C1-C9，其中 **C9 GUI-INTERACT** 是晚于 C1-C8 加入的结构化 GUI 操作面）。这套分类是把一个 Windows-NT-内核架构的 AI 调试器平台的"能做什么"沿资源类别切开的结果：每一类对应一组 MCP 工具、一种底层机制、以及在 7 层门禁里各自的 ring / privilege / capability-SID 要求。

所有引用均指向真实源码（`core/src/main/java/net/marcloud/mcp/core/`，package `net.marcloud.mcp.core`，`--release 25`）。凡尚未落地的能力，均如实标注。

---

## 0. 门禁复习：一个工具是怎么被放行的

每个能力工具在注册时都被 `IoManager.supervise()` 包裹一层监督逻辑，运行时按顺序 AND 组合地跑 7 层参考监视器（任一层拒绝即 fail-fast，不触碰熔断器）：

- 决策核心在 `IoManager.supervise()` — `io/IoManager.java:111`，其中 `engine.evaluate(engine.currentSubject(), toolReq)` 在 `:123` 得出 `SeAccessCheck`。
- L7 边界校验紧随决策之后：`IoProbe.validate(...)`（`io/IoManager.java:135`）+ `IoProbe.freezeArgs(...)`（`:144`），先深拷贝+冻结参数再校验 inputSchema，然后才 dispatch。

工具的逐层要求不写在工具里，而是按工具名从旁表查。**L2-L5 这 4 张由 `SeToolRequirement.forTool` 一处组合**（`se/SeToolRequirement.java:44`）——但**按工具名查的旁表一共 5 张**：L6 的 `ob/ObManager.java:60` `HANDLE_OPS` **不在 `forTool` 里**，读 `forTool` 只会数出 4 张（这正是 2026-07-15b 一份门控清单自己数错的原因）。**查不到名字的那层缺省放行、不报错**（陷阱详解见 `01-SECURITY-KERNEL.md` §2；**加工具的完整登记清单见 `../reference/tool-annotation-convention.md` §0**）：

- **L2 环（Ring）** — `se/Ring.java`，`BUILTIN_RINGS` 表（`Ring.java:86`）。数值越低权限越高：R-1 HYPERVISOR / R0 KERNEL / R1 SYSTEM / R2 OBSERVE / R3 USER。
- **L3 完整性（IntegrityLevel，写目标标签）** — `SeToolRequirement.L3_WRITES`（`SeToolRequirement.java:74`）；只读工具无此项。
- **L4 特权（Privilege，需 enabled）** — `SeToolRequirement.L4_PRIVILEGE`（`SeToolRequirement.java:117`）。
- **L5 能力 SID（CapabilitySid，默认拒绝）** — `se/CapabilityCatalog.java:29` `REQUIRED` 表。

此外，C3/C5/C6 三类在工具 handler 内部还各自做一次 `AccessGate.require(...)` 的**纵深防御**（defense-in-depth），与监督门 AND 组合。

L1 VTL（P-SECURE 独立进程）与 L6 对象句柄两层不在本文重点内：L1 通过 `alpc/AlpcServer` + `se/SeRemoteMonitor` 以 opt-in `-Dmcp.core.psecure=true` 提供；L6 对象句柄 (`ob/ObManager`) **已建并接线**（`-Dmcp.core.handles=true` 开，默认关；strict-handle 姿态见 ADR-0001）。开启时 `DebugTools` 额外注册**两个** C6 句柄工具 `debug_open_thread` / `debug_close_handle`（均 R0，`Ring.java:166-167`）。

---

## 1. C1-C9 总表

| 类 | 能力名 | package | MCP 工具 | 机制 | Ring / Privilege / Cap-SID 门控 | 状态 |
|---|---|---|---|---|---|---|
| **C1** | INTROSPECT | `cm/` | `list_classes` `describe_class` `find_method` `list_hooks` | `Instrumentation.getAllLoadedClasses` + 反射；`HookSource` SPI 聚合 hook | R3 / 无 / `CAP_CLASS_INTROSPECT`（只读） |  已建 |
| **C2** | OBSERVE | （分散：`drivers/world/` + C6） | 被动监视点、方法出入、包抓取 | 已有：`recent_packets`/`disconnect_report`(ByteBuddy packet 日志)；字段监视经 C6 `debug_watch_field`(JVMTI SetFieldModificationWatch) | 见各归属工具 | ◐ 部分（无独立 package；field-watch 依赖 C6 原生层） |
| **C3** | INTERCEPT | `flt/FltDynamicManager` | `install_hook` `uninstall_hook` | ByteBuddy `AgentBuilder` + RETRANSFORMATION，保留 `ResettableClassFileTransformer` 句柄→可逆卸载 | install=R-1 / uninstall=R0；`SE_SEAM_INJECT`；`CAP_CLASS_RETRANSFORM` |  已建 |
| **C4** | REDEFINE | `ldr/`（既有） | `redefine_class` | `LdrEngine.redefineExisting` over JBR+DCEVM（改方法体 + 加字段/方法） | R-1 / `SE_DEBUG_CLASS` / `CAP_CLASS_REDEFINE`；L3=HIGH |  既有（Phase 1 已接线，非 Phase 2 新增） |
| **C5** | MUTATE-STATE | `mm/` | `read_field` `write_field` `invoke_method` `open_module` | `MethodHandles.privateLookupIn` + `VarHandle` + `Unsafe` fallback + `redefineModule`；`AccessGate` 纵深防御 | read=R0，write/invoke/module=R-1；`SE_DEBUG_CLASS`；`CAP_MEMORY_READ`/`CAP_MEMORY_WRITE` |  已建 |
| **C6** | CONTROL-EXEC | `kd/` | `debug_suspend_thread` `debug_pop_frame` `debug_force_return` `debug_set_breakpoint` `debug_clear_breakpoint` `debug_single_step` `debug_read_local` `debug_write_local` `debug_watch_field` | 原生 JVMTI agent `core-jvmti.dll`（`-agentpath`）+ JNI 桥 `KdBridge` | R-1 / `SE_DEBUG_CONTROL` / `CAP_DEBUG_CONTROL`；L3=SYSTEM |  Java 侧 + 原生 DLL 均已建；DLL 用 LLVM/clang 编译（无需 MSVC），已在运行中的 MC 客户端里实地验证 |
| **C7** | SYNTHESIZE | `ps/PsSynthesizer` | `eval_ephemeral` | javac 编译 → `Lookup.defineHiddenClass(bytes, true)` 可 GC 隐藏类 | R-1 / `SE_CREATE_TOOL` / `CAP_TOOL_CREATE`；L3=SYSTEM |  已建 |
| **C8** | SEAM | `flt/seam/` | `seam_netty_install` `seam_netty_uninstall` `seam_glfw_key_hook` `seam_glfw_mouse_hook` `seam_tick_enable` `seam_tick_disable` | Netty `ChannelPipeline` MITM（`NettyTap`）+ GLFW 回调链（`InputHook`）+ retransform `runTick`（`TickInjector`，可逆） | install=R-1，netty/tick uninstall=R0；`SE_SEAM_INJECT` / `CAP_SEAM_INJECT`；L3=HIGH |  已建 |
| **C9** | GUI-INTERACT | `drivers/gui/` | `gui_snapshot` `gui_snapshot_image` `gui_click_element` `gui_type_text` `gui_press_key` `gui_trajectory` | 反射抽取活 `GuiScreen` 为结构化元素（`GuiReflect`/`GuiSnapshotService`），按元素 id 在游戏线程驱动 vanilla 1.8.9 的 `mouseClicked`/`keyTyped`/`textboxKeyTyped`（`GuiActions`）；`gui_snapshot_image` 出 SoM 标注截图，`gui_trajectory` 出操作轨迹 | snapshot/snapshot_image=R2（只读），click/type/press=R1 + `SE_GUI_INTERACT` + L3=HIGH，trajectory=R3 |  已建 |

> **C2 / C4 状态说明**
> - **C4 REDEFINE 是既有能力，非 Phase 2 新增**：`redefine_class` 在 Phase 1（commit `2dddc13`）就已接线到 `LdrEngine.redefineExisting`，Phase 2 只是把它纳入了 7 层门禁的 L3/L4/L5 表。工具定义在 `io/MetaTools.java:194`（`redefineClass()`），handler 在 `MetaTools.java:212`，且 `MetaTools.isReserved`（`MetaTools.java:244`）把它列为不可被生成工具覆盖的保留核心工具。
> - **C2 OBSERVE 没有独立 package**：被动观察分散在既有工具（`recent_packets`/`disconnect_report` 走 ByteBuddy 注入的 packet 日志）和 C6 的 field-watch 里。真正的"字段修改监视点"能力是 C6 的 `debug_watch_field`（JVMTI `SetFieldModificationWatch`，见 `KdBridge` native 声明 `nSetFieldModificationWatch`）——因此 C2 的完整落地依赖 C6 原生 DLL。`CapabilitySid` 里 `CAP_MEMORY_READ`（标注 `C2/C5`）/`CAP_NETWORK_RECV_TAP`/`CAP_SCREEN_CAP`/`CAP_WORLD_READ` 都带 `C2` 标注（`se/CapabilitySid.java:22-27`）。

---

## 2. 各已建能力包详解

### C1 — INTROSPECT（`cm/`）

可查询的自我模型：枚举已加载类、按反射描述类结构、跨类查方法、聚合列出所有运行时 hook。全部**只读**（R3 + `CAP_CLASS_INTROSPECT`），永不暴露裸 `Instrumentation` 句柄（不扩大未门禁访问器漏洞）。

工具注册统一在 `IntrospectionTools.registerAll`（`cm/IntrospectionTools.java:30`），底层服务是 `CmQuery`（快照来自 `AgentAccess.instrumentation()`，agent 缺席时回退反射）。

| 工具 | 注册/handler | 说明 |
|---|---|---|
| `list_classes` | 定义 `IntrospectionTools.java:39`（`listClasses()`），handler lambda `:53` | 列已加载类，可按 package 前缀 + 名字子串过滤；默认 200、硬上限 2000；标注每个类是否 JVM-modifiable、是否被 Core 标记为 protected |
| `describe_class` | 定义 `IntrospectionTools.java:73`，handler `:86` | 反射描述类：kind/modifiers/superclass/interfaces/fields/methods/constructors + loaded/jvmModifiable/protected 标志。未加载的类会尝试无初始化解析（副作用：变为已加载） |
| `find_method` | 定义 `IntrospectionTools.java:139`，handler `:154` | 跨已加载类按方法名子串查方法，可按 owner 类名、参数签名（`int,int` 或 JVM descriptor `(II)`）过滤，默认 100、上限 1000 |
| `list_hooks` | 定义 `IntrospectionTools.java:180`，handler `:189` | 列出所有运行时 hook：目标类/方法、advice 类、hook 种类、是否已安装 |

**HookSource SPI** — `list_hooks` 的聚合能力靠 `flt/HookSource.java:11` 接口实现。`CmQuery.listHooks()`（`cm/CmQuery.java:224`）遍历所有注入的 `HookSource`。当前两个实现：`FltManager`（固定的 NetworkManager 网络 hook）和 `FltDynamicManager`（C3 运行时 hook，`FltDynamicManager` 通过 `implements HookSource` 在 `flt/FltDynamicManager.java:65` 声明，`hooks()` 在 `:331`）。因此新装的 C3 hook 会自动出现在 `list_hooks` 里，无需改动 C1。

---

### C3 — INTERCEPT（`flt/FltDynamicManager`）

运行时装/卸 ByteBuddy retransformation hook，突破 Mixin 的 load-once 天花板。每个 hook 是自己独立的 `AgentBuilder` → `ResettableClassFileTransformer`，卸载时调 `transformer.reset(inst, RETRANSFORMATION)` **外科式**只还原这一个 hook，保留同类上的其他 hook。这正是 Mixin 缺的能力（Mixin 只能 class-load 时织一次，无法 un-weave）。

工具在 `HookTools.registerAll`（`flt/HookTools.java:55`）注册。除监督门的 ring 外，handler 内还先做一次 `gate.require(CapabilitySid.CAP_CLASS_RETRANSFORM)` 纵深防御。

| 工具 | 注册/handler | Ring | 说明 |
|---|---|---|---|
| `install_hook` | 定义 `HookTools.java:94`（`installHook()`），handler `:110`；gate 检查 `:124`；实装调用 `mgr.install()` `:131` | **R-1** | 在任意已加载类的任意方法上装字节码 hook，每次调用触发 `HookFiredEvent`，返回 hookId。protected 核心类被拒（`FltDynamicManager.install` 首步 denylist，`FltDynamicManager.java:143`） |
| `uninstall_hook` | 定义 `HookTools.java:144`，handler `:157`；gate 检查 `:165`；`mgr.uninstall()` `:171` | **R0** | 按 hookId 还原目标类字节码（`FltDynamicManager.uninstall`，`FltDynamicManager.java:265`，其中 `transformer.reset(...)` 在 `:270`） |

机制关键点（`FltDynamicManager.java`）：安装时保留 transformer 句柄（`installOn(inst)` 后不丢弃，`:219`）；hook 经 `HookBridge.dispatch`（`flt/HookBridge.java:100`）发 `HookFiredEvent` 到 EventBus；`canInstall()` 要求 `-javaagent:core-agent.jar`（`:100`）。注意与 C8 seam 的区别：retransform 改的是**类**，所以 hook 跨重连持久、对所有实例生效；C8 的 per-Channel handler 则须每次重连重装。

---

### C5 — MUTATE-STATE（`mm/`）

读写任意字段（含 private / final / static）、调私有方法、开放模块。`MmAccess`（`mm/MmAccess.java:40`）以 `MethodHandles.privateLookupIn` + `VarHandle` 为主路径（快、类型检查），final 写用 `Unsafe` fallback；对 protected 类拒绝；缓存 per-class Lookup 和 per-field VarHandle。

工具在 `MutateStateTools.registerAll`（`mm/MutateStateTools.java:54`）注册。实例路径的读写/调用都经 `GameBridge.onGameThread(...)` marshal 到游戏线程。

| 工具 | 注册/handler | Ring | 说明 |
|---|---|---|---|
| `read_field` | 定义 `MutateStateTools.java:93`，handler `:107` | **R0** | 读字段（含 private）；`path`（点式实例路径）或 `className`（静态）；protected 值（Instrumentation/SeClearancePolicy 内部）redact 为 `<protected>`（`isProtectedValue` 判断 `:319`） |
| `write_field` | 定义 `MutateStateTools.java:148`，handler `:165` | **R-1** | 写字段（含 private/final）；拒绝 protected 类；诚实标注 final 写无 JMM 可见性保证、编译期常量 static final 可能仍被 inline |
| `invoke_method` | 定义 `MutateStateTools.java:201`，handler `:217` | **R-1** | 调方法（含 private）；`paramTypes` 数组指定签名，`args` 支持 `{"$path":"..."}` 传活对象；拒绝 protected 类 |
| `open_module` | 定义 `MutateStateTools.java:281`，handler `:293` | **R-1** | 经 `Instrumentation.redefineModule` 把命名平台模块的某个 package 开放给 Core，启用对 `java.base` 内部的 privateLookupIn/反射 |

**AccessGate 纵深防御** — `MmAccess` 构造时接收一个 `se/AccessGate.java:18` 实现，每个 mutating 方法先调 `gate.require(cap, privs...)`（如 `CAP_MEMORY_WRITE` + `SE_DEBUG_CLASS`）才动作。这是与监督门 AND 组合的第二道 L4/L5 关卡；当前 dev 默认为 `AllowAllGate`（no-op），把真实 L4/L5 引擎的落地留给 P-SECURE 窗口，同时不阻塞 MmAccess。模块故事见 `MmAccess.java:24`：游戏(Java-8)与 core(Java-25)在同一 unnamed module，对游戏类的 `privateLookupIn` 无需 `--add-opens`；`--add-opens` 仅对 java.base 内部（Unsafe，已在 jvm-args 里授予）需要。

---

### C7 — SYNTHESIZE（`ps/PsSynthesizer`）

编译 AI 提供的 Java 并定义为**隐藏类**（`Lookup.defineHiddenClass(bytes, true)`，GC-able，无 STRONG），反射调其 `public String handle(Map<String,Object>)`，返回结果后丢弃所有引用让类被 GC。是 `create_tool` 的一次性对偶：不注册、不归档、对 C1 INTROSPECT 不可见、永不可 redefine（隐藏类不可改）。

工具在 `SynthTools.registerAll`（`ps/SynthTools.java:32`）注册。

| 工具 | 注册/handler | Ring | 说明 |
|---|---|---|---|
| `eval_ephemeral` | 定义 `SynthTools.java:65`（`evalEphemeral()`），handler `:87`，核心调用 `synth.eval(...)` `:102` | **R-1** | 编译 AI Java 并作为丢弃式隐藏类执行。源码必须声明 `package net.marcloud.mcp.core.synth;` 且有 `public String handle(java.util.Map<String,Object> args)`（`defineHiddenClass` 要求同包，`PsSynthesizer.java:19`） |

诚实边界（`PsSynthesizer.java:23`）：containment ≠ sandbox。隐藏 + GC-able 只去掉持久化、名字可见性、可 redefine 性；`handle()` 内代码拥有与 `eval_java` 相同的全 JVM 能力（可调 Unsafe、崩游戏、redefine 类）。R-1 门禁是唯一真控制。

---

### C8 — SEAM（`flt/seam/`）

三种运行时接缝，全部 install=R-1、`SE_SEAM_INJECT` + `CAP_SEAM_INJECT`（`SeamTools.registerAll` 直接给 `Ring.R_MINUS_1`，`flt/flt/seam/SeamTools.java:38`）。工具在 `SeamTools`（`flt/flt/seam/SeamTools.java:50` `all()`）。三种机制分别是 `NettyTap` / `InputHook` / `TickInjector`，由 `SeamController` 统管、在 `stop()` 时拆除。

| 工具 | 注册/handler | Ring | 机制 |
|---|---|---|---|
| `seam_netty_install` | 定义 `SeamTools.java:97`，handler `:106` | R-1 | `NettyTap`：在活动游戏 channel pipeline 尾部装 `ChannelDuplexHandler`（`flt/flt/seam/NettyTap.java:32`），发 `SeamPacketInboundEvent`/`SeamPacketOutboundEvent`。**wire 冻结**：只观察不改，发布的是只读 ByteBuf dup（audit H5 修复） |
| `seam_netty_uninstall` | 定义 `SeamTools.java:116`，handler `:122` | R0 | 从 pipeline 移除 tap；per-channel handler 追踪支持重连后清理（audit H6，`NettyTap.java:38` `installedOn`） |
| `seam_glfw_key_hook` | 定义 `SeamTools.java:129`，handler `:140` | R-1 | `InputHook`：在游戏原 GLFW key 回调**前**链入观察回调（`flt/flt/seam/InputHook.java:21`），发 `SeamKeyEvent`；需活 GLFW 窗口（headless 测试不可用，`isAvailable()` 守卫 `:46`） |
| `seam_glfw_mouse_hook` | 定义 `SeamTools.java:154`，handler `:165` | R-1 | 同上，鼠标按钮回调 → `SeamMouseEvent` |
| `seam_tick_enable` | 定义 `SeamTools.java:179`，handler `:189` | R-1 | `TickInjector`：retransform `Minecraft.runTick` 内联 `TickAdvice`，每 tick（20/s）发 `TickEvent`（此前已声明但未接线的死事件，此接缝把它点活，`flt/flt/seam/TickInjector.java:26`） |
| `seam_tick_disable` | 定义 `SeamTools.java:203`，handler `:211` | R0 | **真可逆**：`transformer.reset(...)` 还原 `runTick` 字节码，无需重启（保留了 transformer 句柄，`TickInjector.java:35`） |

---

### C9 — GUI-INTERACT（`drivers/gui/`）

把整个可点击 GUI 作为**可寻址元素**暴露给 LLM，并让它按元素 id 驱动**真实的** vanilla 处理器——LLM 永不发送像素，只发 `gui_snapshot` 给出的元素 id。这规避了像素落点计算（framebuffer/DPI 换算）这一类困扰 computer-use agent 的 bug：坐标全程在 scaled-GUI 空间（`GuiScreen.mouseClicked` 直接消费的空间）。

`drivers/gui/` 包：快照侧 `GuiSnapshot`/`GuiElement`/`GuiReflect`/`GuiSnapshotService` + 几何值类型 `Bounds`/`Point`/`State`/`Viewport`；操作侧 `GuiActions`；工具面 `GuiTools`。`GuiReflect` 用 **vanilla 1.8.9** 映射反射抽取活 `GuiScreen`（`GuiButton.xPosition`、`Slot.xDisplayPosition`、`getStack()` 空时返回 null 等），不依赖任何外部反编译源。所有操作经 `GameBridge.onGameThread(...)` marshal 到游戏线程，`mouseClicked`/`keyTyped`（vanilla 里 protected）经反射沿类层次向上查找后调用。

工具在 `GuiTools.registerAll`（`drivers/drivers/gui/GuiTools.java:53`）注册，工具在 `all()`（`drivers/drivers/gui/GuiTools.java:61`）。

| 工具 | 定义 | Ring | 说明 |
|---|---|---|---|
| `gui_snapshot` | `GuiTools.java:118` | **R2**（只读） | 抽取当前屏幕每个可点击元素（按钮 / 库存·容器 slot / 文本框）为 `{id,label,bounds,clickPoint}`；返回 `epoch` + `fingerprint`（操作工具须回传，防陈旧误点）；无 GUI 打开时返回 `screen=null`。参数 `onlyInteractable`（默认 true） |
| `gui_click_element` | `GuiTools.java:230` | **R1** | 按 id 点击元素；在游戏线程重解析元素、按活几何重算落点、调真实 `mouseClicked`（slot→向服务器发 windowClick，button→跑其 action）；`button` = `left`(默认)/`right`；须传 `epoch`+`fingerprint`，屏幕变了则**拒绝**（`GuiActions.guardStale`） |
| `gui_type_text` | `GuiTools.java:264` | **R1** | 按 id 往文本框输入：先点击聚焦，再逐字符驱动 `textboxKeyTyped`；`clearFirst=true` 先 `setText("")` 清空；须传 `epoch`+`fingerprint` |
| `gui_press_key` | `GuiTools.java:298` | **R1** | 在当前屏幕按键：`Escape`(关) / `Return`·`Enter`(确认) / `Tab` / `Backspace` / 单字符，映射到 LWJGL keyCode（`mapKey`）后调真实 `GuiScreen.keyTyped`；须传 `epoch`+`fingerprint` |

**陈旧防护（stale-action guard）** — 每个操作携带快照的 `epoch`+`fingerprint`；`GuiActions.guardStale`（`drivers/drivers/gui/GuiActions.java:177`）在游戏线程重取活屏幕，`svc.validateAgainst(...)` 校验身份/结构未变，屏幕若已改变则以可操作的错误消息拒绝（提示重新 `gui_snapshot`），而不是点错东西。

**门控** — `gui_snapshot` 是只读的 R2；三个操作工具 R1 + `SE_GUI_INTERACT`（L4，`SeToolRequirement.java:122`）+ L3=HIGH（`SeToolRequirement.java:80`，因为点击 slot / 触发按钮会产生服务器可见效果）。GUI 类无独立 CapabilitySid，L5 不额外要求。

---

## 3. C6 — CONTROL-EXEC（`kd/`， 原生 DLL 已建 + 实地验证）

调试器级线程控制：暂停线程、PopFrame、ForceEarlyReturn、断点、单步、读写局部变量、字段修改监视。全部 R-1 + `SE_DEBUG_CONTROL` + `CAP_DEBUG_CONTROL`。

**状态说明**：Java 侧全部就绪（`DebugTools` + `KdBridge` + 事件管道），原生层 `core/src/main/native/core-jvmti/`（`core-jvmti.c`/`.h`/`CMakeLists.txt`/`build.bat` + `build-clang.sh`）也已就位，**`core-jvmti.dll` 已编译并实地跑通**。原来的 MSVC 阻塞已解除：改用 LLVM/clang 的无 MSVC 构建路径（`build-clang.sh`：`winget install --id LLVM.LLVM -e`，然后 `clang -shared`，不需要 Visual Studio / Windows SDK）。一处 `JNI_OnLoad` 绑定修复（natives 在 app-classloader 上下文里绑定，而非只在 `VMInit`）让它真正工作。已在**运行中的 MC 客户端**里验证：`debug_set_breakpoint` 打在 `Minecraft.runTick`、线程 suspend 查找、单步开关都执行了真实 JVMTI。编译产物 `core-jvmti.dll` 被 gitignore。

即便原生层缺席（未用 `-agentpath` 启动），这些工具也不是"广告了却是死的"——每个 handler 先经共享 `guard()`（`kd/DebugTools.java:202`）检查 `KdBridge.isAvailable()`（`kd/KdBridge.java:69`），DLL 缺席时返回诚实的 `isError`（"native debugger not loaded — launch with -agentpath:core-jvmti.dll"），并做 `gate.require(CAP_DEBUG_CONTROL, SE_DEBUG_CONTROL)` 纵深防御。JVMTI onload 能力（PopFrame/断点/字段监视）只能 `Agent_OnLoad` 申请，故必须 `-agentpath` 启动加载。

工具在 `DebugTools.registerAll`（`kd/DebugTools.java:75`）注册，9 个工具名集中在 `DebugTools.TOOL_NAMES`（`:70`）：

| 工具 | 定义 | JVMTI 底层（`KdBridge` 桥接） |
|---|---|---|
| `debug_suspend_thread` | `DebugTools.java:214` | `suspendThread`/`resumeThread`（`KdBridge.java:86`） |
| `debug_pop_frame` | `DebugTools.java:262` | `popFrame`（`KdBridge.java:97`），须先 suspend |
| `debug_force_return` | `DebugTools.java:290` | `forceReturnVoid/Int/Object`（object 强制 null） |
| `debug_set_breakpoint` | `DebugTools.java:329` | `setBreakpoint`（`KdBridge.java:117`），命中发 `DebugEvent`；拒 protected 类 |
| `debug_clear_breakpoint` | `DebugTools.java:352` | `clearBreakpoint` |
| `debug_single_step` | `DebugTools.java:400` | `setSingleStep`（每字节码一个 `DebugEvent`，极高频） |
| `debug_read_local` | `DebugTools.java:431` | `readLocalInt`/`readLocalObject`（须 suspend） |
| `debug_write_local` | `DebugTools.java:484` | `writeLocalInt`（仅 int slot，JVMTI `SetLocalInt`） |
| `debug_watch_field` | `DebugTools.java:522` | `watchFieldModification`（JVMTI `SetFieldModificationWatch`，仅写监视）；拒 protected 类 |

---

## 4. 全部已注册 MCP 工具（按 Ring 分组）

以下为工具名 → Ring 的权威映射，来源 `Ring.BUILTIN_RINGS`（`se/Ring.java:86`，条目数以该表为准）。注意：**"内建工具总数" ≠ `BUILTIN_RINGS` 条目数**——个别工具（如 `dev_probe`）以 `builtIn=true` 注册但不在该表内，经 `Ring.forBuiltin` 回退默认 ring；易变的工具总数以 `STATUS.md` 为准。C6 九个工具即便未用 `-agentpath` 启动（原生层缺席）也**已注册且可调用**（返回诚实错误）。

**R-1 HYPERVISOR（在游戏 JVM 内运行/重写任意代码；最危险）**
- Phase 1 既有：`eval_java`、`redefine_class`（C4）
- C3：`install_hook`
- C5：`write_field`、`invoke_method`、`open_module`
- C7：`eval_ephemeral`
- C8：`seam_netty_install`、`seam_glfw_key_hook`、`seam_glfw_mouse_hook`、`seam_tick_enable`
- C6：`debug_suspend_thread`、`debug_pop_frame`、`debug_force_return`、`debug_set_breakpoint`、`debug_clear_breakpoint`、`debug_single_step`、`debug_read_local`、`debug_write_local`、`debug_watch_field`

**R0 KERNEL（修改 agent 自身工具集 / 卸载自修改类接缝 / 自管理特权）**
- Phase 1 既有：`create_tool`、`rollback_tool`
- C3：`uninstall_hook`
- C5：`read_field`
- C8：`seam_netty_uninstall`、`seam_tick_disable`
- L4/L5 自管理（`PrivilegeControlTools`）：`enable_privilege`、`disable_privilege`、`grant_capability`、`revoke_capability`
- L6 句柄（仅 `-Dmcp.core.handles=true` 时注册）：`debug_open_thread`、`debug_close_handle`

**R1 SYSTEM（对游戏/网络的外向效果）**
- `send_raw_packet`、`send_chat`
- C9 GUI：`gui_click_element`、`gui_type_text`、`gui_press_key`（`SE_GUI_INTERACT` + L3=HIGH）

**R2 OBSERVE（在游戏线程上读活游戏/GL 状态）**
- `scan_surroundings`、`capture_screen`、`read_player_state`
- C9 GUI：`gui_snapshot`、`gui_snapshot_image`（SoM 标注截图，+ `SE_SCREEN_CAP`）
- `dev_probe`（连接/世界在场 + GL 上下文诊断；经 `forBuiltin` 回退，不在 `BUILTIN_RINGS` 表内）

**R3 USER（本地只读 / 记账；最安全）**
- 感知/报告：`recent_packets`、`disconnect_report`
- 元工具：`list_capabilities`、`get_tool_source`
- 记忆：`memory_write`、`memory_search`、`memory_delete`
- 叙事/目标：`set_goal`、`push_subgoal`、`complete_goal`、`narrate`、`get_story`
- 权限：`drop_privilege`、`restore_privilege`、`list_permissions`
- C1 INTROSPECT：`list_classes`、`describe_class`、`find_method`、`list_hooks`
- C9 GUI：`gui_trajectory`（操作轨迹，R3）
- Compat：`list_compat_patches`（只读列出启动期补丁，见 `07-COMPAT-SHIM.md`）

> 注：R3 里若干工具虽 ring 最低，但仍受 L3/L4/L5 约束（如 `memory_write` 写 LOW 完整性 + `CAP_STORE_WRITE`，`send_chat` 虽在 R1 但要 `SE_NET_RAW` + `CAP_NETWORK_SEND`）。ring 只是七层之一，完整要求见 `SeToolRequirement.forTool`（`se/SeToolRequirement.java:44`）。
>
> **AI 生成工具的门控（CRITICAL#1 修复）**：AI 通过 `create_tool` / eval 运行时新造的工具现**无条件**落在 R-1（`Ring.DEFAULT_GENERATED = R_MINUS_1`，`Ring.java:69`）+ `SE_RUN_GENERATED` + `CAP_TOOL_CREATE`——因为生成的 Java 在本进程内运行，可触及任何 R-1 能力（Instrumentation / Unsafe / 反射），与 `eval_java` 同等危险。`SeToolRequirement.forTool` 对 `!builtIn` 直接返回该最大门（`SeToolRequirement.java:52`），不经 by-name side-table，堵住"未列名的生成工具无门"的环模型坍塌。（`CapabilityCatalog.DEFAULT_GENERATED`，`CapabilityCatalog.java:83`，仅作为 by-name 缺省的 observe-tier 兜底，被上面的无条件门覆盖。）
