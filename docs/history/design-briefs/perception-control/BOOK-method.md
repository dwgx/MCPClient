---
doc: perception-control-book
title: 方法书 — 感知·操控封顶线如何推进
layer: reference
status: authoritative
updated: 2026-07-14
parent: README.md
read_if: 你是 owner / Claude / Grok，要理解「这套主线怎么推、做完怎么打勾、什么叫完成」。
---

# 方法书 — 感知·操控封顶线如何推进

> 这本书回答三件事：
> **1）我们在推什么**
> **2）用什么流程推**
> **3）做完了怎么在进度板上留下痕迹**

---

## 1. 我们在推什么（一句话）

把 MCPClient 从「能连上活客户端的强内核」推到：

**AI 靠符号世界 + 全包深读 + 唯一 tick 时间轴 + 流式真输入操控游戏；截图只校验；永不靠 Computer-use 当大脑。**

法律全文（架构、FORK、验收 S1–S6）：

`../perception-control-max-roadmap.md`

---

## 2. 角色怎么分工

| 角色 | 干什么 | 不干什么 |
|------|--------|----------|
| **dwgx（你）** | 选当前 Phase、复制提示词、live 验收、拍板 FORK | 不用自己写长提示 |
| **Claude Code** | 读令状 + 法 + 进度板；写代码；测绿；**PROGRESS 打勾**；短汇报 | 一次做多个 Phase；拆安全默认；改 client 修 bug |
| **Grok（或 review）** | 出法/令状、验收 diff、抓「假完成」（如登记≠arm） | 不替代你 live 点窗 |

---

## 3. 推进流程（固定环）

```
① 人打开 prompts/NN-PHASE-*.md → 全选复制 → 新 Claude 会话
② Claude 只做该 Phase；对照 roadmap 完成定义
③ 测试绿 → commit（无 AI 署名）
④ Claude 更新 PROGRESS.md：对应 [ ] 改成 [x]，写「实现笔记」一行
⑤ 人/Grok 验收（headless 必过；live 按 Phase 要求）
⑥ 通过 → 人复制下一张 prompts → 回到 ①
```

### 3.1 为什么「一会话一 Phase」

- 上下文装得下，不会略读 400 行法
- 完成定义可勾选，假完成难混
- 失败时只回滚一个 Phase

### 3.2 什么叫「做完」

必须同时满足：

1. roadmap 该 Phase **完成定义**达标
2. `PROGRESS.md` 对应 ID 已 `[x]`
3. 测试：该动模块 `mvn test` 绿（live-only 已标注）
4. 汇报含：改了什么、测了什么、选了哪个 FORK、已知未做

**仅有 commit 没有打勾 = 进度板视为未完成。**

---

## 4. 进度板规则（PROGRESS.md）

### 4.1 勾选

- 未做：`- [ ] P0.1 ...`
- 已做：`- [x] P0.1 ...`
- **谁做完谁勾**：默认 Claude；你 live 项（如 P0.5）由你勾或 Claude 写 `pending-owner`

### 4.2 实现笔记（每个 Phase 一小节）

Claude 在该 Phase 的「实现笔记」下追加：

```markdown
### 笔记 YYYY-MM-DD — PHASE 0
- HEAD: <hash>
- 完成: P0.1 P0.2 P0.3 P0.4
- 测试: ./mvnw -pl core test → OK
- FORK: 无（或写选用的）
- 未做/风险: P0.5 live 待 owner
```

### 4.3 当前指针

`PROGRESS.md` 顶部 **「当前 Phase」** 必须是唯一进行中的 Phase。
开新 Phase 时 Claude 改指针；人也可以改。

---

## 5. 会话令状规则（prompts/）

每个 `prompts/NN-PHASE-*.md`：

1. **从「你是…」到文末整段复制**（含铁律）
2. 内含：必读路径、唯一范围、禁止、完成定义、**强制改 PROGRESS**
3. 不在提示词里塞其他 Phase 任务

新增 Phase 提示词时：抄 `00-PHASE-0.md` 结构，只换范围与 ID 列表。

---

## 6. 与仓库其他机关的关系

| 机关 | 关系 |
|------|------|
| `CLAUDE.md` | 全局铁律优先于本法 |
| `STATUS.md` | Phase 完成后更新 HEAD/测试数/一句话进度 |
| `known-issues.md` | KI-4/KI-9 等状态与代码对齐 |
| `session-status.py` | 接手前对账 |
| 本分区 | **只服务 perception-control 主线** |

---

## 7. 顺序铁律（不要跳）

```
PHASE 0 补丁 arm  →  T 时间轴  →  P 协议  →  W 世界  →  A 操控  →  E board
```

- **0 不做完**：live 进世界验收无意义
- **T 不做完**：包/世界/动作没有统一时间，后面全是工具堆
- **P 与 W** 在 T 之后可谨慎并行，但 **Clock 契约只能有一份**
- **A 的流式 WS** 必须在 A 的 RPC 动作稳定之后

---

## 8. 禁止清单（写进每张提示词）

1. 改 `client/` 修移植 bug（走 compat）
2. 默认永久 `trustUnsigned` / 拆空 keyring fail-safe
3. 全渲染方法 hook 当主感知
4. 第二套 tick / 第四套 event bus
5. 一次会话做多个 Phase
6. 勾选未测绿的任务
7. AI 署名进 commit

---

## 9. 验收口径（人怎么看一眼）

| Phase | 你怎么验 |
|-------|----------|
| 0 | 测试绿 +（可选）live 进单人；STATUS 不说假话 |
| T | `clock_now` / timeline 有 tickId；包事件带 tick |
| P | 不问截图能答聊天/血量包摘要；inbound 非零（live） |
| W | 不问截图能答面前方块/实体/主手 |
| A | 连续走+挖不靠截图点点 |
| E | ChatLogChip 等 live 有信号 |

终极 S1–S6 见 roadmap §0.3。

---

## 10. 故障怎么退

| 现象 | 动作 |
|------|------|
| Claude 做超范围 | 停；PROGRESS 只勾真正完成的；下会话收紧令状 |
| 测红 | 不勾选；修到绿 |
| 假完成（如 KI-4 未 arm） | Grok/你打回；PROGRESS 改回 `[ ]` |
| 上下文满 | handoff 模板；新窗只贴**同一 Phase** 令状 +「接 PROGRESS 继续」 |

---

## 11. 一句话

> **法在 roadmap，进度在 PROGRESS，每天只贴一张 prompts 令状；做完打勾写笔记，再进下一 Phase。**
