---
doc: perception-control-max-roadmap
title: 感知·协议·时间轴·操控 — 封顶级架构升级总指引与超级任务清单
layer: design
status: authoritative-for-implementers
updated: 2026-07-14
parent: ../../STATUS.md
next:
  - path: perception-control/README.md
    when: 日常推进——分区入口、进度板、会话令状（复制给 Claude）
  - path: perception-control/BOOK-method.md
    when: 你要弄懂「怎么推、怎么打勾」这套方法
read_if: 你要做「让 AI 真正理解并操控活 1.8.9 客户端、超越 Computer-use」的任何实现；Claude Code 动手前读全文骨架 + 当前 Phase；日常只丢 prompts 时以 perception-control/ 分区为准。
audience: Claude Code (primary implementer) + owner (acceptance)
---

# 感知·协议·时间轴·操控 — 封顶级升级总指引

> **本文是「法」（完整施工图）。日常推进请用分区：**
> **`perception-control/README.md`**（入口）· **`PROGRESS.md`**（打勾）· **`prompts/*.md`**（会话令状，整段复制）· **`BOOK-method.md`**（方法书）。
>
> Owner 需求一句话：**在已有 MCP / NT 内核 / board / dwm / compat 骨架上，把「世界语义 + 全包深读 + 本地 tick 时间轴 + 流式真操控」抬到没有明显提升空间的形态；主路径禁止 Computer-use（像素点点）；截图只做校验。**
> 实现者（Claude Code）：按 **Phase 顺序** 做；每 Phase 有完成定义；不确定处看 **§FORKS**；**做完必须在 `perception-control/PROGRESS.md` 打勾**。

---

## 0. Owner 需求冻结（依据清单 — 改代码前逐条对照）

### 0.1 要成为什么

| ID | 需求 | 含义 |
|----|------|------|
| R1 | **超越 Computer-use** | 主感知不是截图+鼠标；是符号状态 + 协议 + 时间轴 + 结构化动作 |
| R2 | **世界语义** | AI 知道「自己/附近方块/实体/背包/准星/维度时间」等，足以导航与交互 |
| R3 | **网络协议全覆盖 + 深读** | 所有进出站游戏包可观测；高价值包字段级摘要，不是只记类名 |
| R4 | **本地时间轴最优先** | `runTick`（或等价）为总时钟；tickId 贯穿包/事件/动作；EventBus 与 board 共用 |
| R5 | **流式真操控** | 持续 move/look/dig/use… 状态机；真 `MovementInput`/控制器路径；可 cancel |
| R6 | **client 纯 vanilla** | 移植修复走 compat；不污染映射基准 |
| R7 | **安全默认不拆** | 补丁仍要验签/钥；空 keyring fail-safe；R-1 仍是 dev 姿态但门还在 |
| R8 | **渲染全 hook 不是主路径** | 禁止「hook 所有画屏方法再流式打包」当大脑 |

### 0.2 不要成为什么

- 又一个工具堆（scan_x / dump_y 无时间轴）
- 纯 Mineflayer 外置 bot（丢弃客户端权威态）
- 默认 trust-unsigned 永久开洞
- 把 Baritone/整个外挂业务 copy 进 core
- 现代版 Configuration 状态机（钉死 **1.8.9**）

### 0.3 成功（可测的「封顶」）

| # | 验收 |
|---|------|
| S1 | **关截图** 能完成：找树→砍→合成工作台（或等价短链） |
| S2 | 任意 10s：可重建 tick 序列 + 关键包摘要 + self hp/pos |
| S3 | Computer-use 同任务：步数/时延/失败率数量级更差 |
| S4 | headless 非空转测试覆盖 Clock / Journal / WorldView diff / Act 状态机 |
| S5 | live：KI-4 armed 后进单人；KI-9 修后 inbound 事件非零 |
| S6 | 文档只宣称「符号优先」，不宣称「渲染全知」 |

---

## 1. 本仓库现状（2026-07-14 代码现实）

### 1.1 已有、可当地基

| 组件 | 路径/事实 | 等级 |
|------|-----------|------|
| MCP + 7 层门 | `io`/`se`，工具 supervise | 强 |
| GameAccess / GameBridge | 游戏门面 + 主线程 marshal | 强 |
| Tick seam | `TickInjector` → `TickEvent(tickCount)` | 有，**opt-in** |
| Packet seam | `FltManager` channelRead0/sendPacket → `PacketReceived/SentEvent` | 有，**无字段摘要** |
| PacketLog | 仅 `inbound + type + timeMillis` | 弱 |
| WorldScanner | Voyager 风格半径计数 + 实体距离 | 中下 |
| GUI 结构化 | `gui_snapshot` + 真 handler 点击 | 强（局部） |
| 截图 | `capture_screen` | 辅助 |
| 动作 | `ActionManager`：chat + **raw packet** | 极薄 |
| NettyTap | pipeline MITM；**KI-9 inbound 可能死** | 有洞 |
| RenderFrame | overlay 用 | 非感知主路径 |
| board Trace/Chip | 契约好；ChatLogChip headless；**几乎无 MC 发布** | 半空 |
| dwm 三后端 | 能画 DemoPanel | 皮 |
| compat KI-4 | ASM 补丁已写；**默认不 arm（空 TrustAnchors）** | 弹药未上膛 |
| Play 包类 | client 内 **C2S≈23 S2C≈71** 已解码对象 | 巨大优势 |

### 1.2 关键缺口（按 R1–R5）

1. **无全局 Clock 产品**：tick 未默认武装；包事件无 tick 附着
2. **协议只见类名**：无法「深读」
3. **世界无网格/槽位/准星/diff**
4. **无持续输入状态机**（无 MovementInput 注入层）
5. **无流式订阅通道**（仅 RPC 工具）
6. **双总线未统一**（core EventBus vs board Trace）
7. **进世界**依赖 KI-4 arm

### 1.3 本仓库铁律（实现者勿破）

- `CLAUDE.md`：安全类、client vanilla、先测再提交、codegraph 读码、无 AI 署名
- `ARCHITECTURE-LOCK`：L0 三骨架；core↔board 零硬依赖
- 生成/eval 顶格 R-1；非 loopback 无 token 拒启

---

## 2. 外部与本地参考（学什么 / 不抄什么）

### 2.1 外网金标准

| 来源 | 架构启示 | 应用到本仓 |
|------|----------|------------|
| **Mineflayer** | 包 → 事件 → `bot.world` 同步世界；`setControlState` 持续控制；physics tick 事件 | **包差分 + 控制状态机**；世界积分我们用 **WorldClient 权威读** 更强 |
| **minecraft-protocol / minecraft-data** | 版本化编解码表 | 仅作 **1.8 字段核对表**；运行时用 **已有 Packet 对象反射**，不引入 Node |
| **Voyager** | 符号 surrounding + 技能 | 升级 WorldScanner，不是换像素 |
| **MineRL/Dojo** | 像素为主 | **反面教材**（Computer-use 亲戚） |
| **Baritone** | Process 优先级、接口分层 | 规划可后置旁路；**先别塞进 core** |
| **wiki.vg / Minecraft Wiki protocol** | 包列表与字段 | 1.8.9 子集优先表见 §4.2 |

### 2.2 本地 `D:\src` / `_refs`（1.8.9 客户端）

| 参考 | 学 | 禁 |
|------|----|----|
| Lavender / Faiths / Emperor / HackSoar | 事件总线热路径、PRE/POST、`MovementInput` 改写、旋转组件时序 | 外挂绕过、注入毒逻辑 |
| LiquidBounce 系 (`D:\src\02_forge_1.8.9\...`) | Packet 事件命名、模块订阅生命周期 | 抄业务模块 |
| 本仓 `docs/history/study` | 11 项目 bus 对比已有 | 重复考古除非矛盾 |

**本地硬事实：** 1.8 操控真路径几乎都落在
`EntityPlayerSP.movementInput`（`MovementInput` / `MovementInputFromOptions`）+ `PlayerControllerMP` 挖放 + `sendQueue` 发包。
**AI 层应镜像「按住键」语义，而不是每 tick 模拟点击屏幕。**

---

## 3. 目标架构（封顶形态 — 唯一推荐主线）

```
                    MCP 工具 / 可选 stream
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
   WorldView(L1)    PacketJournal(L2)   Timeline(L3)
   权威积分读         全包摘要+过滤        tickId 总钟
         │                 │                 │
         └─────────────────┼─────────────────┘
                           ▼
                    L0 Seams（少而硬）
         runTick · NM.channelRead0/sendPacket ·
         (fix) NettyTap · GLFW · render尾仅截图
                           │
                           ▼
                    ActRuntime(L4)
         move/look/dig/place/attack/hotbar/gui
```

### 3.1 分层职责（不许混）

| 层 | 名字 | 职责 | 非职责 |
|----|------|------|--------|
| L0 | Seams | 点火、只读观测、不崩游戏 | 业务语义 |
| L1 | WorldModel / WorldView | 从 WorldClient 读权威态 + diff | 解析原始 ByteBuf |
| L2 | PacketJournal + Summarizers | 全包记录 + 高价值字段摘要 + 可选 reducer | 替代 WorldView |
| L3 | Timeline / Clock | tickId、相位、事件排序、timeline API | 渲染 |
| L4 | ActRuntime | 持续控制状态机 + 与 tick 对齐 | 像素点击 |
| L5 | MCP 暴露 | 工具 schema、ring、流式 | 新哲学 |
| L6 | board 合流 | Trace 吃同一 tick | 第二时钟 |
| L7 | dwm 可选 | 显示真 WorldView | 主感知 |
| L8 | compat | KI 补丁真 arm | 改 client |

### 3.2 推荐默认决策（已替 owner 选好的主路径）

| 议题 | 主路径（默认） | 备选（§FORKS） |
|------|----------------|----------------|
| 世界真相 | **WorldClient 读** | 纯包重建世界（劣） |
| 包观测 | **Packet 对象摘要** | wire 字节（仅调试） |
| Tick | **默认常驻** TickInjector | 保持 opt-in（不推荐） |
| 控制 | **MovementInput 覆盖/合成** | 只发 C03 包（易不同步） |
| 流式 | **先增强 RPC + ring buffer**；再 WebSocket/SSE | 一上来上 WS |
| 渲染 | **1–2Hz 可选截图** | 全 Render* hook（禁止主路径） |

---

## 4. 超级任务清单（按 Phase；ID 稳定可引用）

> 规则：
> - 同一时间 **只做一个 Phase 主线**（可并行「纯文档/纯测试基建」）。
> - 每任务：`[ ]` 未做 / 做完勾 STATUS。
> - **Teeth**：每个行为任务必须有「旧代码会 FAIL」的测试，除非标注 live-only。
> - **禁止范围**写在任务里的不要做。

---

### PHASE 0 — 进世界闸门（阻塞一切 live 验收）

**目标：** KI-4 在 dev 默认路径下 **armed 并生效**；client 仍 vanilla。

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| P0.1 | 采用签发方案 A：Ed25519 钥；公钥进 `TrustAnchors`；私钥不进 git | keygen 步骤可复现；empty anchors 测试仍不 arm | — |
| P0.2 | 签发脚本/小工具调用现有 `Ed25519PatchSigner` | 文档 10 行内可签下一补丁 | P0.1 |
| P0.3 | KI-4 manifest 带有效 signature；defaultDatabase 注册后引擎 arm | 单测：有钥+签→armed；篡改签→不 arm | P0.2 |
| P0.4 | 保留 `-Dmcp.compat.ki4=false` | 关闭后 appliesToRuntime false | P0.3 |
| P0.5 | live：单人进世界不炸（owner 验） | STATUS 记录 PASS/FAIL | P0.3 |
| P0.6 | known-issues KI-4 状态更新（诚实：armed vs live） | 无「已修复」空话 | P0.5 |

**禁止：** 默认 `trustUnsigned=true` 当长期方案；改 `NetworkSystem.java` 源码。

---

### PHASE T — 时间轴脊柱（Owner 最优先的架构件）

**目标：** 全局唯一 Clock；所有观测可排序。

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| T.1 | 设计并实现 `GameClock`（或等价）：`tickId`、`monoNs`、`phase` 枚举（≤3 相位） | 单测：单调递增；tick 对象可复用策略写清 | — |
| T.2 | `TickEvent` 升级：挂 phase；与 Clock 同源 | 破旧字段有迁移/兼容说明 | T.1 |
| T.3 | **默认**在 `McpCore` 启动路径武装 TickInjector（可 `-Dmcp.core.tick=false` 关） | 默认 true；测试可关 | T.1 |
| T.4 | PacketReceived/Sent/Disconnected/**HookFired**/GUI 相关事件附着 `tickId` + `arrivalMono` | Netty 线程用 `lastCompletedTick` 策略写清 | T.1–T.3 |
| T.5 | `Timeline` ring buffer：统一事件信封 `{tickId, kind, summary}` | 容量可配；线程安全 | T.4 |
| T.6 | MCP 工具：`clock_now`、`timeline_tail` | Ring/cap 登记；ToolAnnotations | T.5 |
| T.7 | EventBus 热路径：tick 订阅预索引（参考 study：禁每 tick sort+alloc） | 基准或 teeth：订阅者 N 时无 O(n log n) 每 tick | T.5 |
| T.8 | board：`TickSignal` 由同一 Clock fan-out（反射/bridge，零 hard import 逆向） | BoundaryDiscipline 仍绿 | T.3 |
| T.9 | 文档：时间轴不变量写入 architecture 短文 + STATUS | — | T.6 |

**禁止：** 第二套 tick 计数器；在 render 路径当逻辑时钟。

**FORK 标注：** 相位要不要 PRE/POST world — 默认 **入口一相 + 可选 POST**；若实现中发现移动输入采样点不够，再加第三相（见 §FORKS-T1）。

---

### PHASE P — 协议全覆盖 + 深读

**目标：** 全包可记；高价值包可深读；inbound 真活。

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| P.1 | **修 KI-9**：NettyTap / 观测 handler 装在 `packet_handler` **之前** | 单测能测的测；live 标 pending | — |
| P.2 | `PacketLog` → `PacketJournal`：条目含 dir、class、tickId、summary map、可选 size | 旧 recent_packets 兼容或迁移工具 | T.4 |
| P.3 | `PacketSummarizer` SPI + 注册表 | 未知包：类名+截断 toString | P.2 |
| P.4 | 实现 **优先包摘要器**（下表） | 每包至少 1 teeth 测（合成 Packet 或反射） | P.3 |
| P.5 | 其余 C2S/S2C：generic summarizer 扫完 94 类 | 清单覆盖率 100% 类名级 | P.4 |
| P.6 | 过滤器：include/exclude 前缀、drop KeepAlive 默认 | 工具参数 | P.2 |
| P.7 | MCP：`packets_tail`、`packet_get`；增强/替换 `recent_packets` | 文档与 ring | P.2–P.6 |
| P.8 | （可选）Reducer：S06 血量等 → 高层 Timeline 事件 `HealthChanged` | 不重复 WorldView | P.4, T.5 |
| P.9 | 对照 wiki.vg/1.8 字段表写 `docs` 短表（非代码） | 实现以 client 字段名为准 | P.4 |

#### P.4 优先摘要包（1.8.9 名）

**S2C：**
`S01PacketJoinGame`, `S02PacketChat`, `S03PacketTimeUpdate`, `S06PacketUpdateHealth`, `S07PacketRespawn`, `S08PacketPlayerPosLook`, `S2FPacketSetSlot`, `S30PacketWindowItems`, `S2DPacketOpenWindow`, `S2EPacketCloseWindow`, `S12PacketEntityVelocity`, `S13PacketDestroyEntities`, `S14–S19` 实体移动家族, `S0C/S0E/S0F` spawn 家族, `S21/S22/S23` 方块/chunk 家族（chunk **只摘要坐标/标志，不倾倒 section 数组到 LLM**）, `S40PacketDisconnect`, `S45PacketTitle`（若有）。

**C2S：**
`C01PacketChatMessage`, `C02PacketUseEntity`, `C03PacketPlayer`(+pos/look 子类), `C07PacketPlayerDigging`, `C08PacketPlayerBlockPlacement`, `C09PacketHeldItemChange`, `C0APacketAnimation`, `C0BPacketEntityAction`, `C0EPacketClickWindow`, `C0DPacketCloseWindow`.

**禁止：** 默认把 ChunkData 原始字节/全 section 喂给 LLM；在 Netty 线程做重反射无预算。

**FORK：** 摘要用手写反射 vs 注解生成 — 默认 **手写优先包 + generic 其余**（见 §FORKS-P1）。

---

### PHASE W — 世界语义封顶

**目标：** 关截图也能决策。

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| W.1 | `WorldView` 快照结构：Self / LocalGrid / Entities / Inventory / Target(ray) / Env | schema 稳定；JSON 可序列化 | T.1 |
| W.2 | Self：pos/vel/yaw/pitch/hp/food/xp/armor/air/effects/gamemode/sneak/sprint | 来自 EntityPlayerSP 权威字段 | W.1 |
| W.3 | LocalGrid：可配半径（默认如 8–16）方块 id 网格；空气可压缩 | token 预算测试 | W.1 |
| W.4 | Entities：entityId、type、pos、dist、（可见）hp/名 | 排序+上限 N | W.1 |
| W.5 | Inventory：槽位索引 + item registry name + count + damage（非 displayName 糊弄） | — | W.1 |
| W.6 | Raytrace：准星方块/实体 | 与原版视线一致路径 | W.1 |
| W.7 | `WorldViewDiff`：相对上次快照 | 工具可 `full|diff` | W.2–W.6 |
| W.8 | MCP：`world_view` 替换/增强 `scan_surroundings`（旧工具可委托） | 兼容期写清 | W.7 |
| W.9 | observe profile：`sparse|explore|combat` 三档预算 | — | W.8 |
| W.10 | live 无截图短任务脚本（owner） | STATUS | W.8, P0.5 |

**禁止：** 每 tick 全图 dump；主路径依赖 `capture_screen`。

**FORK：** 网格用柱状采样 vs 实心立方 — 默认 **柱状+脚下层优先** 省 token（§FORKS-W1）。

---

### PHASE A — 流式真操控（赢 Computer-use）

**目标：** 持续控制，真路径。

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| A.1 | `ActRuntime`：持有 move/look/jump/sneak/sprint 目标态 | 线程安全；tick 应用 | T.3 |
| A.2 | 注入点：覆盖/包装 `MovementInput`（参考本地 client 改 input 的方式，但用 seam/不改 vanilla 源） | client 源码零 diff | A.1 |
| A.3 | `look` / `look_at`：写 yaw/pitch + 合法包路径 | — | A.1 |
| A.4 | dig 状态机：对接 `PlayerControllerMP` 挖矿 | start/stop/完成检测 | A.1, W.6 |
| A.5 | use/place/attack 原语 | 与冷却/距离诚实失败 | A.1 |
| A.6 | hotbar / 复用 gui_* | — | — |
| A.7 | MCP：`act_set` / `act_cancel` / `act_status`（先 RPC） | requires 前缀 | A.1–A.6 |
| A.8 | 与 tick 对齐：intent 记录 `effectiveTick` | — | T.1 |
| A.9 | overlay 指针 consumed → 可选吞游戏输入（dwm TODO） | 不挡 A 主线 | 可选 |
| A.10 | 流式通道（第二阶段）：WS/SSE 推 timeline+diff 或双向 act | **仅当 A.7 稳定后** | A.7, T.6 |
| A.11 | 无截图闭环任务 live（owner） | S1 | A.7, W.10, P0.5 |

**禁止：** 仅靠 `send_raw_packet` 当唯一移动手段（可保留实验工具）；每步截图点按。

**FORK：** MovementInput 替换 vs KeyBinding 按下模拟 — 默认 **MovementInput 合成**（更稳）；反作弊敏感服见 §FORKS-A1。

---

### PHASE E — 事件与 board 合流

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| E.1 | 聊天发送路径发布 `ChatSendSignal`（不改 vanilla 则 seam/hook） | ChatLogChip live 可计数 | T.8 |
| E.2 | 关键世界事件 → board Signal 白名单 | 文档列表 | T.8, P.8 |
| E.3 | `Board.init` 可选注册官方 chips | 默认矩阵非空可配 | E.1 |
| E.4 | 禁止第三总线检查（测试/文档） | — | E.2 |

---

### PHASE V — 视觉校验（低优先级）

| ID | 任务 | 完成定义 | 依赖 |
|----|------|----------|------|
| V.1 | `capture_screen` 标注为 validation profile | 工具描述诚实 | — |
| V.2 | 可选：world_view 与截图交叉校验工具（调试） | 默认关 | W.8 |
| V.3 | **明确不做** 全 Render* hook 总线 | 文档红线 | — |

---

### PHASE C — 横切（全程穿插）

| ID | 任务 | 完成定义 |
|----|------|----------|
| C.1 | 每个 Phase 更新 STATUS + 测试数 | session-status 可对账 |
| C.2 | Ring/Capability/ToolAnnotations 同步 | PolicySideTableDrift 绿 |
| C.3 | 性能预算：tick 热路径无重 IO/大 alloc | 注释+关键 teeth |
| C.4 | 安全：Journal 只读；不把 packet 可变引用泄露给 LLM 工具输出（深拷贝摘要） | — |
| C.5 | KI-1 mipmap 补丁（compat）— 画面债，不挡操控主线 | 可后置 |
| C.6 | 签发后 KI-4 外下一颗补丁流程验证 | P0 复用 |

---

### PHASE X — 明确延后 / 不做

| ID | 项 | 原因 |
|----|----|------|
| X.1 | 全渲染方法 hook 流式 | 无符号、拖死、token 爆 |
| X.2 | 内嵌完整 Baritone | 体积与边界；可未来旁路模块 |
| X.3 | 多版本协议自动协商 | 钉 1.8.9 |
| X.4 | 默认公网暴露 MCP | 威胁模型 |
| X.5 | 用 Computer-use 当主回归 | 与 R1 矛盾 |

---

## 5. FORKS — 不确定时的路径（实现者自选须写进 commit/STATUS）

### FORKS-T1 相位数量
- **A（默认）：** tick 入口 1 相 + 可选 POST_WORLD
- **B：** PRE_INPUT / POST_INPUT / POST_WORLD 三相
- **选 B 若：** 发现 move 应用点与观测点错位

### FORKS-P1 摘要实现
- **A（默认）：** 手写高价值 + generic
- **B：** 注解/codegen 扫字段 — 仅当手写维护成本爆

### FORKS-P2 Journal 存哪
- **A（默认）：** 堆内 ring
- **B：** 可选落盘 — 仅调试

### FORKS-W1 网格形状
- **A（默认）：** 水平半径 + 有限垂直
- **B：** 实心立方 — 短半径 combat profile

### FORKS-A1 控制注入
- **A（默认）：** 合成 MovementInput
- **B：** KeyBinding 状态
- **C：** 只发包 — 仅实验，不当默认

### FORKS-A2 流式传输
- **A（默认）：** 先 RPC 拉 timeline/world_diff + act_set
- **B：** WebSocket 双向 — Phase A 后半
- **C：** SSE 单向推 — 次选

### FORKS-S1 补丁 arm
- **A（默认）：** Ed25519 真签 + 公钥进 TrustAnchors
- **B：** 仅 classpath `compat.patches` 免旁路 — 需 owner 书面允许
- **禁止：** 无开关永久免签

---

## 6. 给 Claude Code 的施工纪律

1. **开干前：** 读本文 §0 + 当前 Phase 全表 + `STATUS.md` + 相关 LOCK；codegraph 读符号。
2. **一次只宣称一个 Phase 完成**；禁止「顺便」重写无关模块。
3. **client/ 零业务 diff**；兼容用 compat。
4. **测试：** 新行为非空转；live-only 必须标注。
5. **安全：** 不削弱 verify 默认；不把 Instrumentation 公有化。
6. **完成汇报模板：** 做了哪些 ID / 测试数 / 未做 ID / 选了哪个 FORK / live 是否验。
7. **STATUS：** 数字与 HEAD 必须可对 session-status。
8. **大代码：** 主会话写；勿让 workflow 吐超大 Java 块卡死。
9. **参考 D:\src：** 只读；不执行陌生 jar/exe；大包勿整包解压。
10. **冲突时：** 依据清单 R1–R8 > 本文默认 > 旧临时实现。

---

## 7. 建议执行顺序（贴给人类排期）

```
P0 补丁真 arm
 → T 时间轴脊柱
 → P 协议深读 + KI-9
 → W 世界语义
 → A 动作状态机（RPC）
 → E board 合流
 → A.10 真流式（可选）
 → V 视觉校验
 → C 横切一直做
```

**并行允许：** C 文档；P.9 对照表；在 T 完成后 P 与 W 可部分并行（摘要器 vs 网格），但 **Clock 契约不能两边各写一套**。

---

## 8. MCP 工具目标面（实现时的北极星 API）

**读：**
`clock_now` · `timeline_tail` · `world_view` · `packets_tail` · `packet_get` · `gui_snapshot*` · `capture_screen?`

**写：**
`act_set` · `act_cancel` · `act_status` · 既有 `gui_*` / `send_chat` · `send_raw_packet`（实验）

**元：**
`observe_profile` · `list_compat_patches`

全部带 ring / capability / `[requires:]`。

---

## 9. 与现有模块映射（避免重复造轮）

| 新能力 | 落点建议 |
|--------|----------|
| Clock/Timeline | `ke` 或 `drivers/world` + 事件；工具在 drivers 或新 `drivers/observe` |
| PacketJournal | 进化 `drivers/world/PacketLog` |
| WorldView | 进化 `WorldScanner`/`Surroundings` |
| ActRuntime | 进化 `drivers/action/ActionManager` |
| Summarizers | `drivers/world/packet` 或 `flt` 旁路只读 |
| board fan-out | `board/link` + signals |
| overlay 显示 | dwm 后置读 WorldView |

**不要**新建与 `ke.event` 平行的第四套 bus。

---

## 10. Owner 仍可拍板的点（未阻塞开工）

下列有默认，但 owner 可改；改了就更新本文 §3.2：

1. Tick **默认常驻** — 默认 YES
2. LLM 包默认 **仅摘要** — 默认 YES（debug 可开 verbose）
3. 持续 move 状态机 — 默认 YES
4. 补丁 **仅方案 A 真签** — 默认 YES（B 旁路需明示）

---

## 11. 文档维护

- 本文 `status: authoritative-for-implementers`
- 完成 Phase 后：更新 STATUS 指针到本文 + 勾选进度表（可在 STATUS 加一小节 `perception-control progress`）
- 大改分层须 ADR（触 LOCK 时）

---

## 12. 一句话给所有 agent

> **先武装补丁与 tick 钟，再深读每一包并积分世界，再用 MovementInput 状态机流式操控；截图只验真，渲染全 hook 永不当大脑。**

---

*End of roadmap. Implementers start at PHASE 0 unless owner says otherwise.*
