---
doc: govlog-2026-07-12-commit
title: 治理变更 — 建立 commit 规范 + .gitmessage 模板
layer: archive
status: archived
updated: 2026-07-12
parent: README.md
read_if: 你想追溯 commit 规范是怎么定的、为什么是这个风格。
---

# 治理变更 2026-07-12 — Commit 规范

## 改了什么
- 新增 `docs/reference/commit-convention.md`(规范正文,本地 gitignored)。
- 新增仓库根 `.gitmessage`(提交模板,**进 git**),并 `git config commit.template .gitmessage`。
- docs/README.md + .ai-notes/README.md 索引 commit-convention。

## 时间 / 谁
2026-07-12;窗口 `ad54b25a`。

## dwgx 为什么授权这么改
他要给项目定一个统一的 commit 风格(命名方式、一次提交装什么、介绍写法),以后一直照这个走,
除非重大特例才变。要贴合他喜欢的"系统架构/包名"审美(NT 分层 + PCB 命名的简洁体系感)。
他选了"标准方案"(规范进 .ai-notes + 模板进仓库根 + git config 挂上)+ "完全采纳" subagent 拟稿。

## 思维链(为什么这么设计而不是别的)
- 不发明新风格,从 55 条真实 commit 逆向提炼——他现有风格已成体系(scope: 摘要 / 粗体 bullet
  点名类 / 结尾测试数 / teeth-verified / gitignored 声明 / 无 AI 署名)。规范只是把隐性惯例写显。
- 明确拒绝 Conventional Commits(feat:/chore:)——项目历史从不用,套上去反而破坏既有风格。
- subject 上限写成"目标<=72,容忍~90":诚实反映历史(3 条真实 commit 超 72),不套项目从没守过的硬限。
- 派后台 subagent(general-purpose)分析历史 + 拟稿,与 deep-research 并行;主 agent review 后落盘。

## 确认轨迹
- 未改 CLAUDE.md(规范是新文档,不动锁定骨架),无需 3 次确认。
- 落地方案 + 内容采纳均经 dwgx 明确选择。

## 验证
- doc_lint:见本轮运行结果。无装饰 emoji。
- `git config commit.template` 已验证生效。
