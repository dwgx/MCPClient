---
doc: govlog-2026-07-12-doctrine
title: 治理变更 — 引入外部教义 + 工作流手册/画像/skills/变更日志
layer: archive
status: archived
updated: 2026-07-12
parent: README.md
read_if: 你想追溯 2026-07-12 那次"引入 karpathy/mattpocock/ECC 教义并重构治理文档"的来龙去脉。
---

# 治理变更 2026-07-12 — 工作流教义引入

## 改了什么
- CLAUDE.md 新增两节:"工作方式(授权)" + "接手启动序列"(前几轮,已 3 次确认)。
- 新增 `.claude/skills/{grill,code-review,diagnose,research}/SKILL.md`(4 个金牌 skill)。
- 新增 `docs/reference/cc-workflow-guide.md`(全面 how-to 手册)。
- 新增 `docs/reference/external-doctrine.md`(四来源采纳/调校/拒绝裁定)。
- 新增 `docs/project/owner-profile.md`(dwgx 画像,含坦率不足,本地私有)。
- 新增 `docs/project/governance-log/`(本记录体系)+ `_templates/governance-record.md`。
- 新增 `_templates/README.md`(模板决策树)。
- 更新 `.ai-notes/README.md` + `docs/README.md` 索引。

## 时间 / 谁
2026-07-12;窗口 `ad54b25a`(接手 `2026-07-12-meta-arch-and-obfuscation` 交接后)。

## dwgx 为什么授权这么改
他要:①引入 karpathy/mattpocock/ECC/andrej-karpathy-skills 四个来源的提示智慧,
强力 review、对项目不好的一定砍;②我们提示词"特别烂",要全面提升;③skills 装成可跑的
命令由 agent 自主自约束;④建变更记录体系(固定文档改动留痕 + 为什么);⑤建仓库主人画像
(含不足),让 AI 懂他、能补位;⑥先做最大最全的模板。他非常强烈允许,要 ultrathink 分化需求。

## 思维链(为什么这么设计而不是别的)
- 只装 4 个 skill 而非 ECC 整包:宁精勿滥,整包会污染工作区、违反"只采纳一些"。裁定见 external-doctrine。
- Karpathy"简约/删防御代码"对安全内核调校保留:内核防御性代码是必需的,不能照搬业务代码规则。
- 画像放 .ai-notes(gitignored)不进 git:它评判 dwgx,私有。
- 变更日志只记"固定+被索引+实质"改动:避免记成噪音(dwgx 明确要求"AI 自建的非索引文档不用记")。
- 老文档处理:architecture 是活文档不动;study/audits/design-briefs 早已 archived,无需再动。
  本轮是"新增教义为地基",不是推翻旧结构。

## 确认轨迹
- CLAUDE.md 两节:已获 3 次确认(前几轮)。
- CLAUDE.md 反向指向 guide(W1):**尚未做**,需另一轮独立 3 次确认(铁律#5),已 gate。
- 未动冻结骨架,无需 ADR。

## 验证
- doc_lint:全绿(53 md,零漂移)。
- 无装饰 emoji(strip_emoji 复查零残留)。
- 未动代码,无编译/测试影响。

## 追加:三路对抗复审后的加固(同日,同 campaign)
派 workflow 三路(结构/内容/反方-过度工程)对抗复审,dwgx 三次确认允许修逻辑错误。修了:
- **去重**(HIGH):CLAUDE.md 工作方式节的 how-to 与 guide 重复 ~50%,会漂移。压成纯授权+红线+指针,how-to 全归 guide。
- **交接指针**(HIGH):STATUS 最新交接指向治理层之前的 handoff,新 AI 会错过治理层。补指向 governance-log intake。
- **分层启动序列**(MEDIUM):接手序列拆必读层(<5min)+ 深挖层(大改动才升级),考古降为按需,免烧上下文。
- **skill 闭环链**(HIGH):新增 guide §7 grill→实现→code-review→diagnose→提交闭环;CLAUDE.md 启动序列加 skill 触发。
- **非空转测试技法**(HIGH):guide §6 补"先写复现测试看红→修→看绿"技法 + KI-4 实例。
- **画像可执行化**(HIGH):owner-profile 不足节改成"症状→具体工具动作"(保留坦率,dwgx 明确要)。
- **governance-log 判据收紧**:改成死清单 + 创建 vs 改动区分 + 定位为历史档案非必读。
- 路由表补 guide/profile 行;grill/diagnose skill 补 AskUserQuestion 应对 + dev_probe。

**明确否掉的 reviewer 建议**(与 dwgx 直接指令冲突,不采纳):砍 owner-profile 不足节、冻结画像、
删 skill/合并进 CLAUDE.md、砍治理层内容。这些违背"坦率含不足/living/只装4个金牌skill/先做最大模板"。
