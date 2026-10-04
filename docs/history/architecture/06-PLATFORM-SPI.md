---
doc: board-framework
title: 06 — Board:客户端功能框架(与 mcp-core 平级的第二子系统)
layer: reference
status: authoritative
updated: 2026-07-11
parent: ../README.md
next:
  - path: 00-OVERVIEW.md
    when: 你要看 mcp-core(LLM 侧子系统)与整体
  - path: 01-SECURITY-KERNEL.md
    when: 你要理解 mcp-core 的 7 层门(Board 不走它,平级满权)
read_if: 你要写"游戏内功能"(功能模块/HUD/ClickGUI/键位/启动页替换/正版登录…)、或要理解 Board 框架与 mcp-core 如何平级解耦、可选启动、互相挂载时读。这是"以后都不改"的框架契约。
---
# 06 — Board:客户端功能框架

> **一句话**:`Board`(电路板)是一个**独立、可拆卸**的客户端功能框架——一堆 Manager(事件/功能/HUD/GUI/键位…)的家。它与 `mcp-core`(给 LLM 的子系统)**平级**:各自可单独启动、可互相拉起、各自满权、零硬依赖。
> 命名母题:**PCB / 电路板**(Trace 走线、Signal 信号、Chip 芯片、Matrix 矩阵、Port 端口、Backplane 背板)——全套自创、统一,脱离通用词。
> **本文是定死的框架契约**:接口一旦定稿即"永不改",新功能只实现接口、绝不动骨架。

## 0. 定位(反复澄清后的正确理解)

`Board` **不是** mcp-core 的下层平台,**也不是**上层封装。它是**和 mcp-core 平起平坐的第二个子系统**:

```
              活着的 MC 1.8.9 游戏 (都经 -javaagent 挂上)
                            │
              ┌─────────────┴──────────────┐
              ▼                            ▼
    ┌───────────────────┐        ┌────────────────────┐
    │   Board (本文)      │◄──────►│   mcp-core          │
    │  客户端功能框架       │  平级   │  给 LLM 的 MCP 子系统 │
    │  Trace/Chip/Matrix │  解耦   │  se/ob/io/kd/…      │
    │  HUD/ClickGUI/键位  │  互挂   │  7 层门 + 工具面      │
    └───────────────────┘        └────────────────────┘
       两个独立子系统:各自可单独启动、可互相拉起、各自满权、零硬依赖
```

- **"不启动它,后面写的功能就不存在"**:Board 是所有客户端功能(模块/HUD/GUI)的家。
- **"和 MCP 可以选一个启动"**:三种开机模式 —— 只 Board(纯客户端)/ 只 mcp-core(纯 LLM)/ 两个都起。
- **"MCP 能拉起它,它里面也能快捷启动 MCP"**:双向,通过服务发现互相拉起。
- **"都是外部挂载、最高权限最干净"**:两者彻底解耦,各自直接摸游戏(满权),互不硬依赖。

## 1. 三条解耦原则(用户认同 —— "最干净、可拆卸、完美"的技术保证)

1. **零硬依赖(编译期)**:Board **不 import** mcp-core 任何类,反之亦然。跨子系统只经**纯反射**(`link/McpLink` 反射找 core;`link/BoardPort` 把 Board 暴露出去)+ 登记处 `Backplane`,没有共享的编译期接口。删掉任一方,另一方照样编译、照样跑。
2. **服务发现(运行期)**:"互相拉起/调用"不是 `new TheOther()`(那就耦合了),而是去中立的 `Backplane` 按 key 问"对方在不在?在就给我它的句柄(一个只暴露 JDK 类型的反射端口)"。在→拿到;不在→优雅降级。
3. **配置点火(部署期)**:中立引导器读开关决定这次点谁(`-Dboot=board` / `mcp` / `both`,默认 both)。

## 2. 物理结构:独立新模块(用户定)

新增 Maven 模块 **`board/`**,与 `core/` `client/` `lwjgl2-shim/` 平级:
```
pom.xml <modules>: lwjgl2-shim, client, core, board, pg   ← board 是本文主题;pg 为后加的第 5 模块(加固库)
board/  = 独立客户端功能框架,可整个删掉而不影响 core 编译/运行
```
这是"可拆卸"的物理硬保证:框架自成一个 module,不在 core 里。

## 3. 命名(全套自创,PCB 母题,已定稿)

| 概念 | 定名 | PCB 出处 |
|---|---|---|
| 事件总线 | **`Trace`** | 铜箔走线——事件在走线上传 |
| 一个事件 | **`Signal`** | 走线上的电信号 |
| 功能单元(启动页/登录/外挂都是它) | **`Chip`** | 焊在板上的芯片 |
| 功能总管(EventManager/ModuleManager 的替代) | **`Matrix`** | 芯片焊在矩阵上 |
| 框架总门面(总入口) | **`Board`** | 电路板本体 |
| 解耦中立接口(两子系统共享,零依赖靠它) | **`Port`** | 端口,插上就通 |
| 服务登记处(服务发现靠它) | **`Backplane`** | 背板,子系统插上互相发现 |
| 键位 | **`Pin`** | 引脚 |
| HUD / 显示 | **`Panel`** | 面板 |
| 优先级(信号处理顺序) | **`Clock`** | 时钟定节拍 |
| 引导器(中立点火,扩自现有 CoreBootstrap) | `Bootstrap` | 保留名,扩成中立 |

> 已实现并冻结的骨架:**Trace / Signal / Chip / Matrix / Manager / Board / Backplane(+ Clock)**。`Trace.subscribe` 返回 `Trace.Subscription` 句柄(可 `cancel()` / try-with-resources,防订阅泄漏)。跨子系统解耦**没有**抽象 `Port` 接口——用的是 `link/` 下的 `BoardPort` + `McpLink`(纯反射,零编译耦合),`Backplane` 是登记处。

用起来的样子:
```java
Board.trace().publish(new TickSignal());   // 走线上发一个 tick 信号(signals.TickSignal)
Board.features().add(new FlyChip());        // 往默认功能 Matrix 装一个芯片
class FlyChip extends Chip {                 // 一个功能 = 一个芯片
    // subscribe 返回 Subscription 句柄——保存它,onDisable 时 cancel(),防泄漏
    private Trace.Subscription sub;
    protected void onEnable()  { sub = Board.trace().subscribe(TickSignal.class, this::onTick); }
    protected void onDisable() { if (sub != null) sub.cancel(); }
    private void onTick(TickSignal s) { /* ... */ }
}
```

## 4. 框架骨架(参考 Example 的 managers,用 PCB 命名重塑)

`Board` 门面持有各 `Matrix`(总管)的单例。Example 那套 CommandManager/ModuleManager/RenderStateManager… 对应到:
- **`Trace`**(事件总线):自建、零外部依赖(不用 Example 的 MBassador,与 mcp-core `ke.event.EventBus` 同源风格)。显式泛型订阅 + `Clock` 优先级 + `Signal.Cancellable` 可取消。
- **`Chip`**(功能单元,中性):生命周期 `onLoad → onEnable/onDisable → onUnload`。**中性通用**——启动页替换、正版登录、外挂全是 Chip;分类/`Pin` 键位/开关只是 Chip 的可选属性,**不分作弊/非作弊层**(用户明确砍掉伪需求)。
- **`Matrix`**(功能总管):`add/remove/byId/byType/all` + 批量生命周期。可有多个(功能 Matrix、HUD Matrix、命令 Matrix…)。
- **`Board`**(门面):对齐 Example 的 `Global`,持有 Trace + 各 Matrix 单例的唯一静态入口。

## 5. 两子系统怎么互相挂载(Port + Backplane,零硬依赖)

```
       Backplane (中立登记处,谁都不属于)
        ├── register(Port)   子系统启动时把自己的 Port 登记上来
        └── find(type)       另一方按类型查询,拿到 Port 句柄或 null(降级)
              ▲                          ▲
              │ 登记 BoardPort           │ 登记 McpPort
        ┌─────┴─────┐              ┌─────┴─────┐
        │  Board     │              │ mcp-core  │
        └───────────┘              └───────────┘
```
- **`Port`** = 极小中立接口(如 `id()` / `handle()` / 能力查询)。两子系统各实现自己的 Port,**都不 import 对方**,只 import `Port` + `Backplane`(放在共享的最小契约处 —— 可放 core 的一个中立包或独立 tiny 契约 jar,定稿时定)。
- **mcp-core 拉起 Board**:mcp-core 的一个工具 `board_start` → `Backplane.find(BoardPort)`;没有就按配置引导 Board。
- **Board 拉起 mcp-core**:Board 的一个 Chip(或菜单项)→ `Backplane.find(McpPort)` → 快捷启动 mcp-core。
- **删掉任一方**:`Backplane.find` 返回 null,另一方优雅降级,照常运行。

## 6. 启动流程(中立引导器,扩自现有 CoreBootstrap)

现状(真实源码):`-javaagent` → `StartupAdvice` 织进 `Minecraft.startGame()` 尾 → `CoreBootstrap.onGameInitialized()` → 写死 `new McpCore().start()`。

改成中立点火(加性,默认行为不变):
```
startGame 退出 → Bootstrap.onGameInitialized()
   读 -Dboot (默认 both)
   ├── 含 mcp   → 启动 mcp-core,登记 McpPort 到 Backplane
   └── 含 board → 启动 Board,登记 BoardPort 到 Backplane
   (启动顺序无关;后起的一方能通过 Backplane 发现先起的)
```
**默认 `both`**:两个都起、互相登记 —— 但因零硬依赖,`-Dboot=mcp` 时 Board 类根本不加载,反之亦然。

## 7. 定死的铁律(框架永不改的保证)

1. **骨架冻结**:`Trace` / `Signal` / `Chip` / `Matrix` / `Board` / `Port` / `Backplane` 公开契约定稿即冻结。新功能只实现 `Chip`、新增类,绝不改骨架签名。改骨架需用户明确批准(类比 CLAUDE.md 铁律 #5)。
2. **零外部依赖**:框架核心不引第三方库(不学 Example 的 MBassador),与 mcp-core 自建风格一致。
3. **零硬依赖 / 平级解耦**:Board 与 mcp-core 互不 import,只经 `Port`+`Backplane`。任一方可单独删除/启动。
4. **中性优先**:`Chip` 是中性能力单元,无"作弊层"概念;分类/键位是可选属性。
5. **满权直连**:Board 直接经 `GameBridge`/游戏对象操作(客户端满权),**不走 mcp-core 的 7 层 tool 门**——它是平级子系统,不是被 gate 的工具。
6. **沿用 mcp-core 全部铁律**:无 AI 署名、先编译再测试才提交、每个功能/fix 配非空转回归测试、se/ob 不可动、生成码 R-1。

## 8. 待用户拍板(定稿前)

- 非核心词微调:键位 `Pin` / HUD `Panel` / 优先级 `Clock` 是否 OK?
- `Port`+`Backplane` 契约物理放哪:core 里一个中立包 / 独立 tiny 契约模块 / board 里由 mcp-core 反向依赖(不推荐)?
- 本次是否落地最小骨架(Trace+Signal+Chip+Matrix+Board 各一个 + 一个示例 Chip + 测试),还是继续只留设计文档?
- Chip 的分类集合(如启动页/登录/渲染/自动化…)先定哪些?

## 9. 参考血缘

- **`Example-master`**(`_refs/Example-master/`,gitignored 隔离,绝不污染):`Global`(门面)、`event.Event`(Cancellable/State)、`features.module.AbstractModule`(onEnable/onDisable/toggle/category/shortcut)、`managers/*`(ModuleManager/CommandManager/RenderStateManager…)、`handlers.EventHandler`。Board 取其骨架,换 PCB 命名 + mcp-core 风格 + 平级解耦。
- **mcp-core 现有**:`ke.event.EventBus`(Trace 的前身风格)、`flt`/`flt.seam`(事件来源,Trace 可复用)、`boot.CoreBootstrap`(引导器前身)。

