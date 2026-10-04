---
doc: governance-2026-07-14-l0-shim-substrate
title: 治理变更 — L0 重构:shim 是 client 平台地基垫片,设计骨架恒为 3
layer: archive
status: archived
updated: 2026-07-14
parent: README.md
read_if: 你想追溯"为什么 L0 从骨架恒为4改成3骨架+地基垫片、CLAUDE.md 模块表为何变三层"的来龙去脉。
---

# 治理变更 2026-07-14 — L0:shim = client 平台地基垫片(设计骨架恒为 3)

## 改了什么
- **ARCHITECTURE-LOCK §L0**:从"四骨架平列(core/board/client/lwjgl2-shim)+ 不变量'骨架恒为 4'"
  改为**三层**:平台地基(`lwjgl2-shim`)/ 设计骨架恒为 3(`core`/`board`/`client`)/ 可拆卸辅助
  (`pg`/`dwm`/`dwm-compose`)。判据落进文档:"删了它其余骨架能否独立编译"。
- **同批**:dwm + dwm-compose 补登记进辅助模块列表(此前只有 pg)。
- **CLAUDE.md 模块表**(定死骨架,改需 3 次确认——dwgx 本次明文给了 3 次确认 + 确切措辞):
  从 3 行平列(且漏了 board)改为三层表,新增 board + 辅助行 + shim 标注"与 client 共生"。
- ADR-0002 记录背景/决策/替代方案,accepted。

## 为什么
dwgx 指出真实分类错误:shim 与 core/board/client 不是同类。删 pg/dwm/dwm-compose→骨架照跑(辅助);
删 shim→client 编不了(地基);但 shim 又无业务/安全权,不是"设计核心"。把它当"骨架四分之一"
混淆了"平台地基"与"设计核心"。三层分类更准。

## 触碰的锁 + 闸门
触 **L0**(结构骨架,§7 需用户明确确认)。走完:ADR-0002(proposed→accepted)+ dwgx 确认新措辞
+ CLAUDE.md 改的 3 次确认 + 本留痕。**纯分类/叙述重构:代码/pom/包/构建零改动**,故无回归测试;
L1/L2/L3/L4 全不变;doc_lint 跑通。

## 时间 / 谁
2026-07-14;本会话。dwgx 确认。
