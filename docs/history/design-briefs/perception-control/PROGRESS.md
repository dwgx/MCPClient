---
doc: perception-control-progress
title: 进度板 — 感知·操控封顶线任务打勾
layer: reference
status: living
updated: 2026-07-14
parent: README.md
read_if: 实现前后必读必改——当前 Phase 指针 + 任务勾选 + 实现笔记。
---

# 进度板 — 感知·操控封顶线

> **Claude：做完某个 ID 必须把 `[ ]` 改成 `[x]`，并在文末「实现笔记」追加一节。**
> **Owner live 项**可标 `[ ]` 并在笔记写 `pending-owner`。
> 完成定义以 `../perception-control-max-roadmap.md` 为准。

---

## 当前指针（同时只能有一个进行中）

| 字段 | 值 |
|------|-----|
| **当前 Phase** | 主线 0/T/P/W/A/E/V **全 done**（含 A.10 SSE + A.11 真操控 live PASS）+ 全包暴露 W1-W6 + W7 do_ 家族(10 工具)。**pending-owner live 空洞已清空**。主线封顶达成 |
| **状态** | `done`（headless 全绿:**core 633 / board 211** skip=0;HEAD `d1b0728` 已推 origin）。**2026-07-16 全部 live PASS**（RTX 5070 Ti:KI-4 进世界 / 10 do_ 工具操控 / world_view / 抓包 / SSE 流 / 重签补丁 arm）。2026-07-16 会话另做:对抗安全审计 6 项修复 + do_ 命名重构 + E.3(ADR-0003)|
| **进行中提示词** | 无待跑令状。主线封顶。可选下一步见 §待办/owner 决策(site/ 去留、重操作超时、SSE tick 摘要美化) |
| **上次更新** | 2026-07-16（安全审计 + W7 do_ 家族 + E.3 + A.10 SSE + 全线 live 验证;STATUS/PROGRESS reconcile + 推 origin） |
| **上次 HEAD** | `d1b0728`（A.10 SSE)已推;前 `d6eccfa`(审计+W7+E.3)已推。远端同步 `0 0` |

---

## PHASE 0 — 进世界闸门（补丁真 arm） `DONE`（headless；P0.5 live=pending-owner）

- [x] **P0.1** Ed25519 钥；公钥进 TrustAnchors；私钥不进 git；empty anchors 仍不 arm
- [x] **P0.2** 签发脚本/工具（现有 Ed25519PatchSigner）
- [x] **P0.3** KI-4 有效 signature；引擎可 arm；篡改签不 arm（测试）
- [x] **P0.4** `-Dmcp.compat.ki4=false` 仍可关
- [x] **P0.5** live 单人进世界 — **PASS(2026-07-14)**:MCP gui_click 自动驱动主菜单→Singleplayer→Create World→Create;read_player_state 从 "not in world" → `Player0 pos=(721.5,64,13.5) hp=20`,无 hs_err 崩溃转储,无 LocalServerChannel 错误。KI-4 补丁真机 arm 且生效。
- [x] **P0.6** known-issues KI-4 状态诚实更新

**Phase 0 整包**：[x] **DONE（headless + live 双验证 2026-07-14）** — KI-4 真机进世界不崩

---

## PHASE T — 时间轴脊柱

- [x] **T.1** GameClock（tickId / monoNs / phase≤3）
- [x] **T.2** TickEvent 与 Clock 同源
- [x] **T.3** 默认武装 TickInjector（可 `-Dmcp.core.tick=false`）
- [x] **T.4** 包/断线/Hook/GUI 事件附着 tickId + arrivalMono（GameEvent 基类一处附着，全子类获得）
- [x] **T.5** Timeline ring buffer 统一信封
- [x] **T.6** MCP：`clock_now`、`timeline_tail`
- [x] **T.7** EventBus tick 热路径预索引（无每 tick 全量 sort；dispatchCache）
- [x] **T.8** board TickSignal 同源 fan-out（零 core hard import；BoardClockBridge 反射，board 缺席 no-op）
- [x] **T.9** 文档（`architecture/08-TIMELINE-SPINE.md`）

**Phase T 整包**：[x] **DONE（headless + live 双验证 2026-07-14）** — 真机 clock_now 448→549→2854(~20 tick/s, armed:true)、timeline_tail TickEvent 递增真值。FORK-T1=A。

---

## PHASE P — 协议深读

- [x] **P.1** 修 KI-9（inbound 在 packet_handler 前）— headless 双 teeth（addLast=0/installBefore=1）；**live PASS(2026-07-15)**:装 tap 后 `packets_tail{dir:IN}` count=30 真入站包(S17EntityLookMove/S19EntityHeadLook...,带 tickId),出站 C04 摘要器读真坐标。KI-9 真机修复生效。
- [x] **P.2** PacketJournal（seq/dir/class/tickId/byteLen/summary，只订阅两个 Seam 包事件）
- [x] **P.3** PacketSummarizer SPI + 注册表（exact+fallback+generic，never-throw，tap 内同步跑）
- [x] **P.4** 高价值包摘要器 + teeth（S08/S03/S00/S23/S02/S26/S12/C03族，8 teeth）
- [x] **P.5** 其余包 generic 覆盖（GenericPacketSummarizer=simpleName）
- [x] **P.6** include/exclude 过滤（PacketFilter，deny-first，glob/substring，默认降噪）
- [x] **P.7** MCP packets_tail / packet_get（R3，兼容 recent_packets + tap 未装诚实守卫）
- [ ] **P.8** （可选）高层事件 reducer — **defer**
- [x] **P.9** 1.8 字段对照短文档（`packet-field-map-1.8.9.md`）

**Phase P 整包**：[x] **DONE（headless；P.1 真验证 live=pending-owner）** — core 428→447 绿 skip=0；client/ 纯 vanilla 不变。P.8 reducer 显式 defer。

---

## PHASE W — 世界语义

- [x] **W.1** WorldView schema（`WorldView` 根 record + `WorldViewJson` toMap 投影）
- [x] **W.2** Self（`SelfView`：pos/vel/yaw/pitch/hp/food/sat/xp/armor/air/effects/gamemode/sneak/sprint,EntityPlayerSP 权威读）
- [x] **W.3** LocalGrid（`LocalGrid` 柱状采样,feet-first + RLE 空气压缩,FORK 默认柱状）
- [x] **W.4** Entities（`EntityView`,copy list 防 CME,dist 排序 + profile 上限,living 才 hp）
- [x] **W.5** Inventory 槽位级（`InventoryView`,itemRegistry 名 + count + damage + maxDamage,非 displayName）
- [x] **W.6** Raytrace 准星（`TargetView`,读 `mc.objectMouseOver` 复用原版视线,block/entity/miss）
- [x] **W.7** WorldViewDiff（`WorldViewDiff`,full|diff,dead-band,entities entered/left/moved,纯函数）
- [x] **W.8** MCP world_view（增强 scan_surroundings;R2 + CAP_WORLD_READ;scan_surroundings 描述指明后继）
- [x] **W.9** observe profile 分档（`ObserveProfile` sparse|explore|combat 预算旋钮）
- [x] **W.10** live 无截图短任务 — **PASS(2026-07-15)**:进 FrozenRiver 世界,`world_view` 读到真实 self(pos 27.27,63,4.88/hp20/SURVIVAL)+ 5 实体(兔子/鱿鱼 dist 排序带 hp)+ 柱状 grid(ice 182/water/snow,RLE profile)+ env;diff 模式精准报自身移动 + 实体 entered/moved。无截图决策成立。

**Phase W 整包**：[x] **DONE(headless 全绿 + 11 teeth + live PASS 2026-07-15)** — core 447→459 skip=0;client 零 diff。FORK-W1=柱状+脚下层优先。

---

## PHASE A — 流式真操控

- [ ] **A.1** ActRuntime 状态机骨架
- [ ] **A.2** MovementInput 合成注入（不改 client 源）
- [ ] **A.3** look / look_at
- [ ] **A.4** dig 状态机
- [ ] **A.5** use / place / attack
- [ ] **A.6** hotbar + 复用 gui_*
- [ ] **A.7** MCP act_set / act_cancel / act_status
- [ ] **A.8** intent 与 tick 对齐
- [ ] **A.9** （可选）overlay 吞输入
- [x] **A.10** WS/SSE 流式 — **DONE(2026-07-16,`d1b0728`,SSE only)**:`GET /v1/stream`(text/event-stream)推 GameEvent,`?kinds=tick,packet,world,other` 过滤,reference-free 投影,断开 ~1s 反订阅,限并发 4,继承 bearer+非loopback 鉴权。调研定论只做 SSE(上行走 act_set durable,不需 WebSocket;`com.sun.net.httpserver` 不支持 101 upgrade,加 WS 破零依赖)。`SseStreamTest` 2 非空转。**live PASS**:20s 收 407 帧(400 tick + do_dig/do_entity_action OUT 包实时流)+ kinds 过滤生效。
- [x] **A.11** live 无截图闭环 — **PASS(2026-07-16,RTX 5070 Ti)**:AI 全驱动进单人创造世界,`world_view` 读 self+准星+实体后,do_dig 真挖(grass→air)、do_place_block 真放(→stone 闭环)、do_use_entity 真攻击羊、do_entity_action 真蹲/冲刺——全无截图,符号感知 + typed 操控闭环成立。

**Phase A 整包**：[x] **DONE(A.1-A.10 headless + A.10 SSE 2026-07-16;A.9 overlay defer;A.11 + 真操控 live PASS 2026-07-16)** — 2026-07-15 大并发战役建 A.1-A.8(ActRuntime 无锁 + MovementInput 合成注入 client 零 diff + 4 控制器 + act_set/cancel/status),`521e697`;2026-07-16 补 A.10 SSE(`d1b0728`)+ 全 do_ 工具真机操控验证。**pending-owner 已清空。**

---

## PHASE E — board 合流

- [x] **E.1** 聊天路径发 ChatSendSignal
- [x] **E.2** 白名单世界事件 → board Signal（Tier-1 disconnect/chat/blockchange + Tier-2 health/death/join，`16acce6`）
- [x] **E.3** Board.init 注册官方 chips — **DONE(2026-07-16,ADR-0003)**:`Board.init()` 加一行 `OfficialChips.install(FEATURES,TRACE)`(加法式,不动 §L2 冻结签名);默认装 ChatLog+Ticker 两诊断 chip,`-Dmcp.board.officialChips=false` 可关。回归 `BoardInitInstallsOfficialChipsTest`(非空转:撤那行则挂)+ 顺修 2 个撞 id 的既有测试。ADR-0003 accepted + governance-log 留痕。
- [x] **E.4** 禁止第三总线检查

**Phase E 整包**：[x] **DONE(E.1/E.2/E.4 + E.3 收口 2026-07-16)** — commit `913357c`(Tier-1)+`16acce6`(E.2 Tier-2 health/death/join)+ E.3 待 commit(ADR-0003)。聊天 veto(honor+报 reason)、白名单世界信号(disconnect/chat/blockchange/health/death/join wire)、NoThirdBus 红线、Board.init 装官方 chips。PlayerLeave 诚实未 wire(REMOVE 无 name 在线上)。

---

## PHASE V — 视觉校验（低优先）

- [x] **V.1** capture_screen 标注 validation — ToolRegistry capture_screen 描述=验证档(world_view 主感官,截图次要校验)；`0e79d36`
- [ ] **V.2** （可选）交叉校验工具 — defer
- [x] **V.3** 文档红线：不做全 Render hook — 两总线红线由 NoThirdBusTest(E.4) 守；全渲染 hook 在明确不做 X.1

**Phase V 整包**：[x] **DONE(V.1 capture_screen 验证档描述 + V.3 两总线红线 NoThirdBus;V.2 交叉校验 defer)** — commit `0e79d36`。

---

## PHASE C — 横切（穿插勾选）

- [x] **C.1** STATUS 与测试数 — 2026-07-15b reconcile：E.2 Tier-2 提交 `16acce6` 后 core 551→565、board 204，工作树干净；STATUS/GAP-REPORT 同步（见本次审计 §笔记）
- [x] **C.2** Ring/Cap/ToolAnnotations — Ring.java world_view=R2/act_set·act_cancel=R1/act_status·clock_now·timeline_tail·packets_tail·packet_get=R3；ToolAnnotations 全覆盖（审计验）
- [x] **C.3** tick 热路径性能意识 — EventBus dispatchCache（computeIfAbsent，subscribe/unsubscribe 清），暖后无 per-event isInstance 扫描；`EventBusDispatchCacheTest`(5)
- [x] **C.4** Journal 输出深拷贝/只读摘要 — PacketJournal.Entry 全原始/枚举/String，tail/byId 返回新 ArrayList 拷贝，reference-free；`PacketJournalTest`(7)
- [x] **C.5** KI-1 补丁 — **不是"后置"，是真的**：MipFill + Ki1MipmapZeroFillPatch（真 ASM INVOKESTATIC 注入，LWJGL3 gate，真 Ed25519 签名，status VERIFIED，注册进 defaultDatabase）；`MipFillCoverageTest`+`Ki1SignedArmingTest`+`Ki1MipmapZeroFillPatchTest`。headless done；live GPU 斑点消失=pending-owner
- [x] **C.6** 签发流程可签第二颗补丁 — KI-1 即第二颗签名补丁（KI-4 是首颗）；PatchSignerCli 离线签发可用，两颗都带真内核签名并经 verify 路径 arm

---

## 明确不做（永不打勾为完成）

- X.1 全渲染 hook 流
- X.2 内嵌完整 Baritone
- X.3 多版本协议自动协商
- X.4 默认公网暴露 MCP
- X.5 Computer-use 当主回归

---

## 实现笔记（追加在下方，旧的勿删）

### 笔记 2026-07-14 — 分区创建

- 创建 perception-control 分区：BOOK / PROGRESS / prompts
- 代码实现：尚未开始
- 下一动作：人复制 `prompts/00-PHASE-0.md` 给 Claude

### 笔记 2026-07-14 — PHASE 0（补丁真 arm）

- **HEAD**: `ccb79dd`（前置 `58fd15b` = KI-4 ASM 补丁本体）
- **完成 ID**: P0.1 P0.2 P0.3 P0.4 P0.6（P0.5 = pending-owner）
- **测试命令与结果**: `./mvnw -pl core -am test` → **367 绿, 0 fail, skip=0**。新增
  `Ki4SignedArmingTest`（10：baked anchor+真签→arm、空 anchors→不 arm、篡改/错 keyId/
  截断/无签→不 arm、target NetworkSystem 命中）+ `PatchSignerCliTest`（2）。旧
  `Ki4LocalServerChannelPatchTest` 7 项仍绿（transform 仍只改 addLocalEndpoint）。
- **FORK 选用**: 方案 A（Ed25519 真签，单一签名路径）。**无免签旁路** —— owner 经 Grok
  override 否决了早期的 in-code KERNEL_INCODE 免签双层设计；最终 = 仅 `signer.verify` 通过才 arm。
- **实现**: `KernelTrustAnchor` 烤内核公钥（jar 资源 `kernel-ed25519.pub`, keyId
  `mcp-kernel-ed25519-v1`）进 `Compat.defaultTrustAnchors()`（fail-closed 到 empty）；
  `PatchSignerCli`（`--privkey` 读文件，用现有 `Ed25519PatchSigner`+`PatchCanonicalizer`，
  wire 沿用 `ed25519:v1:<keyId>:<b64url>`）；`Ki4LocalServerChannelPatch` 带真签名进 manifest。
  私钥不进 git（`.gitignore` 兜底 `scripts/secrets/` `*.key.b64`）。
- **KI-10 边界**: `PatchCanonicalizer`/`PatchSigner`/`Ed25519PatchSigner` 未动；签名绑
  manifest 标签、不绑 transform 字节——javadoc 诚实保留，本 Phase 不做该语义大改。
- **未做/风险 (pending-owner)**: P0.5 —— headless 只证 transform 正确 + 引擎报 armed；
  真进单人世界确认不再崩需 owner 起真游戏点一次（KI-4 原症状是 live 发现的）。
- **顺序铁律下一步**: PHASE T（时间轴脊柱）—— owner 复制 `prompts` 下一张令状再推。

### 笔记 2026-07-14 — PHASE T（时间轴脊柱）

- **HEAD**: `25caaf7`
- **完成 ID**: T.1 T.2 T.3 T.4 T.5 T.6 T.7 T.8 T.9（真机 per-tick advance = pending-owner）
- **测试**: `./mvnw -pl core -am test` → **382 绿, 0 fail, skip=0**（367→382，+15）。新增
  GameClockTest(4)/TimelineTest(4)/EventBusDispatchCacheTest(5)/BoardClockBridgeTest(2)。
- **FORK**: FORKS-T1 = A（入口一相 START + 保留 POST_WORLD，默认不 advance）。
- **实现**: ke/GameClock(唯一 tick 源) + TickBridge 改 advance GameClock(删私有 counter) +
  GameEvent 基类构造附 tickId(全子类零改动) + ke/Timeline(ring 安全投影) + drivers/observe/
  ObserveTools(clock_now/timeline_tail R3) + EventBus dispatchCache + link/BoardClockBridge(反射
  fan-out board TickSignal，零 hard import) + McpCore 默认武装 TickInjector。文档 architecture/08。
- **pending-owner**: 真机 per-tick advance —— headless 证机制;runTick 每 tick 真 advance +
  clock_now 递增 + timeline_tail 有真事件，需 owner 起 -javaagent 真游戏验一次。
- **下一步**: PHASE P（协议深读 + 修 KI-9）—— 包附 tickId 已就绪(T.4)。

<!-- Claude 追加模板：

### 笔记 YYYY-MM-DD — PHASE X
- HEAD:
- 完成 ID:
- 测试命令与结果:
- FORK 选用:
- 风险/pending-owner:

-->

### 笔记 2026-07-15 — PHASE P（协议深读，ultracode）

- **HEAD**: `56a2789`（`22134fb` P.1/P.3/P.4 → `66fa624` P.2 → `56a2789` P.6/P.7;前置 `fa4a571` scripts fix;已推 origin/mcp-core）。
- **完成 ID**: P.1 P.2 P.3 P.4 P.5 P.6 P.7 P.9（P.8 reducer defer；P.1 真验证 live=pending-owner）
- **测试**: `./mvnw -pl core test` → **447 绿, 0 fail, skip=0**（428→447，+19）。新增 NettyTapInboundBeforeTerminalTest(4)/PacketJournalTest(7)/PacketSummarizerRegistryTest(8)。
- **设计法**: ultracode 工作流 5 路并行分析（P1/P2/P3-4/P6-7/P9）+ 综合；P3-P4 那路 agent 失败，主 agent 用同步 subagent 补齐；蓝图定后**主 agent 串行实现**（守 [[feedback-workflow-hard-limits]]：大段代码不并行）。
- **KI-9 修复**: NettyTap 加 installBuiltinTap/installBefore/resolveTerminalName（实例身份→"packet_handler"→末尾 SimpleChannelInboundHandler→addLast fallback）。SeamController.installNettyTap 改用之。旧 installHandler(addLast) 保留。**真验证只能 live**：SeamOnLiveConnectionLiveIT 加 inbound 测试（-Dmcp.it.live=true 连服务器）。
- **摘要器**: PacketTapHandler.frozen() 内同步调 registry 在活 Packet 上算 String，MessageSnapshot 加 summary 字段，活引用不逃逸。Timeline.summarize 对 Seam 包事件读 MessageSnapshot.summary()。
- **工具**: ObserveTools ctor 加 PacketJournal+SeamController，packets_tail/packet_get（R3）+ PacketFilter；McpCore 接线。字段对照文档 `packet-field-map-1.8.9.md`。
- **client/ 纯 vanilla 不变**。下一步: PHASE W（世界语义）——等 owner 令状。

### 笔记 2026-07-15 — 波次1查漏补缺 + PHASE W（世界语义,ultracode）

- **HEAD**: PHASE W + F1 待 commit（前置 `56a2789` PHASE P 已推 origin）。
- **完成 ID**: W.1–W.9（W.10 live=pending-owner）。
- **测试**: `./mvnw -pl core test` → **459 绿, 0 fail, skip=0**（447→459,+12 = F1 test + WorldViewDiffTest 6 + WorldViewJsonTest 5）。
- **波次1（6 只读 subagent 并行审计）**: S1 文档 / S2 PHASE P 回归（真绿非假绿）/ S3 TUF 红队（无 CRITICAL,F1 HIGH 明确 bug）/ S4 WorldView 设计 / S5 Act 预研(design-only) / S6 安全扫描（私钥不在 git;SeProtectedObjects 未覆盖 compat=defer）。综合 → `GAP-REPORT.md`。
- **本会话修**: (1) 文档漂移 C.1（STATUS client 20→14/ahead 4→已推/HEAD;PROGRESS 未提交→已提交;known-issues KI-9 FIXED/KI-10 MITIGATED）(2) **F1 HIGH**：CompatEngine supersession-suppression 加 `signer.verify`+VERIFIED 门,未签补丁不能 disarm 已签补丁（teeth `unsignedPatchCannotDisarmSignedPredecessor_F1`)(3) F2 诚实注释（ContentHash/CompatEngine 不再 overclaim「签名绑行为」）。
- **PHASE W 实现**: `drivers/world/` 新 11 类（ObserveProfile/SelfView/EntityView/InventoryView/TargetView/EnvView/LocalGrid 柱状/WorldView/WorldViewCapture/WorldViewJson/WorldViewDiff）+ `world_view` 工具（ToolRegistry,R2+CAP_WORLD_READ,full|diff,AtomicReference last）+ scan_surroundings 描述指后继。单次 GameBridge.onGameThread marshal;client 零 diff。
- **defer（GAP-REPORT）**: S3 F3/F4/F5/F6、S6 SeProtectedObjects compat 覆盖、S2 重连 tapInstalled stale、A/E 实现。
- **下一步**: PHASE A（流式真操控）—— S5 接口草案在 GAP-REPORT,等 owner 04-PHASE-A 令状。W.10 + P.1 KI-9 需 owner live。

### 笔记 2026-07-15b — E.2 Tier-2 收尾 + 结账（查漏审计 + 补打勾 + 修漂移）

- **HEAD**: `16acce6`（E.2 Tier-2）。前置 `0e79d36`。
- **完成 ID**: E.2 Tier-2（health/death/join）落地 + 提交。**补打勾**（代码早做完，只是没勾）: C.1 C.2 C.3 C.4 C.5 C.6 / E.1 E.4 / V.1 V.3。
- **审计法**: workflow 6 路只读（2 审计 + 4 编目 108 包）。审计结论:**代码超前文档,不是落后**;无假绿、无空心声明;唯一真 hazard = E.2 未提交（已修）。GAP-REPORT F5/S6/S2 陈旧（`239ffe1` 当天已修）→ 本次同步。
- **E.2 Tier-2 实现**: HighValueSummarizers 加 Health(S06)/Combat(S42)/PlayerList(S38) 摘要器 + BoardWorldEventBridge onInbound 三分支（emitHealth/emitDeath/emitPlayerJoins）。honest：S42 只 ENTITY_DIED 出 DeathSignal；S38 只 ADD_PLAYER 出 PlayerJoinSignal（每 name 一个）；**PlayerLeave 诚实未 wire**（REMOVE 线上无 name，只有 UUID）。
- **测试**: `./mvnw -pl core,board test` → core **565** 绿 / board **204** 绿，skip=0。新增 PacketSummarizerRegistryTest +5、BoardWorldEventBridgeTest +9。
- **owner 决策记录（本会话）**: (1) 全包 MCP 暴露 = 分层混合（观测 packet_view A/B/C + packets_tail 全 108 覆盖；操控 ~10 typed act 工具 + send_raw_packet 兜底）——**待做,下一大活**。(2) KI-10 = **暂不改**（无数据下发通道，绑 transform 字节会随 ASM 漂移致签名脆，等真开通道再做）。(3) PlayerLeave 测试允许硬编名字。
- **下一步**: 全包摘要器分层实现（108 包编目在本次 workflow 结果）。需先跟 owner 定 A/B/C 分层的命名与 typed 工具清单（架构必问）。

### 笔记 2026-07-15b(续) — 全 108 包 MCP 暴露 W1-W6 完成

- **HEAD**: `98fad76`。8 提交:`0e62955` W1 → `95de073` W2.1 → `a0741e5` W2.2 → `b6cd693` W2.3 → `3ac5bbe` W4 → `71a4351` W5 → `1cc0829` W6 → `98fad76` W3。**已推 origin**(连同 `16acce6`,实测 `0 0`)。
- **owner 三决定**(动手前问的): ① SPI 升级出结构化 kv(最高质量) ② typed 工具用 **`send_` 前缀**(跟 send_chat/send_raw_packet 家族,不用 `act_`——那是 ActRuntime 连续状态机) ③ typed 发包**过 board veto**。W4 数据模型取舍(journal 存不存 fields)owner 让我抉择 → **存**(单一真相,ring 1024 上限内存可控)。
- **W1 SPI 地基**: `PacketView`(有序 JSON-ready kv builder,drop-null=诚实无编造,putRounded 稳定小数;`Json.write` 直接序列化零重造)+ `PacketSummarizer.project()` **default null**(B/C 与既有 14 个零改动)+ registry `projectStructured()`(同 exact→fallback→generic + never-throw)。
- **W2 A 层 ~46 个**: `Summ` 共享助手(fp32=/32、angle=*360/256、pos、enumName、clip)+ `WorldSummarizers`(10)/`MovementSummarizers`(4)/`EntitySummarizers`(11)/`InventorySummarizers`(9)/`SessionSummarizers`(9)。每个 String `summarize` + typed `project`。
- **W4 packet_view**: tap 时 `projectStructured` 算好 → `MessageSnapshot`+`PacketJournal.Entry` 加 `Map fields`(reference-free:PacketView 已是不可变拷贝)→ `packet_view`(R3)读。**砍掉 tier 假参数**(只存 fields 时无存储支撑,不做静默 no-op 参数)。
- **W5 veto**: board `PacketSendSignal`(Cancellable,payload=包 FQCN)+ `BoardTraceLink.publishPacketSend`(专用反射 handle,与 chat 独立)+ `ActionManager.sendRawPacket` veto(**连接检查在先**,不连接不发假 veto)+ ToolRegistry 解包 ExecutionException 报 `vetoed:`。**所有出站发包(含 typed)自动过 veto**。
- **W6 typed send_***: 4 个(`send_client_status` 重生/`send_held_item`/`send_close_window`/`send_dig`)+ 共享 `sendTyped` helper。**4 gate 表**:Ring **R1** + CapabilityCatalog **CAP_NETWORK_SEND**(核实:send 类用这个,不是 CAP_WORLD_WRITE)+ SeToolRequirement **L3 HIGH** + ToolRegistry 注册。`PolicySideTableDriftTest` 绿。
- **W3 B 层**: `FxMoveSummarizers`(S24/S25/S2A/S28/S29 + S14 移动族 base+S15/S16/S17 **共享一个摘要器**;delta/32、angle*360/256;look 只在 `func_149060_h()` 为真时出)。
- **测试**: `./mvnw -pl board,core test` → core **605** 绿 / board **204** 绿,skip=0。新增 ~34 teeth。**maven 坑**:单 `-pl core` 会用陈旧 board jar 假失败(BoardTraceLinkTest 找不到 PacketSendSignal)→ 用 `-pl board,core` 或先 `-pl board install -DskipTests`。
- **诚实边界(defer,不是漏)**: ① **W6 余 6 个 typed 工具**(use_entity/place/click/creative/entity_action/abilities):ctor 需 Entity/ItemStack/PlayerCapabilities → 需"游戏线程解析游戏对象"机制 + 只能 live 验;现走 `send_raw_packet` 兜底。② entity-only-id 包(S19/S43/S49/S0A)id 只能 `getEntity(World)`,reference-free 下不硬凑。③ S3B/S3D 混淆 int mode 归 B 层(A 层平铺会误导)。
- **并发教训(再次验证)**: fan-out 只读研究安全(编目 108 包/查 getter 都成功);**输出大的 agent 必挂**——两轮 workflow 各有 2 路 retry 到爆(bp-entity 433k/bp-tools-security 671k/w3/w6),TaskStop 止损 → 拆细重跑 or 主 agent 自己做。**写代码始终串行**(共享 gate 表/registerInto 并行必撞车)。
- **下一步候选**: (1) 推 origin(9 提交待 owner 确认)。(2) W6 余 6 个 typed 工具的游戏线程解析机制 + live 验。(3) E.3 Board.init 待 ADR。
