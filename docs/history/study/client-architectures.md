---
doc: study-client-architectures
title: 客户端架构研究 — 逐项目细节
layer: reference
status: reference
updated: 2026-07-11
parent: README.md
read_if: 你想看某个参考客户端(Example/Southside/Faiths/HackSoar/Lavender/Emperor/Meteor/Baritone/Fabric/Forge)具体是怎么设计 event/module/gui/config/injection 的 —— 保留了具体类名和亮点。横向对比与"我们该学什么"去 README.md。
---

# 客户端架构研究 — 逐项目细节

> 十项研究:6 个本地 MC 客户端(Example-master、Southside-main、Faiths-Recode、
> HackSoar-main、Lavender-rise、Emperor-reborn)+ 工业级项目(Meteor Client、Baritone、
> Fabric API、Forge/NeoForge)。每节保留**具体类名**与**该项目独有的亮点**。
> 版本适配性已逐条标注:**1.8.9-applicable**(具体类名可直接参考,我们的 `client/` 是 vanilla 映射)
> vs **modern-only**(1.16+/Fabric,只有结构可借,vanilla 名字不通)。

> 我们的坐标系(用来对照):Board 的框架契约是 `Trace`(事件总线)/`Signal`(事件基类,
> 带 `Cancellable` 的 PRE/POST `State`)/`Clock`(优先级枚举)/`Chip`(功能单元)/
> `Matrix`(功能管理器)/`Backplane`(服务注册表)/`McpLink`+`BoardPort`(反射桥)。
> 其中 `Trace/Signal/Clock/Chip/Matrix/Backplane` 是**冻结契约**(design doc 06 §7,
> 只能加子类、不能改签名);`Panel`/`HudMatrix`/`Pin`/`link` 是非冻结的上层 seam。

---

## 目录(按事件总线机制从"我们的血亲"到"工业级"排序)

| # | 项目 | MC 版本 | 与我们的相关度 |
|---|---|---|---|
| 1 | Lavender-rise | 1.8.9 | 最高 — 自建 bus 的性能标杆(`@EventLink` 字段 lambda + listenerCache) |
| 2 | Faiths-Recode | 1.8.9 | 高 — `isAccessible()` 门 + 字段 `Handler<T>` + 全自动发现 |
| 3 | HackSoar | 1.8.9 | 高 — 反射注解 bus + SpongeMixin(证明 1.8.9 能跑 Mixin) |
| 4 | Emperor-reborn | 1.8.9 | 高 — 反射注解 bus + 源码内嵌注入 + 设置依赖谓词 |
| 5 | Southside | 1.12.2 | 中 — 双 bus 反面教材 + OneConfig 声明式配置 |
| 6 | Example-master | 1.21.4 | 中 — MBassador 外部 bus + ModuleServer 远程控制面(mcp-core 的镜像) |
| 7 | Meteor Client | 1.21.x | 高(结构)— orbit bus 的 LambdaMetafactory 零反射派发 + 崩溃安全持久化 |
| 8 | Baritone | 1.12→1.20 | 高(结构)— api/impl 纪律 + `IBaritoneProcess` 优先级仲裁 |
| 9 | Fabric API | 1.20+ | 参考 — `EventFactory.createArrayBacked` invoker 工厂 |
| 10 | Forge / NeoForge | 1.12-1.20 | 参考 — `IEventBus` + 事件继承 + `Event.Result` |
| 11 | 现代化项目 | 1.7-1.12→LWJGL3 | 侧证 — lwjgl3ify / legacy-lwjgl3 佐证我们的 shim 路线 |

> **版本适配图例**: **1.8.9-applicable**(具体类名/seam 可直接照抄,我们的 `client/` 是 vanilla 映射);
>  **结构可借**(1.12/Fabric/现代,只借设计,vanilla 名字不通)。

---

## 1. Lavender-rise  — 自建事件总线的性能标杆

> `com.alan.clients`("Lavender"/"Rise",作者 Tecnio/Patrick/Hazsi)。MC **1.8.9**,LWJGL2,
> 源码内嵌全套 deobf `net.minecraft.**`。~2725 java 文件(含全 vanilla)。无 Forge/Fabric loader。

**事件系统(本组最强,直接对标 Trace):** `com.alan.clients.newevent.bus.impl.EventBus`。
核心接口:`Event`(marker)/ `CancellableEvent`(`setCancelled`)/ `Listener<T>`(`@FunctionalInterface`,单 `call(T)`)/ `Bus<T>`。
**关键设计:listener 是"字段"不是"方法"** —— 订阅者声明
`@EventLink(Priorities.LOW) public final Listener<Render2DEvent> onRender = event -> {...};`。
`register(subscriber)` 反射扫字段,遇 `@EventLink` 则:① 从字段泛型 `ParameterizedType` 取事件类型;
② 用 `MethodHandles.Lookup.unreflectGetter(field).invokeWithArguments(subscriber)` **一次性**取出 lambda;
③ 包成 `CallSite{owner, listener, priority}` 按优先级降序存。**register/unregister(冷路径)后重建**一张
扁平 `listenerCache: Map<Type,List<Listener>>`(剥掉 owner),于是 `handle(event)`(热路径,每帧/每包)
只是 `getOrDefault(event.getClass()).` 上的**索引 while 循环**,零反射、零分配、优先级只在注册时排一次。
`Priorities` 是 byte(VERY_LOW=0..VERY_HIGH=4)。取消是协作式:调用点在 `handle()` 返回后查 `isCancelled()`。
派发器还有一个 **world-null 硬门**:`mc.theWorld==null` 时丢弃除 ServerKick/Game/WorldChange/ServerJoin 外的一切事件。
~50 个事件在 `newevent/impl/{input,inventory,motion,other,packet,render}`。

**模块系统:** `module.Module`(abstract,implements `InstanceAccess`),`@ModuleInfo(name, description, category, keyBind, autoEnabled, allowDisable, hidden)` 注解在类上,构造器反射读它、缺失即抛。
**关键:`superEnable()` 里 `eventBus.register(this)`,`superDisable()` 里 unregister —— 模块只在启用时收事件**,
且这两个方法是 **final** 的(子类改不了订阅簿记),`onEnable/onDisable` 才是可覆写钩子。启用还会级联注册
活跃的 `ModeValue` 子模式和挂了 Mode 的 `BooleanValue`。另有平行的 `Component`(常驻、不可 toggle 的 listener,
如 `S35Component`/`PacketLogComponent`)与用户可见的 `Module` 分开——**两层功能模型**。

**管理器:** enum 单例 `Client.INSTANCE` 持 ~25 个 manager 引用。`initRise()` 先按依赖序 new 全部 manager,
再做**一次** classpath 扫描 `ReflectionUtil.getClassesInPackage("com.alan.clients.")`,按可赋值性把
Component/Module/Command 实例化进对应 manager(`@Hidden` 跳过)。`ModuleManager`/`CommandManager` 直接 `extends ArrayList<T>`。
`InstanceAccess` 接口给实现者免费带上 `mc`/`instance` 常量 + `getModule(Class)` 等默认方法(环境注入)。

**GUI:** ClickGUI 是 vanilla `GuiScreen` 子类(`ui.click.standard.RiseClickGUI`),Screen+Component 组合;
**每个设置 `Value` 类型有一个对应的 `ValueComponent` 渲染器**(BooleanValueComponent/NumberValueComponent/...)——
干净的"每类型一视图"映射。重渲染栈:`RiseShaders`(outline/gaussian blur/bloom)、自定义 Font/FontManager、Animation/Easing。

**配置:** GSON JSON 单文件 `config/latest.json`。`util.file.File` 抽象基类 + `ConfigFile`。写:遍历 module → 每个
`Value` 按 **instanceof 阶梯**序列化,key 为 `name + "*" + index`(index 消歧重名——**这是个坑**,设置顺序变了就错位)。
读镜像,每 value 各自 try/catch(一坏不毁全局),`has()` 容缺失键。**设置自注册**:`Value<T>` 构造器收 parent Module,
`parent.getValues().add(this)`,声明字段即自动持久化+渲染+复位。

**注入: 直接源码补丁**——因内嵌全 deobf 源码,直接编辑 vanilla 方法插 `Client.INSTANCE.getEventBus().handle(new XEvent(...))`。
~22 个 vanilla 类含内联调用(Minecraft/EntityRenderer/EntityPlayerSP/NetworkManager/GuiScreen…)。启动经
`com.alan.clients.Loader` 调 `initRise()`;`DeveloperReload` 命令可热重跑。**这是把 client 焊死在一个补丁过的 1.8.9 jar 上的极端耦合——对我们是反面教材,只借 hook 的位置不借机制。**

**标志性想法:** ① `@EventLink` 字段 lambda + 预算 `listenerCache` 的零反射派发(全组最快);
② `superEnable/superDisable` final 化,子类结构上无法破坏订阅生命周期;③ 一次 classpath 扫描按父类型自动注册(零手工列表);
④ Value 自注册 + Value↔Component↔序列化三点对称;⑤ 派发器集中的 world-null 门。

## 2. Faiths-Recode  — `isAccessible()` 门 + 字段 `Handler<T>` + 全自动发现

> MC **1.8.9**(1.8.9 的 `C0X` 封包命名:`C00PacketKeepAlive`/`C03PacketPlayer`;LWJGL2 `org.lwjgl.input.Keyboard`;
> 内嵌 ViaVersion/ViaLoadingBase 可连 1.12.2 服)。~2492 java 文件,其中 ~1614 是 deobf vanilla,真正 client 在 `dev.faiths.*`。
> LiquidBounce-b73 血统。

**事件系统:** 字段反射 bus(`dev.faiths.event`)。核心:`Event`(基类)/`CancelableEvent`(加 `isCancelled/setCancelled`)/
`Handler<T extends Event>`(`@FunctionalInterface`,单 `invoke(T)`)/`EventHook<T>`(配 `Listener`+`Handler`)/
`Listener`(接口,单方法 `boolean isAccessible()`)/`EventManager`。`EventManager` 持
`Map<Class<? extends Event>, List<EventHook<Event>>>`。**注册靠反射**:`register(Listener)` 扫
`getDeclaredFields()`,任何值为 `Handler` 的字段即注册,事件类型从字段的
`((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]` 还原。于是模块只声明一句
`public final Handler<MotionEvent> h = event -> {...}` 就自动订阅。派发 `call(Event)` 按 `event.getClass()`
**精确类匹配**(不走继承链)查 handler,`.filter(hook.getListener().isAccessible())` 后按注册序 invoke,异常 `printStackTrace` 吞掉。
取消协作式:调用点在 `call()` 后查 `isCancelled()`。~40 个事件(`MotionEvent` 带 PRE/POST `EventState`,`PacketEvent` 带
SEND/RECEIVE `Type` + `Packet` + `INetHandler`,`Render2D/Render3DEvent`、`UpdateEvent`、`SlowDownEvent` 等)。

**关键设计——`isAccessible()` 门:** `CheatModule implements Listener, IMinecraft`,其 `isAccessible()` **返回 `state`**。
于是所有 handler **开机时注册一次**,启用/停用只是翻 `state` 布尔,派发时靠 `hook.getListener().isAccessible()` 过滤——
**没有 subscribe/unsubscribe 的 map 抖动、没有并发修改风险、也没有每个 handler 写 `if(!enabled)`**。代价是常驻一个极廉价的过滤判断。

**模块系统:** `CheatModule`(name、`Category` 枚举带 displayName、`keyBind` int、state、GUI 动画 float)。
`setState()` 调 `onEnable/onDisable` 并 `NotificationManager.pop()` 弹 toast。**全自动发现**:`Faiths.init()` 用
Guava `ClassPath.from(loader).getTopLevelClasses()` 过滤 `startsWith("dev.faiths")` + `CheatModule.isAssignableFrom`,
`newInstance()` 后排序注册,~81 个模块。设置也反射发现:`getValues()` 懒扫字段找 `AbstractValue`。

**管理器:** 神对象 + 静态单例(`dev.faiths.Faiths` 静态字段 `moduleManager/configManager/commandManager/notificationManager/eventManager`)。
无统一生命周期接口、无 DI,一切经 `Faiths.*` 静态互相触达。`ModuleManager` 自己是 `Listener`,持 `Handler<KeyEvent>` 按 keyBind 切模块。

**GUI:** 全自定义即时模式,无 vanilla widget 复用。`ui/{clickgui,altmanager,menu,notifiction,music,font}`。
自建 TrueType 字体渲染器(`FontManager.sf`/`TrueTypeFontDrawer`)。`NotificationManager` 是个漂亮的自包含动画子系统:
`CopyOnWriteArrayList<Notification>`、**FPS 归一化滑入动画**(`2000/Minecraft.getDebugFPS()` 缩放位移)、按消息去重(重置计时而非堆叠)。

**配置:** snakeyaml(设置)+ Gson(部分)。`AbstractConfig(File)` 抽象 load/save;`ConfigManager` 固定集
(`ModuleConfig=modules.yml`/`AccountsConfig=accounts.yml`/`MusicConfig=music.yml`)+ `configs/` 目录存命名 profile,
建目录树 `mcDataDir/Faiths/{configs,cover,music}`。**多态自序列化**:`AbstractValue<T>` 声明抽象 `toYML()/fromYML(String)`,
每个子类型(`ValueBoolean/Float/Int/Color/Mode/MultiBoolean`)各自实现——config 引擎从不 switch 类型。keybind 存**人类名**
(`Keyboard.getKeyName`)非 raw code。**坑**:`AbstractValue.setValue()` 每次改动都 `saveConfig(modulesConfig)`——写放大。

**注入: 直接源码补丁**——内嵌全 deobf MC 1.8.9,手改 vanilla 方法内联 `Faiths.eventManager.call(new XxxEvent(...))`。
已核实 hook 位置:`EntityPlayerSP`(6 处:UpdateEvent/MotionEvent PRE-POST/PushOutOfBlockEvent/MoveEvent/SlowDownEvent)、
`Minecraft`(6)、`NetworkManager`(`dispatchPacket` 前后 SEND/RECEIVE)、`PlayerControllerMP`、`EntityRenderer`、`GuiIngame`、
`World`、`Block`、`Entity`、`EntityLivingBase`。设置用 `visible(Valider)` 谓词做条件显隐(`fireDelay.visible(autoFire::getValue)`)。
启动是普通 `Start.java` 调 `Main.main(...)` + DevAuth 离线认证,无 tweaker/coremod。

**标志性想法:** ① `Listener.isAccessible()` 通用门——启用=开机注册一次、翻布尔,派发时过滤,永远忘不了 enabled 检查;
② 字段泛型签名还原事件类型的零样板订阅;③ Guava classpath 扫描 + 字段扫描全自动发现模块与设置(加功能只碰一个类);
④ 每 Value 多态 `toYML/fromYML`;⑤ `visible(Valider)` 条件设置;⑥ `NotificationManager` FPS 归一化动画 toast。

**给我们的教训:** 精确类派发(不走父类)是坑——若 Trace 想要事件继承(Board 的 `Trace` **已经用
`s.type.isInstance(signal)` 支持子类型 fan-out**,比 Faiths 强),注册时就要把 supertype 建进索引。Guava classpath 扫描慢且
classloader 脆——JDK25 上宁可编译期生成注册表或 ServiceLoader。**绝不学**它每次 setValue 全量 dump 配置(写放大),也别学静默吞异常。

## 3. HackSoar  — 反射注解 bus + SpongeMixin(证明 1.8.9 能跑 Mixin)

> MC **1.8.9**(`build.gradle version="1.8.9"`),LaunchWrapper + SpongePowered Mixin(`mixins.soar.json`,
> compatibilityLevel JAVA_8,obf context `notch`)。与我们同代同注入时代,模式直接可迁。`me.eldodebug.soar`。

**事件系统:** 注解反射 bus(`me.eldodebug.soar.management.event`)。`EventManager` 持
`Map<Class<?>, ArrayHelper<Data>> REGISTRY_MAP`(按事件类)。`register(Object)` 扫 `getDeclaredMethods()` 找
`@EventTarget`(RUNTIME、单参、`value()` 是优先级 byte,0 FIRST..4 FIFTH,默认 2),包成 `Data{source, Method, byte priority}`。
`Priority.VALUE_ARRAY` 驱动 `sortListValue()` 使 listener 按优先级排序。派发:每个事件 `extends Event`,调用点
`new EventX(...).call()`;`Event.call()` 静态触达 `Soar.getInstance().getEventManager()` 查表 `Method.invoke()` 每个 listener。
取消靠基类 `boolean cancelled` + `setCancelled/isCancelled`;有的事件带**可变 payload**(`EventZoomFov.setFov`、
`EventLocationCape.setCape`)让 listener 改值而非仅取消。`ArrayHelper<T>` 是手搓 copy-on-write 数组(add/remove 都新分配)——
作者为了派发中迭代安全避开 ArrayList,但 O(n) 每次增删,笨重。

**模块系统:** 功能单元 = `Mod`(`management.mods.Mod`),~140 个子类。`Mod` 持 name/description 为 `TranslateText`(i18n key)、
category、toggled/hide、`SimpleAnimation`。生命周期:`toggle()/setToggled()` 翻状态并调 `onEnable/onDisable`,后者**正是
`eventManager.register(this)/unregister(this)`**——启用=订阅,停用模块零派发成本。设置自注册:每个 `Setting` 构造器调
`Soar.getInstance().getModManager().addSettings(this)` 并存 parent `Mod` 反指针——声明字段即接进全局注册表 + 持久化层。
`HUDMod` 加 x/y/w/h/scale/draggable。

**管理器:** 神对象 `Soar`(单例 `static Soar instance = new Soar()`)持 ~19 个 manager 字段,`start()` 按硬编码依赖序 new。
`ModManager.init()` 有 140 行 `add(new XxxMod())`。无扫描/DI,全经 `Soar.getInstance().getXxxManager()`。

**GUI:** 两套。① NanoVG 菜单:`GuiModMenu extends GuiScreen` 但经 `NanoVGManager` 绘制(drawRoundedRect/drawShadow/drawSvg/
drawPlayerHead/scissor/blur),由 `Category` 对象(Home/Module/Cosmetics/Setting…)组成,轻量组件树 `ui/comp`。② HUD:`HUDMod` 可拖拽,
`GuiEditHUD` 编辑。还有替换 vanilla 主菜单的 `GuiSoarMainMenu` 场景系统、甚至 MCEF 内嵌 Chromium 浏览器。

**配置:** profile 化 JSON(Gson)。`ProfileManager` 存 `JsonObject` 树:"Profile Data"/"Appearance"(accent/theme/bg/lang)/"Mods"。
每 Mod 写 Toggle(+HUD 几何),设置值**按 setting 的 i18n key 命名**、**instanceof 阶梯**序列化(Color→RGB int、Combo→option key、
Number→double、Keybind→keyCode…)。多 profile 分文件,`Default.json` 自动加载,**profile 记 server-IP 子串,join 匹配服自动切换**
(`SoarHandler.onJoinServer`)。关机存(`Soar.stop -> profileManager.save()`)。`JsonUtils` 空安全 getXxxProperty(...,default)。

**注入:** LaunchWrapper `ITweaker`(`SoarTweaker`)+ SpongeMixin。`SoarTweaker.injectIntoClassLoader` 注册 raw ASM
`LwjglTransformer`、`MixinBootstrap.init()`、`Mixins.addConfiguration("mixins.soar.json")`,并**反射把 `"org.lwjgl."`
从 `LaunchClassLoader.classLoaderExceptions` 移除**以便 transform LWJGL(shim 需要的正是这条 seam)。mixin 在
`injection/mixin/mixins/{client,entity,gui,block,chunk}`,配 `@Accessor/@Invoker` accessor 接口暴露私有字段。
vanilla→bus 全在 mixin 里:`MixinMinecraft` `@Inject/@Redirect/@Overwrite` 触发 `new EventTick().call()`、
`@Redirect` `Mouse.next()` 造 `EventClickMouse` 并在取消时重 poll/清零、`startGame` 注入 `Soar.getInstance().start()`。

**标志性想法:** ① 启用=订阅;② Setting 自注册带 parent 反指针,声明字段即接进注册表+持久化;
③ **i18n key 一键三用**——显示名 + JSON 持久化 key + 搜索 token(改显示文本不破坏存档);④ mixin 纯事件适配器
(vanilla 只用来发事件或读 cancel 标志,逻辑全在 listener);⑤ payload 可变可取消事件(`setFov`/`setCape` 把 `@Redirect` 变可扩展 hook);
⑥ profile 按 server-IP 自动切换;⑦ `@EventTarget(byte)` + `VALUE_ARRAY` 声明式优先级、每次注册重排。

**给我们的教训:** 启用=订阅是这里最有价值的模式(Board 的 `Chip.onEnable/onDisable` 应做成 `Trace` 订阅边界)。
但**避开** `Soar` 神单例 + 140 行手写 add + `Event.call()` 从派发内部反向触达 `Soar.getInstance()`(隐藏全局依赖,bus 无法单测);
**避开**反射 `Method.invoke` 派发 + 手搓 `ArrayHelper`(热路径反射成本,用 `CopyOnWriteArrayList`——Board 的 `Trace` 已用);
**避开** `ProfileManager` 那份 save/load 双份 instanceof 阶梯(让每个 setting 类型自己 toJson/fromJson)。

## 4. Emperor-reborn  — 反射注解 bus + 源码内嵌注入 + 设置依赖谓词

> MC **1.8.9**(LWJGL2,内嵌 deobf vanilla,带 `com.diaoling.client.viaversion` 多协议)。client 在 `dev.emperor.*`。无 Forge/Fabric、无 mixin。

**事件系统:** 反射注解 bus。静态 `dev.emperor.event.EventManager` 持
`Map<Class<? extends Event>, List<MethodData>>`(HashMap of `CopyOnWriteArrayList`)。`register(obj)` 扫单参
`@EventTarget`(byte value 默认 2/MEDIUM)方法,`setAccessible(true)`,包成 `MethodData`。派发 `call(Event)` 按精确类
`Method.invoke()` 按优先级序。优先级 byte 阶梯(`Priority`:HIGHEST=0..LOWEST=4,VERYVERYLOW=100),**注册时经
`sortListValue()` 预排序**(派发不排序)。**两种取消**:`EventStoppable`(基类,`stop()/isStopped()`,`call()` 遇 stop 提早 break)
与 `Cancellable` 接口(`setCancelled`,由生产者查)。`EventTyped/EventType`(PRE=0/ON=1/POST=2/SEND/RECIEVE)让一个事件类带 phase byte。
~50 事件按域分包(attack/rendering/world/misc)。派发按精确运行时类(无父类/接口 fan-out)。

**模块系统:** `dev.emperor.module.Module`(name、`Category`、state、key、defaultOn、内建 GUI 动画字段)。
`toggle()/setState(bool)` 翻状态、播 `random.click`、弹 `NotificationManager` toast,并 **`EventManager.register(this)`/unregister**——
停用模块零事件。`setStateSilent()` 跳过 onEnable/onDisable。设置声明为 `public Value<?>` 字段
(`BoolValue/NumberValue/ModeValue<Enum>/ColorValue/TextValue`)。**`ModuleManager.addModule()` 反射扫字段自动收集 Value**——
声明字段即进 GUI + config。~90 模块 `init()` 里手写逐个 `addModule()`,加载后按字母排序。

**管理器:** 神对象 `dev.emperor.Client`(静态单例 `Client.instance`、静态 `mc`)持全部 manager 为 public 字段。
`init()` 固定序构造。`ModuleManager` 是事件订阅者(`@EventTarget onKey` 按 key 切模块;`on2DRender` 懒启第一帧 defaultOn 模块)。
跨切面 "component"(RotationComponent/FallDistanceComponent/PingSpoofComponent/BadPacketsComponent)开机直接注册 bus,独立于模块列表。

**GUI:** **多套可互换 ClickGUI 作为 mode**:`gui/clickgui/book/NewClickGui`(+RippleAnimation)、`gui/clickgui/drop/DropdownClickGUI`、
`gui/clickgui/express/NormalClickGUI`——全绑同一 `Module/Value` 模型。`ClickGui` 模块门控开启。HUD 本身是模块(`render/HUD` 渲染
`EventRender2D`)。`UiManager` + `gui/notification/NotificationManager`(SUCCESS/DISABLE toast)、自定义主菜单 + shader 背景。

**配置:** Gson JSON。抽象 `dev.emperor.config.Config { name; abstract JsonObject saveConfig(); abstract void loadConfig(JsonObject); }`。
`ConfigManager` 持 `List<Config>`,写 `<mcDataDir>/Emperor/<name>.json`。`ModuleConfig` 遍历模块 → `{state, key, values{...}}`,
每 Value **instanceof 派发**(Number/Bool/Mode→enum name/Color→RGB int)。**config 按显示名 key**(改名即丢存档,脆)。无版本/迁移。

**注入: 直接源码补丁** ~23 处 vanilla 手插 `EventManager.call(new EventXxx(...))`:`Minecraft`(runTick/key/mouse/loadWorld)、
`NetworkManager`(sendPacket 前后)、`KeyBinding`、`EntityPlayerSP/EntityLivingBase`、`EntityRenderer/RenderPlayer`、`GuiIngame/GuiScreen`、
`MovementInputFromOptions`、`PlayerControllerMP`、`NetHandlerPlayClient`。用 `sun.misc.Unsafe` + 自定义 LWJGL2 display shim。

**标志性想法:** ① 模块 setState 自订阅;② `ModuleManager.addModule()` 反射收集 `Value<?>` 字段;
③ **优先级注册时预排序**,派发只直迭 `CopyOnWriteArrayList`(热路径不排序);④ `Value<V>` 带函数式 `Dependency`
(`isAvailable()/check()`)——一个设置按另一设置状态条件显隐;⑤ `EventStoppable`(bus 短路)vs `Cancellable`(数据标志)
vs `EventTyped` phase byte 三种正交取消/相位契约;⑥ 可插拔多 ClickGUI 前端绑同一 `Module/Value` 抽象。

**给我们的教训:** 采纳"注册时预排序优先级"(Emperor 的 `sortListValue`)——**Board 的 `Trace.publish` 目前每次发布都
`matching.sort(BY_PRIORITY)`(`Trace.java:152`),是可优化点**:可在订阅/取消(冷路径)时维护按类型的已排序缓存。
反射 `Method.invoke` + 静默吞 `IllegalAccess/InvocationTarget` 是弱点(藏 bug)——Board 用类型化 listener,且**绝不静默吞异常**
(`Trace` 已 log 到 stderr)。config 按显示名 key 脆——用稳定 ID + 版本字段。可插拔 GUI 前端绑一份数据模型验证了保持
Board 功能/设置模型 UI 无关(mcp-core 也要读同一模型,一模型多消费者)。

## 5. Southside  — 双 bus 反面教材 + OneConfig 声明式配置

> MC **1.12.2**(README 自称 "hacked 1.12.2 client";1.12-era `SPacket*/CPacket*`、`EnumHand`、`RayTraceResult`)。
> 经 `lwjgl3-compatibility-layer`(`org.lwjglx.*` shim)跑 LWJGL3 + JDK17。`dev.diona.southside`。封包/实体类名与 1.8.9 不同,**具体类名不通,只借结构**。

**事件系统:** 注解反射 bus,**且两 bus 共存**(反面教材)。client 自己的 bus 是 `me.bush.eventbus`(dep
`com.github.PasteIndustrial:eventbus:1.0.3`),暴露为静态 `Southside.eventBus`。事件 `extends me.bush.eventbus.event.Event`,
覆写 `protected boolean isCancellable()` 选择性开启取消(`AttackEvent`/`PacketEvent` 返回 true)。listener 方法注解
`@EventListener(priority = ListenerPriority.HIGH/LOWEST)`,98 个 listener。对象级订阅 `subscribe(this)/unsubscribe(this)`。
**第二个 bus** 是 OneConfig 的 `EventManager.INSTANCE`(从 `Minecraft.java` 发 RenderEvent/FramebufferRenderEvent/PreShutdownEvent)——
kbrewster 式(`me.kbrewster.eventbus.EventBus` 带可插拔 `InvokerType`,LMF 派发)。**值得偷的细节**:`EventState{PRE,POST}` 枚举
传进**一个**事件构造器(`RenderManager` 发同一 `Bloom2DEvent` 两次 PRE 后 POST),而非定义两个事件类——相位事件类数减半。

**模块系统:** `Module extends cc.polyfrost.oneconfig.config.Config implements BaseModule`——**模块即 OneConfig 配置对象**。
设置直接是 body 里 `public final` 的 OneConfig option 字段(`Slider/Switch/Dropdown/Color`,KillAura ~25 个)。`setEnableNoSave(true)`
调 `eventBus.subscribe(this)`,false 调 unsubscribe——停用零派发。类级注解:`@Binding(Keyboard.KEY_R)` 默认 keybind、`@DefaultEnabled` 自动开。
`onEnable()/onDisable()` 返回 boolean 可**否决** toggle。`NonToggleableModule` 是常驻 base(背景追踪器)。注册全手工。

**管理器:** 静态神对象。`Southside` 类持 ~10 个 manager public static 字段,`start()` 按依赖序手 new
(注释:"moduleManager depends on FontManager's loaded font sizes")。`ModuleManager.initialize()` ~90 行手写 `register(new XxxModule(...))`,
`register()` 内部反射收集字段的 `Value<?>`/OneConfig option。无扫描/DI。

**GUI:** NanoVG(单 vg context `nvgCreate(NVG_ANTIALIAS|NVG_STENCIL_STROKES)`,`Render2DEvent` LOWEST 驱动帧,stencil FBO +
KawaseBloom 后处理),自定义 glyph 字体(`GlyphFontManager/NvgFontRenderer`)。HUD 继承 OneConfig 的 `Hud`(ArrayListHud/TargetHud/…)。
**client 自己的 `PowerClickGui extends GuiScreen` 整个被注释掉**——出货配置 UI 用 OneConfig 的 `OneConfigGui`(`Module.openGui()` 委托 Config 基类)。

**配置:** 两层。① 每模块:OneConfig `Config.save()/load()` 反射注解字段 → 每 profile JSON(gson),**parse 失败改名 `*.corrupted`
重载默认**;每次 toggle 触发 save。② client 全局:`FileManager` 写 `<gameDir>/Southside/*.json`,用**元数据信封**
`{metadata:{version,create,modify}, data:{...}}`。Southside 自己的 `ConfigManager` 注释掉——profile 切换委托 OneConfig。

**注入: 无 mixin、无字节码变换**——把**整棵 deobf `net.minecraft` 源码 check 进 `src/main/java` 手改**:51 个 `net.minecraft`
文件直接 import `dev.diona.southside` 并内联发事件。`NetworkManager.channelRead0/sendPacket` 造 `PacketEvent` 后 `isCancelled()` 早返回。
启动是普通 `Start.java` 调 `Main.main(...)` + DevAuth,无 ForgeModLoader/tweaker/coremod。另有 phantomshield 反破解 / native 虚拟化注解层(与 hook 正交)。

**标志性想法:** ① 一个事件类带 `EventState{PRE,POST}` 字段服务两相位(而非两事件类);② 模块即其配置(`Module extends Config`,
字段反射自动收集+持久化);③ 声明式类注解 `@Binding`/`@DefaultEnabled`;④ 取消是类型级 `isCancellable()` 覆写 opt-in;
⑤ config 坏文件改名 `*.corrupted` 重载默认;⑥ `FileManager` `{metadata,data}` 信封自描述版本+时间戳。

**给我们的教训:** 采纳一个 Signal 带 phase 字段(Board 的 `TickSignal.Phase`、`Signal.Cancellable.State` 已如此)。
**避开双 bus**(kbrewster + me.bush + OneConfig 三个 bus 令人困惑——Board 保持单一 `Trace`,mcp-core 要事件就经反射 `Port` 桥,别引第二 bus);
**避开** `Module extends OneConfig Config` 这种重度第三方耦合(把功能系统焊死在一个 config/GUI 库)——Board 零硬依赖是更好选择。
**采纳**坏配置改名重载默认(容错持久化)与 `{metadata,data}` 信封(自描述版本)。

## 6. Example-master  — MBassador 外部 bus + ModuleServer 远程控制面(mcp-core 的镜像)

> MC **1.21.4**(Fabric Loom 1.9,Yarn 1.21.4+build.8,fabric-loader 0.16.10,JDK 21,Mixin JAVA_21)。
> split source sets(`src/main`=common,`src/client`=client-only)+ accesswidener。**1.21 render/entity-render-state
> API + Yarn 名——具体类/方法目标不通,只借结构。**`com.example` / `com.diaoling.schema`。

**事件系统:** 第三方 bus:`net.engio.mbassador`(MBassador)。全局单例 `com.example.Global`
(`MBassador<Object> EVENT_BUS = new MBassador<>(PublicationErrorHandler.getInstance())`)。listener 方法注解
`@net.engio.mbassy.listener.Handler`,支持优先级(`@Handler(priority = -1337)` 跑晚)。派发显式同步 `post(event).now()`。
事件层级手写:`com.example.event.Event` 抽象基类持 `State{ANY,PRE,POST}` 枚举;嵌套 `Event.Cancellable` 加 `canceled` +
**`cancelReason` + `cancel(String)`**;~40 个具体事件带可变 payload getter/setter(handler 可在游戏消费前改写 packet/输入/velocity)。
`GameTickEvent` 覆写 cancel 在 POST 抛异常(禁止 POST 取消)。`PublicationErrorHandler implements IPublicationErrorHandler`
格式化完整错误报告(时间/listener/handler/stacktrace),**一个抛异常的 handler 永不杀死 bus**。

**模块系统:** `AbstractModule extends NamedEntity implements GameAccessor + Configurable<ModuleConfig>`。**模块即自己的 listener**:
`setState(true)` 调 `EVENT_BUS.subscribe(this) + reset() + onEnable()`,false 调 `unsubscribe(this) + onDisable()`——停用零 bus 开销、
`@Handler` 方法只在启用时触发。模块是单例(`Singleton.getInstance`)。设置为类型化 `BasicValue<T>` 字段
(`com.example.value`:带 children + `ReentrantReadWriteLock`,`NumberValue` 带 min/max/increment,`ChoiceValue` 带嵌套 `Multi`,
`ObjectValue`、`ValueGroup`),模块覆写 `getValues()` 返回 `Set.of(...)`;`getConfig()` 从 `getValues()` 构 protobuf `ModuleConfig`。

**管理器:** 7 个单例 manager(`ModuleManager/CommandManager/FileManager/RotationManager/RenderStateManager/ScriptManager/TaskManager`)。
**统一契约靠两个小接口**:`Initializable {boolean init(); boolean destroy();}` 与 `Manageable<T> {add; remove; List<T> items();}`。
**注册显式非扫描**:`ModuleManager.items()` 返回硬编码 `Arrays.asList(ModuleX.getInstance(), ...)`,`init()` 入 `LinkedHashSet`(插入序稳定)。
生命周期由 `ClientHandler.init()/shutdown()`(经 `MinecraftClient` `<init>/stop` mixin 发 `GameActionEvent` 触发)集中编排,
**每阶段包在 MC `Profiler.swap(tag)` 里做游戏内 profiling**。`RotationManager` 持 `PriorityQueue<Rotation>`(优先级瞄准仲裁)。

**GUI:** 两套非常规栈。① 游戏内 overlay 经 **Skija**(Google Skia 绑定):`Skija` 把 MC 的 GL framebuffer
(`BackendRenderTarget.makeGL(fbo)`,`Surface.wrapBackendRenderTarget`)包成全 GPU 2D canvas,直接画在 MC 帧上,绕过 `DrawContext`;
`GLContextCacheManager` 存/恢复 GL 状态。② **远程 web GUI**:`ModuleServer` 跑内嵌 HTTP(8080,静态 gui/ 文件)+ Java-WebSocket(8081),
把模块状态流成 JSON 并收 toggle/bind/setValue/import/export 动作。没有传统游戏内 ClickGUI——配置从浏览器或命令驱动。

**配置: protobuf**(非 JSON/TOML)。`com.diaoling.schema` 定义 `ConfigSchema`(ClientConfig/ModuleConfig/SettingsConfig)、
`Datatypes`(tagged `Type` union:int/long/double/float/bool/string/bytes/enum-index/Color/collection)、`FileSchema`
(`BaseFile` 信封:type + createdTime/modifiedTime + metadata map + context bytes)。`ConfigUtils.toType(BasicValue)` 按运行时类型建
`Datatypes.Type`。`FileManager(Manageable<AbstractFile>)` 遍历 rootDir,parse 成 `BaseFile` 后按 `FileType` 分派到对应 message。
配置是**自描述二进制信封,按稳定 setting NAME 键控,容忍缺/多键**(`getOrDefault`)。

**注入:** Fabric Mixin + MixinExtras(`@ModifyExpressionValue`)。两个 mixin config(common 空 + ~35 client mixin)+ accesswidener。
模式:mixin `@Inject` 在精确 vanilla 调用点造事件、`post(evt).now()`、按 `CallbackInfo.cancel()` 兑现取消。
`ClientConnectionMixin` inject `channelRead0`(入站)+ 两个 `send()` 重载(出站)发 `PacketEvent`/`HigherPacketEvent`——全可取消封包拦截。
accessor mixin + duck-type `example$-` 前缀接口把 mod 状态挂到 vanilla 对象。**mixin 很薄,只把 vanilla 调用点翻译成 bus 事件。**

**标志性想法(与 mcp-core 直接相关):**  **`ModuleServer`——内嵌 HTTP(8080)+ WebSocket(8081)控制面,把活体 client 暴露给外部 UI**:
`collectModulesData()` 把每个模块序列化成类型化 JSON 描述符(name/category/enabled/keybind + 值类型/options/min/max/increment),
动作 getModules/toggleModule/bindKey/setValue/import/export 改活体游戏,`broadcastModulesStatus()` 推 delta。
**这架构上和 mcp-core 是同一个动作:把运行中的游戏经 socket 暴露给进程外控制器。** 其他:模块即 listener 生命周期;
`Singleton`(MethodHandles `computeIfAbsent` + `findConstructor` 统一 getInstance);protobuf 自描述信封;`PublicationErrorHandler`
全局弹性;Skija-on-framebuffer;`Initializable`+`Manageable` 两个正交小接口;`RotationManager` 优先级仲裁。

**给我们的教训(mcp-core 重点):** `ModuleServer` 是你在造的东西的**更简单同类,但要注意其缺陷**——它在固定端口 `0.0.0.0:8080/8081`
跑**未认证** HTTP+WS,`Access-Control-Allow-Origin:*`,一个 "close" 动作直接 `System.exit(0)`。**那正是你的 NT-Executive
分层权限模型要防的爆炸半径**——把它当安全边界的**反例**,同时借它的状态投影思想(`collectModulesData` → 类型化 JSON 描述符 + 变更广播推送)。
`Initializable`+`Manageable` 是 Board `Matrix` 的轻量契约模板(但它硬编码 `items()` 列表,规模大了会烂)。protobuf 自描述信封的
**前向兼容纪律**(按稳定 name 键、容缺/多键、metadata 版本)值得学,但 protobuf vs 简单格式是权衡。**不适用**:Skija 栈(重量级 native)、
luaj ScriptManager(空 stub)、全部 1.21 vanilla 目标。也别学它半吊子 manager(`FileManager.init()` 成功路径返回 false、
`TaskManager` 的 "queue" 其实在 `submit()` 里同步跑)。

## 7. Meteor Client (结构) — orbit bus 的 LambdaMetafactory 零反射派发 + 崩溃安全持久化

> Fabric,追最新 MC 1.21.x(Yarn 映射,Java 21+,record/switch-pattern)。**版本无关的是模式,MC hook 是 1.20+ 形状。**
> 事件 bus 是独立库 `meteordevelopment.orbit`(dep `meteordevelopment:orbit:0.2.3`)——本组**最直接对标 Board 的一块**。

**事件系统(orbit `EventBus.java`):** listener 按事件**类**键控:`Map<Class<?>, List<IListener>> listenerMap`
(`ConcurrentHashMap` of `CopyOnWriteArrayList`)。派发 = 按 `event.getClass()` 一次查表迭代,O(1) 查 + O(listeners) 调,
**无类型继承 walk**(精确运行时类)。`@EventHandler`(RUNTIME、METHOD)带 `int priority()`(HIGHEST=200/HIGH=100/MEDIUM=0/LOW=-100/
LOWEST=-200)——**int 优先级**让 addon 能 `HIGHEST + 1` 插队。`bus.subscribe(object)` 反射扫父类链找 `@EventHandler`(void、单非原始参),
参数类型即事件类型。`insert()` 按优先级排序插入(同级插入序稳定)。**取消经重载**:`post(T)` 普通;`post(T extends ICancellable)`
是独立重载,`isCancelled()` 一 true 就 break。`isListening(Class)` 让热路径在无订阅者时跳过构造事件对象(0.2.4 加)。

** 性能秘密(`LambdaListener.java`):派发不用 `Method.invoke()` 反射。** subscribe 时用 `LambdaMetafactory` 旋出一个
`Consumer<Object>` 直接调目标方法(`findVirtual/findStatic` 绑到实例)。于是 `listener.call(event)` 是真 lambda 调用(近 native、
零 per-call 反射)。需要 per-package `MethodHandles.Lookup`:`registerLambdaFactory(packagePrefix, factory)`——每个 addon 注册自己的
lookup 以造私有访问 lambda。事件对象常是**单例 + 静态 get()**:`TickEvent.Pre/Post` 缓存一个 `INSTANCE` 复用——**每 tick 事件零 GC 抖动**。

**模块系统(`systems/modules/Module.java`):** 每个有 category/name/title/desc/aliases、per-module `Settings`、`Keybind`、
flag(serialize/runInMainMenu/autoSubscribe/…)。**toggle = 事件订阅生命周期**:enable 时 `MeteorClient.EVENT_BUS.subscribe(this)`
(若 autoSubscribe)+ `onActivate()`;disable 时 `unsubscribe(this)` + `onDeactivate()`——`@EventHandler` 只在启用时收事件。
注册显式手工:`Modules.init()` 一堆 `add(new KillAura())`。模块身份=name(equals/hashCode/compareTo 全按 name,后加同名替换前者,addon 可覆盖)。

**管理器——两层 "Systems" 注册表(`systems/Systems.java`+`System.java`):** `System<T>` 抽象基类=可持久化子系统(name、
后备 `<name>.nbt` 文件、init/load/save、`ISerializable` toTag/fromTag);Modules/Config/Friends/Macros/Accounts/Waypoints/Hud 全是
`System` 子类。`Systems` 是 manager-of-managers:`Map<Class<? extends System>, System<?>>`,`Systems.add(sys)` 入 map、
**订阅到 bus、调 init()**。`Systems.init()` 硬编码构造顺序(Modules first),`save()/load()` fan-out。三层:`Module ⊂ Modules(feature 管理器,本身是 System) ⊂ Systems`。

**GUI:** 自定义 widget 工具箱(非 per-feature vanilla Screen)。`GuiTheme`/`GuiThemes`(可换主题)、`WWidget`/`WidgetScreen`/`Tabs`。
设置自渲染:每个 Setting 类型知道怎么建 widget,模块配置面板从 Settings 列表生成(无 per-module 手写屏)。HUD 是自己的 `System`
(`HudElement/HudElementInfo/HudGroup` + `HudEditorScreen`)。

**配置: NBT**,每 System 一个 `meteor-client/<name>.nbt`。一切可持久化实现 `ISerializable<T> { toTag(): CompoundTag; fromTag(tag): T }`,
组合向下(Modules→ListTag of module tags)。** 安全原子写(`System.save`):写临时文件 → `Files.move` `ATOMIC_MOVE + REPLACE_EXISTING`,
不支持则流复制回退——崩溃永不损坏真文件。 损坏恢复(`System.load`):读到 `ReportedException` 就把坏文件移到
`<name>-<timestamp>.backup.nbt` 并 log,不崩溃。** 存触发:`GameLeftEvent` handler + JVM `addShutdownHook`。
**加载前 reset-to-default 再 load**(缺键干净回落默认)。

**注入:** Fabric + SpongeMixin,mixin 发 orbit 事件。两套 init:Fabric 入口点(addon 声明 `MeteorAddon` 入口)+ 反射扫描
`@PreInit/@PostInit`(声明 `dependencies()`,拓扑 DFS 排序)。per-addon `MethodHandles.Lookup` 注册进 bus 以跨模块边界造快 lambda。

**给我们的教训:** Board 的 `Trace` **已经匹配** orbit 的多数最佳想法(类键控派发、`Clock` 优先级、`Signal.Cancellable`、`Subscription` handle)。
**唯一要偷的:orbit 的 `LambdaMetafactory` 派发**——若 `Trace` 未来成热点,可在 subscribe 时旋一次 `Consumer`,使 publish 零反射
(引 orbit `LambdaListener.java`;1.8.9/JDK25 上直接用 `privateLookupIn`,不需 orbit 的 Java-8 分支)。 **偷 `ISerializable` + 原子写 +
坏文件备份**给 Board 持久化(Board 现无等价物,1.8.9 有同样 NbtIo API,近乎逐字可移;但**在 Board 内重实现,别从 client 映射 import**)。
 **偷单例/池化 Signal + 静态 get()**给热信号——**Board design doc 里 `new TickSignal()` 每 tick 是 GC 抖动**(`TickSignal.java` 每次 new),
缓存一个实例;再给 `Trace` 加 `isListening(Class)`/`hasSubscribers` 让发布者在无人订阅时跳过构造 Signal。
`System/Systems` 两层可参照(每个 Matrix 可持久化 + 一个注册表 fan-out save/load),但**保持 Board 零外部依赖**
(Meteor 用 fastutil + reflections 库,Board 用 plain HashMap + 显式注册)。**不适用**:Fabric 入口点 SPI、reflections 扫描、
`@PreInit/@PostInit` 依赖排序(拉 reflections 库、违反零依赖)——Board 用反射-only `Port`/`Backplane` 解耦 + 显式 boot 序,更契合两-peer 模型。
**mcp-core 类比**:Meteor "toggle=(un)subscribe" 展示了门控活体行为的干净法子;对 7 层工具门控,类比是"订阅范围绑能力"
(能力包 handler 只在层解锁时在 bus 上),但**安全门必须权威**——订阅范围是优化,不是访问控制。

## 8. Baritone (结构) — api/impl 纪律 + `IBaritoneProcess` 优先级仲裁

> 寻路 AI(1.12.2 → 1.20+),`api/impl` 分层与版本无关。**给我们的价值:接口-only 公共面纪律 + 控制仲裁模型。**

**api/impl 严格分层:** `src/api/java/baritone/api` 只放接口(`IBaritone`、`IBaritoneProcess`、`IPathingBehavior`、`IEventBus`、
`IPathingControlManager`、`Goal`)vs `src/main`(impl)。消费者**只**编程针对 `baritone.api`(README:"usage of anything outside
baritone.api is not supported");impl 经 `BaritoneAPI.getProvider().getPrimaryBaritone()` 注入。mixin 在 impl 里把 tick 事件喂进
`IEventBus`(`getGameEventHandler()`),从不碰功能代码。**这个接口-only 公共面正是我们 `Port`/`Backplane` + `link/` 反射层在够的纪律。**

** `IBaritoneProcess` 优先级仲裁:** 多个 process 各报 `isActive()` + `priority()`;`PathingControlManager` 把控制权交给
优先级最高的活跃者,`isTemporary()` process(auto-eat 暂停、combat 暂停)可挂起而**不**在输者上触发 `onLostControl()`。
一个干净、可测的"此刻谁在开车"模型——**当 LLM 和某 Board `Chip` 都想操控玩家时的理想解**。

**给我们的教训:**  **采纳 Baritone 的 api/impl 接口-only 纪律给 `Port`/`Backplane` 边界**:Board↔mcp-core 之间**只应**穿过 JDK 类型 +
微小接口(正如 Baritone 禁碰 `baritone.api` 之外)。我们的 `link/McpLink` + `BoardPort` 反射是对的直觉;把"任何 impl 类型都不外泄"正式化,
加一条测试:若跨子系统签名引用了具体类就 fail。 **偷 `IBaritoneProcess` 优先级仲裁**给 Board `Chip` 与 LLM(mcp-core)都想控制玩家的情形:
建模报 `isActive()`+`priority()` 的 "controller",小仲裁器选最高者,支持 `isTemporary()` controller(如 mcp-core 安全暂停)不永久驱逐 Chip——
比临时互斥干净且可测。

## 9. Fabric API (参考) — `EventFactory.createArrayBacked` invoker 工厂

> Fabric 1.20+/1.21 的 `Event<T>` 事件模型。**结构参考,不适用于 1.8.9 vanilla。**

**invoker 工厂模式:** `Event<T>` 经 `EventFactory.createArrayBacked(type, listeners -> aggregateInvoker)`。listener 存 plain 数组;
工厂返回**一个**聚合回调(你自己写成循环);调用方 `SomeCallback.EVENT.invoker().method(args)`——无 per-listener 反射,是编译的数组循环。
协作靠返回协议(PASS→下一个,SUCCESS/FAIL→停)。**无优先级**,顺序=注册序,另有单独的 named-phase API(Identifier 排序)。
**这是生态里最干净的零反射类型化事件设计**:派发/短路策略只写一次(在 invoker 工厂里),调用方无 per-listener 间接。

**给我们的教训:** invoker 工厂优雅,但其返回值协作协议 + 无优先级更适合 mod-API,不适合需要有序功能拦截的 client。
Board 的**优先级 + 可取消类型化 bus(Forge 语义 + Lavender 实现)**是 `Trace` 的正确综合。仅当我们日后对第三方暴露稳定公共事件 API 时再借 Fabric。

---

## 10. Forge / NeoForge (参考) — `IEventBus` + 事件继承 + `Event.Result`

> Forge/NeoForge(1.12-1.20)。**结构参考。**

**`IEventBus`:** `@SubscribeEvent` + `bus.register(obj-or-Class)` 或 `addListener(consumer)`;`EventPriority{HIGHEST..LOWEST}`;
`@Cancelable` + `setCanceled`;**`Event$Result{DENY,DEFAULT,ALLOW}`**(三态结果,比布尔取消更表达);**事件子类层级**
(监听 `PlayerEvent` 捕获所有子类——真继承 fan-out);ASM 生成的 `ASMEventHandler` 避开反射 invoke。**两个 bus**(主游戏 bus vs mod 生命周期 bus)。

**给我们的教训:** Forge 的取消/结果语义值得借鉴——`Cancellable` 基类带 `cancel(reason)` 存原因串(Example 也这么做)比裸布尔更可调试,
**当 LLM 问 mcp-core "为什么这个动作被抑制"时有用**。事件继承 fan-out:Board 的 `Trace` 用 `s.type.isInstance(signal)` **已支持**
(比 Forge 更轻,无需 ASM)。**避开两 bus。** `Event$Result` 三态若 Board 日后需要"默认放行/强制拒绝/强制放行"可参考,但当前 `Cancellable` 二态够用。

---

## 11. 现代化项目 (侧证) — lwjgl3ify / legacy-lwjgl3 佐证 shim 路线

> 1.7-1.12 → LWJGL3/JDK17+ 的迁移研究。**佐证我们 `lwjgl2-shim` 的行业标准地位。**

- **lwjgl3ify**(GTNH):打包 `RetroFuturaBootstrap` 作早加载插件系统,在 classload 时 **ASM 重写** LWJGL2 调用为 LWJGL3(1.7.10/1.12.2 on Java 17+)。
- **legacy-lwjgl3**(Zarzelcow/Lassebq):legacy-fabric mod,**运行时桥接** LWJGL2 Display/Keyboard/Mouse API 到 LWJGL3+GLFW(是 shim 不是重写),已在 1.8.9 验证。

**给我们的教训:** 两者都证明 shim 路线是行业标准。我们的 `lwjgl2-shim` 对齐 legacy-lwjgl3 的**运行时桥接**模型(而非全量重写)——
更低风险的选择。HackSoar 的 `LwjglTransformer` + 从 `LaunchClassLoader.classLoaderExceptions` 移除 `org.lwjgl.` 是需要 classload 拦截时的精确 seam。

---

## 附:注入机制的三分法(横跨全组,与我们最相关)

这是最尖锐的架构分叉,直接关系 Board/mcp-core 怎么碰活体游戏:

- **A. 内嵌 deobf 源码内联编辑**(Faiths / Emperor / Lavender / Southside,全 1.8.9/1.12.2):把整棵 deobf `net.minecraft` 编进自己 src,
  手插 `Bus.call(new XEvent(...))`。简单、总控、易调试,但零可移植、巨大合并面、vanilla 与 client 代码纠缠。Faiths 有 **exactly 32 个** hook 点(易审计)。
- **B. SpongePowered Mixin**(HackSoar;Example on Fabric):`@Inject/@Redirect/@Overwrite` + `@Accessor/@Invoker` accessor 接口。
  干净分离 client 与 vanilla、经 refmap 抗混淆、可组合——专业做法。**Mixin(行为)vs Accessor(字段访问)分离**是关键纪律。
- **C. 我们的路线**:`-javaagent` + `StartupAdvice` 字节码 weave(`flt`/`flt.seam` seam)进运行中 client,`client/` 是 vanilla 映射。
  **两头都不是**。教训:内联编辑客户端证明"把所有 vanilla hook 集中为具名事件发射(一个 vanilla seam 一次 `Trace.publish`)"可行,
  这正是 Board 的 `flt.seam` 复用应标准化的;Mixin 客户端证明把 hook 点**留在功能代码之外、藏在稳定事件契约后**的价值。








