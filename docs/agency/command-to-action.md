# 从一条命令到游戏里的动作:现状、差距、岔路

2026-08-03。目标是**交给它一条命令,它像人一样原生、快速地在游戏里做完**。
这份记的是那条路径今天到哪了、卡在哪、以及哪些取舍必须由 owner 定。

> **全部是静态读码的结论,没有人跑过游戏。** 这是整份文档最大的保留,不是谦辞。
> 待验假设与最便宜的验证实验在 §6,`scripts/run-mcp.sh` 在本机可用。

`docs/` 之前**完全没有内核侧文档**(18 份全是 dwm 与 macOS),这是第一份。

---

## 0. 一句话结论

**不是能力缺失,是谁持有循环。**

现在**模型就是控制回路**:约 500ms 一个决策,对着 50ms 的游戏 tick。于是
"清空这个箱子"退化成约 27 次工具调用,"造一把镐"约 11 次,"去那个村庄"在 8 格视野下约 20 次。
每一次都是一个完整的 LLM 往返。

要"像人一样快",就得让**某些循环跑在代码侧**,而这是架构取舍不是补丁(§5 Fork D)。

---

## 1. 已经建好的部分(不要重建)

这一层的设计质量高于我审计前的预期,列出来是为了防止后人推倒重来。

| 东西 | 位置 | 为什么值得保留 |
|---|---|---|
| **三通道正交意图模型** | `ActSlot.java:15-21`、`ActRuntime.java:104-108` | move / look / interact **并发**,`effectiveTick = lastCompleted+1`,所以工作线程提交永不半应用。看和走可以同时发生,不必序列化 |
| **`ActActuator` 无客户端 seam + `FakeActuator`** | `ActActuator.java:3-17`、`core/src/test/.../act/FakeActuator.java` | 行动层最高杠杆的设计选择:**controller 可以 headless 单测** |
| **`MovementInput` 楔子(client 零改动)** | `ActMovementInput.java:39-62`、`MovementInputInstaller.java:104-120` | 先委托 vanilla,仅在 ACTIVE 时覆盖,空闲时完全隐形;玩家实体身份变了会重新装 |
| **列式 RLE 网格** | `LocalGrid.java:38-98` | `O((2r+1)²)` 而非三次方,垂直剖面按空气可压缩。**扩展 column 记录,不要退回实心立方** |
| **结构化 token 预算** | `ObserveProfile.java:16-46`、`ToolRegistry.java:488-497` | 同一次调用 2 KB vs 38 KB。这一条自己就推翻了两个"blocker"级断言 |
| **一次快照一个 tick、无引用** | `WorldViewCapture.java:41,53-54` | 各 section 之间没有撕裂读 |
| **感知与行动共享地址空间** | `WorldViewCapture.java:128,152-157`、`LocalGrid.java:101-114` | 实体 id 直接喂给 `act_set look{entityId}`;registry 名两侧同样剥离,所以网格里的 `iron_ore` 和背包里的 `iron_pickaxe` 对得上 |
| **ring / L4 / L5 三张侧表 + 漂移测试** | `se/Ring.java:116-118`、`se/SeToolRequirement.java:135-136`、`se/CapabilityCatalog.java:65-66`、`PolicySideTableDriftTest.java:74-77` | 那个测试**已经抓到过** `act_*` 以 HIGH writer 身份发布却没有 L4 权限。新增 nav/craft 工具必须进三张表,忘了测试会说 |
| **`LiveIT` skip-gate 模式** | `core/src/test/.../act/DigLiveIT.java:23-27` | `-Dmcp.it.live=true`,否则 assume-skip。§6 每个实验都照这个写 |

### 1.1 `DigController` 是已经跑通的正确形状

**这一条最要紧,而审计的第一版结论把它漏了**(见 §7)。

`DigController.java` 是一个**真正持久、自终止、自纠正的多 tick controller**:

```
RESOLVING(校验目标+距离,startDig) -> DIGGING(每 tick pumpDig,轮询 blockPresent) -> COMPLETE/CANCELLED/FAILED
```

- 每 tick `pumpDig`,**轮询 `blockPresent` 判完成**(`:81-85`)
- `pumpDig` 报无进展 → **诚实失败**(`:106-108`),不假装在挖
- 超出 `reachDistance` → 失败,因为游戏本来就会拒绝(`:90`)
- 遵守 vanilla `blockHitDelay` 重试上限而不是每 tick 猛敲 `startDig`(`:96-103`)

**导航需要的正是这个形状。** 所以第一步不是发明架构,是把这个模式复制到 MOVE 上。

---

## 2. 真正的差距:MOVE 没有反馈回路

`DigController` 有的东西,MOVE 一个都没有。

**`MoveApplier` 是纯生命周期计数器,零感知。** 全文只做四件事:标 ACTIVE、数 tick、
执行 duration 预算、收尾 cancel。它报的是 `"moving (tick N/M)"` —— **"按住了键"**,
从不是 **"走到了哪"**。

**`ActRuntime.moveForward()`(`:191-193`)返回 `moveIntent().forward()`** ——
提交时的轴,永远保持。冻结的 record 字段。

**`ActActuator` 的 9 个读访问器里没有一个是移动状态**(`:23-47`):
`inWorld` / `eyePos` / `yaw` / `pitch` / `reachDistance` / `mouseOver` / `blockPresent` /
`heldSlot` / `entityEyePos`。**没有 position、没有 `onGround`、没有碰撞。**

后果:一个 MOVE 意图提交后就是开环。撞墙了不知道,走偏了不纠,到了不停,
1 格台阶、3 格落差、水、栅栏各自会发生什么都得模型自己推。

### 2.1 写侧其实已经通了

这一点降低了修复成本,值得说清:**"actuator seam 缺一个移动动词"是误导**。
写侧早就在跑 —— `ActMovementInput.java:55-61` 在 slot ACTIVE 时每 tick 强制 vanilla 四个字段。
缺的是**读侧**加上**每 tick 重算**。

`LivePlayerActuator` 每个方法都握着活的 `EntityPlayerSP`
(`:45,55,61,101,123,135,178,191,206,221,231`),所以
`posX`/`posZ`/`onGround`/`isCollidedHorizontally` 各是一次字段读。

**这是一个 controller 形状的洞,不是管道形状的洞。**

---

## 3. 唯一"如实陈述"的缺口:垂直窗口截断在坠落伤害阈值上

`LocalGrid.java:54-61` 在 `[-vBelow, +vAbove]` 窗口内自上而下找地面,窗口外
`surfaceDy` 为 null、`surface="air"`。EXPLORE 档 `vBelow=3`(`ObserveProfile.java:19`)。

而 vanilla 坠落伤害是 `ceil(distance - 3 - jumpBoost)`
(`client/src/main/java/net/minecraft/entity/EntityLivingBase.java:1156`)。

**于是:无害的 3 格落差恰好是能看见的最深处,而每一个会掉血的落差都长得像 40 格深渊。**

修法(不需要新的方块分类学):向下有界扫描得 `groundDy`/`dropDepth`,再给每列加**一个字符**的
可行性等级,取自 vanilla 自己的判断 —— `WalkNodeProcessor.func_176170_a` 是 `public static`
(`client/src/main/java/net/minecraft/world/pathfinder/WalkNodeProcessor.java:194`),
返回码注释在 `:183-187`:

```
-1 水(若避水) · -2 岩浆 · -3 栅栏与墙 · -4 关闭的活板门 · 0 实体阻挡 · 1 通行 · 2 除开活板门/涉水外通行
```

这直接给出 vanilla 对那个 1.5 格高栅栏的裁决(`BlockFence.java:73,92`),不必自己定义。

> **注意 §3 与 Fork B 是耦合的**:vanilla 自己的 `getSafePoint` 也把下台阶封顶在
> `getMaxFallHeight()==3`(`WalkNodeProcessor.java:160`、`Entity.java:2518`),
> 所以选了 vanilla A* 就继承同一个 3 格盲区,除非喂它一个懂危险的 processor。

---

## 4. 关键路径(有序)

| # | 做什么 | 解锁 | 体量 | 性质 |
|---|---|---|---|---|
| 1 | **MOVE 的闭环导航 controller** | 到达判定、航向纠正、卡住自失败、障碍处理 | 最大,但远小于"写一个寻路器" | **架构决策**(Fork C) |
| 2 | `act_status` 报**提交以来的位移** | MOVE 卡住时诚实失败;让步骤 1 可测 | 小 | 补丁 |
| 3 | column 带 `groundDy` + 可行性等级(§3) | 落差与危险可见 | 小到中 | 补丁 |
| 4 | 按类型过滤、返回坐标的方块查询 | "挖你看见的铁矿";今天找一块矿要吐 289 列 | 小,**可与 1 并行** | 补丁 |
| 5 | LOOK 的**追踪不自终止**模式 | 跟随移动目标 | 小 | 补丁 |
| 6 | INTERACT 的**保持**通道(吃、拉弓、举剑格挡) | 这些今天在 R-1 以下不可达 | 小到中 | **架构决策** |
| 7 | craft 工具:配方 → 格子布局 → 一次游戏线程内点完 | 今天约 11 次往返,人类约 3 秒 | 中 | 补丁 |

**为什么第 1 步压倒第 4 步**(第 4 更小、见效更快):第 4 只是让**已有的推理更便宜** ——
模型今天已经能靠扫 289 列找到矿。第 1 步加的是**在任何 token 价格下都无法用现有 83 个工具组合出来的东西**:
一个比 LLM 往返更快的循环。而且不做第 1 步,第 2、3 步的输出形状就只能靠猜。

第 1 步的具体三件事:
1. `ActActuator.java:20-47` 加 3-4 个读访问器(position、`onGround`、`isCollidedHorizontally`)
   加上对应的 `FakeActuator` 条目 —— 接口自己的 javadoc(`:3-17`)就写着
   "controller 需要的每个游戏接触点都是这里的一个方法",所以这在既有设计**之内**。
2. `NavController`/`NavApplier`,每 tick 从当前位置与目标重算 forward/strafe/jump。
3. 一个真的 seam 决策:`ActRuntime.java:187` 用 `instanceof MoveIntent` 把关,
   而 `LookApplier.java:32-34` 用**意图对象同一性**判断"是否新提交" ——
   一个每 tick 改写 record 意图的 nav applier 不能破坏这两个约定。

---

## 5. 必须 owner 定的岔路

按 CLAUDE.md,命名与架构取舍不由 agent 发明。这里只摆两边的理由。

### Fork D — 谁持有循环,以什么频率(**最关键,吞掉其余几个**)

- **模型持环 ~2Hz**:灵活性最大、零新语义,上面每个缺口都退化成 token 成本问题而不是能力问题。
- **代码持环 20Hz、模型只下目标**:唯一能让"去那个村庄""跟着我走"显得原生的路,
  也是唯一让执行不再每 tick 花一次 LLM 调用的路。
  代价:你在写一个游戏 agent,而不是暴露一个游戏 —— 而且行为越往代码侧移,
  模型判断参与执行的比例越低,**这与这个项目有意思的地方相反**。

### Fork A — 多 tick 行为住在哪

- **编译进 `core/drivers/act` 的 Java controller**:贴合现有 `ActApplier`/`ActActuator`/`FakeActuator`,
  headless 可测,门控在 R1(actuation 本来就在那),漂移测试会管住它。
  代价:每个新行为都要 build+重启,模型无法自己发明行为。
- **R-1 门控下的生成代码**:`create_tool` 是 R0(`Ring.java:98`)且能到游戏线程,
  所以模型理论上能在运行时写行为 —— 但生成工具只拿 `CAP_WORLD_READ + CAP_MEMORY_READ`
  (`CapabilityCatalog.java:111`),要动世界必须显式授权,而 `eval_java` 是 R-1 by design
  (`Ring.java:88`)。**等于把玩法放在任意代码执行的同一档上。**
- **模型驱动的行为树/计划解释器**:一个 R1 工具接受声明式计划,以 20Hz 推进 ——
  模型仍然在写行为,但在一个你能门控、能测试的有限词汇里。代价:你从此拥有一个解释器和它的语义,
  三者中最大。

### Fork B — 导航引擎

- **包 vanilla A***:`PathFinder.createEntityPathTo(IBlockAccess, Entity, BlockPos, float)` 是 public
  且接受裸 `Entity`(`client/src/main/java/net/minecraft/pathfinding/PathFinder.java:33`),
  `ChunkCache(World, BlockPos, BlockPos, int)` 是 public(`ChunkCache.java:24`),
  `WalkNodeProcessor` 从 `entity.width/height` 取尺寸,而且被十年的怪物验证过。
  代价:**`PathNavigate` 用不了**(它要 `EntityLiving` 和玩家没有的 `getMoveHelper()`),
  所以 follower 还是得自己写;继承 3 格坠落盲区;把更多 `net.minecraft` 拉进 core
  (`LivePlayerActuator` 已经这么做了,所以这是程度问题)。
- **在 `LocalGrid` 上写局部贪心转向**:除已有耦合外不碰客户端,可以拿合成网格轻松测,
  而且**可以教它挖穿或搭桥而不是绕开** —— 那才是人真正的做法。
  代价:可行性分类学和每一个地形边界情况从此永久归你。
- **不做规划器,只发布可行性让模型路由**:零新引擎,而模型对 17×17 网格确实在行。
  代价:每次重路由一次 LLM 往返,又回到 Fork D 的节奏问题。

### Fork C — nav 意图的形状

- **第四个 `ActSlot`**:干净分离,MOVE 留作手动用的裸轴原语。代价:两个东西能驱动
  `ActMovementInput`,你得定义优先级。
- **MOVE slot 内的 `NavIntent`**:locomotion 只有一个主人,没有优先级问题,
  "新提交替换 slot"本来就是对的语义。代价:`ActRuntime.java:187` 的 `instanceof` 门
  和 `LookApplier.java:32-34` 的同一性约定都要改。
- **一个工具在内部每 tick 提交 MOVE 意图**:diff 最小,runtime 不动。
  代价:locomotion 状态活在 slot 模型之外,于是 `act_status` 和 `act_cancel` 对它开始说假话。

### Fork E — 门控粒度(被第 1 步逼出来)

一个会挖穿障碍的 nav 工具需要 interact 通道,而 `act_set` 把 move+look+dig+place+attack
作为**一个整体**门控在 R1 / `SE_WORLD_WRITE` / `CAP_WORLD_WRITE`。

- **保持整面**:这是刻意的、有文档的,且被 `SupervisedGateL4L5DenyTest` 钉住。
- **按 slot 拆分**:让操作者能说"可以走和看,但不许改世界",适合第一次放开自主跑。
  代价:三张侧表要扩、一个测试要重写,而且仍然表达不了"只砍树、绝不攻击" ——
  那需要参数级策略,`SeProtectedObjects.isProtected` 有 14 处 in-handler 先例但没有框架。

### Fork F — 感知面

- **过滤查询**(`find_block`、关注方块参数):便宜、精确,但每个都是要命名和门控的新工具。
- **更好压缩的完整 dump**(省掉全空气列):不加新表面,但扫描仍由模型做。
- **推送**:`GET /v1/stream` 的 SSE 侧通道**今天就以封包速率**携带 `posLook`、
  实体传送/相对移动、方块变更 —— 即 20Hz 世界馈送已经存在,只是不在 MCP socket 上、
  对 MCP 客户端而言是带外的。统一到它意味着接受两个传输层。

---

## 5.5 真机实测结果(2026-08-03,Fork B 已定)

`scripts/nav-astar-probe.py`,5/5 通过,真客户端 + `smoke_world`,玩家在 `(60.5, 63, 82.9)`。

> **为什么是 python 脚本而不是 JUnit LiveIT。** `GameAccess` 读的是
> `Minecraft.getMinecraft()` —— **只存在于游戏那个 JVM** 的静态单例。surefire 的 JVM 里它是
> null,`isInWorld()` 返回 false,于是 assume 门控的测试**永远 skip、什么都没探到**。
> `DigLiveIT` 是同一个形状,而 `git log` 显示它只在 PHASE A 被一起加进来过,**没有证据表明它跑过**。
> 本仓库的真机验证走 MCP socket + `eval_java`(`scripts/live-dwm-probe.py` 就是这么做的)。
> 我先照 `DigLiveIT` 写了一个 `NavPathLiveIT`,那是错的选择,已删。

### ① vanilla A* 对客户端玩家可用 —— Fork B 的"包 vanilla"分支活着

```
NODES 22  took=5357us   (首次,含 JIT)
n=26      us≈2000       (稳态,同一查询三次完全一致)
```

12 格斜向查询给出 22-26 个节点,**稳态 ~2ms**。对 50ms 的 tick 预算来说足够便宜,
可以按需调用而不必缓存整条路线。节点是逐格的(`60,63,82 → 60,63,83 → 61,63,83 → 61,64,84 …`),
含台阶上升,所以 follower 拿到的是格级航点而不是粗折线。

**读代码时担心的 `initProcessor` 问题不存在**:`createEntityPathTo` 自己调它
(`PathFinder.java:44`),尺寸从 entity 取。

### ② `reachedTarget=false` 是常态,必须写进 follower 的到达判定

三次全部:`target=64,63,91` 而 `final=64,64,91`。

目标 Y 取的是玩家脚下高度,而那个 XZ 上地形高一格,**vanilla 把目标吸附到了可行走表面**。
这不是失败 —— 但任何 follower 若按"位置 == 请求的目标"判到达,**就永远不会到达**。

**必须用 `getFinalPathPoint()` 当真正的终点**,而不是自己请求的那个坐标。

### ③ `IBlockAccess` 绕过:不崩,但会静默改变路径

故意传一个只覆盖玩家自己方块的 `ChunkCache`:**没有抛异常,给出 16 个节点**(完整 cache 是 22)。

比崩溃更糟的形态:**它不失败,它给你一条不同的路**。所以 wrapper 必须自己把 cache 撑到覆盖
整个查询盒 —— 这条现在是实测结论而不是推测。

### ④ vanilla 的可行性裁决可从 core 调用 —— 感知修法可行

`WalkNodeProcessor.func_176170_a(cache, p, x, y, z, 1, 2, 1, false, false, false)` 对真实地形返回:

```
dy-2=0  dy-1=0  dy0=1  dy1=1      (0 = 实体阻挡,1 = 通行)
```

脚下实体、脚与头部通行,与玩家站在平地上完全吻合。**§3 那个"一个字符的可行性等级"取自
vanilla 自己裁决**的修法,现在有真机支持。

### ⑤ 开环 MOVE 在平地走的是完美直线 —— 比我预期的好

```
位移 dx=-7.515 dz=-3.773  |d|=8.408 格
yaw 预测 dx=-7.512 dz=-3.778
偏离朝向 0.04 度
8.41 格 / 40 tick = 4.2 格/秒(走路速度)
```

`durationTicks:40` 精确到点停下,血量 20 未变。**探针只断言了"有位移",所以它低报了这个结果。**

这削弱了"MOVE 开环所以不可用"的说法:**平地直线段是可靠的**。真正缺的是
到达判定、航向纠正、以及撞上东西之后的处理 —— 也就是 §2 说的反馈,而不是位移本身。

---

## 6. 还不知道的(以及最便宜的验证)

> **§6 写于全静态阶段。上面 §5.5 已经实测掉了原编号 1、4(部分)、5;
> 其余仍然未验。** 下面保留原文,因为未验清单本身仍然有效。

**本机 `scripts/run-mcp.sh` 与 `jvm-args-mcp-macos.txt` 可用**(已实测起得来,
`3 patch(es) armed, 0 skipped`,socket 在 25599);
**没有 macOS 的 P-SECURE 启动器**(只有 `.bat`),所以 P-SECURE 相关结论在本机大概复现不了。

1. **vanilla A* 对 `EntityPlayerSP` 在客户端跑得起来吗(不 NPE)?** 这一条独自决定 Fork B。
   注意 `func_176170_a` 的铁轨检查直接读 `entityIn.worldObj.getBlockState(...)`
   (`WalkNodeProcessor.java:235-237`),**绕过了你传进去的 `IBlockAccess`** ——
   所以缓存窗口之外的行为未验。
   **最便宜:** 约 30 行的 `NavPathLiveIT`,照 `DigLiveIT.java:23-27` 的 skip-gate 写 ——
   `new ChunkCache(mc.theWorld, from, to, 0)` + `new PathFinder(new WalkNodeProcessor())`,
   断言 `createEntityPathTo(cache, mc.thePlayer, target, 32f)` 非 null,记节点数与墙钟时间。
   **一个下午定掉 Fork B。**
2. **`world_view` 真实体积与延迟。** 本审计所有字符数都来自合成列喂过真序列化器。
   最便宜:真机各跑一次 r=8 与 r=16,记 payload 字符数与往返毫秒。
3. **`world_view` r=16 或 `capture_screen` 自己会不会把游戏线程卡住 ≥5s?**
   会的话 `GameBridge.java:62` 与 `IoSupervisor` 的 5s 超时撞车就是自伤而非需要一个卡死的客户端。
   最便宜:折进实验 2,看 p99。
4. **撞墙卡住的玩家在捕获时刻真的读到 `vel≈[0,y,0]` 吗?** "卡住信号已经接好了"这个反驳
   建立在 tick 内捕获顺序上,那是读出来的、不是观测到的。
   最便宜:顶着墙按前进,一次 `world_view`,看 `vel` 与 `sprinting`。
5. **开环 MOVE 意图能走直线吗?** 这是"笨拙但非不可能"的承重假设。
   最便宜:`read_player_state` → `act_set move{forward:1,durationTicks:100}` → `read_player_state`,
   比实际方位与 yaw。
6. **真实 1.8.9 服务端会拒绝过期的 `actionNumber` 吗(`do_click_slot`)?**
   决定裸封包背包操作(以及任何建在它上面的 craft 工具)是否健全。
   工具自己在 `ToolRegistry.java:893` 的警告两个方向都未证实。
7. **吃/拉弓的自动释放陷阱真的会触发吗?** `Minecraft.java:2118-2123` 只做了括号核对,没观测。
   最便宜:手持食物 → `act_set interact{kind:use}` → 40 tick 内轮询饱食度。不涨则第 6 步确认必要。
8. **`create_tool` + 显式 `CAP_WORLD_WRITE` 授权,真的能给模型一条可用的行动路径吗?**
   这决定 Fork A 的中间选项是真的还是纸上的。无人测过。

---

## 7. 这轮审计自己犯的错(保留,因为形状会重演)

1. **我 grep "寻路" 只搜了 `core` + `board`,没搜 `client/src`**,于是 baseline 写成
   "寻路完全没有",并把这条当既定事实喂给了五个 agent。
   真相:**vanilla 自带完整 A***(`pathfinding/` 六个类 + `world/pathfinder/` 三个),
   只是没接到玩家身上。**"X 不存在"是最容易搞错、也最贵的一类断言。**
2. **审计的第一版中心论点是"repo 里不存在任何持久多 tick 行为",那是错的** ——
   `DigController` 就是(§1.1)。四个 controller 里三个一次性,但第四个恰好是要抄的样板。
   这个错误让建议看起来比实际更贵。
3. **我的 workflow 脚本只把 `CONFIRMED` 当存活,丢掉了 `corrected_severity`。**
   实际是 8 major + 8 minor,只有 1 条"如实"。`OVERSTATED` 意思是**严重度夸大,不是不存在** ——
   这个过滤差点把 7 个 major 缺口从我眼前删掉。从 journal 里捞回来的。
4. **`research:orchestration` 那一路六次重试全部 stall、零产出** —— 恰好是最贴目标的一路。
   所以"一条命令 → 上百动作"这块只有其他四路的旁证,没有专门调研。
5. **工具总数我先说 64,实际 83** —— 我只扫了 `*Tools*.java`,漏了别处注册的。
