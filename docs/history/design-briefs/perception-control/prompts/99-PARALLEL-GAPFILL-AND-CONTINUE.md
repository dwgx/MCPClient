# 会话令状 — 并行查漏补缺 + 续作（整段复制到 Claude Code）

---

你是 MCPClient **主 agent（编排者）**。本会话目标：

1. **并行查漏补缺**（用 workflow / subagent 扇出，只读+写报告为主，大代码回主线）
2. **继续未完成主线**：修文档漂移 → 可选 live 清单 → **PHASE W 世界语义实现**（主交付）
3. 全程遵守 `CLAUDE.md` 铁律 + `perception-control/BOOK-method.md` + roadmap

**禁止：** 一次做完 W+A+E；默认 trust-unsigned；改 client 修 bug；全渲染 hook；workflow 吐超大 Java 块（主线写代码）；私钥进 git / 日志。

---

## 0. 必读（5 分钟内）

1. `CLAUDE.md`
2. `docs/history/design-briefs/perception-control/PROGRESS.md`
3. `docs/history/design-briefs/perception-control-max-roadmap.md` §0 + PHASE W
4. `python -X utf8 .ai-notes/tools/session-status.py`
5. `git log --oneline -15`；HEAD 应对 `56a2789` 或更新

---

## 1. 已知问题清单（开工依据 — 不要假装已全部修好）

### A. 主线未完成（产品）

| ID | 问题 | 动作 |
|----|------|------|
| **W.\*** | PHASE W 世界语义 **整包未做** | **本会话主交付**（W.1–W.9；W.10 live=pending-owner） |
| **A.\*** | 流式真操控未做 | **本会话不做**；可派 research 出接口草案，禁止实现 |
| **E.\*** | board 合流未做 | 本会话不做实现；可 research |
| **P.1 live** | KI-9 真服 inbound 是否非零 **pending-owner** | 能 live 则验；不能则清单化步骤给 owner |
| **P.8** | 高层 packet→事件 reducer **defer** | 可 design-only，默认不实现 |
| **V.\*** | 视觉校验低优先 | 跳过 |

### B. 文档 / 进度板漂移

| 问题 | 动作 |
|------|------|
| `PROGRESS` 指针仍写「未提交 / 待添 W 令状」过时 | 主 agent 修正指针→ PHASE W in_progress |
| STATUS core 447 vs live ~451；client **20 vs 14** | **C.1 必须修** |
| STATUS 部分段落仍写 KI-4 planned / KI-9 未做等陈旧句 | 对账 known-issues + 代码，删假进度或改诚实状态 |
| C.2–C.4 横切未勾 | 实现 W 时顺带检查 Ring/Annotations；Journal 只读 |

### C. 安全 / compat 债

| 问题 | 动作 |
|------|------|
| **密钥曾泄露并轮换** | 确认 git 无私钥；secrets 仅本地；报告是否仍有硬编码旧钥 |
| **TUF L0–L3 新且大** | 派 subagent **对抗审查**（有罪推定），列 HIGH/MEDIUM，**本会话只修 CRITICAL/明确 bug**，大 redesign defer |
| **KI-1** 补丁在 vs「WON'T-FIX-NOW」叙事冲突 | 查 known-issues + 补丁是否默认 arm；统一诚实文案 |
| **KI-10** 已有 L0 canary 绑定 | 确认文档不再说「完全未绑」；边界写清 |

### D. 工程卫生

| 问题 | 动作 |
|------|------|
| dwm-compose target 残骸 / 杂文件 | 列清单，非必要不删 gitignored 大目录 |
| `*LiveIT` 默认 skip | 保持；live 步骤写进笔记 |
| 双轨观测：FltManager 类名 log vs PacketJournal | research 是否重复/谁为真源 |

---

## 2. 并行波次（必须先扇出，再主线写 W）

### 波次 1 — 只读并行（同时开 4～6 个 subagent / workflow）

每个 subagent **只读**，产出带证据的报告（路径+行号/测试名），**禁止大改代码**。

| Agent | 任务 | 产出 |
|-------|------|------|
| **S1 STATUS/PROGRESS 审计** | session-status + 扫 STATUS/PROGRESS/known-issues 矛盾句 | 必须改的文件列表 + 建议 diff 要点 |
| **S2 PHASE P 回归** | 读 NettyTap installBefore、PacketJournal、Filter、ObserveTools；跑相关 test | P 是否真绿；P.1 live 步骤；缺口 |
| **S3 TUF/compat 红队** | L0 canary、L1 chain、L2 root、L3 snapshot；钥资源；KI-4/KI-1 arm 路径 | 发现列表 severity；有无 CRITICAL |
| **S4 WorldView 设计落地** | 读 WorldScanner/PlayerState/GameAccess + roadmap W；对照 client WorldClient API | W.1–W.9 类/方法草图 + token 预算建议 |
| **S5 Act 预研（只设计）** | MovementInput 注入点（client 只读 + _refs 模式） | A 接口草案一页，**不写生产代码** |
| **S6 安全扫一眼** | git ls-files 是否含 .key/priv；SeProtectedObjects 是否漏新包 observe/compat | 是/否 + 路径 |

**主 agent 综合波次 1** → 写短文到
`docs/history/design-briefs/perception-control/GAP-REPORT.md`
（新建；含：P0 问题、已修、defer、W 实施顺序）

### 波次 2 — 主 agent 串行实现（代码）

按顺序，**不可跳**：

1. **Doc fix（C.1）**
   - STATUS 测试数对齐 session-status（client=14，core=实测）
   - PROGRESS 指针改为 `PHASE W` / `in_progress`，修正过时「未提交」
   - known-issues KI-4/KI-9/KI-1 与代码一致

2. **PHASE W 实现（主交付）** — 严格按 roadmap W.1–W.9
   - WorldView schema：Self / LocalGrid / Entities / Inventory slots / Ray / Env
   - Diff + observe profile
   - MCP `world_view`（增强/替换 scan_surroundings，兼容说明）
   - 非空转测试；client **零 diff**
   - Ring/Cap/ToolAnnotations（C.2）

3. **仅修 S3 标为 CRITICAL 的 bug**（若有）；其余进 GAP-REPORT defer

4. **测**
   `./mvnw -pl core test`（动 board 再 `-pl board`）必须绿

5. **收尾**
   - PROGRESS：W.1–W.9 打勾；W.10 pending-owner
   - 实现笔记 + HEAD
   - 短汇报（见下）
   - commit 清晰（无 AI 署名）；**不要 push 除非 owner 要求**

### 波次 3 — 可选 live（有游戏环境才做）

- 进世界后：`world_view` 无截图可读面前方块/实体/主手
- 若可连服：`packets_tail` inbound 非零 → 勾 P.1 live
- 否则笔记写 pending-owner 步骤（复制粘贴命令）

---

## 3. 实现约束（W）

- 真相 = **WorldClient / 玩家权威读**（不是纯包重建）
- 网格默认有限垂直 + 水平半径；支持 full|diff
- 背包槽位级 item id + count + damage
- 禁止每 tick 全图 dump；禁止 Computer-use 主路径
- 大 Java 主 agent 写；subagent 只研究/审

---

## 4. 完成定义（本会话）

- [ ] GAP-REPORT.md 存在且覆盖问题清单 A–D
- [ ] STATUS/PROGRESS 漂移已修（至少 client 14、HEAD 正确、指针 W）
- [ ] PHASE W.1–W.9 代码+测试绿
- [ ] PROGRESS 打勾 + 笔记
- [ ] 未做 A/E 实现（除非 owner 改令）
- [ ] 汇报：

```
## 并行查漏补缺 + 续作 汇报
- HEAD:
- 波次1发现 TOP5:
- 本会话已修:
- PHASE W 完成 ID:
- pending-owner:
- 建议下一令状: 04-PHASE-A 或 live 清单
- 测试: ./mvnw -pl core test →
```

---

## 5. 自动化调度口令（你自己执行）

```
波次1: 并行 spawn S1..S6（read-only / explore）
等待全部完成 → 写 GAP-REPORT
波次2: 主线 Doc → W 实现 → CRITICAL only
波次3: live 若可
打勾 PROGRESS → commit → 汇报停
```

失败两次换路；同一 approach 禁止死磕。workflow 不写大代码。

**现在开始：先跑 session-status，再扇出波次 1。**
