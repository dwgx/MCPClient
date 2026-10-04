---
doc: external-doctrine
title: 外部教义裁定 — 采纳/调校/拒绝的四个来源
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: cc-workflow-guide.md
    when: 你想看这些教义具体怎么落进我们的工作流
read_if: 你想知道我们从 karpathy/mattpocock/ECC 借了什么、砍了什么、为什么砍——尤其别把被拒的东西又加回来。
---

# 外部教义裁定

> dwgx 引入四个外部来源,要求"只采纳一些自己的、强力 review、对项目不好的一定去掉"。
> 本文是裁定记录:每一项标 **采纳 / 调校 / 拒绝** + 理由。
> **被拒的项要读理由**——不然下一个 AI 会把砍掉的东西又加回来。

## 来源

1. karpathy(本人仓库,主要是 ML 训练库)
2. multica-ai/andrej-karpathy-skills(Karpathy 风格的 AI 编码 4 原则)
3. mattpocock/skills(组合式工程纪律 skill 集)
4. affaan-m/ECC(harness 原生 operator 系统,278 skills + 67 agents 的大包)

## 裁定表

| 项 | 来源 | 裁定 | 理由 |
|---|---|---|---|
| Think-before-coding | karpathy-skills | 采纳 | = 我们的"先规划再确认";落进 grill skill |
| Simplicity-first(200行→50) | karpathy-skills | 调校 | 对业务代码采纳;**安全内核的防御性代码是必需的**,不套"删掉不可能情况的处理" |
| Surgical-changes(每行可追溯需求) | karpathy-skills | 采纳 | = 我们禁 drive-by refactor + client 纯 vanilla;落进 code-review 规格轴 |
| Goal-driven(tests-first 循环) | karpathy-skills | 采纳 | = 铁律#2 非空转回归测试;落进 diagnose skill |
| grilling(逼问决策树) | mattpocock | 采纳 | 正中 dwgx 指纹;落成 grill skill |
| TDD 红绿重构 | mattpocock | 采纳 | 落进 diagnose + code-review |
| code-review 双轴(标准+规格) | mattpocock | 采纳 | 落成 code-review skill,安全类升级对抗性 |
| domain-modeling / deep-modules | mattpocock | 采纳(理念) | 小接口藏大行为 = 我们 Board 的 PCB 契约思路,写进 guide |
| research(一手来源+并行) | mattpocock/ECC | 采纳 | 落成 research skill;= 我们混淆 Wave1/2 的做法 |
| continuous-learning / instinct(带置信度的直觉) | ECC | 采纳(理念) | = dwgx 要的"主人画像"+ ~/.claude 记忆;落进 owner-profile + memory,不装它的系统 |
| security-review / AgentShield(密钥扫描) | ECC | 采纳(理念) | 我们管 HMAC key/token,值得有扫描意识;写进 code-review 标准轴,暂不装 npx 工具 |
| ECC 整包(278 skills/67 agents/hooks/dashboard) | ECC | **拒绝** | 违反"只采纳一些"+ 会污染工作区;它是庞大 operator 系统,我们只要概念不要系统 |
| karpathy ML 仓库(nanoGPT/llm.c/llama2.c) | karpathy | **拒绝** | 是 GPT 训练/推理库,与"控制活体 MC JVM"零关系 |
| skills/commands 塞满一屏 | ECC/通用 | **拒绝** | 只装 4 个金牌 skill(grill/code-review/diagnose/research);宁精勿滥,多了没人用 |
| Cursor rules / 多 harness 适配 | 各源 | **拒绝** | 我们只用 Claude Code,不需要跨 harness 层 |

## 落地位置

- 4 个 skill:`.claude/skills/{grill,code-review,diagnose,research}/SKILL.md`
- 提示智慧提炼:`cc-workflow-guide.md`
- "直觉/画像"理念:`../project/owner-profile.md` + `~/.claude` 持久记忆

## 相关
- 工作流手册:`cc-workflow-guide.md` · 主人画像:`../project/owner-profile.md`
- 变更记录:`../project/governance-log/README.md`
