---
doc: tool-annotation-convention
title: MCP 工具标注规范 — 让 LLM 更懂每个工具
layer: reference
status: authoritative
updated: 2026-07-13
parent: ../README.md
next:
  - path: ../architecture/02-CAPABILITIES.md
    when: 你要看每个工具的 ring/privilege/capability 门控(标注与门控对齐)
  - path: ../architecture/01-SECURITY-KERNEL.md
    when: 你想懂"漏登记 = 缺省放行"这个陷阱的机制(§2 安全默认)
read_if: 你要新增/改一个 MCP 工具。**先过 §0 门控登记清单(安全),再按本文填标注(可用性)**。所有工具遵循本规范。
---

# MCP 工具标注规范 + 加工具清单

> **一句话**:加一个工具要做两件事——**①把它登记进安全门控表(§0,漏了会静默失守)**;
> ②填 MCP 协议标准的 `ToolAnnotations`(4 个行为 hint)+ 固定标记声明前提,让 LLM 看懂它的行为属性。
> 标注方法学自成熟宿主-app MCP(ida-pro-mcp / unity-mcp / blender-mcp / codegraph),但**我们比它们都严格**——它们都没填这套标准 hint。

## 0. 门控登记清单(加工具第一件事,漏一张 = 那层门静默失效)

工具的门控**不写在工具里**,而是运行时**按工具名**去几张旁表查。
**关键陷阱**:表里查不到你的名字 = 那层门**缺省放行**,不报错、不告警(见 `01-SECURITY-KERNEL.md` §2)。
所以"忘了登记"的表现不是编译失败,而是**一个看起来正常、实则少一道锁的工具**。

| 层 | 表(权威位置) | 管什么 | 漏登记的后果 |
|---|---|---|---|
| L2 | `se/Ring.java` → `BUILTIN_RINGS` | 要多高权限才能调 | 回退 R3 USER(最低权限即可调) |
| L3 | `se/SeToolRequirement.java` → `L3_WRITES` | 写的资源多要紧 | 无完整性门(视作只读) |
| L4 | `se/SeToolRequirement.java` → `L4_PRIVILEGE` | 要开哪个特权开关 | **无特权门 → `disable_privilege` 关不掉它** |
| L5 | `se/CapabilityCatalog.java` → `REQUIRED` | 要持有哪个能力 SID | 内建工具→无能力要求 |
| **L6** | `ob/ObManager.java` → `HANDLE_OPS` | 该 handle 操作要 READ/WRITE/EXECUTE | **两条静默路径,见下** |

**L6 单列说明(最容易漏,因为它不在 `SeToolRequirement.forTool` 里)**:前 4 张由 `forTool` 一处组合,
读那个方法只会数出 4 张;**L6 的表在完全另一层**(`ObManager.checkHandle`)。只要你的工具**按句柄操作对象**
(如 `debug_*` 族),就必须登记 `HANDLE_OPS`,漏了有两条静默失效路径(`ObManager.java:185-201` 实证):
- **不传 handle**:hardened 姿态(`-Dmcp.core.hardened=true`)本该拒绝无句柄调用,但 `HANDLE_OPS.containsKey(name)`
  为 false → 直接 `allowed()` **放行**;
- **传了 handle**:`HANDLE_OPS.getOrDefault(name, READ.bit())` → 你的**写/执行**操作按 **READ** 权限过门。

**外加第 6 个登记点(不是门,但测试强制)**:handle-op 工具的 `inputSchema` **必须声明 optional `handle` 属性**
(`L6DebugGateThroughRegistryTest` 强制);反过来,`"handle"` 这个 arg key **是保留字**——非 handle 工具不许声明。

> **`ToolRegistry` 里注册**不是门,是**暴露**——把工具给 LLM 看见。别把它数进门控表里。
> **两次真事,同一种病**:
> - 2026-07-15b:W6 的 4 个 `send_*` 把"注册"当成第 4 张表,L4 一个没填 →
>   `disable_privilege(SE_NET_RAW)`(专门关发包面的开关)**关了照发**。
> - 同日:本清单初版**自己**写"共 4 张表",因为作者从 `forTool` 数出 4 张、不知道 L6 另有一张 →
>   **一份专门防这个 bug 的文档,犯了同一个 bug**。(由 Fable 5 以"新 AI 视角"只读审计抓出。)
> **教训**:数表别读 `forTool`,读这张表;**加新门层的人,有责任回来更新这张表**。

**同族工具照抄同族的值**,别自由发挥。已确立的族:

| 族 | L2 | L3 | L4 | L5 | L6 |
|---|---|---|---|---|---|
| 发包(`send_*`) | R1 | HIGH | `SE_NET_RAW` | `CAP_NETWORK_SEND` | 不适用 |
| GUI 交互(`gui_click/type/press`) | R1 | HIGH | `SE_GUI_INTERACT` | `CAP_WORLD_WRITE` | 不适用 |
| 任意码(`eval_java`/`eval_ephemeral`) | R-1 | SYSTEM | `SE_CREATE_TOOL` | `CAP_TOOL_CREATE` | 不适用 |
| 只读观测(`packet_view`/`world_view`) | R2/R3 | 无 | 无 | 读类 CAP 或无 | 不适用 |
| **句柄操作(`debug_*`)** | R-1 | SYSTEM | `SE_DEBUG_*` | `CAP_DEBUG_CONTROL` | **`HANDLE_OPS` 必填 + schema 声明 `handle`** |

**守卫(现有,别绕过)**:`PolicySideTableDriftTest` 守着这些不变式——包括反向的
`everySendToolWritingAtHighDeclaresTheNetPrivilege`(L3 声明了危险、L4 却漏了)。
**加新族时,同时加一条守它的反向不变式**;否则下一个人漏登记时没有任何东西会挂。

**自检**:登记完,把 L4 那条**临时删掉跑测试**——必须挂。不挂 = 你的测试没咬合(见
`cc-workflow-guide.md` §6"非空转 ≠ 正确")。

## 前提:SDK 支持(已验证)

MCP Java SDK **2.0.0** 的 `Tool.Builder` 支持 `.title()` / `.annotations(ToolAnnotations)` /
`.outputSchema()` / `.meta()`。`ToolAnnotations.builder()` 有:`title` / `readOnlyHint` /
`destructiveHint` / `idempotentHint` / `openWorldHint` / `returnDirect`(全 `Boolean`,可空)。

## 四个标准 hint(MCP 协议钦定的"风险词汇表")

| hint | 含义 | 默认(不填时) |
|---|---|---|
| `readOnlyHint` | true=不修改环境(纯读/查) | false(假设会改) |
| `destructiveHint` | true=可能破坏性/不可逆更新;false=仅增量/可逆。**仅 readOnly=false 时有意义** | true(假设破坏) |
| `idempotentHint` | true=同参重复调用无额外副作用。**仅 readOnly=false 时有意义** | false |
| `openWorldHint` | true=与外部实体交互(网络/外部系统);false=自包含闭域 | true |

> **不要依赖默认值**——默认是最坏假设(可破坏/开放世界)。每个工具**显式填**,即使填的就是默认值,
> 也是一次诚实的声明。客户端据此:只读工具自动放行、破坏性工具要确认、幂等工具可安全重试。

## readOnly 的判定尺度(关键,每个工具都要过一遍)

这是最容易标错的一条。判据分三档:

- **纯观测 → `readOnly=true`**:只读游戏/系统状态,不改任何东西。如 `read_player_state`、
  `scan_surroundings`、`list_classes`、`gui_snapshot`。
- **非破坏性的环境修改 → `readOnly=false, destructive=false`**:改了运行时环境但可逆、不毁数据。
  典型:装/卸一个观测 seam(`seam_netty_install` 改了 Netty pipeline 但不改 wire bytes/游戏状态,
  且能干净卸载)。**它不是 readOnly**——改了 pipeline;但**不是 destructive**——可逆。这一档最微妙。
- **破坏性/不可逆 → `readOnly=false, destructive=true`**:改游戏状态、发不可撤销的动作、改代码。
  如 `write_field`、`send_raw_packet`、`eval_java`、`redefine_class`、`debug_pop_frame`。
  这一档应与 7 层门的 R-1 顶格 gate 对齐(destructive 的基本都是危险工具)。

> 尺度原则:**改没改环境**决定 readOnly;**可不可逆**决定 destructive。二者独立。
> "装了个观测器"改了环境(readOnly=false)但可逆(destructive=false)——这是本项目 seam 类的共性。

## idempotent / openWorld 的判定

- `idempotent`:重复调用同参数,第二次是不是 no-op?装 seam(已装则跳过)= true;发聊天/丢物品 = false;
  teleport 到定点 = true;相对移动 = false。
- `openWorld`:碰不碰外部?观测/发网络封包、连服务器 = true;纯游戏内 JVM 自包含(读字段、改本地状态)= false。

## 前提(preconditions):description 固定前缀标记

MCP 协议**没有** precondition 字段。用 description 开头的固定标记补上,LLM 可 pattern-match:

```
[requires: <条件>, <条件>] <正常描述……>
```

常见条件词(统一用词,别自由发挥):`connected-to-server` · `in-world` · `not-paused` ·
`-javaagent`(需 Instrumentation)· `-agentpath`(需 native DLL)· `player-alive` · `GLFW-window`。

**并且**:前提不满足时,handler 返回的错误串要**同样点明前提**,让 LLM 自我纠正
(如 `"not in a world — join a world first"`)。这直接治"装了但环境不满足就静默变死"的坑(见 KI-9)。

## title

给一个人类可读短标签(`.title()` 和 annotations 的 title 都填,取一致)。如 `seam_netty_install` →
"Install packet observer (Netty tap)"。用于客户端显示,优先级高于 name。

## 命名(不变)

保持现有 `pack_verb` snake_case(`seam_*`/`gui_*`/`debug_*`/`memory_*`…)。pack 前缀 = 能力包分组,
帮 LLM 归类 + 消歧。60+ 工具下扁平命名会拖累选择,前缀是刻意设计,别改。

## 样板(seam_netty_install,已落地)

```java
Tool.builder()
    .name("seam_netty_install")
    .title("Install packet observer (Netty tap)")
    .description("[requires: connected-to-server, -javaagent] Install the built-in "
            + "Netty packet observer ... observes but never mutates. ... Idempotent.")
    .inputSchema(schema(Map.of(), List.of()))
    .annotations(ToolAnnotations.builder()
            .title("Install packet observer (Netty tap)")
            .readOnlyHint(false)      // 改了 pipeline(但不改 wire/游戏状态)
            .destructiveHint(false)   // 可干净卸载、可逆
            .idempotentHint(true)     // 重复装是 no-op
            .openWorldHint(true)      // 观测外部服务器连接
            .build())
    .build();
```

## 相关
- 门控对齐:`../architecture/02-CAPABILITIES.md`(ring/privilege/capability)· `01-SECURITY-KERNEL.md`
- 写文档规范:`doc-style-guide.md` · 已知"装了但环境不满足就死"的坑:`../project/known-issues.md` KI-9
