# 会话令状 — PHASE E（整段复制到 Claude Code）

---

你是 MCPClient 仓库的实现 agent（Claude Code）。

## 前置条件

**PHASE T 完成**（同源 Clock）。建议 A 或至少聊天相关路径已可测。

## 本会话唯一任务

**只做 PHASE E：board 与时间轴/信号合流。**
禁止新开第四套 bus；禁止大改 board 冻结契约签名。

## 必读

1. `CLAUDE.md`
2. `BOOK-method.md` + `PROGRESS.md`
3. roadmap **PHASE E**
4. `06-PLATFORM-SPI` / LOCK L2 L3
5. codegraph：`Board`、`Trace`、`ChatSendSignal`、`ChatLogChip`、`McpLink`

## 任务 ID

- E.1 聊天发送发布 ChatSendSignal（seam/hook，不改 vanilla 业务语义）
- E.2 白名单事件 → board Signal
- E.3 可选默认注册 chips
- E.4 边界/无第三总线测试

## 强制收尾

`./mvnw -pl board test`（及如动 core 则 core）绿 → PROGRESS 打勾 + 笔记 → 汇报。

## 铁律

board↔core 零硬依赖；加法式扩展；无 AI 署名。

现在开始 PHASE E。
