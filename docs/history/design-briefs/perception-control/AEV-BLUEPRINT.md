---
doc: aev-blueprint
title: PHASE A/E/V + C横切 + 技术债 实施蓝图 (2026-07-15 ultracode 波次1综合)
layer: reference
status: living
updated: 2026-07-15
parent: PROGRESS.md
read_if: 你要实现 PHASE A/E/V,或按 lane 并行 worktree 分工,或看本战役的冻结契约。
---

# AEV-BLUEPRINT — 并发实现蓝图

8 路并行设计(6 workflow + 2 补齐 subagent)综合。目标:非冲突 lane 分工,worktree 并行实现新文件,主 agent 串行合并共享文件。

## LANE 分工(不相交新文件集)

### Lane 1 — act 核心包(串行一条 lane,内部编译依赖紧)
`core/.../drivers/act/` 全部 A.1+A.2+A.3-A.6:
- A.1: ActRuntime/ActTickLoop/ActApplier/ActSlot/ActPhase/ActIntent/MoveIntent/LookIntent/InteractIntent/ActStatus
- A.2: ActMovementInput/MovementInputInstaller/MoveIntentView/PlayerInputSlot
- A.3-A.6: ActActuator(接口,client-free 测试边界)/LivePlayerActuator(唯一 live-only)/LookController/DigController/InteractController/HotbarController/ActOutcome
- 测试: ActRuntimeTest/ActMovementInputTest/MovementInputInstallerTest/Look·Dig·Interact·HotbarControllerTest/FakeActuator + *LiveIT(pending-owner 壳)
- **为何一条 lane**:三组同包 `drivers/act/` 互相引用(installer 用 ActRuntime;controllers 被 loop 调),分开 worktree 合并必碎+假绿。ActActuator 接口是测试楔子——所有 controller 纯状态机,live 只在 LivePlayerActuator。

### Lane 2 — act tools(独立包)
`core/.../drivers/action/ActTools.java` + ActToolsTest。A.7 三工具 RPC 面(act_set R1/act_cancel R1/act_status R3)。依赖 Lane1 的 ActRuntime facade → Lane1 合并后再合。

### Lane 3 — board E(独立模块 board/ + core/link)
- `core/.../link/BoardTraceLink.java`(反射 board-Trace facade + publishChatSend)+ BoardWorldEventBridge.java
- `board/.../signals/ChatReceiveSignal.java` + DisconnectSignal.java(Tier-1)
- `board/.../chips/OfficialChips.java`
- 测试: BoardTraceLinkTest/BoardWorldEventBridgeTest(core)+ WorldSignalsTest/OfficialChipsTest/NoThirdBusTest(board)

### Lane 4 — 技术债(compat/se/seam)
- S6 FIX-NOW: SeProtectedObjects.java 加 `COMPAT_PACKAGE="net.marcloud.mcp.core.compat."` 前缀(一行 OR),覆盖整个信任核。teeth: 断言 isProtected("...compat.Ed25519PatchSigner")==true。
- S2 FIX-NOW: NettyTap.isHandlerInstalled 重连后 stale → status 检查里重新 acquire/比对活 channel。
- F5 FIX-NOW: CompatEngine cycle 全局毒化 → 用 findCycleMembers 把 cycle 限定到自己那条链(+F6 compareVersions 溢出 cosmetic 一并)。teeth: CompatEngineCycleScopeTest(X 有环被拒,Y 合法签名链仍 arm)。
- **注意 Lane4 碰 CompatEngine.java 和 SeProtectedObjects.java = 也是共享文件**,但只有 Lane4 碰它们 → Lane4 可独占这两个文件(不与其他 lane 冲突),当作 lane 内文件。

## 主 AGENT 串行合并(worktree 不碰)
按序:
1. **SeToolRequirement.java**(C-partition 关键发现:第4张表!Ring+Cap 之外,PolicySideTableDrift 还查这个)—— act_set/act_cancel 加 L3_WRITES。
2. Ring.java BUILTIN_RINGS: act_set R1/act_cancel R1/act_status R3。
3. CapabilityCatalog.java: act_set/act_cancel → CAP_WORLD_WRITE(act_status 省略,同 clock_now R3-read 先例)。
4. ToolRegistry.java: V.1 capture_screen 描述改诚实(validation profile,world_view 为主)。
5. McpCore.start(): ActTickLoop.attach(bus) → registerApplier(A.2/A.3-5 的 applier)→ new ActTools(actRuntime).registerAll → MovementInputInstaller.attach → BoardWorldEventBridge.attach。stop() 里 installer.disarm()。
6. ActionManager.sendChat: publishChatSend + honor veto(E.1,**待 dwgx 确认语义**)。
7. Board.init(): OfficialChips.install(**冻结文件,待 dwgx ADR+3确认**)。
8. PROGRESS/STATUS/known-issues 打勾 + 测试数(C.1,最后做)。

## 待 dwgx 决策(不发明品味)
- **D1** E.1 honor veto 改 AI-send 语义;send_chat 是否报 reason()?
- **D2** E.2 scope:Tier-1(chat-recv+disconnect)now,Tier-2 defer?
- **D3** E.3 改冻结 Board.init() → 要 ADR + 3 次确认?
- **D4** C.6 第二颗签名补丁选哪个 target/name?
- **D5** act 工具 Ring:act_set/cancel=R1 写,act_status=R3 读 —— 确认?

## DEFER(保持)
S3 F3(L2 fallback 撤销)/F4(L3 未 wire)= 数据下发才咬合,doc-only note。KI-1=WON'T-FIX。KI-10=owner sign-off。A.9 overlay 吞输入/A.10 WS-SSE = phase-2 defer。V.2 交叉校验 = defer(省 3 张共享表 churn)。

## LIVE pending-owner
A.11 无截图操控闭环、A.3-A.6 真 PlayerControllerMP 路径(*LiveIT)、E 端到端。
