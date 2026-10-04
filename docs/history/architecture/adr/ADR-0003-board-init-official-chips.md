---
doc: adr-0003
title: ADR-0003 — Board.init() 默认安装官方 chip 名册(PHASE E.3)
layer: reference
status: authoritative
updated: 2026-07-16
parent: ../../README.md
read_if: 你想知道"为什么 Board.init() 启动时会自动装两个诊断 chip、什么时候定的、能不能改"时读本篇。
---

# ADR-0003:Board.init() 默认安装官方 chip 名册(PHASE E.3)

- **状态**:accepted(dwgx 2026-07-16 明确指令:"当然要走 ADR、让他生效、写留痕、认这两个 chip")
- **日期**:2026-07-16
- **触及 ARCHITECTURE-LOCK**:**L2**(board PCB 冻结契约;加法式改动,不动公共签名)

## 背景(为何要改)

PHASE E 的 E.3 一直挂着。`OfficialChips.install(matrix, trace)` 早已实现并测试覆盖
(`board/.../chips/OfficialChips.java`,`OfficialChipsTest` 7 测绿),它把框架内置的两个演示
chip —— `ChatLogChip`(在 Trace 上观察发出的聊天)与 `TickCounterChip`(数 tick)—— 装上并使能,
带 `mcp.board.officialChips` opt-out、idempotent-friendly。

但它**只在测试里被调用**:`Board.init()`(`board/.../Board.java:49`)从不 delegate 到它,所以真实
启动时名册永远是空的。E.3 的完成定义就是"`Board.init()` 一行 delegate 到 `OfficialChips.install`"。

之所以一直 defer:`Board` 是 ARCHITECTURE-LOCK §L2 冻结的 8 个 PCB 契约类之一。虽然本改动是
**加法式**(§L2 明说"加法式扩展自由":只在 `init()` 方法体内加一行调用,不改任何公共/protected
签名、不动 final),但它**改变了这个冻结门面的启动默认副作用**(启动后 board 多出两个已使能 chip)。
为免后续 review/AI 误以为有人偷改了冻结类,按 §7 闸门立此 ADR 留痕。

## 决策

在 `Board.init()` 的既有主体末尾**加一行** `OfficialChips.install(FEATURES, TRACE)`,让框架启动即装上
官方 chip 名册。不改 `init()` 签名、不改任何 L2 冻结类的公共 API,纯方法体内加法。

- 默认装:`ChatLogChip` + `TickCounterChip`(均为诊断类,不改游戏玩法)。
- opt-out 不变:`-Dmcp.board.officialChips=false|none|off|0` 装 0 个,启动仍成功。
- idempotent 不变:二次 `init()` 仍是 no-op(`init()` 顶部 `started` 短路 + `OfficialChips` 按 id 去重双保险)。

## 后果

- **正面**:E.3 闭环 —— 框架"开箱即用",一装即有两个诊断 chip 在跑,不再需要手工 wire;
  `OfficialChips` 从"仅测试调用的死码"变成真正的启动路径消费者。北极星"无 advertised-but-dead"再收一处。
- **负面 / 代价**:`Board.init()` 的默认副作用变大(启动后 board 非空)。缓解:两个 chip 都是中性诊断、
  可一键 opt-out、有测试锁住;且它们本就是框架自带演示 chip,装它们正是设计意图。
- **不影响**:L1(core NT 分层)/L3(core↔board 零硬依赖:`OfficialChips`/`ChatLogChip`/`TickCounterChip`
  全是 board 自身类,core 不参与)/L4(安全脊柱,完全不碰)。`Board`/`Matrix`/`Trace` 等 8 个契约类
  公共签名零改动。

## 替代方案(为何不选)

- **什么都不做(维持 defer)**:E.3 永远半成品,`OfficialChips` 永远只被测试调用 = advertised-but-dead,
  违背北极星。dwgx 明确要 let it work,不选。
- **默认 opt-out(装 0,靠 flag 才装)**:把"开箱即用"反转成"开箱空",违背 `OfficialChips` 自身
  "fresh install 即有用"的设计意图;且 opt-out 通道已存在,想要空板的场景用 flag 即可。不选。
- **不走 ADR、直接改 + commit 说明交代**:改动虽是加法,但改的是 §L2 冻结类的启动行为;§7 闸门要求
  留痕,dwgx 亦明确要 ADR。不选。

## 是否触及 ARCHITECTURE-LOCK 冻结项

- **触及**:是(加法式,不动签名)
- **触及哪些冻结项**:**L2**(board PCB 契约的 `Board` 类)—— 仅在 `init()` 方法体内加一行,
  不改其公共/protected 签名、不去 final。未触及 L0/L1/L3/L4。
- **用户确认记录**(L2 闸门 = ADR + 用户确认,非 L4 的三次;已满足):
  - 记录:2026-07-16 dwgx 原话摘要 —— "当然要走 ADR 了、让他生效、按照 ADR 吧、在 Board.init() 加入、
    写 ADR 留痕";并在前一轮明确"认这两个 chip"。L2 非安全脊柱,一次明确指令即满足闸门。

## 落地清单

1. `Board.init()` 主体末尾加 `net.marcloud.mcp.board.chips.OfficialChips.install(FEATURES, TRACE);`。
2. 配非空转回归 `BoardInitInstallsOfficialChipsTest`:`Board.init()` 后 `Board.features()` 含
   ChatLog+Ticker 且已使能;opt-out flag 下 `init()` 后名册为空。撤掉那行 install → 测试挂。
3. governance-log 留痕(`2026-07-16-board-init-official-chips.md`)。
4. STATUS / PROGRESS 的 E.3 打勾;PACKET/PROGRESS 相关描述同步。
