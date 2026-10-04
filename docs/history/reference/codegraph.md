---
doc: reference-codegraph
title: CodeGraph — 代码知识图谱 + 热更新(本项目已接入)
layer: reference
status: authoritative
updated: 2026-07-11
parent: ../README.md
read_if: 你想用 codegraph 一次性读懂本项目结构/调用/影响面,或想知道索引怎么热更新、装在哪。
---

# CodeGraph — 本项目的代码知识图谱

> 一句话:本地跑的**代码知识图谱 MCP server**,一次查询就返回"相关符号的源码 + 调用路径 + 影响面",
> 替代 AI 逐文件 grep/read。**100% 本地**(tree-sitter → SQLite,无数据外传、无 API key)。
> 上游:https://github.com/colbymchenry/codegraph(MIT)。CLI 版本见 `codegraph --version`(接入时 1.3.1)。

## 它解决什么

传统 AI 读代码是"grep 一个文件、读一个文件"循环,慢且烧 token。CodeGraph 预先把代码建成图,
AI 问一句(自然语言或符号名)就拿到:命中符号的**逐字源码**(按文件分组)、它们之间的**调用路径**、
以及一次改动的**影响半径(blast radius)**。**优先用它代替 grep/read 循环**——上游明确建议
"Trust the results — don't re-verify with grep"。

## 工作原理(三段流水线)

1. **提取**:tree-sitter 把源码解析成 AST,查询抽出节点(函数/类/方法)和边(调用/import/继承/实现)。
2. **存储**:全部落到本地 `.codegraph/codegraph.db`(SQLite + FTS5 全文检索)。
3. **解析**:把引用接起来(调用→定义、import→文件、继承、框架路由)。支持 20+ 语言。

## 本项目接入状态

- **已装**:CLI 全局装在 npm(`~/AppData/Roaming/npm/codegraph`)。
- **已索引**:`codegraph init` 在项目根建了 `.codegraph/`(**已加进 `.gitignore`** —— 机器本地、可重建,绝不进 git)。
- **MCP 工具可用**:本环境暴露 `mcp__codegraph__codegraph_explore`(主工具)。查询本项目时传
  `projectPath` 指向 `D:\Project\MCPClient`(或其子目录),它用最近的 `.codegraph/` 索引。

## 热更新(你问的重点)—— 索引怎么保持不过期

**Auto-sync 默认开,通常无需手动重建。** 三层机制保证索引不 stale:

1. **原生文件监听**:FSEvents / inotify / ReadDirectoryChangesW 捕获 create/modify/delete 事件,
   触发**防抖重索引**(默认 2000ms,`CODEGRAPH_WATCH_DEBOUNCE_MS` 可调)。一串连续改动合并成一次 sync。
2. **per-file staleness 横幅**:防抖窗口内,工具响应会前置一条 staleness 横幅,提示 AI 那个还没重索引的文件请直接读。
3. **连接时补账**:server (重)连接时对比 `(size, mtime)` + 内容哈希,把"没开 server 时改的"也吸收进来。

**手动 sync 只在**:watcher 被关、或你在 agent 会话外脚本化操作时才需要。

## 常用命令(CLI)

```
codegraph init [path]      # 初始化项目 + 建图(本项目已跑过)
codegraph sync [path]      # 增量更新(watcher 会自动做,一般不用手动)
codegraph index [path]     # 全量索引(--force 强制重建)
codegraph status [path]    # 看统计(节点数/符号数)
codegraph explore <query>  # 相关符号源码 + 调用路径(= MCP 主工具的 CLI 版)
codegraph node <symbol>    # 单个符号的源码 + 谁调它
codegraph callers <symbol> # 谁调用了它
codegraph callees <symbol> # 它调用了谁
codegraph impact <symbol>  # 改它影响谁(blast radius)
codegraph affected [files] # 受影响的测试文件
codegraph upgrade          # 升级 CLI
```

## 配置

零配置默认可用:自动跳过 `node_modules`/`dist` 等构建目录、`.gitignore` 里的东西、>1MB 的文件。
可选在项目根放 `codegraph.json` 配 `exclude`/`include`/自定义 `extensions`。
**本项目注意**:`.gitignore` 已忽略 `.ai-notes/`、`_tools/`、`_refs/`、游戏资源等——这些也不会被索引,
所以 codegraph 只覆盖 `core/board/client/lwjgl2-shim/pg` 的真实源码,正合适。

## 怎么用在本项目(实践建议)

- **读懂某子系统/流程**:优先 `codegraph_explore`(传 `projectPath=D:\Project\MCPClient` + 一句问题或符号名),
  一次拿到跨文件的源码 + 调用链,别再手动 grep+read 循环。
- **改动前评估影响面**:改某个安全内核类前,`codegraph impact <symbol>` 看 blast radius——
  尤其配合 [[reference-architecture-lock]] 判断是否触碰冻结骨架。
- **信任结果**:索引热更新,查询结果反映当前代码;别再 grep 复核(除非命中 staleness 横幅)。

## 指向

- 上游 repo:https://github.com/colbymchenry/codegraph
- [`ARCHITECTURE-LOCK`](../architecture/ARCHITECTURE-LOCK.md) —— 改动前用 `impact` 配合它判断冻结项
- `../../STATUS.md` —— 项目现状单一真相

