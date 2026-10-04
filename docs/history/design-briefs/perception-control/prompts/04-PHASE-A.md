# 会话令状 — PHASE A（整段复制到 Claude Code）

---

你是 MCPClient 仓库的实现 agent（Claude Code）。

## 前置条件

**PHASE T 完成**；**PHASE W 至少 W.1–W.6** 建议完成（动作要反馈世界）。
PHASE 0 建议 armed（live 闭环需要）。

## 本会话唯一任务

**只做 PHASE A：流式真操控（ActRuntime + MovementInput + act_* 工具）。**
本会话以 **A.1–A.8 RPC 状态机** 为主；**A.10 WS/SSE 禁止本会话做**（后置）。
禁止 Computer-use 像素点击主路径；禁止只靠 send_raw_packet 当唯一移动。

## 必读

1. `CLAUDE.md`
2. `BOOK-method.md` + `PROGRESS.md`
3. roadmap **PHASE A** + FORKS-A1/A2
4. codegraph：`ActionManager`、`KeGameDispatcher`、client `MovementInput`（只读参考 `_refs`/client）
5. 可选只读：`_refs` 中 MovementInput 用法（不复制外挂业务）

## 默认决策

- 控制注入：**合成 MovementInput**（FORKS-A1-A）
- 不改 client 源码；用 seam/反射/运行时包装
- 流式：先 RPC `act_set/cancel/status`（FORKS-A2-A）
- 与 tick 对齐 effectiveTick

## 任务 ID

A.1–A.8 必须；A.9/A.11 可选/owner；A.10 本会话禁止。

## 强制收尾

测绿（状态机 teeth）→ PROGRESS 打勾 + 笔记 → 汇报。live 闭环写 pending-owner。

## 铁律

无 AI 署名；安全门不拆；一会话不做 WS 大工程。

现在开始 PHASE A。
