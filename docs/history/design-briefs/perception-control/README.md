---
doc: perception-control-zone
title: 感知·操控封顶线 — AI 工作分区入口
layer: index
status: authoritative
updated: 2026-07-14
parent: ../perception-control-max-roadmap.md
read_if: 你要推进「时间轴/协议/世界语义/流式操控」或把任务丢给 Claude Code——先读本文 30 秒。
---

# 感知·操控封顶线 — 分区入口

> 这是 `.ai-notes` 里为 **perception-control 主线** 单独划的工作区。
> **人（dwgx）**：只看本页 + 复制 `prompts/` 里当前 Phase。
> **Claude Code**：按提示词读法/进度板/令状执行，做完在 `PROGRESS.md` 打勾。

## 文件地图

| 文件 | 作用 |
|------|------|
| **[BOOK-method.md](BOOK-method.md)** | **方法书**：怎么推动、谁干什么、打勾规则、禁止项 |
| **[PROGRESS.md](PROGRESS.md)** | **进度板**：全部任务 ID 的 `[ ]` / `[x]`，Claude 做完必须改 |
| **[prompts/](prompts/)** | **会话令状**：整段复制给 Claude，一会话一 Phase |
| [../perception-control-max-roadmap.md](../perception-control-max-roadmap.md) | **法**：完整架构、FORK、完成定义、验收 S1–S6 |

## 你现在该丢哪张提示词

| 顺序 | 复制文件 | Phase |
|------|----------|--------|
| 0+ T + P | 已完成（见 PROGRESS） | — |
| **现在推荐** | **`prompts/99-PARALLEL-GAPFILL-AND-CONTINUE.md`** | **并行查漏 + 做 PHASE W** |
| 单 Phase W | `prompts/03-PHASE-W.md` | 仅世界语义（不扇出审计） |
| 其后 | `prompts/04-PHASE-A.md` | 流式真操控 |
| 再后 | `prompts/05-PHASE-E.md` | board 合流 |
| 历史 | `00`/`01`/`02` | 已收口，勿重复大做 |

**规则：同一时间只开一个 Phase 提示词。** 做完验收再开下一张。

## 一键操作（人）

1. 打开 `prompts/00-PHASE-0.md`（或当前 Phase）
2. **全选复制** → 粘贴到 Claude Code 新会话
3. 等他汇报 + 改 `PROGRESS.md`
4. 你看 live / 或让 Grok review → 再丢下一张

## 关联

- 总法：`../perception-control-max-roadmap.md`
- 现状：`../../../STATUS.md`
- 已知问题：`../../project/known-issues.md`（KI-4 / KI-9 / KI-1）
