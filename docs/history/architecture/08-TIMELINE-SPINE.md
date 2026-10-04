---
doc: arch-timeline-spine
title: 08 — 时间轴脊柱(PHASE T:GameClock / tickId / Timeline)
layer: reference
status: authoritative
updated: 2026-07-14
parent: ../README.md
read_if: 你要理解或改动 tick 时间、事件排序、Timeline;或在 PHASE P/W/A 里给包/世界/动作附 tickId 时。
---

# 08 — 时间轴脊柱(PHASE T)

> 一句话:**全局唯一时钟 `GameClock`,一个 tickId 计数器,所有 EventBus 事件构造时自动附 tickId,Timeline 把它们折成可读时间线。** 禁止第二套 tick 计数器。

## 不变量(勿破)

1. **一个时钟**:`net.marcloud.mcp.core.ke.GameClock.INSTANCE` 是进程内唯一 tick 源。
   `TickBridge.onTick()`(runTick seam)是唯一 advancer。`TickBridge.tickCounter()` 现在
   读的就是 `GameClock.tickId()`,不再自己数。**新增 tick 计数器 = 违反 PHASE T。**
2. **tickId 自动附着**:`GameEvent` 基类构造时 `tickId = GameClock.INSTANCE.lastCompletedTick()`。
   所有事件子类(Packet/Disconnect/HookFired/Seam/Tick)零改动即获得 `tickId()`。
   `arrivalMono` = 已有的 `timestampNanos()`。
3. **线程语义**:游戏线程事件(tick/hook)拿"进行中的 tick";off-thread 事件(Netty worker 的
   inbound/outbound packet)拿 `lastCompletedTick()` = "到达时游戏所处的 tick"。这是异步到达的
   正确归属。`GameClock` 用 AtomicLong + volatile 快照,跨线程读安全。
4. **相位(FORKS-T1 = A)**:`Phase.START`(runTick 入口,唯一 shipped)+ `Phase.POST_WORLD`
   (保留,enum 里有但默认 seam 不 advance)。`POST_WORLD` 是同 tick 的第二相,**不 bump tickId**
   (仍按整 tick 计数)。若将来发现移动应用点与观测点错位,再接真 POST_WORLD advance(§FORKS-T1-B 三相)。
5. **默认武装**:`McpCore.start` 默认装 TickInjector(`-Dmcp.core.tick=false` 关)。缺 Instrumentation
   (无 -javaagent)⇒ 时钟不 advance(tickId 恒 0),MCP 照常服务,非致命。之前是 opt-in(只经
   seam_tick_enable 工具);现在时间轴是核心设施,默认开。
6. **热路径(T.7)**:`EventBus.publish` 按事件具体类缓存匹配订阅者(`dispatchCache`),warm 后无
   每事件 isInstance 扫描/alloc。订阅/退订清缓存(cold path)。镜像 board `Trace` 的 dispatchCache。

## 组件落点

| 件 | 类 | 说明 |
|---|---|---|
| 时钟 | `ke/GameClock.java` | tickId/monoNs/phase,单例 INSTANCE + 可构造(测试) |
| tick 事件 | `ke/event/events/TickEvent.java` | 挂 phase;`tickCount()` 保留=`tickId()` 兼容旧订阅者 |
| tick 源 | `flt/seam/TickBridge.java` | onTick → GameClock.advance();唯一 advancer |
| 事件基类 | `ke/event/GameEvent.java` | 构造时附 tickId |
| 时间线 | `ke/Timeline.java` | 锁式 ring,订阅 GameEvent 基类,折成 {tickId,arrivalMono,kind,summary} 安全投影 |
| 工具 | `drivers/observe/ObserveTools.java` | `clock_now`(R3)+ `timeline_tail`(R3,limit 默认 50) |
| board fan-out | `link/BoardClockBridge.java` | 反射把 TickEvent 镜像成 board TickSignal(零 hard import;board 缺席=no-op) |
| 热路径 | `ke/event/EventBus.java` | dispatchCache 预索引 |

## board 合流(T.8)

core↔board 零硬依赖不破:`BoardClockBridge` 订阅 core TickEvent,**反射**(经 Backplane
`board.port` → `trace()` → 反射 `new TickSignal(tickId)` + `publish`)镜像到 board Trace。board
缺席/未启动 → 静默 no-op。board 的 tick chips(如 TickCounterChip)因此跑在同一 GameClock 上——
一个时钟跨两个子系统。注意:Board.init 点火 + 默认装 chip 是 **PHASE E.3** 的事,不在 T.8。

## live 边界

headless 证:时钟单调、事件附 tickId、Timeline 折叠、EventBus 缓存正确、board 桥 degrade。
**真机才能验**:runTick seam 真的每 tick advance(需 -javaagent 起真游戏);`clock_now` 在活客户端
返回递增 tickId;`timeline_tail` 有真事件。这些是 PHASE T 的 live 项(P0.5 同性质),待 owner 起游戏。

## 下一步(顺序铁律)

PHASE T 完成后 → PHASE P(协议深读 + 修 KI-9):包附 tickId 已就绪(T.4),PacketJournal 可直接用。
