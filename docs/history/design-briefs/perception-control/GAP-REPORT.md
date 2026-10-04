---
doc: gap-report
title: 查漏补缺报告 — 2026-07-15 ultracode 波次 1
layer: reference
status: living
updated: 2026-07-15
parent: PROGRESS.md
read_if: 你要知道本次并行审计发现了什么、哪些本会话修了、哪些 defer、PHASE W 怎么实施。
---

# GAP-REPORT — 并行查漏补缺（2026-07-15 ultracode 波次 1）

6 个只读 subagent 并行审计（S1 文档 / S2 PHASE P 回归 / S3 TUF 红队 / S4 WorldView 设计 / S5 Act 预研 / S6 安全扫描）。本报告 = 综合结论 + 本会话行动分类。

## P0 — 本会话修（CRITICAL / 明确 bug）

- **F1（S3，HIGH 明确 bug）**：未签补丁能 **disarm** 已签补丁。`CompatEngine` 的 `supersededIds` 只查 `PatchChain.rejectionReason==null`（链有效性），**不查 superseding 补丁的签名/status**，且 suppression（`:155`）发生在签名门（`:184`）之前。攻击者可注册一个无签名、更高版本、supersedes=KI-1 patchId 的 `EvilSupersede` → KI-1 被静默 disarm（自身也不 arm）。违反"in-code 注册不赋予信任"不变式（arm 方向成立，disarm 方向被破）。**修法**：supersession-suppression 只在 superseding 补丁也 `signer.verify` 通过（且 status==VERIFIED）时才生效。→ **本会话修 + teeth**。
- **文档漂移（S1，全部 VERIFIED）**：STATUS client=20→14（且括号方向说反）、STATUS "ahead 4"→已推 ahead 0、PROGRESS "未提交"→已提交 56a2789、known-issues KI-9 OPEN→FIXED headless/live-pending、KI-10 "完全未绑"→MITIGATED(L0 canary)。→ **本会话 C.1 修**。
- **F2（S3，MEDIUM 诚实缺陷）**：`ContentHash.java:13-18` + `CompatEngine.java:190-192` 注释 overclaim「签名绑行为/闭合 KI-10」,实际签名绑的是 manifest label hash（`sha256(TRANSFORM_SEED)` 作者标签串）,L0 canary 是**独立未签**的等值检查(attacker 能同时改 transform 和 sibling EXPECTED_CANARY_HASH 常量→L0 无对抗性,只挡意外漂移)。honest boundary 在 PatchCanonicalizer 写对了,但 ContentHash/CompatEngine 注释仍矛盾。→ **本会话随 Doc-fix 修注释诚实文案**（不改行为）。

## 已验证正确（无需动）

- **PHASE P 真绿非假绿（S2）**：40 tests 全过,seam 位置/journal 序列/filter 优先级/summarizer 安全全对,wire-frozen 契约在 mutation 攻击下成立。
- **私钥不在 git（S6）**：`scripts/secrets/` 已 gitignored 未跟踪,无残留旧钥,false positive 仅 NT Privilege 类名。
- **TUF arm 方向铁桶（S3）**：空 anchors 不 arm,无未签补丁能 arm,L2 crypto(threshold/root-introduction)无 bypass,cycle 检测正确。**无 CRITICAL**。
- KI-1/4/5/6/7/8、PHASE 0/T/P 勾选（S1）全部与代码一致。

## Defer（进 backlog,本会话不做）

- **F3（S3,MEDIUM）**：L2 fallback（`Compat.java:96-100`,root-metadata 空则退回 kernel anchor）交换掉了撤销属性——攻击者删 root-metadata.json 可绕过撤销。需 targets-key 泄露 + jar 篡改(强攻击者)。文档写清或 L2 资源保证存在后移除 fallback。
- **F4（S3,LOW）**：L3 SnapshotVerifier 未 wire 进 CompatEngine（mix-and-match/freeze 保护潜伏）。数据下发前必 wire,现 in-code 模式下 latent（已诚实记录）。
- **F5（S3,LOW）→ 已修（`239ffe1`）**：L1 cycle 不再全局毒化所有 superseding 补丁,`CompatEngine` 改用 `findCycleMembers` 限定到出问题的链自身。teeth `CompatEngineCycleScopeTest`。**F6 仍 defer**:compareVersions 溢出段→0(只会让攻击者版本更低,无害)。
- **S6 安全缺口（MEDIUM,姿态性)→ 已修（`239ffe1`)**：`SeProtectedObjects` 现覆盖 `net.marcloud.mcp.core.compat.` 整包前缀,堵住 hardened 模式下 redefine 阉割签名脊柱(Ed25519PatchSigner/CompatEngine/TufTrust 等)。teeth `SeProtectedCompatPackageTest`。
- **S2 stale-tap → 已修（`239ffe1`)**：`NettyTap.isHandlerInstalled` 重连后诚实报 false(不再读陈旧 trackedChannel)。teeth `NettyTapStaleChannelTest`。**仍如设计**:重连不自动重装 tap;byteLen 总是 -1(tap 在 decoder 后拿解码 Packet,诚实无害)。
- **A/E 实现**：本会话不做。S5 已产出 PHASE A 接口草案(movementInput 公共字段 seam 子类 wrap 注入,client 零 diff),存此备未来 04-PHASE-A 令状。

## PHASE W 实施顺序（S4 设计,本会话主交付）

包 `core/.../drivers/world`（扩现有 scan 包）+ `world_view` 工具在 `drivers/observe`。全 client 零 diff,单次 `GameBridge.onGameThread` marshal。11 步串行(每步编译+测):
1. ObserveProfile 枚举(sparse/explore/combat 预算) 2. SelfView+capture(W.2) 3. EnvView 4. InventoryView(W.5,registry name 非 displayName) 5. EntityView(W.4,copy list 防 CME) 6. LocalGrid 柱状采样(W.3,feet-first+RLE 空气压缩,FORK 默认柱状) 7. WorldView 根+Capture+Json(W.1) 8. TargetView(W.6,读 mc.objectMouseOver 复用原版视线) 9. WorldViewDiff(W.7,full|diff) 10. world_view 工具(W.8,R2+CAP_WORLD_READ)+Ring+McpCore 接线+scan_surroundings 委托 11. profile 端到端(W.9)。
**坑**：Json 只序列化 Map/List/scalar(写 toMap);gamemode/objectMouseOver 世界加载前 null(guard);getSaturationLevel/getPotionID 真名;itemRegistry.getNameForObject 非 displayName;world_view 必进 Ring.BUILTIN_RINGS(drift-guard 测试)。
