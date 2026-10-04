---
doc: adr-0001
title: ADR-0001 — L6 hardened posture 下强制 handle(堵自愿式绕过)
layer: reference
status: authoritative
updated: 2026-07-11
parent: ../../README.md
read_if: 你想知道 L6 句柄层为何在 hardened 下强制 handle、什么时候定的、能不能改时读本篇。
---

# ADR-0001:L6 hardened posture 下强制 handle

- **状态**:accepted
- **日期**:2026-07-11

## 背景(为何要改)

四维审计(见 `../../audits/` 及 STATUS 提交栈)发现 L6 对象句柄层是**"自愿式"gating**:
`ObManager.checkRequest`(`core/src/main/java/net/marcloud/mcp/core/ob/ObManager.java`)在请求**不带
`handle` 参数**时直接 `allowed()`。而 `HANDLE_OPS` 里登记的 6 个 debug 工具
(`debug_read_local`/`debug_write_local`/`debug_force_return`/`debug_suspend_thread`/`debug_pop_frame`/`debug_single_step`)
在无 handle 时会走 `DebugTools` 的按名解析(`findThread(name)`)路径——即**只要不传 handle,就完全绕过 L6**,
而 L6 的全部意义正是用冻结句柄闭合 jthread 名复用 TOCTOU。用 codegraph `explore` 已在源码级实证此绕过。

对一个自我定位为"7 层安全内核"的项目,L6 的 TOCTOU 防护对不传 handle 的调用是空的——是宣传与兑现的裂缝。

## 决策

给 `ObManager` 加**加法式**的 `strictHandles` 布尔姿态(默认 false=历史行为不变)。
`checkRequest` 在 `strictHandles==true` 且工具在 `HANDLE_OPS` 里、却无 `handle` 参数时 → **deny**
(而非放行到按名 TOCTOU 回退)。`McpCore.buildObjectManager` 在 `-Dmcp.core.hardened=true` 时
以 `strictHandles=true` 构造 ObManager。默认(dev)姿态与全部现有 headless 测试行为不变。

## 后果

- **正面**:hardened 姿态下 L6 从"自愿"变"强制",TOCTOU 防护不再能靠省略 handle 绕过,兑现安全宣传。
- **负面 / 代价**:多一个构造器重载 + 一个姿态标志(小复杂度);hardened 下调 handle-op 工具必须先
  `debug_open_thread` 开句柄,行为更严(这正是意图)。默认姿态零变化,故对现状零影响。

## 替代方案

- **方案 A(未选)**:无条件对所有无-handle 的 handle-op 都 deny。否决——会破坏默认 dev 姿态与现有
  `handleLessDebugCallIsUnaffectedByL6` 等测试,且默认全开姿态本就不承诺 L6 强制。
- **方案 B(未选)**:移除按名解析路径。否决——那会砍掉一个便利入口,且改动面大、破坏兼容。
- **什么都不做**:保留裂缝。代价=安全宣传名不副实,审计中危项长期挂账。

## 是否触及 ARCHITECTURE-LOCK 冻结项

- **触及**:是
- **触及哪些冻结项**:`ARCHITECTURE-LOCK.md` L4 安全脊柱(7 层权限模型 + fail-safe 语义)。本变更是
  **强化**该层(把 L6 从自愿变强制),不削弱、不改分层语义;属加法式增强。
- **用户确认记录**(L4 按闸门需明确确认):
  - 记录:2026-07-11,用户经 AskUserQuestion 就"L6 缺口如何处理"明确选择 **"现在就修"**
    (选项原义:hardened/strict posture 下让 checkRequest 对 HANDLE_OPS 工具强制携带 handle,缺失即 deny,配非空转回归测试)。
  - 判定:本变更是对 L4 的**强化**(而非削弱/重塑分层),默认姿态零改动,且有用户对该具体修法的明确授权 →
    满足闸门,accepted。若未来是**削弱** L4 的改动,仍需 CLAUDE.md 铁律③的 3 次明确确认。

## 落地与验证

- 代码:`ObManager`(strictHandles 字段 + 新构造器 + checkRequest 强制分支)、`McpCore.buildObjectManager`(接线 hardened→strictHandles)。
- 回归:`L6ObjectHandleTest.strictHandlePostureDeniesHandleLessHandleOp`(teeth-verified:在旧代码上会失败)。
- core 全量 254/254 绿(测试数以 `../../STATUS.md` 为准)。
