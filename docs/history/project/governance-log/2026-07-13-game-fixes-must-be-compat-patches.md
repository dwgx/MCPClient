---
doc: governance-2026-07-13-compat-patch-lesson
title: 教训 — 游戏 bug 修复必走 compat 补丁,绝不直接改 client 源
layer: archive
status: archived
updated: 2026-07-13
parent: README.md
read_if: 你要修 client/net.minecraft.* 的移植 bug,或想知道"为什么派 agent 修游戏 bug 前要写死走 compat 补丁"。
---

# 教训 2026-07-13 — 游戏修复必走 compat 补丁

## 疏忽经过(AI 自己造成的,要记住)
一个"修复候选" workflow 派了 8 个 agent 修 KI(移植 bug),**8 个全部产出 `DIRECT_CLIENT_EDIT` 候选** —— 直接改 `client/` 的 vanilla 类(EntityRenderer/NetworkSystem/EnumConnectionState/TileEntity/MapGenStructureIO 等)。AI(我)当时还准备"应用主树 + 提交"。这**直接违反**:
- `07-COMPAT-SHIM.md`:游戏移植 bug 必须"启动期套已签名补丁,client 源码零改动"。
- 铁律③ / ARCHITECTURE-LOCK L0:`client/` 保持纯 vanilla 映射(反射/字段名基准)。

幸好全部在隔离 worktree,未落地主树。dwgx 发现并纠正。

## 根因
派 agent 时**没把"必走 compat 补丁"写进硬约束**,让 agent 自己选 `delivery_recommendation`(DIRECT vs COMPAT)。agent 图省事(直接改源码最直观)就全选了 DIRECT。**约束没写死 = agent 会走阻力最小的错路。**

## 铁则(以后照做)
1. **任何"修 client/net.minecraft.* 移植 bug"的任务,派发前写死:产出 `CompatPatch`(启动期签名补丁),绝不 `DIRECT_CLIENT_EDIT`。**
2. 已有的 DIRECT 候选:修复**逻辑/测试可复用**,但**形态必须重做成补丁**。
3. 前提:compat 引擎(`CompatEngine`/`CompatPatch`/`CompatDatabase` 等)必须先落地 —— 之前一直是 `planned`,引擎没建,所以"走补丁"根本无处可走,这也是 agent 走 DIRECT 的诱因之一。引擎建好后,补丁才有家。

## 更广的元教训
**AI 派 workflow 时,凡涉及架构铁律的约束,必须写进 agent 的硬约束,不能留给 agent 自选。** agent 没有 dwgx 的品味,会选最直观的路 —— 而最直观的路常常违反项目刻意立的边界。这条适用于所有 fan-out。见 [[feedback-collaboration-boundary]] [[project-endgame-and-directions]]。

## 验证
07-COMPAT-SHIM.md 顶部加了醒目铁则横幅;本记录留痕;写入持久记忆。CLAUDE.md 铁律区是否加"游戏修复必走 compat"—— 需 dwgx 3 次确认(改定死骨架),暂列待办,未擅改。
