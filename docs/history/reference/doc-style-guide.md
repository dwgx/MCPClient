---
doc: doc-style-guide
title: 文档写作规范 — md 怎么写,下一个 AI 才读得顺
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: ../../_templates/README.md
    when: 你要新建一份文档,先按母版决策树选模板
  - path: codegraph.md
    when: 你要引代码——先 codegraph 定位,再写 file:line
read_if: 你要写或改任何 .ai-notes 文档前读本篇。它立"怎么写不乱"的死规矩;违反最严重的几条 doc_lint 会报。
---

# 文档写作规范(写给下一个 AI 的 md)

> **一句话**:这些文档的首要读者是**下一个 AI**(接手/考古)。所以"好"不是好看,是
> **能扫描 + 可信 + 不漂移**。本篇把这三点拆成可查的死规矩;写前扫一眼速查表即可。

## 速查表(先看这个)

| # | 规矩 | 一句话 | 反例 → 正例 |
|---|---|---|---|
| 1 | **结论先行** | 段落/条目第一句就是结论或状态,别埋在中段 | "经调查……最终发现 X" → "**X**。(下面是怎么查到的)" |
| 2 | **事实进表,推理留散文** | ≥3 条并列事实(模块→数、工具→ring、项→状态)用表格;因果/取舍/叙事用散文 | "core 262、client 14、board 172……" 一行 → 一张四行表 |
| 3 | **一个数字一个家** | 易变数字(测试数/HEAD/工具数)只在 STATUS.md 维护;别处一律指过去 | 别处写死 "262" → "core 测试数见 STATUS.md" |
| 4 | **就地更新,别拖尾巴** | 值变了直接改成现值,不留 "13→18→20" 增长链 | "120→172 波次A" 可留一次作里程碑;`A→B→C→D` 越滚越长要砍 |
| 5 | **加粗是路标不是装饰** | 每条 bullet 最多一个加粗锚点;别整句加粗、别给裸数字加粗 | `**178**` 骗过 lint 正则,是真 bug 源;数字别加粗 |
| 6 | **不重复,只链接** | 同一事实别在两篇各写一遍;写一处,其余用 wikilink/相对路径指过去 | 抄一遍架构说明 → "见 `01-SECURITY-KERNEL.md`" |
| 7 | **引代码带 file:line** | 论断挂代码坐标(先 codegraph 定位);"某处如此"不算 | "有个守卫" → "`ObManager.checkRequest`(`ob/ObManager.java:60`)" |
| 8 | **诚实标状态** | 用统一状态词,别把设计当已建 | `planned` / `built, opt-in` / `live` / `designed-not-built` / `REVERTED` |

## 为什么是这几条(不是审美,是踩过的坑)

这些规矩全部反推自本项目真实发生的问题,不是通用文风偏好:

- **结论先行 / 事实进表**:接手 AI 上下文有限,读文档是为了**快速定位**,不是欣赏。
  一行塞十几个逗号分隔事实,得线性扫完才能取一个数;表格一眼命中。
- **一个数字一个家 + 就地更新**:同一个数散落多篇 = 漂移之源。本项目栽过——
  HEAD 指针、client 测试数曾在多处各说各话(见 `../project/governance-log/`)。
  单一真相(STATUS.md)+ 别处只链接,是唯一能长期不漂的写法。
- **加粗别装饰**:`**178**` 这种给裸数字加粗,不只是丑——它**骗过了 doc_lint 的陈旧数字正则**
  (加粗符插在数字和"个 core 测试"之间,正则匹配不到),让一个陈旧数字蒙混过关。
  这是加粗滥用直接制造的检测盲区,已实证。
- **诚实标状态**:文档若把"设计了"写成"已建"、把已回滚写成 RESOLVED,下一个 AI 会
  基于假事实决策。本项目 L6、KI-1/KI-4 都栽过这个(见 `../project/known-issues.md`)。

## before / after(照着学)

**规矩 2 —— 事实进表:**

```
 before(一行 mega-sentence,数字用 N 占位,勿钉真实计数):
N core @Test + N client @Test + N board tests + N pg-engine(headless 全绿;
core 演进链…含 L6 + dev_probe;client 黄金 + SmokeIT……)。跑:./mvnw -pl ...

 after(表 + 表下备注):
| 模块 | @Test | 文件 | 跑 | 备注 |
|---|---|---|---|---|
| core | 262 | 56 | ./mvnw -pl core test | 258 常规 + 4 LiveIT skip |
| client | 14 | 5 | ./mvnw -pl client test | 纯 vanilla |
```

**规矩 1 —— 结论先行:**

```
 before:GUI 自动化进单人世界时崩于 LocalServerChannel...一行修复经真机验证...但已回滚。
 after:**KI-4:已回滚,现为 planned 补丁 MCP-KI0004。** 机制:GUI 进世界崩于 ...(证据)。
```

## 什么该是散文(别矫枉过正)

表格是给**并列事实**的。以下**必须**留散文,硬转表反而更糟:

- 调查/排查叙事(KI 条目的"Confirmed by investigation / Ruled out")——它是逻辑链,
  切碎成表格会断掉推理。
- 设计取舍、为什么选 A 否 B、review 砍了什么——这是"思维链",散文才承载得住。
- 判据是:**这段是"N 个平行的点"还是"一条推理"?** 平行→表;推理→散文。

## 分工:guide 管判断,doc_lint 管机械

- **doc_lint 硬拦**(非零退出):死链/大小写、陈旧聚合测试数、机关地图漂移。
- **doc_lint advisory**(软提示、不阻断):超长行 / 高密度段落——提醒你考虑规矩 1/2,但不强制。
- **本 guide 管**:结论先行、事实/散文之分、加粗纪律、状态词——**判断类,靠人(AI)自觉 + review**。
- 写完文档:跑 `../../tools/doc_lint.py`;改的是被索引的治理文档,按 `../project/governance-log/README.md` 留痕。

## 相关
- 母版决策树:`../../_templates/README.md` · commit 规范:`commit-convention.md`
- 干活手册:`cc-workflow-guide.md`(本 guide 是它的"写文档"专章)· 读代码:`codegraph.md`(铁律⑥)
