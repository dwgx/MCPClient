# 会话令状 — PHASE T（整段复制到 Claude Code）

---

你是 MCPClient 仓库的实现 agent（Claude Code）。

## 前置条件

确认 `PROGRESS.md` 中 **PHASE 0 的 P0.1–P0.4 已 [x]**（P0.5 live 可仍 pending-owner）。
若 PHASE 0 未完成：**停止**，只汇报阻塞，不要开始 T。

## 本会话唯一任务

**只做 PHASE T：时间轴脊柱（GameClock + 默认 tick + Timeline + 事件打戳）。**
禁止做 P/W/A 协议摘要、世界网格、动作状态机（除非为挂 tickId 所必需的最小改动）。

## 必读

1. `CLAUDE.md`
2. `docs/history/design-briefs/perception-control/BOOK-method.md`
3. `docs/history/design-briefs/perception-control/PROGRESS.md`
4. roadmap：`perception-control-max-roadmap.md` 的 **§0、§3、PHASE T、FORKS-T1**
5. codegraph：`TickInjector`、`TickEvent`、`TickBridge`、`EventBus`、`PacketReceivedEvent`、`McpCore`、board `TickSignal`

## 默认决策

- Tick **默认常驻**（`-Dmcp.core.tick=false` 可关）
- 相位：默认 **入口一相 + 可选 POST**（FORKS-T1-A）；若必须三相写进笔记
- **唯一** tick 源；禁止第二套计数器；禁止 render 当地逻辑钟
- board fan-out 不得破坏 core↔board 零硬依赖

## 任务 ID

- T.1 GameClock（tickId / monoNs / phase）
- T.2 TickEvent 同源
- T.3 默认武装 TickInjector
- T.4 包/断线/Hook 等事件附着 tickId + arrivalMono（Netty 线程策略写清）
- T.5 Timeline ring + 统一信封
- T.6 MCP `clock_now` / `timeline_tail`（Ring/Cap/Annotations）
- T.7 EventBus 热路径：无每 tick 全量 sort+alloc
- T.8 board TickSignal 同源（可反射桥）
- T.9 短文档 + STATUS

## 强制收尾

1. 相关模块测试绿（至少 core；动 board 则 board test）
2. **PROGRESS.md** 勾选 + 指针改为 PHASE T 完成或下一 Phase + 实现笔记
3. 汇报模板同 PHASE 0（改标题为 T）

## 铁律

无 AI 署名；先测再提交；不拆安全默认；一会话不跨 Phase。

现在开始 PHASE T。
