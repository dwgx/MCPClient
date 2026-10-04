---
doc: governance-2026-07-12-codegraph-mandate
title: 治理变更 — 铁律6:读代码必须用 codegraph + AI 自维护索引
layer: archive
status: archived
updated: 2026-07-12
parent: README.md
read_if: 你想追溯"为什么 CLAUDE.md 加了 codegraph 强制铁律、AI 要自己维护索引"这条治理改动的来龙去脉。
---

# 治理变更 2026-07-12 — 铁律6:读代码必须用 codegraph

## 改了什么
`CLAUDE.md`(定死骨架,改需 3 次确认):
- **新增铁律 6**:读代码(理解/查找/改前看代码)一律先走 codegraph(`codegraph_explore` 等 MCP 工具或 `codegraph` CLI),而非 Grep/Read 兜圈;codegraph 是被授权的默认读码路径。且 **AI 自己负责让索引可用**——动过代码后自行判断是否需 `codegraph sync`(守护进程通常已自动同步,拿不准就 sync 一次),让下一个 AI 一进来就能用 codegraph 预览当前代码。索引陈旧 = 骨架漂移,按 doc_lint 同等严肃对待。
- **同步调和"工作方式"节**:原第 79 行"subagent 委派:广泛调研派它,定向查已知符号自己 Grep"与新铁律直接冲突,改为"读代码走 codegraph——定向查用 `codegraph_node`,探索用 `codegraph_explore`,Grep/Read 仅当 codegraph 查不到时兜底"。

## 时间 / 谁
2026-07-12;本会话(doc-reconcile 之后)。

## dwgx 为什么授权这么改
dwgx 原话:"加入 claude.md AI 要自己决定更新 codegraph 可供下一个 AI 预览用 codegraph 读代码 读代码必须用 codegraph 我确认再三允许可用允许的"。
拆成两条永久规则:①读代码必须用 codegraph(不再 Grep/Read 兜圈);②AI 自主维护索引新鲜度,把"可被下一个 AI 用 codegraph 预览当前代码"作为交接契约的一部分。解决的问题:codegraph 索引已接入且守护进程自动同步,但没有明文规则强制用它读码、也没规定谁负责索引新鲜——导致 AI 仍习惯性 Grep,且索引可能悄悄漂移。

## 思维链(为什么这么设计而不是别的)
- **为何进铁律而非只进"工作方式"节**:dwgx 说"必须用",是硬约束不是偏好;铁律区才是硬约束的归属。工作方式节是"授权+思维方式",强度不够。
- **为何强制调和第 79 行**:留着"自己 Grep"会与铁律自相矛盾,下一个 AI 无所适从。外科手术式改动要求消除冲突,不是叠加。保留 Grep 作兜底(codegraph 查不到时),因为纯 API 转发/新写未索引代码等场景 codegraph 可能暂缺——一刀切禁 Grep 会制造死角。
- **为何把"索引陈旧=骨架漂移"类比 doc_lint**:项目已有"文档漂移按严肃对待"的先例(doc_lint 非零退出),把索引新鲜度挂到同一严肃度,给"AI 自维护索引"一个可对标的分量,而非软建议。
- **未做**:没写死具体 sync 频率/命令编排(守护进程已自动同步,过度规定会僵化);把"拿不准就 sync 一次"留给 AI 判断,符合 dwgx"judge 权在你"的授权风格。

## 确认轨迹
CLAUDE.md 改动需 3 次确认(铁律⑤)。dwgx 本次明确表述"我确认再三允许可用允许的"——即再三(3 次)允许,授权到位。本记录即留痕(铁律⑤要求改固定治理文档留痕)。

## 验证
`doc_lint.py` 绿(链接/数字/清单三项 0 问题)。未动代码,无需编译/测试。governance-log README 索引已加一行;governance-log README 的"要记清单"已含 CLAUDE.md,无需扩清单。
