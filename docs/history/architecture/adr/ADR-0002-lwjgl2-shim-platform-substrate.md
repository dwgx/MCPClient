---
doc: adr-0002
title: ADR-0002 — 重构 L0:lwjgl2-shim 是 client 的平台地基垫片,非并列骨架
layer: reference
status: authoritative
updated: 2026-07-14
parent: ../../README.md
read_if: 你想知道 L0 为何从"骨架恒为4"改为"3 真骨架 + shim 作 client 平台地基垫片"、什么时候定的、能不能再动时读本篇。
---

# ADR-0002:lwjgl2-shim 是 client 的平台地基垫片,不是并列骨架

- **状态**:accepted(dwgx 2026-07-14 确认新措辞 + 同意一并对齐 CLAUDE.md 模块表[3 次确认])
- **日期**:2026-07-14
- **触及 ARCHITECTURE-LOCK**:**L0**(结构骨架,§7 需用户明确确认)

## 背景(为何要改)

L0 现把 `core/` `board/` `client/` `lwjgl2-shim/` 并列为"四骨架模块",不变量写"骨架模块恒为 4"。
但 dwgx 指出(2026-07-14)一个真实的分类错误:**shim 与其余三个不是同一种东西**。判据是"删了它,其余骨架还能不能独立编译运行":

- 删 `pg`/`dwm`/`dwm-compose`(可拆卸辅助)→ 4 骨架照编照跑,只是少了辅助功能。
- 删 `lwjgl2-shim` → **client 编译失败、游戏起不来**。
- 但 shim 又不是"我们设计的核心功能":它只做 **LWJGL2→LWJGL3 的 ABI 兼容**(重实现 LWJGL3 删掉的 `Display`/`Keyboard`/`Mouse`/`Sys`/`GLContext`/`util.vector`/`GLU` 等),pom 自述"只做 ABI 兼容,不含业务"。它是**给 LWJGL 补的缺件**,概念上属于 client 赖以在现代 runtime 存在的**平台地基**,而不是与 core/board 平起平坐的功能骨架。

即:项目其实是**三层**,不是"四平列骨架 + 辅助":
1. **平台地基层**:`lwjgl2-shim`(让 vanilla client 能存在的 ABI 垫片,无业务、无安全权)。
2. **真骨架层**:`core`/`board`/`client`(我们真正设计的:内核 / 功能框架 / 游戏本体)。
3. **可拆卸辅助层**:`pg`/`dwm`/`dwm-compose`(挂骨架外,删了不影响核心)。

把 shim 塞进"骨架四分之一"混淆了"平台地基"与"设计核心"。

## 决策

**重构 L0 的分类叙述**(不动任何代码 / pom / 包结构):
- 把"四骨架"改为**3 个设计骨架(`core`/`board`/`client`)+ `lwjgl2-shim` 作为 client 的平台地基垫片单列**。
- 不变量从"骨架模块恒为 4"改为:**设计骨架恒为 3(core/board/client);shim 是 client 的 ABI 地基垫片(与 client 共生,不含业务/安全权);二者之外皆可拆卸辅助。**
- shim 的边界铁律不变(只做 ABI 兼容、不含业务)。client 仍 vanilla、core/board 仍不 import client 映射类、L3 反射边界不变。

这是**纯分类/叙述重构**:模块数、pom、代码、包名、构建全不动;变的只是 ARCHITECTURE-LOCK 对这些模块的**层次归类**。

## 拟议的 L0 新措辞(dwgx 请审这段;确认后我落到锁上并转 accepted)

> ## L0 — 模块层次(设计骨架不可增删/合并/越界;地基与辅助分列)
>
> **三层结构:**
>
> | 层 | 模块 | 职责(冻结) | 边界铁律 |
> |---|---|---|---|
> | 平台地基 | `lwjgl2-shim/` | LWJGL2→LWJGL3 ABI 兼容垫片 | 只做 ABI 兼容,不含业务/安全权;与 client 共生(删它 client 编不了) |
> | 设计骨架 | `core/` | NT 内核:MCP server、7 层安全内核、能力包 C1-C8、JVMTI 调试器 | 内核本体,唯一可持有安全决策权 |
> | 设计骨架 | `board/` | 客户端功能框架(PCB 隐喻):事件总线 + 功能单元 + 管理器 | 与 core 零硬依赖(见 L3) |
> | 设计骨架 | `client/` | MC 1.8.9 vanilla 映射(反射/GUI 字段名唯一真相源) | 不被 core/board 污染;保持 vanilla |
>
> **不变量 L0**:**设计骨架恒为 3(core/board/client)**,不可增删/合并/职责越界;`lwjgl2-shim` 是 client 的 ABI 地基垫片(层次低于骨架、与 client 共生,不是第 4 个骨架、也不是可拆卸辅助);`client/` 保持 vanilla;core/board 绝不 import client 映射类到自身逻辑(走反射/seam)。此三者 + shim 之外的一切皆"可拆卸辅助",增删不触 L0、无需 §7 闸门。

## 后果

- **正面**:分类不再骗人——"设计核心是哪几个"一眼分明;判据"删了它其余能否独立编译"落到文档。dwm/pg/dwm-compose 归位为辅助(已登记),shim 归位为地基,core/board/client 是唯三设计骨架。
- **代价**:"恒为 3" 比"恒为 4"多一句地基垫片的说明,略增表述复杂度。缓解:三层表把归类一次讲清,比原来"4 平列 + 一段辅助说明"其实更准。
- **不影响**:代码/pom/构建/测试/包结构零改动;L1(core NT 分层)/L2(board PCB 契约)/L3(反射边界)/L4(安全脊柱)全不变。

## 替代方案(为何不选)

- **物理归并 shim 进 client**:动 pom/构建,风险大、收益仅"少一个 module 条目";shim 独立可测(59 测试)有价值,不值得并。
- **维持"骨架恒为 4"、只在认知上承认不同**:文档继续误导后续 AI 把 shim 当设计核心之一;dwgx 明确要改(选项1),不选。
- **把 shim 也归入"可拆卸辅助"**:错——删它 client 起不来,它不可拆卸。它是地基,不是辅助。

## 落地清单(dwgx 确认新措辞后执行)

1. 用上面"拟议新措辞"替换 ARCHITECTURE-LOCK §L0 表 + 不变量段。
2. 本 ADR status → accepted。
3. governance-log 留痕(`2026-07-14-l0-shim-substrate.md`)。
4. 同步:CLAUDE.md 模块表若称"骨架"需措辞对齐(CLAUDE.md 改需 3 次确认——本 ADR 不改 CLAUDE.md,仅在 ADR 标记"CLAUDE.md 模块表用词待后续按需对齐");STATUS 无硬编码"4 骨架"处则不动。
5. 无代码变更,故无需回归测试;doc_lint 跑通即可。
