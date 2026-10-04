---
doc: packet-exposure-tools
title: 全包 MCP 暴露(105 顶层 Packet) — 工具面 + 安全门控(已实现 W1-W6)
layer: reference
status: living
updated: 2026-07-15b
parent: PROGRESS.md
read_if: 你要改/扩全包 MCP 暴露(packet_view + typed send_* 工具);本文记已落地形态与 defer 项。
---

# 全包 MCP 暴露(105 顶层 Packet) — 工具面 + 安全门控(**W1-W6 已实现**,HEAD `98fad76`)

> **实现状态**:本文原为草案,W1-W6 已落地。**与草案的差异已在下方标注**——以代码为准。
> dwgx 定的形态:**分层混合**。观测 = 结构化 `packet_view`(A structured / B 类别 / C generic)+ 现有 packets_tail/packet_get 全部 105 覆盖。操控 = typed `send_*` 工具 + 现有 send_raw_packet 兜底。
> 逐包摘要器规格见 `PACKET-EXPOSURE-BLUEPRINT.json`(107 spec)。实现细节见 PROGRESS 笔记 2026-07-15b(续)。

## 现状(已读代码,不重复暴露)

- **Ring 模型**:R-1 hypervisor(任意码)/ R0 kernel(改自身工具)/ **R1 system(对外·网络效果)** / R2 observe(游戏线程读)/ R3 read-only。
- **已有、不重造**:移动/看/挖/交互 = `act_set`(R1);聊天 = `send_chat`(R1);GUI = `gui_click_element`/`gui_type_text`/`gui_press_key`(R1);任意包 = `send_raw_packet`(R1)。
- **发包底座**:`ActionManager.sendRawPacket(Packet)` 已在游戏线程 marshal + 检查 channel open。typed 工具只需构造 typed Packet 再交给它。
- **观测底座**:PacketJournal(reference-free ring)→ `packets_tail`/`packet_get`(R3)。摘要器在 Netty 线程同步跑,产 String(`summarize`)**+ 结构化 kv(`project`,W1 起)**,两者都 reference-free。

## 观测面:packet_view(新增 1 个 **R3** 工具)

| 字段 | 设计 |
|---|---|
| name | `packet_view` |
| ring | R3(纯读 journal 投影,不碰游戏线程) |
| capability | 无(读) |
| args(**实际**) | `limit?`(默认 50)、`dir?`(IN/OUT)、`class?`(类名子串)、`sinceSeq?`(增量轮询) |
| 输出(**实际**) | 结构化 JSON:`{count, tickNow, tapInstalled, entries:[{seq, tickId, dir, class, simpleName, fields:{...}}]}`。**只含有 typed 投影的包**(A 层 + B 层);无 fields 的包被略过(去 packets_tail 看)。 |
| 与现有关系 | packets_tail 给 String 摘要行(全部 105 覆盖);packet_view 给**结构化 typed**投影,LLM 不用 parse 字符串。二者共用 PacketJournal。 |

> **实现**:摘要器同时产 String(`summarize`)和 kv(`project`),**两者都在 tap 时同步算好**(reference-free:PacketView 是不可变拷贝),structured map 存进 `MessageSnapshot`→`PacketJournal.Entry.fields`。packet_view 只是读它 = 单一真相,不反解字符串。

## 控制面:typed send_* 工具(全 R1 + CAP_NETWORK_SEND + L3 HIGH + **L4 SE_NET_RAW**)

> **铁律(4 个维度,一个都不能漏)**:发包=WRITE=对外效果,必须与 send_raw_packet/send_chat 完全同级 ——
> **L2 Ring=R1** · **L3 IntegrityLevel.HIGH** · **L4 Privilege.SE_NET_RAW** · **L5 CAP_NETWORK_SEND**。
> **L4 最容易漏且后果最重**:`disable_privilege(SE_NET_RAW)` 是专门关掉发包面的开关,漏了 L4 = 关了也照发。
> W6 初版就漏了这条(4 个工具无 L4),`PolicySideTableDriftTest.everySendToolWritingAtHighDeclaresTheNetPrivilege` 现在守着它。
> 危险/罕见包不做 typed,留给 send_raw_packet 兜底。

### 已实现(W6,`1cc0829`)—— 4 个,命名用 `send_` 前缀(owner 定)

| 工具名 | 发的包 | 用途 | args |
|---|---|---|---|
| `send_client_status` | C16PacketClientStatus | **重生**(PERFORM_RESPAWN)/请求统计/开成就 | status |
| `send_held_item` | C09PacketHeldItemChange | 切换手持热键栏槽(0-8) | slot |
| `send_close_window` | C0DPacketCloseWindow | 关闭容器 | windowId |
| `send_dig` | C07PacketPlayerDigging | 挖掘 start/stop/abort、丢物、松手 | status, x, y, z, face |

全部共享 `ToolRegistry.sendTyped()` → `ActionManager.sendRawPacket`(**自动过 W5 的 board veto**),统一报 `vetoed:` / not-connected / success。

### DEFER(需游戏对象,只能 live 验)—— 6 个

**真需要活游戏对象(理由成立)**:`use_entity`(C02,ctor 要 `Entity`,LLM 只有 entityId 得在游戏线程按 id 反查)· `entity_action`(C0B,同样要 `Entity`)。

**理由较弱(可以做,只是没做)**:`place_block`(C08)/ `click_window`(C0E)/ `creative_set_slot`(C10)—— ctor 要 `ItemStack`,但仓库现有测试就用 `null` stack headless 构造过;真正的难点是让 LLM 表达"哪个物品"(要 item id → ItemStack 解析)。`player_abilities`(C13)—— **原写"要 PlayerCapabilities"是错的**:它有 public 无参 ctor + `setFlying/setAllowFlying/...` setters,`PlayerCapabilities` 也是有默认 ctor 的 POJO,**完全能 headless 测**;真正该斟酌的是别发出自相矛盾的能力状态(应先读当前 capabilities 再改一位)。

**现全部走 `send_raw_packet` 兜底。** 下会话候选:C13 直接做(最简单);C02/C0B 做"游戏线程按 id 解析"的小机制 + live 验。

**危险包不做 typed(留 send_raw_packet)**:C0FConfirmTransaction(协议内部握手)、C15ClientSettings(连接期配置)、C17CustomPayload(任意 channel,注入面)、C11EnchantItem(边角)。**已被现有工具覆盖不重造**:移动/看/挖(`act_set`)、聊天(`send_chat`)、GUI(`gui_click_element` 等)。

## 安全对抗视角(有罪推定)

- **发任意包给服务器 = 可作弊/可被服务器反作弊踢**。typed 工具比 send_raw_packet **更安全**——它们构造合法的 typed Packet,不能拼出畸形字节;但仍是 WRITE,必须 R1 门控,clearance 降级时应锁死。
- **fail-safe**:每个 typed 工具在 `player()==null` 或 `!channel.isChannelOpen()` 时返回明确 error,不静默。复用 ActionManager 的 game-thread marshal(已有 channel 检查)。
- **不给 typed 的包**:协议握手/加密/压缩/资源包状态(C0F/C01login/C19/C46 等)——LLM 手发只会破坏连接,无正当用途,留 send_raw_packet 且默认降噪。
- **board veto 一致性 → 已解决(W5)**:veto 挂在 `ActionManager.sendRawPacket`(最底层),所以 typed send_* **和** send_raw_packet **全部**过 `PacketSendSignal`。连接检查在 veto 之前(不连接时不发假 veto)。

## 4 张 gate 表(**W6 已登记**;扩新工具照此加,主 agent 串行改绝不并行)

每个发包工具要同时在 4 处登记(实测值,已核实):
1. **ToolRegistry.all()** — 注册 spec + handler(经 `sendTyped()` 调 ActionManager)。
2. **Ring.java BUILTIN_RINGS** — 发包工具 = **R1**;`packet_view` = **R3**。
3. **CapabilityCatalog** — 发包工具挂 **CAP_NETWORK_SEND**(**修正**:草案原写 CAP_WORLD_WRITE,实测 send_chat/send_raw_packet 用的是 CAP_NETWORK_SEND;CAP_WORLD_WRITE 是 gui_*/act_* 用的)。
4. **SeToolRequirement L3_WRITES** — 发包工具挂 **IntegrityLevel.HIGH**。
5. **SeToolRequirement L4_PRIVILEGE** — 发包工具挂 **Privilege.SE_NET_RAW**。**这才是第 4 张 gate 表**(ToolRegistry 注册不是 gate,是暴露)。

**守卫**:`PolicySideTableDriftTest` 三条老断言全是 L3/L4 → Ring 方向(查不到"L3 有、L4 漏"),故新增反向不变式 `everySendToolWritingAtHighDeclaresTheNetPrivilege`;`SendToolsW6Test` 断言 4 个 send_* 在**四**个维度齐全 + 每个已注册 send_ 工具都有 SE_NET_RAW。
**既存缺口(非本波引入,待 owner 定)**:`act_set`/`act_cancel` 是 HIGH 写入者却无任何 L4 特权,同类问题。

## owner 已定(全部落地)

1. **packet_view 的 typed fields 怎么来** → **升级 SPI**(方案 a,最高质量):`PacketSummarizer.project()` default null + `PacketView` kv + registry `projectStructured()`。A 层填 kv,B 层给中等投影,C 层不填。既有摘要器零改动。
2. **typed 发包过 board veto** → **是**。W5 `PacketSendSignal` 落地在 `ActionManager.sendRawPacket`,所以**每一个**出站发包(typed + raw)都过 veto。
3. **命名** → **统一 `send_` 前缀**(跟 `send_chat`/`send_raw_packet` 家族)。**不用 `act_`**——那是 ActRuntime 的连续状态机(移动/看/挖),性质不同不混。
4. **journal 存不存结构化 map**(W4 实现时冒出,owner 授权我抉择)→ **存**。tap 时算好写进 `MessageSnapshot`+`Entry`,单一真相;ring 有 1024 上限,内存可控。
5. **packet_view 无 `tier` 参数**(实现时发现):只存 fields 时 tier 冗余(有 fields=A 层)且无法诚实服务 B/C → **不做假参数**,改用 `class` 子串过滤。

