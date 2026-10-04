---
doc: cc-workflow-guide
title: Claude Code 工作流手册 — 怎么高效干这个项目的活
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: external-doctrine.md
    when: 你想知道这些技法从哪来、我们砍了什么
  - path: ../project/owner-profile.md
    when: 你要懂 dwgx 的标准和指纹,好对齐他
read_if: 你想知道"怎么高效用 Claude Code 干这个项目的活"——技巧、提示策略、环境坑;不是项目架构本身(那在 00-META)。
---

# Claude Code 工作流手册

> 这是 how-to 操作手册,回答"怎么干活",不碰项目技术决策(那在 00-META/architecture)。
> 与 `session-history.md`(考古"为什么")互补,与 CLAUDE.md(路由"去哪找")互补。
> 融合了 karpathy / mattpocock / ECC 的提示智慧,裁定见 `external-doctrine.md`。
> 铁规矩:全文及所有产出一律无 emoji;技术箭头(内容里的 -> <- 之类)不算。

## 1. 首次接手速通(15 分钟)

CLAUDE.md 的"接手启动序列"是完整版;这是快速版,先建立心智模型再深挖。

1. 核心三联读(<5min):`CLAUDE.md` + `.ai-notes/README.md` + `.ai-notes/STATUS.md`。
2. 一句话定位(1min):`docs/architecture/00-META.md` 北极星——本质是系统级控活体 MC JVM,MCP 是功能非本质。
3. 最新交接(3min):STATUS 指向的最新 `handoff/archive/<日期>.md`,拿结论化上手指南。
4. 提交采样(2min):`git log --oneline -30`,与 STATUS 提交栈对上;挑一条 `git show <hash>` 感受粒度。
5. 边界摘要(2min):`SECURITY.md` + CLAUDE.md 铁律,知道哪些不能碰。
6. 试跑一条(2min):`./mvnw -pl board,core test`(**不是 `-pl core`**,见 §4 陈旧 jar 陷阱)或 `scripts/run-mcp.bat`,确认环境活着。

深挖(考古历史对话、判断架构格局)是进阶,不强制首轮。拿不准了再回 `session-history.md`。

## 2. CC 技巧清单(该补的高价值项)

**subagent 委派**:深度调研 / 跨多文件探查 / 审计,派 subagent,别把主上下文烧在读文件上。
- 用它:广泛调研(跨 >5 文件)、独立可并行的活、产结构化报告的审计。
- 别用它:定向查一个已知符号/文件(那用 Grep/Read)。判断留主 agent,别外包。

**Task list(3+ 步必建)**:任何 3 步以上的活,先 TaskCreate 建清单,开工标 in_progress,做完标 completed。
能力包 C1-C8 铺开、审计修复批次这类多阶段活尤其要。

**codegraph_explore**:问"X 怎么工作 / 调用链"用它,一次拿到源码+调用图,省 Read->Grep->Read 循环。
问"X 在哪"用 Grep。子系统开工前先 explore 关键符号名。

**并行工具调用**:无依赖的读一次性并发发出(同时 Read SECURITY + 相关 architecture)。
反模式:有依赖别并行(STATUS 必须先读才知道读哪篇 handoff)。

**上下文压缩后重新定位**:感觉近处上下文变稀=刚压缩过。立刻:重读 STATUS、`git status && git log -5`、
查有没有 in_progress 的 task、核对测试数与 STATUS 一致。先归位再继续。

## 3. 提示 / 委派策略(融合大佬教义)

**何时用哪个模式(CLAUDE.md"工作方式"授权你主动切):**
- `ultrathink` — 深想/权衡/定架构/审需求时,自己拉高思考深度。
- `workflow` — 能拆成独立并行项(多文件、多检查、多候选、探索性研究)时,派 subagent 编排。
- `ultracode` — 实质任务默认按最彻底、最正确做;质量优先,token 不是约束。

**Karpathy 4 原则(调校后适配本项目):**
- 动手前先想:摊开假设、有更简单的路就push back、confused 就停下问(= grill skill)。
- 简约优先:业务代码写最少能解决问题的量,不加投机特性。**但安全内核的防御性代码是必需的**,
  不套"删掉不可能情况的处理"(裁定见 external-doctrine.md)。
- 外科手术式改动:每一行都能追溯到需求,不顺手 refactor,匹配现有风格。
- 目标驱动:把命令式任务转成可验证目标(tests-first),循环到达标。

**subagent 委派边界:**
- 广泛调研 vs 定向查询——前者派 subagent,后者自己 Grep。
- 探索性研究控 token:用 sonnet + 低工具轮次 + 高并行度(上个交接教训:workflow 别太慢;
  本轮工作流用 sonnet 三路并行 83 秒完成,是正面例子)。
- 综合和"我的意见"由主 agent 做,subagent 只出素材。
- **fan-out 卡死的真变量是"单 agent 输出体量",不是"代码 vs 文本"。**(2026-07-15b 更正:旧版写的是"只做研究/纯文本就安全"——**照做仍卡死 4 个 agent**,因为它们做的正是研究/纯文本。)实测同一会话内:

  | 结果 | 形状 |
  |---|---|
  | 全绿 | 编目 108 包(每路 10-20 条,**扁平**对象)· 核验 getter(每包一行文本) |
  | retry 到爆 | 20 包 × **嵌套对象含数组**(433k tok)· **数组套对象套数组 + 长文本**(671k)· 25 包 + 长 recipe(274k)· 10 工具 × 8 字段 + 长 recipe(243k) |

  **可操作规则**:①输出 schema **最多两层**(数组套扁平对象),别数组→对象→再套数组;②**长自由文本(recipe/design)不要和结构化数组混在同一个 schema**——要么纯文本、要么纯列表;③单 agent 覆盖 **≤10-15 条目**,超了就拆;④**大段 Java/pom 仍然主线自己写**(这条依旧成立,源:compat-dwm-compose 交接 §坑)。
- **卡死了就止损,别等 retry 5 次。** 看到 `retry 2` + 单 agent >200k tok 基本就是要挂:`TaskStop` 整个 workflow,已完成的 agent 结果**在 journal.jsonl 里有缓存**可直接取用,失败的那路拆细重跑或主 agent 自己做。
- 让 subagent 反复数 `file:line` 也卡过两次、烧了数小时——那是 `tools/linecheck.py` 存在的原因(确定性脚本 + 单个 subagent,别用 workflow 数行号)。
- **先冻契约,再并行。** board 第一次并行 fan-out 出过"假绿":两个 agent 各造了一份 `Signal`/`TickSignal`,`isInstance` 静默永不匹配 → 订阅从不触发,却 110 测试全绿(测试碰巧用了"对"的那份副本)。这是 `BoundaryDisciplineTest` 和"先冻契约再并行"规则的来由。**不收敛的并行 agent 会产假绿**:共享契约先冻死,最后用一次统一编译 + 全量测试整合,别信各分支自己的绿。

**deep-modules(mattpocock):** 小接口后面藏大行为,放在干净的缝(seam)上。= 本项目 Board 的
PCB 契约(Trace/Chip/Matrix 中性单元)+ 7 层门在注册表统一强制的思路。

## 4. 环境坑清单(踩过的)

- **maven 陈旧 jar 陷阱(坑过 3 次,浪费最多)**:core 的测试**依赖 board**(测试作用域)。单跑 `./mvnw -pl core test` 会拿**本地仓库里陈旧的 board jar**,于是"改了 board 主代码 + core 测试引用它"必**假失败**(症状:`BoardTraceLinkTest ... cannot find symbol`,但代码明明是对的)。
  - **正解**:`./mvnw -pl board,core test`;或先 `./mvnw -pl board install -DskipTests` 再单跑 core。
  - 别用 `-pl core -am`:`-am` 会把 lwjgl2-shim 等一起拉进来,`-Dtest=X` 在那些模块匹配不到测试直接 BUILD FAILURE(要配 `-Dsurefire.failIfNoSpecifiedTests=false`)。
  - 症状识别:报错文件是你**没改过**的测试、且报 `cannot find symbol` 指向你**刚加的 board 类** → 就是它,不是你的代码问题。
- MSYS 路径:`/c/...` 需转 Windows 路径;含空格加引号。
- 中文文件:一律 `python -X utf8`(session-search / doc_lint / strip_emoji 都是)。
- clang:用 GNU driver(`clang -I"path"`),**不是** clang-cl;C6 native DLL 靠它,免 MSVC。
- 运行时:JBR 25 + DCEVM 在 `_tools/`(gitignored,保留),`scripts/run-mcp.bat` 用它。
- 临时目录:用 `$CLAUDE_JOB_DIR/tmp`,别用 `/tmp`。
- CI:GitHub Actions 仅手动触发(workflow_dispatch);C6 DLL 是 windows-x64,ubuntu 编不了 -> 本地 `scripts/build-c6-local.bat`。

## 5. 文档守卫与维护

- `tools/doc_lint.py`:改完文档 / 新增 next 引用后立刻跑。报错=路标头 parent/next 指向的路径不存在,
  或树里写的路径与磁盘对不上,按提示修。写交接前也跑一遍确保内部一致。
- `tools/strip_emoji.py`:去装饰性 emoji(保留技术箭头),幂等可复跑。**只扫 `.ai-notes/*.md`——够不到 `.java`。** 铁律"代码/注释内无 emoji"这工具执行不了,代码里的 emoji 只能靠自检/review 人工挡(见 §6)。
- `tools/doc_pathfix.py`:**一次性 NT 改名迁移脚本**(按 `nt_oldnew.txt` 改 5 篇 arch doc 的旧类名),**不是通用相对路径修复器,且无预览直接写盘**——重构文档树断了 `../` 链接,它一个都不修。
- `tools/session-search.py`:考古历史窗口(先 list 再 search/show,别 dump 大文件进上下文)。只读 `~/.claude/projects/**.jsonl` 原始 transcript;**`session-archive.py` 导出的 `.txt` 它不读**(两者不对接,归档不进检索路径)。
- `tools/linecheck.py`:**`file:line` 引用校验器**(核对 arch doc 里 `Foo.java:NN` 的行号)——**不是找巨文件**(README/本节旧描述有误);且只认 core/src,board 及同名类会静默解析到错文件。
- `tools/doc_lint.py` [2] 陈旧数字:**这一路正则匹配不到 05-TEST-MAP 的写法,实际拦不住**——别把"doc_lint 绿"当"数字没漂"。数字真相靠 `session-status.py` 对账。

## 6. 操作层技法(铁律正本在 CLAUDE.md,这里只讲"怎么做对")

铁律清单见 CLAUDE.md 的铁律节 + 工作方式"红线",不在这里重复。本节只补铁律**怎么执行**:

- **无 emoji**:产出前自检。**文档(`.ai-notes/*.md`)**提交前可跑 `tools/strip_emoji.py` 兜底(幂等,技术箭头保留)。**代码(`.java` 等)strip_emoji 够不到**——它只扫 `.ai-notes/*.md`;代码里的 emoji 只能靠产出时自检 + review 人工挡,别以为跑了 strip_emoji 就清了代码。
- **非空转回归测试(关键技法)**:铁律要求"旧代码上会失败的测试"。
  - 做法:①修 bug 前先写 `@Test` 复现 bug → 跑 → 确认红;②改代码;③再跑 → 绿。这测试才锁住了修复。
  - 反例:写一个新旧代码都通过的测试 = 空转,没锁任何东西。
  - 实例:KI-4 的测试是"创建世界触发 bind",无修复时抛 `UnsupportedOperationException`(红)、有修复则成功(绿)——断言要锁住被修的行为,不是只断言 `channel != null`。
  - **实证而不是推理**:别在提交信息里写"这测试旧代码会挂"就完事。真去撤(`git stash push -- <file>`)→ 跑 → 看它挂 → `git stash pop`。三十秒的事,买的是"这条测试真的咬合"。
- **非空转 ≠ 正确(2026-07-15b 新增,W6 血的教训)**:测试可以**又非空转、又固化 bug**。
  - 真事:W6 加了 4 个发包工具,漏登记 L4 特权表。我写的 `SendToolsW6Test` 断言"三张表齐全"——它在改动前确实会挂(工具都不存在),**完全满足"非空转"**,但它断言的是**我以为的规矩**(数错成 3 张),于是把漏洞钉成了"预期行为",还绿着推上了 origin。
  - 根因:**测试写给了代码,不是写给规格。** 我先实现,再写测试描述我实现了什么——这样测试永远同意实现,包括同意它的错。
  - 做法:写断言前先回原始规格/安全模型问"**这里应该是几张表?权威在哪?**",去读那份权威文档(`02-CAPABILITIES.md` / `01-SECURITY-KERNEL.md`),按它写断言,而不是按自己刚写的代码写。
  - 配套:凡是"必须同时改 N 处"的规矩,补一条**反向不变式**测试(如"L3 声明了危险的工具,L4 必须非空")。正向断言只能守住你想到的那几个名字;反向不变式守住**下一个人**。
- **STATUS 与代码不符**:代码比 STATUS 新 → 先补 STATUS;STATUS 描述的功能代码没有 → flag dwgx。代码+测试是真相,不是 STATUS。
- **改固定治理文档要留痕**:见 `../project/governance-log/README.md`,时机=用户确认后、提交前,与文档改动同一 commit。

## 7. Skill 闭环链(非平凡改动的主干流程)

单个 skill 别孤立用,串成闭环:

```
定方向/设计   → grill(逼问决策树见底,dwgx 铁规矩:先规划再确认)
     ↓
实现          → 3+ 步先 TaskCreate;陌生子系统先 codegraph_explore;写代码
     ↓
自审          → code-review(标准轴+规格轴;安全类有罪推定)
     ↓
测试红?      → diagnose(复现→最小化→假设→插桩→修→非空转回归测试)→ 回自审
     ↓ 绿
提交          → 按 CLAUDE.md git 规矩(先编译再测,无 AI 署名)
```

- 研究类:`research`(并行调研)→(若结论要落地改架构,先 grill)→ 实现 → code-review。
- 这条链对**非平凡改动是默认流程**;单行 typo / 纯查询不必全走。
- AskUserQuestion 本环境偶报 array 格式错但答案仍传回——**报错也去读用户回答**;反复失败就改用文字编号选项问,别让工具错误卡住收集决定。

## 附:冻结骨架速查(判断动没动骨架,免读长文档)

来自 `ARCHITECTURE-LOCK.md`。碰到下面这些 = 动骨架,需 ADR + dwgx 确认;在现有结构内加代码 = 不需要:
- **模块层次(ADR-0002,2026-07-14 重新分类)**:设计骨架**恒为 3**(core/board/client)+ 平台地基 `lwjgl2-shim`(client 的 ABI 垫片,与 client 共生)+ 可拆卸辅助(pg/dwm/dwm-gl/dwm-imgui/dwm-skiko,删了 3 骨架照跑)。判据=删了它其余骨架能否独立编译。
- NT 7 层包结构(se/ob/io/alpc/ke/mm/flt/kd/ps/cm)
- PCB 契约类签名(Trace/Signal/Chip/Matrix/Board/Backplane)· client vanilla 基线 · 零硬依赖

## 相关
- 裁定来源:`external-doctrine.md` · 主人画像:`../project/owner-profile.md`
- 考古"为什么":`../project/session-history.md` · 变更记录:`../project/governance-log/README.md`
- Skills:`.claude/skills/{grill,code-review,diagnose,research}/SKILL.md`
