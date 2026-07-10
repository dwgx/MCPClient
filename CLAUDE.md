# CLAUDE.md — AI 接手第一读

> 本文件在仓库根(进 git),是 AI/人类接手本项目的入口说明。AI 专用的深度笔记、
> 架构文档树、安全模型、工作规范在 gitignored 的 `.ai-notes/`(见 `.ai-notes/README.md`)。

## 这是什么

MCPClient(代号"神器"):把一个**活着的 Minecraft 1.8.9 客户端**(移植到 LWJGL3 + JDK25)通过 **MCP** 暴露给 LLM,让 AI 能观察、操作、甚至热改运行中的游戏。核心是一套 **NT 内核架构的 7 层权限系统** + 能力包(C1-C8)+ 原生 JVMTI 调试器。

- 分支:`mcp-core`
- 语言/工具链:Java,JDK 25,Maven(用 `./mvnw`)
- 运行时:JetBrains Runtime 25 + DCEVM(`_tools/jbrsdk-25.0.3-windows-x64-b508.16`)

## 模块

| 模块 | 作用 |
|---|---|
| `core/` | 神器本体:MCP server、7 层安全内核、能力包、工具 |
| `client/` | MC 1.8.9 客户端(**vanilla 映射** — GUI 反射按这里的字段名) |
| `lwjgl2-shim/` | LWJGL2→LWJGL3 兼容层(含 `org.lwjgl.opengl.Display` 等 shim) |

## 构建 / 测试 / 运行

```bash
./mvnw -pl core test              # 跑 core 测试(当前 178 绿)
./mvnw -q -pl core -am package -DskipTests   # 打 fat agent jar
run-mcp.bat                       # 启动游戏 + MCP Core(Windows)
```

启动后:socket transport `127.0.0.1:25599`,REST facade `http://127.0.0.1:1337/`(`GET /v1/models`、`POST /v1/tools/{name}`)。

C6 原生调试器(可选):加 `-agentpath:<abs>/core-jvmti.dll -Dmcp.core.jvmtiLib=<abs>/core-jvmti.dll`。DLL 用 `core/src/main/native/core-jvmti/build-clang.sh` 编(需 `winget install LLVM.LLVM`,**不需要 MSVC**)。

## 当前状态(截至最近提交)

- 7 层安全内核(L1-L7)完成,54+ 测试
- 能力包 C1(introspect)/C3(hooks)/C5(deepaccess)/C6(JVMTI 调试器)/C7(synth)/C8(seam)全部集成
- C6 native DLL 已解锁(clang 编译)并 live 验证
- 结构化 GUI 交互:`gui_snapshot` + `gui_click_element`/`gui_type_text`/`gui_press_key`,live 验证过
- Codex ultra 审计 18 项 + 完备性审计 gap 全部处理

## 铁律(违反会出事)

1. **参考代码不进目标**:`_tools/`(JBR 运行时)是 gitignored 工作区,绝不污染 `core/client/shim`。参考 repo 克隆(`_refs`、`_analyze_base` 等)已删除——需要时重新克隆到一个 `_*` 目录(务必 gitignored)。
2. **每次改动先编译再测试才提交**;每个 fix 配**非空转**回归测试。
3. **不加 AI 署名**到提交(用户全局规则):无 `Co-Authored-By: Claude`、无 "Generated with"。
4. **安全类不可动**:见 `.ai-notes/SECURITY.md` 的 ProtectedClasses 清单。
5. **GUI 反射按 vanilla 映射**:字段名以 `client/src/main/java` 为准,不是 `Southside`(已删)。
6. 大任务:worktree 隔离 + 文件所有权互斥 + lead 撑 hub 文件 + lead 做 live 验证(见 `.ai-notes/CONTRIBUTING.md`)。

## 别处的上下文(均在 gitignored 的 `.ai-notes/`)

- 架构文档树:`.ai-notes/docs/`(architecture / audits / design-briefs / project)
- 已知问题:`.ai-notes/docs/project/known-issues.md`
- 工作规范 / 安全模型:`.ai-notes/CONTRIBUTING.md` / `.ai-notes/SECURITY.md`
- Claude 持久记忆:`~/.claude/projects/D--Project-MCPClient/memory/MEMORY.md`
