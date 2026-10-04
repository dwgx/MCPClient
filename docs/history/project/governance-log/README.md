---
doc: governance-log-index
title: 治理变更记录 — 谁改了固定文档、什么时候、dwgx 为什么授权
layer: index
status: authoritative
updated: 2026-07-12
parent: ../../README.md
next:
  - path: ../../../_templates/governance-record.md
    when: 你要新写一条变更记录
read_if: 你即将修改一份固定/被索引的治理文档,或想追溯某次治理改动"当时为什么这么改"。
---

# 治理变更记录

> dwgx 要求:AI 改动我们**固定的、被索引的重要治理文档**时,留一条记录——
> 改了什么 / 时间 / **dwgx 为什么授权这么改** / 思维链。这样每一次对项目根基的改动都可追溯,
> 下一个 AI 知道来龙去脉,不会盲目推翻或重复。

## 什么触发记录(重要:别记成噪音)

**要记**(固定/被索引的治理文档):
- `CLAUDE.md`(锁定骨架,改需 3 次确认)
- `docs/history/architecture/00-META.md`、`ARCHITECTURE-LOCK.md`(冻结骨架/元架构)
- `.ai-notes/README.md`、`STATUS.md`、`docs/README.md`(机关地图/单一真相/文档树)
- `cc-workflow-guide.md`、`external-doctrine.md`、`owner-profile.md`(治理教义)
- `doc-style-guide.md`(文档写作规范:结论先行/事实进表/加粗纪律)
- `SECURITY.md` 及安全边界相关

**不用记**(会变噪音):
- AI 自己创建的、未被上述索引收录的临时/散文档。
- 会话总结、任务记录、日常代码改动(那些走 STATUS / handoff / git log)。
- 纯错别字、路径修正这类无实质的小修(除非顺带改了实质内容)。

判据:**改上面那份明确清单里的文件、且是实质性改动** -> 记;否则不记。清单是死的,别靠临场判断。
- 创建 vs 改动:**新建一份确立新规则/流程的被索引治理文档 = 记**(如建 cc-workflow-guide);
  在里面改个错别字 = 不记;改一条工作流步骤/规矩 = 记。
- 新增了新的权威治理文档,把它加进上面清单,否则以后对它的改动漏记。

## 这些记录是什么(定位,别误解)

记录是**给 dwgx 复盘 + 给深挖考古用的历史档案,不是每会话必读**。别让接手 AI 花上下文读完所有记录——
现状看当前权威文档,只有要追"当时为什么这么改"时才来翻对应那篇。

## 怎么记

1. 抄 `../../../_templates/governance-record.md`。
2. 存成 `<YYYY-MM-DD>-<短标题>.md`(如 `2026-07-12-workflow-doctrine-intake.md`)。
3. 在下面"记录索引"加一行。
4. 时机:用户确认后、提交前;与文档改动同一 commit。跑 `../../tools/doc_lint.py` 确认没漂移。

## 记录索引

（新记录追加到这里,最新在上）

- [2026-07-15 LOCK 不变量 L1 纠错 HMAC→Ed25519](2026-07-15-lock-l1-hmac-to-ed25519.md) — 动 status:locked 文档,dwgx 3 次确认授权;只换过时算法名(架构决定"靠签名验签"未动);冻结假实现细节比不冻更危险(会诱导 AI 破坏 Ed25519 签名核心)
- [2026-07-15 工作流手册 3 处更正 + 加工具门控清单](2026-07-15-workflow-guide-corrections.md) — fan-out 卡死真变量是输出体量非"代码vs文本"(旧规矩被证伪);新增"非空转≠正确"(测试可写给代码而固化 bug,W6 漏 L4 教训);maven 陈旧 jar 陷阱;修陈旧"4模块"(ADR-0002);**tool-annotation-convention 补 §0 门控登记清单**(加工具时读的文档从没提过那 4 张表=W6 漏 L4 的结构性根因)
- [2026-07-13 游戏修复必走 compat 补丁](2026-07-13-game-fixes-must-be-compat-patches.md) — AI 疏忽教训:8 agent 全走 DIRECT_CLIENT_EDIT 差点提交;根因=约束没写进 agent 硬约束
- [2026-07-13 工具扫盘复用](2026-07-13-tool-scan-reuse.md) — 铁律7:需要工具先扫全盘找已装的复用 + 外来归档只读安全铁则(Hack.7z 卡机教训)
- [2026-07-12 文档写作规范](2026-07-12-doc-style-guide.md) — 建 doc-style-guide + doc_lint 加固(修 `**加粗**` 数字盲区 + 可读性 advisory)
- [2026-07-12 codegraph 读码强制](2026-07-12-codegraph-mandate.md) — 铁律6:读代码必须用 codegraph + AI 自维护索引供下一个 AI 预览
- [2026-07-12 Commit 规范](2026-07-12-commit-convention.md) — 建立 commit 规范 + .gitmessage 模板(从真实历史提炼)
- [2026-07-12 工作流教义引入](2026-07-12-workflow-doctrine-intake.md) — 引入四来源教义 + guide/doctrine/profile/skills/变更日志体系

## 相关
- 母版:`../../../_templates/governance-record.md`
- 考古更深的"为什么":`../session-history.md`(原始窗口对话)
