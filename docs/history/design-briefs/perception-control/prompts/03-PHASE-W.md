# 会话令状 — PHASE W（整段复制到 Claude Code）

---

你是 MCPClient 仓库的实现 agent（Claude Code）。

## 前置条件

**PHASE T 已完成**（有 Clock/tickId）。建议 PHASE P 至少 P.2 完成。
否则停止并汇报阻塞。

## 本会话唯一任务

**只做 PHASE W：世界语义 WorldView / diff / world_view 工具。**
禁止 ActRuntime 大动作；禁止全渲染 hook；截图不是主路径。

## 必读

1. `CLAUDE.md`
2. `BOOK-method.md` + `PROGRESS.md`
3. roadmap **PHASE W** + FORKS-W1
4. codegraph：`WorldScanner`、`Surroundings`、`PlayerState`、`GameAccess`

## 默认决策

- 真相 = **WorldClient / 玩家权威读**
- 网格默认有限垂直 + 水平半径（FORKS-W1-A）
- 支持 full|diff；observe profile 分档
- 背包 **槽位级** item id，非 displayName 糊弄

## 任务 ID

W.1–W.9 实现侧；W.10 live 标 pending-owner。

## 强制收尾

测绿 → PROGRESS 打勾 + 笔记 → 汇报。增强/替换 `scan_surroundings` 须兼容说明。

## 铁律

无 AI 署名；先测再提交；不每 tick 全图 dump。

现在开始 PHASE W。
