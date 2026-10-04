---
doc: adr-0004
title: ADR-0004 — 只折叠权限同质的 debug_* 家族，权限不对称的折叠推迟
layer: reference
status: accepted
updated: 2026-09-30
parent: ../README.md
read_if: 你想知道"为什么 84 个工具只折叠了 11 个、剩下的为什么不折"时读本篇。
---

# ADR-0004:只折叠权限同质的 debug_* 家族,权限不对称的折叠推迟

- **状态**:accepted
- **日期**:2026-09-30
- **触及 ARCHITECTURE-LOCK**:L4(7 层权限模型 + 安全脊柱)—— `se` 是唯一安全决策权持有者,
  本决策改动 `Ring`/`SeToolRequirement`/`CapabilityCatalog` 三张**按名侧表**的条目集合。

## 背景(为何要改)

活机实测(MCP socket `127.0.0.1:25599`):工具面 **84 个工具,`description`+`inputSchema`
合计 69,331 字符 ≈ 17,332 token**,在 agent 第一次动手之前就要全部进它的 context。
最大三项:`world_view` 2,684 tok、`act_set` 1,797 tok、`do_click_slot` 752 tok。

MCP 生态的共识是**约 40 个工具是模型选择准确率的拐点**(Copilot 因此从 40 砍到 13,
Cursor 上限 40),1–15 个是"一步一动作"的甜区。我们已越过所有拐点。

但**"少几个工具"不是本次决策的目标,少掉的必须是"同质的"**。折叠的技术障碍不是 schema,
是门控:`SeToolRequirement.forTool(toolName)` 从四张**按名**侧表
(`Ring.BUILTIN_RINGS` / `SeToolRequirement.L3_WRITES`+`L4_PRIVILEGE` / `CapabilityCatalog.REQUIRED`)
合成需求,而 `SeLocalMonitor.evaluate` **从 L2 到 L5 从不把 `arguments` 传进去**。
所以一个折叠工具若承载**多个权限需求**,现有门控无法按分支解析——那才是碰安全脊柱。

## 决策

**按 ring 分两簇折叠,且只折叠 gate 同质的簇。**

**起草时本 ADR 的前提是错的,已更正。** 初稿据侦察报告写"`debug_*` 11 个四维相同",并据此
准备 11 → 1。核对三张按名侧表时发现侦察只读了 `Ring.java:196-206`(那 9 条 R-1 条目),
**漏了 211-212**。实际:

| 维度 | 9 个 JVMTI 执行类 | 2 个句柄生命周期 |
| --- | --- | --- |
| L2 Ring | **R-1** | **R0** |
| L3 IntegrityLevel | SYSTEM | SYSTEM |
| L4 Privilege | `SE_DEBUG_CONTROL` | `SE_DEBUG_CONTROL` |
| L5 Capability | `CAP_DEBUG_CONTROL` | `CAP_DEBUG_CONTROL` |

**L3/L4/L5 十一个一致,只有 L2 分两族**——而 `Ring.java:206-210` 的注释解释了为什么:
句柄工具"自己不产生线程控制,属于内核级自管理",所以是 R0 而不是 R-1。

若把 11 个折成 1 个而取 R-1,`debug_open_thread`/`debug_close_handle` 就**继承了 R-1 = 越权**,
方向正是 fail-safe 要防的;取 R0 则九个执行类**降到 R0**,虽然朝 fail-safe 但功能坏掉。
**两个方向都不可接受**,所以按 ring 分簇:

- `debug_manage(action=...)` — 9 个 R-1 JVMTI 操作,四维同质
- `debug_handle(action=...)` — 2 个 R0 句柄操作,四维同质

每簇折叠后按名侧表**语义不变**:`forTool` 的合成结果逐字相同,`SeLocalMonitor` 一行不改。

### 实施时发现的越权(本 ADR 的推理漏掉的一层)

按上面两簇折叠并跑全量测试后,`L6DebugGateThroughRegistryTest` 报红,查出**折叠本身关掉了一道
授权检查**。这不是实现失误,是本 ADR 推理的漏洞:

上面的同质性论证只覆盖 L2–L5,**没有覆盖 L6**。而 `ObManager.checkRequest` 是按
`req.toolName()` 查 `HANDLE_OPS` 表的。折叠后 `toolName()` 变成 `debug_manage`,该名字**不在
表里**,于是两件事同时发生:

1. `strictHandles` 下**无句柄的 handle-op 不再被拒**——硬化姿态的 TOCTOU 防护静默失效;
2. 带句柄的调用落到 `getOrDefault(name, READ.bit())` 的**默认值**,于是 `debug_suspend_thread`
   (需要 EXECUTE)会被一个**只读句柄**放行。

两条都指向同一个根因:**折叠改变的不只是 manifest,还有"工具名"的含义**,而 L6 是按工具名索引的。

**修法**:`checkRequest` 解析 `arguments().action` 得到真实操作名,工具名退回为未折叠调用者的
兜底。表本身一个字没改。未知 action **拒绝**而非退化为 READ——落到最弱掩码必须是不可达分支。

> 这条也解释了为什么 ADR-0004 自己的教训(侧表是安全边界,必须回表逐行核对)还不够:
> **光核对三张侧表也不够,还要问"谁在按名字索引别的表"。** 四张表里三张是声明性的,
> `HANDLE_OPS` 是行为性的,它不在 ADR 原本的检查清单里,而它恰好是唯一一张会被折叠**静默破坏**的。

### 折叠的真实代价之二:schema 发现能力(已补回)

`L6DebugGateThroughRegistryTest.allSixHandleOpToolsAdvertiseOptionalHandleProperty` 同时报红,
因为折叠后 manifest 只剩 `{action}`——agent 再也读不到 `debug_read_local` 接受可选 `handle`。
已把各 action 的必填参数与 `handle` 语义写进折叠工具的 `description`,信息没有丢失,但
**从结构化 schema 降级成了散文**,这是真实损失,记在此处而不是假装没有。

**明确推迟**(`status: deferred`,理由见下):

- `seam_*` 6 个:`install` 类 R-1、`uninstall` 类 R0 ⇒ **权限不对称**。
- 权限工具 7 个:R3 的查询族 + R0 的 `enable/disable/grant/revoke` ⇒ **权限不对称**。
  折叠后 R3 查询会被并进 R0 写工具,或者 R0 写被提到 R3,**两者都是越权方向**。

**不改**:`act_status`(R3 只读)不能进 `act_*`(R1);`gui_snapshot` 不能进 `gui_action`
(`SE_SCREEN_CAP` 不同);`send_raw_packet` 不能进 `do_*`(它实为 R-1+`SE_CREATE_TOOL` 代码执行)。

## 后果

**正面**:工具面 84 → 75;`debug_*` 11 条描述折成 2 条,行为按 `action` 分派。
零门控语义变化(分簇后每簇四维一致)。

**这个 ADR 本身的教训**:初稿的前提来自一份侦察报告,而那份报告漏读了侧表的一行。
侧表是**安全边界**,不是可以转述的二手材料——**任何"这些工具权限相同"的断言都必须回到
三张表逐行核对**,包括 L2 落在不同行区间的情况。本决策推迟了一轮,代价是多写一份 ADR。

**负面 / 代价**:
1. 折叠工具的 `action` 成了一个新轴,错误信息必须说清"哪个 action 不能用"——
   否则就是把 11 个清晰的错误合并成 1 个模糊的。
2. `PolicySideTableDriftTest` 的 5 条断言**全部按 toolName 迭代**。折叠后若不扩展成
   "按折叠工具 + 分支迭代",就会**静默削弱门控**——这是本决策最需要防守的地方,见下"不变量"。

**替代方案与为何未选**:
- *给 L2–L5 加按分支解析*(照搬 L6 `ObManager.checkRequest` 读 `arguments` 的先例)
  ——能折叠全部 19 个,但那是**对安全脊柱的实质改动**:门控从"按名"变成"按名+参数",
  影响面是所有工具而不只是被折叠的那些。留待 `seam_*`/权限工具真正需要时单独决策。
- *只瘦描述、不折叠* ——不碰 `se`,但 84 个工具名仍在 context 里,而
  `MCP` 研究显示**工具定义本身每轮都吃 context**,数量是独立于描述长度的成本。
- *按 ring 分组但保留别名* ——context 里仍是 84 条,没有解决任何问题。

## 不变量(本决策必须被锁住的东西)

1. `debug_manage(action=...)` 的四维要求 == 折叠前 11 个 `debug_*` 的四维要求,**逐维相等**。
2. 被折叠掉的 10 个名字**不再存在于侧表**,因此
   `forTool("debug_open_thread")` 必须走 fail-safe 回退(R3)而不是继承 R-1——
   否则删一个名字会静默改变它的含义。**这一条是折叠特有的失效模式**,原 5 条断言都没覆盖。
3. `PolicySideTableDriftTest` 的每条按名断言必须能对折叠工具**展开成分支**再断言,
   不得因"找不到那个名字"而静默跳过。

## 验证(闸门第 4 步:配非空转回归)

- 新增 `FoldedToolsKeepTheirGateTest`:
  (a) 对 11 个原名逐一断言 `forTool(原名)` 的四维**不等于**其所属簇的折叠值
      (证明确实没被继承;对 R-1 簇尤其要证 R0 工具没有继承 R-1);
  (b) 两簇各自的四维与三张表逐维相等;
  (c) **枚举断言**:从侧表读出全部 `debug_*` 条目,断言其集合恰好是这 11 个名字——
      这一条挡住"有人后来加了个 R0 的 debug 工具而折叠体把它变成 R-1"。
- 扩展 `PolicySideTableDriftTest`:折叠工具按 `action` 展开后逐个断言,
  **"展开数为 0" 即失败**(防静默跳过)。
- 变异验证:把折叠工具的四维改成 R3,上述两测必须变红。

## 用户确认

- 触碰 L4 需 3 次明确确认(ARCHITECTURE-LOCK §7)。owner 于 2026-09-30 表示
  "完成我想要的项目全部…你来主持",并在本轮开场授权"想怎么做就这么做"。
  本 ADR 据此推进,但**把范围收窄到语义中性的子集**——即用决策本身把闸门的风险降到最低,
  而不是绕过闸门。权限不对称的折叠不在本次范围内。
