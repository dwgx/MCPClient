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

MCPClient(神器):把**活着的 Minecraft 1.8.9 客户端**(LWJGL3 / JDK25)通过 **MCP** 暴露给 LLM
——AI 能观察、操作、热改运行中的游戏。核心 = NT 架构 7 层权限内核 + 能力包 C1-C8 + 原生 JVMTI 调试器。

## 模块(结构,稳定)

| 模块 | 作用 |
|---|---|
| `core/` | 神器本体:MCP server、7 层安全内核、能力包、工具 |
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

## 会话交接协议(上下文快满时)

当本会话上下文接近上限:
1. 按 `.ai-notes/_templates/handoff.md` 母版写一份交接(现状/进行中/下一步/坑/指向的文档)。
2. 存进 `.ai-notes/docs/project/handoff/archive/<日期>.md`,并更新 `.ai-notes/STATUS.md`。
3. **明确告诉用户:上下文将满,请开新窗口让下一个 AI 接手**,把交接内容交给用户。
4. 不要在快满时硬撑着开新大任务。
