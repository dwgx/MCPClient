---
doc: governance-2026-07-16-board-init-official-chips
title: 治理变更 — Board.init() 默认安装官方 chip 名册(PHASE E.3,ADR-0003)
layer: archive
status: archived
updated: 2026-07-16
parent: README.md
read_if: 你想追溯"为什么 Board.init() 启动会自动装 ChatLog+Ticker 两个 chip、走了什么闸门"的来龙去脉。
---

# 治理变更 2026-07-16 — Board.init() 默认装官方 chip 名册(E.3)

## 改了什么
- **`board/.../Board.java` `init()`**:主体末尾加一行
  `net.marcloud.mcp.board.chips.OfficialChips.install(FEATURES, TRACE);` ——
  框架启动即装上内置名册(`ChatLogChip` + `TickCounterChip`,均诊断类)。
  **加法式**:不动 `init()` 或任何 §L2 冻结类的公共签名,仅方法体内加一行。
- **回归**:新增 `BoardInitInstallsOfficialChipsTest`(2 测,非空转)——init 后名册含两 chip 且已使能;
  opt-out flag 下名册为空。撤掉那行 install → 第一条挂。
- **ADR-0003** 记录背景/决策/替代方案,accepted。

## 为什么
E.3 一直半成品:`OfficialChips.install` 早已实现 + 测试绿,却只被测试调用,`Board.init()` 从不
delegate,真实启动名册永远空 = advertised-but-dead。让它生效即闭环 E.3、消除死码。

## 触碰的锁 + 闸门
触 **L2**(board PCB 契约的 `Board` 类;加法式,不动签名)。L2 闸门 = ADR + 用户确认(**非** L4 的
三次)。走完:ADR-0003(accepted)+ dwgx 2026-07-16 明确指令("当然要走 ADR、让他生效、写留痕、
认这两个 chip")+ 本留痕 + 非空转回归。未触 L0/L1/L3/L4。

## 边界诚实
headless 绿(`Board.init` + 名册安装 + opt-out 都测过)。真机启动时名册在活 board 上的行为随
board 整体 live 验证一起看,不单独 pending。
