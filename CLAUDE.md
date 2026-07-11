---
doc: root-entry
title: MCPClient — AI 接手入口
layer: entry
status: authoritative
updated: 2026-07-11
parent: null
next:
  - path: .ai-notes/README.md
    when: 你要动手做任何事之前——它是机关地图,带你去现状/规矩/文档树
  - path: .ai-notes/STATUS.md
    when: 你想知道"现在做到哪了"
read_if: 每个会话开始都读本文(它很短,是路由器)。细节按 next 指引按需展开。
---

# CLAUDE.md — MCPClient 入口(路由器,不是仓库)

> 本文是机关入口:只放**永不变的骨架**——项目是什么、命令、铁律、去哪找细节。
> 易变的状态、深度文档、模板都在 gitignored 的 `.ai-notes/`,按需读,别全塞进上下文。

## 这是什么(一句)

MCPClient(the Kernel):把**活着的 Minecraft 1.8.9 客户端**(LWJGL3 / JDK25)通过 **MCP** 暴露给 LLM
——AI 能观察、操作、热改运行中的游戏。核心 = NT 架构 7 层权限内核 + 能力包 C1-C8 + 原生 JVMTI 调试器。

## 模块(结构,稳定)

| 模块 | 作用 |
|---|---|
| `core/` | 内核本体(the Kernel):MCP server、7 层安全内核、能力包、工具 |
| `client/` | MC 1.8.9 客户端(**vanilla 映射**——反射/GUI 字段名以此为准) |
| `lwjgl2-shim/` | LWJGL2→LWJGL3 兼容层 |

## 命令(稳定;易变数字不写在这,看 `.ai-notes/STATUS.md`)

```bash
./mvnw -pl core test                          # 跑 core 测试
./mvnw -q -pl core -am package -DskipTests    # 打 fat agent jar
scripts\run-mcp.bat                           # 启动游戏 + MCP Core(Windows;脚本+jvm-args 在 scripts/)
```

## 铁律(违反会出事)

1. **不加 AI 署名**到提交:无 `Co-Authored-By`、无 "Generated with"。提交信息只讲改动本身。
2. **先编译再测试才提交**;每个 fix/feature 配**非空转**回归测试(旧代码上会失败的那种)。
3. **安全类不可动**:ProtectedClasses 清单见 `.ai-notes/SECURITY.md`;生成/eval 代码一律 R-1 门控。
4. **参考代码不进目标**:`_*` 目录(如 `_tools`)是 gitignored 工作区,绝不污染 `core/client/shim`。
5. **本文件(CLAUDE.md)是定死骨架**:agent **不许自行修改**。只有影响所有会话的永久事实/铁律才值得改,
   且**必须经用户明确确认 3 次**方可动笔——否则一律拒绝修改本文件。

## 去哪找(路由表)

| 你想要 | 去 |
|---|---|
| 机关地图 / 总索引 | `.ai-notes/README.md` |
| 当前状态(测试数、进度、已解锁能力) | `.ai-notes/STATUS.md` |
| 安全模型 + 必守边界 | `.ai-notes/SECURITY.md` |
| 架构文档树(7层/能力/native/测试图) | `.ai-notes/docs/README.md` |
| 已知问题 | `.ai-notes/docs/project/known-issues.md` |
| 接手 / 交接 | `.ai-notes/docs/project/handoff/README.md` |
| 文档模板(交接/会话总结/任务记录) | `.ai-notes/_templates/` |
| 怎么高效干活(速通/技巧/提示策略/环境坑/skill 闭环) | `.ai-notes/docs/reference/cc-workflow-guide.md` |
| 和 dwgx 对齐(指纹/标准) | `.ai-notes/docs/project/owner-profile.md` |

## 工作方式(授权 + 思维方式,适用所有会话)

这一节是**怎么干活的纲**:思维方式在前,授权在中,红线在后。具体操作(速通/技巧清单/环境坑/可跑 skill)见
`.ai-notes/docs/reference/cc-workflow-guide.md` —— 本节立"怎么想 + 允许你做什么",手册讲"具体怎么做"。

**思维方式(先想再动):**
- **先规划再确认**:动手前摊开假设、给详细大纲、并发研究;有岔路先 grill 到决策树见底再写。命名/架构取舍/动骨架**必须问 dwgx**,不许替他发明品味。
- **外科手术式改动**:每一行都能追溯到需求;不顺手 refactor;匹配现有风格。简约优先——但**安全内核的防御性代码是必需的**,不套"删掉不可能情况的处理"。
- **目标驱动**:把命令式任务转成可验证目标(tests-first),循环到达标。
- **诚实报边界**:别把"减速带当城墙"吹;真机/live 才能验的别假装 headless 测过;拍马屁("你绝对正确")不说。
- **失败两次就换路**:同一approach 失败两次,退一步找根因,换根本不同的路,别递增打补丁。

**授权(judge 权在你,质量优先,无需逐次请示;何时用哪个、边界、技法全在 guide):**
- `ultrathink`(深想/定架构)· `workflow`(并行拆解,探索性研究控 token)· `ultracode`(质量优先,token 不是约束)。
- **subagent 委派**:广泛调研派它,定向查已知符号自己 Grep;判断留主 agent。
- **Review 是常态**:改完对抗性复查;安全类有罪推定。可跑 skill:`grill`(逼问)`code-review`(双轴)`diagnose`(bug闭环)`research`(调研),闭环链见 guide。

**红线(授权不覆盖这些):**
- 无 emoji(文档/注释/代码内一律禁,技术箭头除外)。
- 先编译再测才提交;每个 fix/feature 配非空转回归测试。
- client 纯 vanilla;`_*` 目录不进 target;安全类不可动。
- 改本文件需 3 次确认;改固定治理文档要留痕(见 `governance-log/`)。

## 接手启动序列(会话开始时,先走这套)

分两层:**必读层每会话都走**;**深挖层**只在大改动/定架构/现有文档解释不了时才升级。别一上来把深挖层全跑一遍烧上下文。

**必读层(每会话,<5min):**
1. **读现状骨架**:本文 → `.ai-notes/README.md`(机关地图)→ `.ai-notes/STATUS.md`(单一真相)。
2. **看提交栈**:`git log --oneline -30`,与 STATUS 提交栈对上。**对不上就 reconcile**:代码比 STATUS 新 → 先补 STATUS;STATUS 描述的功能代码里没有 → flag 给 dwgx(别默认 STATUS 是真相,代码+测试才是)。
3. **认 skill**:任务是定方向/设计 → `grill`;写 fix/feature → 写完 `code-review`;查 bug → `diagnose`;调研外部 → `research`。

**深挖层(大改动 / 定架构 / 拿不准来由时才升级):**
4. **读北极星 + 最新交接**:`00-META.md`(本质/四关注点)+ STATUS §最新交接**明确指向的那篇**(即使磁盘有更新日期的文件,以 STATUS 指针为准;指针 stale 就 flag)。
5. **判断骨架边界**:结合 `ARCHITECTURE-LOCK.md`,想清楚动的东西碰不碰冻结骨架(动骨架走 ADR)。
6. **考古历史对话(学"为什么")**:多数时候 STATUS+交接+00-META+LOCK 已够;**只有**"这段代码为什么设计得这么怪"且现有文档无解时,才用 `session-search.py`(`python -X utf8`;先 `list` 再 `search`/`show`)定点考古。只读考古,别把旧决定当现状。

## 会话交接协议(上下文快满时)

当本会话上下文接近上限:
1. 按 `.ai-notes/_templates/handoff.md` 母版写一份交接(现状/进行中/下一步/坑/指向的文档)。
2. 存进 `.ai-notes/docs/project/handoff/archive/<日期>.md`,并更新 `.ai-notes/STATUS.md`。
3. **明确告诉用户:上下文将满,请开新窗口让下一个 AI 接手**,把交接内容交给用户。
4. 不要在快满时硬撑着开新大任务。
