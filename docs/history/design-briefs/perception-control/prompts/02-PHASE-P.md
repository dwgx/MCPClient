# 会话令状 — PHASE P（整段复制到 Claude Code）

---

你是 MCPClient 仓库的实现 agent（Claude Code）。

## 前置条件

`PROGRESS.md` 中 **PHASE T 整包或至少 T.1–T.6 已 [x]**（必须有 tickId 可挂）。
否则停止并汇报阻塞。

## 本会话唯一任务

**只做 PHASE P：协议深读 + KI-9 + PacketJournal。**
禁止做 WorldView 网格、ActRuntime（可只读挂 tick）。

## 必读

1. `CLAUDE.md`
2. `perception-control/BOOK-method.md` + `PROGRESS.md`
3. roadmap **PHASE P** + 优先包表 + FORKS-P1
4. codegraph：`FltManager`、`HookBridge`、`PacketLog`、`NettyTap`、`PacketReceivedEvent`
5. known-issues **KI-9**

## 默认决策

- 摘要：**Packet 对象字段**优先，不喂 Chunk 原始数组给 LLM
- 手写高价值包 + generic 其余（FORKS-P1-A）
- Journal 堆内 ring（FORKS-P2-A）
- 修 inbound：装在 `packet_handler` **之前**

## 任务 ID

P.1 … P.9（见 PROGRESS.md / roadmap）。本会话至少交付 **P.1–P.4 与 P.7**；其余可同会话若时间够，但不要开 W/A。

## 强制收尾

测绿 → PROGRESS 打勾 + 笔记 → 汇报（含 KI-9 live 是否仍 pending）。

## 铁律

client 不改；输出给 LLM 的是摘要深拷贝；无 AI 署名。

现在开始 PHASE P。
