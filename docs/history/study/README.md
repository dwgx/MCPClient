---
doc: study-index
title: 架构研究 — 索引 + 横向综合
layer: reference
status: reference
updated: 2026-07-11
parent: ../README.md
read_if: 你想在动 Board/mcp-core 前先看清"业界都怎么做 event/module/gui/config/injection",要一张横向对比表和一份"我们该学什么"的排名清单。逐项目细节去 client-architectures.md。
---

# 架构研究 — 索引 + 横向综合

> 十项研究、11 个参考实现:6 个本地 MC 客户端 + 工业级项目(Meteor、Baritone、Fabric、Forge)+ 现代化 shim 项目。
> 目标不是复刻,而是**给 Board(客户端框架)和 mcp-core(把活体游戏暴露给 LLM)各自定位**——哪些是我们**已经做对**的、
> 哪些**值得加**(additive,尊重冻结契约)、哪些是**反面教材**。
> 版本图例: **1.8.9-applicable**(具体类名/seam 可直接参考,我们 `client/` 是 vanilla 映射)·  **结构可借**(1.12/Fabric/现代,名字不通)。

## 我们的坐标系(冻结契约,对照用)

Board 的框架契约(design doc 06 §7,**只能加子类、不能改签名**):
`Trace`(事件总线,`board/Trace.java`)/ `Signal`(事件基类,带 `Cancellable` 的 PRE/POST `State`)/ `Clock`(优先级枚举 HIGHEST..LOWEST)/
`Chip`(功能单元)/ `Matrix<T>`(功能管理器)/ `Backplane`(静态服务注册表)/ `McpLink`+`BoardPort`(反射桥)。
非冻结上层 seam:`Panel`/`HudMatrix`/`Pin`/`PinMatrix`/`link`。

**已核实的当前实现事实(动手前必读,以源码为准):**
- `Trace.publish` **每次发布都 `new ArrayList` 收集匹配者再 `matching.sort(BY_PRIORITY)`**(`Trace.java:146-152`)——热路径分配+排序,是首要可优化点。
- `Trace` 用 `s.type.isInstance(signal)` 派发,**已支持子类型/接口 fan-out**(比 orbit/Emperor/Faiths 的精确类匹配强)。
- `Chip.onEnable/onDisable` 是**用户钩子**,`setEnabled` 已做异常隔离(`Chip.java:79-94`),但**订阅簿记要 Chip 作者自己在 onDisable 里 cancel()**——目前没有基类自动管理订阅袋。
- `TickSignal` **每 tick `new`**(`TickSignal.java:33`)——GC 抖动点。
- Board **暂无配置持久化层**。`Backplane` 是静态 `ConcurrentHashMap` 服务注册表,`find` 返回 null 优雅降级(两-peer 解耦已就位)。

---

## 索引(按与我们的相关度)

| # | 项目 | MC 版本 | 相关度 | 一句话 |
|---|---|---|---|---|
| 1 | Lavender-rise |  1.8.9 | 最高 | 自建 bus 性能标杆:`@EventLink` 字段 lambda + `listenerCache` 零反射派发 |
| 2 | Faiths-Recode |  1.8.9 | 高 | `isAccessible()` 门(开机注册一次、翻布尔)+ 字段 `Handler<T>` + 全自动发现 |
| 3 | HackSoar |  1.8.9 | 高 | 反射注解 bus + SpongeMixin(证明 1.8.9 能跑 Mixin)+ i18n-key 一键三用 |
| 4 | Emperor-reborn |  1.8.9 | 高 | 反射注解 bus(注册时预排序)+ 设置依赖谓词 + 可插拔多 ClickGUI |
| 5 | Southside |  1.12.2 | 中 | **双 bus 反面教材** + OneConfig 声明式配置 + 坏文件容错 |
| 6 | Example-master |  1.21.4 | 中 | **`ModuleServer` 远程控制面(mcp-core 的镜像 + 安全反例)** + protobuf 信封 |
| 7 | Meteor Client |  1.21.x | 高(结构) | orbit bus 的 `LambdaMetafactory` 零反射派发 + 崩溃安全持久化 |
| 8 | Baritone |  1.12→1.20 | 高(结构) | api/impl 接口-only 纪律 + `IBaritoneProcess` 优先级仲裁 |
| 9 | Fabric API |  1.20+ | 参考 | `EventFactory.createArrayBacked` invoker 工厂 |
| 10 | Forge / NeoForge |  1.12-1.20 | 参考 | `IEventBus` + 事件继承 fan-out + `Event.Result` 三态 |
| 11 | 现代化项目 |  1.7-1.12→LWJGL3 | 侧证 | lwjgl3ify / legacy-lwjgl3 佐证 `lwjgl2-shim` 路线 |

逐项目细节(保留具体类名与亮点)见 [client-architectures.md](client-architectures.md)。

---

## 横向对比表(项目 × 五维)

| 项目 | event | module | gui | config | injection |
|---|---|---|---|---|---|
| **Lavender**  | 字段 `Listener<T>` + `@EventLink`,MethodHandles 取一次 → `listenerCache` 零反射;精确类;world-null 门 | `@ModuleInfo` 注解;`superEnable/Disable` **final** 化订阅簿记;`Component`(常驻)/`Module`(可 toggle)两层 | vanilla `GuiScreen` + `Value↔ValueComponent` 每类型一视图;shader/自定义字体 | GSON 单文件;`Value` 自注册;instanceof 阶梯(`name*index` 键,**坑**) |  源码内联补丁 ~22 类 |
| **Faiths**  | 字段 `Handler<T>`,`isAccessible()` **门**(开机注册一次);精确类;静默吞异常 | `CheatModule implements Listener`;Guava classpath **全自动发现** | 全自定义即时模式;自建 TrueType;`NotificationManager` FPS 归一化动画 |  snakeyaml;`AbstractValue.toYML/fromYML` **多态自序列化**;keybind 存人类名;**setValue 全量写(放大)** |  源码内联补丁 32 hook 点 |
| **HackSoar**  | `@EventTarget(byte)` 反射方法 bus;`VALUE_ARRAY` 排序;payload 可变可取消;手搓 `ArrayHelper` COW | `Mod` toggle=`register/unregister`;Setting 自注册带 parent 反指针 | NanoVG 菜单 + `ui/comp` 组件树;HUD 可拖拽;MCEF 浏览器 |  profile JSON(Gson);**i18n key = 显示名+持久化 key+搜索**;**server-IP 自动切 profile**;instanceof 阶梯(双份) |  LaunchWrapper + **SpongeMixin**;Mixin/Accessor 分离;移除 LWJGL classloader 例外 |
| **Emperor**  | `@EventTarget` 反射方法 bus;**注册时预排序**;`EventStoppable`/`Cancellable`/`EventTyped` 三契约 | `Module` setState 自订阅;`addModule` 反射收集 `Value<?>` 字段 | **可插拔多 ClickGUI**(book/drop/express)绑同一 `Module/Value` |  Gson;`Config` 抽象 save/load;instanceof 派发;**按显示名 key(脆)**,无版本 |  源码内联补丁 ~23 处;`sun.misc.Unsafe` |
| **Southside**  | **双 bus(反例)**:me.bush + kbrewster(LMF)+ OneConfig;`EventState{PRE,POST}` 一类两相位 | `Module extends OneConfig Config`(重耦合);`@Binding`/`@DefaultEnabled`;onEnable 返回 boolean **否决** | NanoVG(stencil FBO + bloom);**自己的 ClickGui 注释掉**,用 `OneConfigGui` |  OneConfig JSON;**坏文件改名 `*.corrupted` 重载默认**;`{metadata,data}` 信封 |  **内嵌 deobf 源码手改** 51 文件;无 mixin;`org.lwjglx` shim |
| **Example**  | **MBassador 外部库**;`Event.State` + `Cancellable(cancelReason)`;`PublicationErrorHandler` 全局弹性 | 模块即 listener(`setState` subscribe);`Initializable`+`Manageable` 两小接口;`items()` 硬编码 |  **Skija-on-framebuffer** +  **`ModuleServer` 远程 HTTP/WS 控制面** |  **protobuf 自描述信封**(`BaseFile` type+时间戳+metadata);按稳定 NAME 键 |  Fabric Mixin + MixinExtras;薄适配器;`example$-` duck-type |
| **Meteor**  | orbit 库; **`LambdaMetafactory` 零反射**;int 优先级(可插队);单例事件 `get()`;`isListening()` | toggle=`subscribe/unsubscribe`(autoSubscribe);`Settings` 树;显式 `add()` | 自定义 widget 工具箱;可换 `GuiTheme`;设置自渲染;HUD 是 `System` |  NBT per-System; **原子写 + 坏文件备份 + reset-before-load** |  Fabric Mixin;`@PreInit/@PostInit` 拓扑排序;per-addon Lookup |
| **Baritone**  | `IEventBus`,mixin 喂事件,不碰功能代码 | `IBaritoneProcess`(报 `isActive()`+`priority()`) | — | — |  **api/impl 接口-only 严格分层**; **优先级仲裁 + `isTemporary()`** |
| **Fabric API**  |  `EventFactory.createArrayBacked` invoker 工厂;返回协议协作;无优先级 | callback 接口 | — | — | Fabric Mixin |
| **Forge**  | `IEventBus` + `@SubscribeEvent`;**事件继承 fan-out**;`Event.Result` 三态;ASM handler;两 bus | `@Mod` 生命周期 | — | — | coremod/Mixin |

---

## 光谱一:事件总线设计(从我们的 `Trace` 到工业级)

按"派发时的反射成本"从高到低,恰好也是从 1.8.9 血亲到工业级:

1. **反射方法 bus(1.8.9 主流)** — HackSoar/Emperor(LiquidBounce 血统):`@EventTarget(byte)` 扫方法,`Method.invoke()` **每次派发反射**。
   简单、可证(1.8.9 就这么跑),但热路径慢、常静默吞异常。Emperor 的进步:**注册时预排序**,派发只直迭 `CopyOnWriteArrayList`。
2. **字段 `Handler<T>`/`Listener<T>` + 门** — Faiths(`isAccessible()` 门,开机注册一次)/ Lavender(`@EventLink` + `MethodHandles.unreflectGetter`
   取一次 lambda + 预算 `listenerCache`)。Lavender 是**本组最快自建 bus**:反射成本只在注册付,派发是零反射索引 while 循环。
3. **LambdaMetafactory 零反射** — Meteor orbit / Southside kbrewster:subscribe 时旋 `Consumer<Object>`,派发是近-native lambda 调用。
   工业级性能上限。
4. **invoker 工厂 / 事件继承** — Fabric(聚合 invoker,无优先级,返回协议)/ Forge(`IEventBus` 事件子类 fan-out + `Event.Result` 三态 + ASM handler)。
   mod-API 取向。
5. **外部库** — Example MBassador / Southside me.bush。功能全(异步/弱引用/全局 `PublicationErrorHandler`),但**违反我们零外部依赖铁律**。

**取消/相位的普遍共识(全组一致,Board 已匹配)**:事件建模为带 PRE/POST 相位 + 可取消子类型。Example `Event.State{ANY,PRE,POST}` +
`Cancellable(cancelReason)`、Emperor `EventTyped` phase byte、Southside `EventState{PRE,POST}`。**一个事件类带 phase 字段服务两相位**,
胜过定义两个事件类——Board 的 `Signal.Cancellable.State{PRE,POST}` 和 `TickSignal.Phase{START,END}` 正是如此。

**Board 的定位**:`Trace` 已在第 2~3 档之间(类键控 + `Clock` 优先级 + `isInstance` fan-out + `Subscription` handle + 异常隔离到 stderr),
比 1.8.9 主流(第 1 档反射 bus)先进。唯一落后于工业级的是**派发时排序+分配**(见上文实现事实)与**未用 LMF**——两者都是**可选优化,非契约变更**。

## 光谱二:模块/管理器设计

**功能单元(module/Chip)——一个普遍不变式贯穿全组:「启用 == 订阅事件总线」。**
Example `setState`→`subscribe(this)`、Lavender `superEnable`→`register(this)`、HackSoar/Emperor/Faiths(门变体)、Meteor `toggle`→`subscribe`。
停用模块**物理离开派发表**,handler 永不写 `if(!enabled)`,也不会泄漏 listener。**这是最重要、最普遍的模块模式。** 两种实现:
(a) subscribe/unsubscribe 抖动(多数),(b) Faiths 的 `isAccessible()` 门(开机注册一次、翻布尔、派发时过滤——零抖动但常驻极廉过滤)。

**注册风格两分**:① 手工硬编码列表(Example `items()`、HackSoar/Southside/Meteor `add(new X())`)——显式、可 grep、确定序,但每加一个功能改管理器;
② 反射发现(Faiths Guava classpath 扫描、Lavender `@Hidden`-skip 的 classpath 扫描、Emperor/Southside 字段扫收集 Value)——加功能只碰一个类,但慢、classloader 脆。
**没有一个客户端用 DI 框架。**

**管理器风格两分**:① 神对象(HackSoar `Soar` ~19 字段、Southside/Emperor/Faiths/Lavender `Client.INSTANCE`)——便利但隐藏全局依赖图、不可测;
② 接口契约(Example `Initializable{init/destroy}` + `Manageable<T>{add/remove/items}`、Meteor `System/Systems` 两层)——统一生命周期、可批处理、可测。
**启动顺序是普遍暗坑**(font→module、file→managers,每个客户端都手动处理)——支持**显式有序 boot** 而非靠 static-init 副作用。
Board 的 `Backplane` 服务注册表 + `Matrix` 已是比神对象干净的答案;`Matrix<T>` 的 `add/remove/byId/all/enableAll/disableAll` 已对齐 `Manageable` 契约。

## 光谱三:GUI / config / injection 模式

**GUI**:全组把 **HUD 元素建模为功能单元本身**(可拖拽、持久化 x/y/scale、从管理器枚举)。**ClickGUI 常是"另一个功能单元"**
(Lavender/Faiths 里 ClickGUI 就是个 Module,toggle 即开屏)。**数据/视图分离**:Lavender `Value` vs `ValueComponent` 每类型一渲染器;
Emperor 可插拔多 ClickGUI 前端绑同一 `Module/Value` 模型。**字体渲染永远自建**(NanoVG/Skija/bitmap-atlas,从不用 vanilla FontRenderer)。
**一模型多消费者**对我们尤其重要:mcp-core 也要读同一 `Value`/设置模型,不只 GUI。

**config**:光谱从 snakeyaml(Faiths,1.8.9 最干净)→ Gson JSON(HackSoar/Emperor,含 profile + server-IP 自动切换)→ OneConfig 框架
(Southside,重外部依赖)→ protobuf 信封(Example,最重)。**两个反复出现的好想法**:(a) **每个 value 类型自己 (de)serialize**(Faiths
`toYML/fromYML`),config 引擎从不 switch 类型;(b) **自描述信封**(Example `BaseFile`、Southside `{metadata,data}`:type+时间戳+版本,容缺/多键)。
**反复出现的坑**:按**显示名**键控(Emperor/Southside/Lavender `name*index`)——改名即丢存档;应按**稳定 ID**键。Meteor 的**原子写 + 坏文件备份**是工业级基线。

**injection 三分法**(详见 client-architectures.md 附):A. 内嵌 deobf 源码内联编辑(全 1.8.9 客户端,简单但零可移植)· B. SpongeMixin
(HackSoar/Example,Mixin/Accessor 分离,专业)· C. 我们的 `-javaagent` + `flt.seam` weave(两头都不是)。教训:**把 vanilla hook 集中为一组
可枚举的具名 `Signal` 发射点**(Faiths 恰好 32 个,易审计),且**留在功能代码之外、藏在稳定事件契约后**(Mixin 客户端的纪律)。

---

## 什么值得我们 Board / mcp-core 学习(排名 · 可引用 · additive · 尊重冻结契约)

> 每条:模式 → 出处项目/类 → 怎么落到我们身上。全部**加法式**(新子类/新上层 seam/新可选层),不改冻结签名。

###  T1 — `Chip` 基类自动管理订阅袋,把「启用==订阅」做成不可破坏(而非靠作者 cancel())
- **出处**:全组普遍不变式;**Lavender `superEnable/superDisable` 做成 final**(子类结构上无法破坏订阅簿记)是最强形态;Meteor `subscribe(this)/unsubscribe(this)`。
- **落地**:给 `Chip` 加一个**非冻结**的订阅袋(如 `protected Trace.Subscription track(Subscription s)`),`Chip.setEnabled`(已 final,`Chip.java:79`)
  在 disable 时自动 `cancel()` 袋里全部。Chip 作者只在 `onEnable` 里 `track(trace.subscribe(...))`,**忘不了、漏不了**。这是加法(不改 `onEnable/onDisable/setEnabled` 签名),
  直接消除 design doc 担心的订阅泄漏,比"信任每个 Chip 在 onDisable 里 cancel()"更安全。**当前 `Chip` 没有这个袋——这是最高价值的补强。**

###  T2 — `Trace.publish` 热路径去掉「每次排序+分配」:订阅时维护按类型的已排序缓存
- **出处**:Emperor `sortListValue()`(**注册时预排序**)· Lavender `populateListenerCache()`(扁平 `Map<Type,List>`)· Meteor orbit(类键控 map)。
- **落地**:`Trace.java:146-152` 目前每次 publish 都 `new ArrayList` 收集 + `matching.sort(BY_PRIORITY)`。改为在 `subscribe/unsubscribe`(冷路径)时维护一个
  `Map<Class<? extends Signal>, List<Subscription0>>` 已排序缓存,`publish` 直接迭代。**保留** `isInstance` 子类型 fan-out(可在缓存构建时把 supertype 展开进各具体类型桶,
  或退一步按已注册类型分组)。纯内部优化,`Trace` 公共签名不变——冻结契约安全。

###  T3 — 崩溃安全持久化 + 自描述信封:给 Board 补一个持久化层(目前完全没有)
- **出处**:Meteor `System.save/load`( 临时文件 + `ATOMIC_MOVE` + **坏文件改名 `<name>-<timestamp>.backup.nbt`** + **reset-before-load**)· Southside(`*.corrupted` 重载默认)·
  Example `BaseFile` / Southside `{metadata:{version,create,modify},data}` **自描述信封** · Faiths `AbstractValue.toYML/fromYML` **每类型自序列化**。
- **落地**:Board 现无配置持久化。新增一个**非冻结**持久化 seam:每个 value 类型自己 (de)serialize(不要 Emperor/HackSoar 的双份 instanceof 阶梯)、
  按**稳定 ID** 键(不要显示名——Emperor/Lavender 的坑)、原子写 + 坏文件备份、metadata 带版本。**在 Board 内重实现,不从 `client/` 映射 import**(1.8.9 NbtIo 或直接 JSON 皆可,建议零依赖 JSON)。

### T4 — 单例/池化热 Signal + `Trace.hasSubscribers()`:消除每 tick 的 GC 抖动
- **出处**:Meteor `TickEvent.Pre/Post` 单例 `get()` · orbit `isListening(Class)`。
- **落地**:`TickSignal.java:33` 每 tick `new TickSignal()`——热路径 GC 抖动。给 `TickSignal`(非冻结的 `signals/` 子类)加一个可复用的 END-phase 单例(其不可变、不可取消,复用安全);
  给 `Trace` 加**非冻结**的 `hasSubscribers(Class)` 让发射点在无人订阅时跳过构造 Signal。加法,不改基类。

### T5 — Baritone api/impl 接口-only 纪律 + `IBaritoneProcess` 优先级仲裁:给 `Port`/`Backplane` 加纪律,给「谁开车」加仲裁器
- **出处**:Baritone `baritone.api`(接口-only 公共面,禁碰 impl)· `IBaritoneProcess.isActive()/priority()/isTemporary()` + `PathingControlManager`。
- **落地**:两件加法。(1) 正式化"跨子系统只穿 JDK 类型 + 微接口"——`Backplane`/`McpLink`/`BoardPort` 已是对的直觉,**加一条测试**:若跨子系统签名引用具体 impl 类就 fail。
  (2) 当 LLM(mcp-core)与某 `Chip` 都想操控玩家时,建一个**非冻结**的 controller 仲裁器(报 `isActive()`+`priority()`,支持 `isTemporary()` 安全暂停不永久驱逐 Chip)——比临时互斥干净且可测。

### T6 — 一模型多消费者:设置/`Value` 对象既自渲染又自序列化,GUI 与 mcp-core 读同一份
- **出处**:Lavender `Value↔ValueComponent` 每类型一视图 · Emperor 可插拔多 ClickGUI 绑同一 `Module/Value` · Meteor `Settings` 树自渲染+自序列化 · HackSoar Setting 自注册带 parent 反指针。
- **落地**:Board 的 `Panel`/`HudMatrix`(非冻结上层)应把 `Chip` 的设置建模为一等 `Value` 对象:声明字段即自动进 GUI + 持久化 + 复位;`Panel` 是薄视图。
  **关键**:同一批 `Value` 对象既给人类 GUI 编辑、也经反射 `Port` 给 LLM 读/改——这是 mcp-core 驱动配置的天然桥(Example `ModuleServer.collectModulesData` 的类型化 JSON 描述符即此思路,但要在我们的安全门后做)。

### T7(mcp-core 专项)— `ModuleServer` 是 mcp-core 的更简单同类:借它的状态投影,把它的安全缺陷当反例
- **出处**:Example `mod/server/ModuleServer.java`(内嵌 HTTP 8080 + WS 8081,`collectModulesData` 序列化每模块类型化描述符,`broadcastModulesStatus` 推 delta)。
- **落地**:**架构上和 mcp-core 是同一个动作**(socket 暴露活体游戏给进程外控制器),直接研究其形状。但它是**安全反例**:`0.0.0.0:8080/8081` 未认证、`Allow-Origin:*`、
  "close" 动作 `System.exit(0)`——**正是 NT-Executive 7 层权限模型要防的爆炸半径**。借其**状态投影 + 变更广播**思想,套进我们的分层门控;所有 mutate 走 `IoManager.supervise()`(见架构 doc 01)。

### T8 — 取消带原因串,便于 LLM 追问「为什么被抑制」
- **出处**:Example `Event.Cancellable.cancel(String reason)` + `cancelReason` · Forge `Event.Result{DENY,DEFAULT,ALLOW}` 三态。
- **落地**:`Signal.Cancellable`(冻结)现只有布尔 `cancel()`。**不改基类**,但新 Cancellable 子类可**加**一个 `reason` 字段(加法子类完全允许)。当 mcp-core 观察到某动作被 Chip 取消,能把原因回给 LLM——比裸布尔可调试。Forge 三态当前二态够用,暂不需要。

---

## 明确不学(避免踩别人的坑)

- **外部事件/配置库**:MBassador(Example)、me.bush/kbrewster(Southside)、OneConfig(Southside)——违反零外部依赖铁律。手搓 bus ~130 行即可,`Trace` 是正确的自建。
- **双/三 bus 共存**(Southside 三个 bus)——保持单一 `Trace`;mcp-core 要事件经反射 `Port` 桥,别引第二 bus。
- **神对象 + 手写超长注册列表**(HackSoar 140 行、Southside 90 行、`Client.INSTANCE` 25 getter)——`Backplane` + `Matrix` 已是更干净的答案。
- **精确类派发**(orbit/Emperor/Faiths 不走父类)——Board 的 `isInstance` fan-out 已更强,别退化。
- **反射 `Method.invoke` 派发 + 静默吞异常**(HackSoar/Emperor/Faiths)——`Trace` 已用类型化 listener + log 到 stderr,保持。
- **config 按显示名键 + 无版本**(Emperor/Southside)、**setValue 全量写放大**(Faiths)——用稳定 ID + 版本 + 边界/防抖存盘。
- **源码内联编辑 vanilla / Skija native 栈 / Fabric 入口点 SPI / reflections 依赖排序**——与我们 `-javaagent` weave + LWJGL3/JDK25 + 零依赖不符。
- [porting-backlog.md](porting-backlog.md) — 1.8.9→现代运行时移植 bug backlog(compat 补丁候选;三源研究合并 + corpus 陷阱)
