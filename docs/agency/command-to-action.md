# 从一条命令到游戏里的动作:现状、差距、岔路

2026-08-03。目标是**交给它一条命令,它像人一样原生、快速地在游戏里做完**。
这份记的是那条路径今天到哪了、卡在哪、以及哪些取舍必须由 owner 定。

> **原文:"全部是静态读码的结论,没有人跑过游戏。这是整份文档最大的保留,不是谦辞。"**
>
> **那条已不再整体成立(2026-08-04),但也没有整体作废,所以逐节看横幅而不是看这一句。**
> 三轮真机跑过之后:§5.5 的数字是实测的、§6 第 1/4/5/6/7 项各有自己的订正块、
> hold 通道两次 7/7(`bfb12c3`)。
>
> **2026-08-05(交接文件名是 08-06,见它顶部的说明):导航那三条与 LOOK 追踪都验过了。** 三个探针 38 条全绿
> (LOOK 12/12、hold 14/14、nav 12/12),**而对角线第一次到达**(0.32 格,
> forward/strafe 各 0.707)。跑真机这一轮抓到七条,三个是生产缺陷,**两个是当轮作者
> 自己的误诊** —— 那七条的判据收进了 `debugging.md` §10。会话原文不进 git。
>
> **仍然是纯静态的**:§6 第 8 项(`create_tool` 那条路)、`do_click_slot` 的锁窗序列
> (只读过 vanilla 源码,没在真服上见它发生)、以及 **`CraftController` × 活窗口跨 tick**(两半各自都验过,合起来没验过)。
>
> 保留原句是因为它当时是对的,而**"这份文档是不是可信"的答案从来不是一个句子** ——
> 是每一节自己的横幅。整份文档一句"已验证"会比一句"未验证"危险得多。
>
> **2026-08-22(进 git 的主线):** GitHub `main` 已快进到与 `mcp-core` 同一 tip,
> Mac qml4j UI 就是产品 UI。`act_set move.route` 与 `act_plan` 的 Windows COMPLETE
> 已验(含突变体打红)。§2 的历史标题不要读成「MOVE 还没闭环」。Owner 未拍:
> Fork E/F、`act_plan` 技能库、STEP_UP 真跳、craft 生产驱动。SHA/条数以工作站
> `.ai-notes/STATUS.md` 为准,本文不钉。

`docs/` 之前**完全没有内核侧文档**(18 份全是 dwm 与 macOS),这是第一份。

---

## 0. 一句话结论

> **主题订正(2026-08-05,owner 裁决 —— 本节与 §5 Fork D 的取舍框架建立在一个错误前提上)。**
>
> owner 的原话:**"这个 MCP 就是游戏 agent。他不是在游戏里面的,他是控制整个 java,
> 可以控制一切、知道一切的,非常宏观。"**
>
> **于是 Fork D 定了:代码持环。** 而它定下来的方式不是"权衡之后选了这一侧" ——
> 是**下面记的那个代价根本不存在**。原文把"行为越往代码侧移,模型判断参与执行的比例越低"
> 写成"与这个项目有意思的地方相反";§5 Fork D 下面重复了同一句。**那句话对这个项目是错的。**
> 有意思的地方不是模型逐 tick 参与运动控制,是它**在 JVM 那一层什么都看得见、什么都动得了**
> —— `eval_java`、`redefine_class`、热改运行中的游戏、7 层权限内核、JVMTI。
> **模型的位置在宏观,不在马达。**
>
> 所以:20Hz 的运动控制归代码,模型下目标并观察/改写整个 JVM。原文保留(见本文件顶部
> 那条纪律:误判的序列本身比结论有教育意义),但**别再照它把 Fork D 当成未决的取舍**。

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
| ~~**`LiveIT` skip-gate 模式**~~ **已废除,别照它写** | 现在是 `core/src/test/.../LiveGameGate.java` | 见下方横幅。原文推荐的双 `Assume` 让"显式要求真机"也报成功,而**照这一行写出来的 `HoldLiveIT` 就带着那个缺陷**(2026-08-04) |

> **订正(2026-08-04):上一行原来推荐的写法已被废除,而这份文档本身害过一次。**
>
> 原文是:`LiveIT` skip-gate 模式,`DigLiveIT.java:23-27`,"`-Dmcp.it.live=true`,否则
> assume-skip。§6 每个实验都照这个写"。
>
> 那个形状门控**两次** —— `Assume(mcp.it.live)` 之后再 `Assume(game reachable)` ——
> 所以一个显式带 `-Dmcp.it.live=true` 跑的操作者拿到的是 skip + BUILD SUCCESS,
> 与"验过了"从外面看一模一样。仓库里的 surefire 报告记着那一幕。`68f7e01` 用
> `LiveGameGate` 换掉了它:**没要求 → SKIP;要求了但这个 JVM 不可能 → FAIL**,
> 信息带真实 NPE 原因并指向 `scripts/nav-astar-probe.py`。
>
> **而这一行在被废除之后仍然留在文档里,于是同一批里另一个作者照它写了 `HoldLiveIT`,
> 一字不差地重造了那个缺陷。** 教训不是"改代码时记得改文档",是更硬的一条:
> **废除一个写法的改动没有同时删掉教那个写法的文字,那个写法就还活着。**
>
> 另外要知道:`GameAccess` 读 `Minecraft.getMinecraft()`,那是只存在于游戏 JVM 的静态
> 单例,所以这六个 IT 在 surefire/failsafe 里**只可能走 FAIL** —— 它们是诚实的墓碑,
> 不是能用的测试。**真机验证走 MCP socket + `eval_java`**(`scripts/nav-astar-probe.py`
> 是范例),不要再写新的游戏依赖 LiveIT。

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

## 2. MOVE 闭环已进树(历史诊断:没有反馈回路)

> **订正(装配已进树,Windows live 已验 2026-08-21):** `act_set` `move.route`
> (`RouteIntent` / `RouteExecutor` / `AModelCanAskForARouteThroughActSetTest`) 在 HEAD。
> headless 有。Windows 真机 `scripts/live-route-probe.py` 5/5,含一次生产突变(第一 tick
> 假 COMPLETE,探针打红)再还原 5/5。缺的不是 MOVE 闭环。不要把本节标题读成「还没写」。
> 一格高墙会被规划成 STEP_UP,而 `NavController` 不跳,那是另一条债,不是 route 没接线。
>
> 下面到 §2.1 是 2026-08-03 的开环诊断原文。保留误诊形状;不要跳过横幅把它当缺口清单。

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

> **上面那一行有两处错,而它是照抄 vanilla 自己的注释(2026-08-04 逐行确认)。**
>
> **① `-4` 与 `2` 是反的:`-4` 是打开的活板门,关闭的落到 `2`。** 读 dispatch 而不是注释:
> 活板门先在 `WalkNodeProcessor:230-233` 置 `flag`,再由 `:242` 的 `!isPassable` 分岔,
> 而 `BlockTrapDoor.isPassable` 是 `!OPEN`(`:53-56`)。所以打开的(竖起来挡住格子)在
> `:251` 返回 `-4`,关闭的(平躺在地板上、格子可穿)落到 `:271` 的 `flag ? 2 : 1`。
> vanilla 的注释在 `:183-187` 就是反的,本文和 `LocalGrid` 的第一版都照抄了它。
>
> **② `-1` 在我们的调用下不可达。** `LocalGrid.walkVerdict` 传 `avoidWater=false`
> (`:228-230`),而 `-1` 只在 `avoidWater` 为真时返回,所以水读作 `2`。
>
> 现行图例见 `LocalGrid.Column#walk` 的 javadoc 与 `world_view` 的工具描述(`f5b8206`),
> 两处都已订正。原文保留,因为**"照抄上游注释"这个错误形状**比正确的表更值得记:
> 上游注释和上游代码不一致时,代码才是权威,而抄注释的代价是把它的错误也一起继承。

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

> **进度(2026-08-05):1-6 已落地,7 一半落地。** 原表保留,因为排序的理由
> (为什么第 1 步压倒第 4 步,见下)仍然是这一节的价值所在。
>
> - **1** `NavController`,`df108a5`。**2** MOVE 报位移与卡住,`0de98a3`。
>   **3** column 带 `drop` + `walk`,`e6703ed`/`9b9e59a`。**4** `find_block`,`fea034e`。
> - **6** `HoldController` + `act_set interact{kind:"hold"}`,`b94a7d6`/`612b661`,
>   **真机 7/7 两次**(`bfb12c3`)。Fork C 的那个架构决策也一并定了:hold 扩既有 interact
>   意图词表,零新侧表行。
> - **7 的前提被推翻了一半。** "一次游戏线程内点完"只对玩家自己窗口里的 **2x2** 成立;
>   **3x3 要一次开窗往返**(C08 → S2D → 客户端建屏),所以它**是 controller 不是工具**。
>   配方侧(布局解析 + 缺料报告)已提交 `40527d6`,点击侧状态机 `9e22b2d`,
>   **活窗口实现 `79cc387`**(`LiveCraftWindow`,槽位一律问容器要,真机逐条验过)。
>   **仍然缺的是多 tick 驱动器**,而那一步被结构挡住:探针持不住跨 tick 状态
>   (`eval_java` 的 static 不跨提交存活,见 `debugging.md` §10⑧),所以只剩两条路 ——
>   注册工具(触发 L4 决定),或改 controller 让它一次提交内走完(改契约)。**两者都要 owner 拍。**
> - **5** LOOK 的追踪不自终止模式,`8cea6c6`。`LookIntent.AimMode.KEEP` +
>   `act_set look{track:true, durationTicks}`。**缺的不是重算角度** —— `LOOK_AT` 早就每 tick
>   重算了;缺的是纠正得继续被写下去:ONCE 下 controller 瞄到就 `done()`,applier 丢掉它,
>   `ActTickLoop` 之后再不步进这个 slot,于是目标走掉的下一 tick 没人纠正。
>   **"到位"明确不是 KEEP 的终止条件**;终止只来自 cancel / 替换 / 实体消失 / 世界没了 /
>   `durationTicks`。到期时瞄到过报 COMPLETE、从未瞄到报 FAILED —— 慢 slew 追不上时
>   报成功是往危险方向说谎。**真机验过 11/11**(`scripts/live-look-probe.py`),
>   headless 九次变异全红,**且在真机上也做过变异**:把 KEEP 改回"到位即终止"重新打包重跑,
>   11 条里 6 条转红 —— 只有这一步之后那 11/11 才是证据。

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

### Fork D — 谁持有循环,以什么频率(~~最关键,吞掉其余几个~~ **已定:代码持环**)

> **已定(2026-08-05,owner)。裁决与理由见 §0 顶部的主题订正横幅。**
>
> **代码持环 20Hz,模型下目标。** 定它的不是权衡,是下面那个"代价"基于一个错误前提:
> 这个项目有意思的地方不是模型逐 tick 参与运动控制,而是它在 JVM 那一层知道一切、
> 控制一切。**运动控制下沉到代码不减损那个位置,它释放那个位置。**
>
> 下面两侧的原文保留,因为"模型持环"那一侧列的成本数字(27 / 11 / 20 次往返)仍然是
> 这条路为什么走不通的实测证据。

- **模型持环 ~2Hz**:灵活性最大、零新语义,上面每个缺口都退化成 token 成本问题而不是能力问题。
- **代码持环 20Hz、模型只下目标**:唯一能让"去那个村庄""跟着我走"显得原生的路,
  也是唯一让执行不再每 tick 花一次 LLM 调用的路。
  代价:你在写一个游戏 agent,而不是暴露一个游戏 —— 而且行为越往代码侧移,
  模型判断参与执行的比例越低,**这与这个项目有意思的地方相反**。

> **2026-08-03 调研:这条岔路上,已知的实现全部选了"代码持环"。**
>
> [Voyager](https://voyager.minedojo.org/) 明确"use code as the action space instead of
> low-level motor commands",理由就是长时程目标需要跨时间、可组合的行为(详见 Fork A 下的展开)。
> [Baritone](https://github.com/cabaletta/baritone) 的寻路在**独立线程**上算,主线程只拿
> "最新考察到的节点和目前最好的路径",执行可在最佳路径穿过玩家当前位置时暂停 —— 这是一个
> 彻底的代码侧循环,外部只给目标(`setGoalAndPath(new GoalXZ(10000, 20000))`)。
>
> **但注意这不是对 Fork D 的回答,只是对"20Hz 那一侧技术上可行且被验证"的回答。**
> 那两个项目都不背着一个 7 层权限内核,也都不以"模型判断参与执行"为设计目标。
> "行为越往代码侧移、模型参与越少"这个代价对它们不是代价,对本项目可能是。
> 所以这条仍然是取舍,不是有正确答案的问题。

### Fork A — 多 tick 行为住在哪

> **订正(2026-08-21):** 第三条路已进树,工具名 `act_plan`。它是 sidecar 解释器,不是
> 第四个 `ActSlot`,也不是 `create_tool`/`eval_java` 玩法。词汇 = 现有 `act_set`
> 的 move/look/interact;20Hz 仍归 `ActTickLoop`;FAILED 不继续下一步。门控与
> `act_set` 同面(R1 / HIGH / `SE_WORLD_WRITE` / `CAP_WORLD_WRITE`)。headless:
> core 1125,`ActPlanInterpreterAdvancesOnlyAfterCompleteTest` 等。Windows live
> `scripts/live-act-plan-probe.py` 3/3 (look then hotbar; yaw landed and slot
> changed) + mutant (omit `ActTickLoop.stepPlan`: plan stays RUNNING, INTERACT
> IDLE, probe red) + restored 3/3. §6.8 的 `create_tool` 写路径仍未测,也不是这条路。

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

#### 前人怎么做的:Voyager 选了"生成代码"(2026-08-03 调研)

[Voyager](https://voyager.minedojo.org/)(NVIDIA/Caltech,GPT-4 驱动的 Minecraft agent)
**明确地把代码而不是低层动作当作动作空间**,理由正是本文 §0 那条:程序能表达跨时间、可组合的行为,
而长时程目标需要这个。一次 LLM 调用产出一段跨很多 tick 执行的程序,不是一个原子动作。

它的三个部件对 Fork A 的"词汇"问题给了一个具体答案 —— **词汇不该是固定的,它应该增长**:

- **技能库**:跑通的程序按描述的 embedding 索引存起来;新任务先检索 top-5 相关技能当上下文。
  程序可组合,所以简单技能变成困难技能的积木。论文说这"迅速复利式增长能力并缓解灾难性遗忘"。
- **迭代提示**:生成的代码要过三道反馈才进库 —— 环境反馈(缺两块木板)、执行错误
  (把不存在的 "acacia axe" 改成木斧)、**自我验证**(GPT-4 拿着任务和状态当 critic 判成败并给评语)。
- **自动课程**:从当前状态和探索进度提议下一个任务,即 in-context 的 novelty search。

报告的结果:160 轮拿到 63 种独特物品(基线的 3.3 倍),木器时代快 15.3 倍,技能零样本迁移到新世界。

**但有一条关键差异,它决定这条经验能不能直接搬过来。** Voyager 的生成代码跑在
**Mineflayer 里 —— 进程外,没有安全内核**。本项目里 `create_tool` 是 R0 但生成工具只拿
`CAP_WORLD_READ + CAP_MEMORY_READ`,`eval_java` 是 R-1 by design。
**照搬 Voyager 等于把玩法放在任意代码执行的同一档上**,而那正是铁律③要防的事。

所以 Voyager 是"生成代码可行且有效"的强证据,**不是**"本项目应该这么做"的证据 ——
两者之间隔着这个内核。第三条路(有限词汇的解释器)存在的意义正是想同时拿到两边:
模型仍在写行为,但写在一个你能门控的词汇里。

### Fork B — 导航引擎

> **2026-08-03 更正:这一节原来把"包 vanilla"列为可选项之一,那是错的。**
> vanilla 的邻居生成器只有四个正交方向,建模的是怪物而不是人 —— 详见 §5.5 顶部的更正横幅。
> **它不能表达挖穿、搭桥、跨隙跳,而这三件正是"像人一样"的内容。**
> 下面保留原文,因为"我实测了它跑不跑,却没问它能表达什么"这个错误形状值得留着。

- ~~**包 vanilla A***~~ **已排除,但留一个真实用途:廉价的可达性预言机。**
  `PathFinder.createEntityPathTo(IBlockAccess, Entity, BlockPos, float)` 是 public
  且接受裸 `Entity`(`client/src/main/java/net/minecraft/pathfinding/PathFinder.java:33`),
  `ChunkCache(World, BlockPos, BlockPos, int)` 是 public(`ChunkCache.java:24`),
  ~~而且被十年的怪物验证过~~ —— **"被怪物验证过"恰恰是问题所在,不是卖点。**
  找到路 ⇒ 不改地形就能走到(有用的事实);找不到 ⇒ 什么都没说明,因为人可能挖三格就过去了。
  代价:`PathNavigate` 用不了(要 `EntityLiving` 和玩家没有的 `getMoveHelper()`)。
- **在 `LocalGrid` 上写局部转向 + 自有邻居生成器** ← **现在是主路**。
  除已有耦合外不碰客户端,可以拿合成网格轻松测,而且**可以教它挖穿或搭桥而不是绕开** ——
  那才是人真正的做法,也是 Baritone 和 mineflayer-pathfinder 各自重写寻路器的原因。
  代价:可行性分类学和每一个地形边界情况从此永久归你(§3 的 `walk` 已经借了 vanilla 的裁决,
  所以这笔债比看起来小)。
- **不做规划器,只发布可行性让模型路由**:零新引擎,而模型对 17×17 网格确实在行。
  代价:每次重路由一次 LLM 往返,又回到 Fork D 的节奏问题。

#### 前人怎么做的(2026-08-03 调研)

两个成熟实现**都自己写了寻路器,而且他们本来可以不写**:

| | 建模了什么 | 是否用 vanilla |
|---|---|---|
| [Baritone](https://github.com/cabaletta/baritone) | "A*, with some modifications":分段计算、增量代价回退、最小改进重传播。**1/2/3 格跨隙跑跳、pillaring、sneak-back-place、梯子藤蔓、半砖楼梯**,权衡挖穿 vs 绕路(考虑手上工具),放置代价默认 1 秒,避开火/岩浆/临水方块。区块压成 2-bit(AIR/SOLID/WATER/AVOID)驻内存,可落盘,所以能超视距寻路 | **不用**。而它跑在客户端同一个 JVM 里,完全能直接调 |
| [mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder) | `digCost` / `placeCost` / `scafoldingBlocks` / `allow1by1towers` / `maxDropDown=4` / 跨隙 parkour / 游泳 / 实体规避 | **不用**(它在游戏进程外,本来也调不到) |

Baritone 那一栏是关键:**唯一一个技术上能复用 vanilla 的实现,选择了不复用。**

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

> **重要更正(2026-08-03,联网调研 + 复核 vanilla 源码之后)。**
>
> 下面 ① 说"Fork B 的包 vanilla 分支活着",**那个推荐是错的**,而错在我问错了问题:
> 我实测了 vanilla A* **跑不跑**(跑,~2ms),却从没查**它能表达什么**。
>
> `WalkNodeProcessor.findPathOptions` 只生成**四个正交邻居**(`:83-91`,逐行读过):
> `z+1` / `x-1` / `x+1` / `z-1`。**没有对角、没有跳跃、没有跨隙、不挖不搭。**
> `canBreakDoors` 只管门。`getMaxFallHeight()==3` 是怪物约束。
>
> **它建模的是一只会走路、会游泳、会开门的怪物,不是一个人。**
>
> 而这条证据一直躺在我自己的实测数字里:12 格斜向查询返回 **22 个节点**,
> 11+11=22 —— 正是纯正交走法的步数。我当时把它读成"格级航点",没意识到
> 节点数本身就在告诉我这件事。
>
> **前人全都自己写了寻路器,而且他们本来可以不写。**
> [Baritone](https://github.com/cabaletta/baritone) 跑在客户端同一个 JVM 里、
> 完全能直接调 vanilla,但它"uses A*, with some modifications" —— 分段计算、
> 增量代价回退、最小改进重传播 —— 并支持 **1/2/3 格跨隙跑跳、pillaring、
> sneak-back-place、梯子藤蔓、半砖楼梯**,还会**权衡挖穿 vs 绕路**(考虑手上的工具)。
> [mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder) 同样自己写,
> 建模 `digCost`/`placeCost`/`allow1by1towers`/`maxDropDown=4`。
>
> **所以"像人一样"这个目标本身就排除了包 vanilla。** 人会挖穿、会搭桥、会跳三格;
> vanilla 的邻居生成器里没有任何一条能表达这些决定,再快也没用。
> 3 格坠落上限不是"继承的一个限制",它是"这是给别的生物设计的"的症状。
>
> **vanilla A* 仍有一个真实用途,只是不是导航引擎:廉价的可达性预言机。**
> 它找到路 ⇒ 不改地形就能走到,这是有用的事实。它找不到 ⇒ 什么都没说明,
> 因为一个人可能挖三格就过去了。
>
> Fork B 的天平因此翻向**在 `LocalGrid` 上写局部转向 + 自有邻居生成器**,
> 而"可以教它挖穿或搭桥,那才是人的做法"这句原本写在代价栏里的话,现在是主要理由。

### ① vanilla A* 对客户端玩家可用(**但不适合当导航引擎,见上方更正**)

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

### ④b `world_view` 的真实体积 —— 比审计估的大一个量级

| radius | 字符 | 约 token | 往返 |
|---|---|---|---|
| 4 | 11,444 | 2.9k | 85ms(首次) |
| 8 | 39,844 | 10.0k | 16ms |
| **16** | **150,526** | **37.6k** | 54ms |

**一次 r=16 的观察就是 37.6k token。** 审计里"2 KB vs 38 KB"那个对比量的是别的东西。
延迟不是问题(54ms),**体积才是** —— 这单独就足以支持 §4 第 4 步(按类型过滤、返回坐标的查询)。

> **顺带修掉一个探针 bug,而 `live-dwm-probe.py` 也有。** 读取循环一看到缓冲里出现 `"id":2`
> 就 break,而 r=16 的回复是 **180,149 字节 / 4 个 recv 块** —— 于是截断成
> `unparseable reply: Unterminated string`。**"标记出现"不等于"消息到齐"**;
> 现在改成解析成功才算到齐。dwm 探针从没撞到只是因为它的 payload 一直够小。

### ④c 撞墙的速度信号:反驳是对的,而我一度搞错

前提两边都确认后实测:

| 状态 | 真值 | `world_view` 的 `vel` |
|---|---|---|
| 自由行走 | `dPos=(0,0.109)` `collidedH=false` | `[0.0,-0.02,0.09]` |
| 撞墙卡住 | `dPos=(0,0)` `collidedH=true` | `[0.0,-0.02,0.0]` |

**`vel` 能区分**,Z 分量归零。所以那条"卡住信号已经接好了"的反驳**成立**。

> **我第一次测这条时得出了相反结论,而错法正是本仓库反复记录的那一条:采样前没确认前提。**
> 我标成"自由行走"的那组,玩家其实卡在别的东西上(`collidedH=true`、位置冻结),
> 于是两行读数当然一样。**先 `item_box` / 先确认前提**,这条教训在 dwm 侧写过三遍,我又犯一次。

保留的限制不是"没有信号",而是**信号只在观察路径上**:`vel` 来自 `WorldViewCapture.java:94`,
而 act 包里的 controller 拿不到它;`ActActuator` 也没有 `isCollidedHorizontally`
—— 后者比速度更干净(它就是布尔值,不用比阈值)。

### ④d 吃东西:两层缺陷,其中一层是我们自己的 bug

Unknown 7 确认,但真因比文档写的深一层。前提完整成立(生存模式、`canEat=true`、面包在手、饥饿 6/20):

```
act_set interact{kind:use}  ->  phase=FAILED  ticksActive=1  message="use rejected in air"
食物 6 未变 · useCount=0 · itemInUse=null · 面包 5 个没少
```

**第一层是我们的 bug。** `LivePlayerActuator.useItemInAir()`(`:189-201`)直接返回
`PlayerControllerMP.sendUseItem` 的布尔值。而对食物,vanilla 的 `onItemRightClick`
返回**同一个 stack**,所以 `sendUseItem` 返回 **false —— 尽管它已经成功启动了使用**。实测直接调它:

```
call0 sendUseItem=false  useCount=32  itemInUse=yes
```

`useCount=32` 就是面包的 `getMaxItemUseDuration()`。**使用开始了,而 `InteractController`
把这个 false 读成"拒绝"并报 `use rejected in air`。** 一个成功的开始被误报成失败,
而错误消息还把人指向错误的方向(以为是"对空使用不被允许")。

**第二层是那个陷阱,确认触发。** 手动 `sendUseItem` 让 `useCount=32` 之后,不再维持按键:
约 8 tick 内 `useCount` 掉到 0、`itemInUse=null`、食物不变。
出处核对过:`Minecraft.java:2117-2121` ——
`if (thePlayer.isUsingItem()) { if (!keyBindUseItem.isKeyDown()) { onStoppedUsingItem(...) } }`。

所以 §4 第 6 步(保持通道)是**必需的**,而且它前面还有一个更便宜的修复:
**先让 `useItemInAir` 不要把成功当失败。**

### ④e 真机验证的环境约束:窗口失焦 = 世界停摆

`[Server thread/INFO]: Saving and pausing game...` —— **vanilla 单人在窗口失焦时自动暂停。**
从脚本起的客户端窗口从不获得焦点,所以过了初始宽限期后世界时间冻住
(实测冻在 `T 185140`,而 `serverRunning=true`)。

- `mc.isGamePaused()=true` 且 **`Display.isActive()=false` 是驱动因素**
- 把 `currentScreen` 设 null **没用** —— vanilla 失焦时会重新打开 `GuiIngameMenu`
- 游戏线程仍在服务任务,所以 `eval_java` 照常工作 —— **只有世界推进停了**
- 聚焦窗口后立刻恢复 ~21 tick/秒

**任何依赖 tick 的真机探针必须先确保窗口有焦点**,否则它读到的是冻结状态而且不会报错。
dwm 探针从没撞到,因为它自己用 `surface.frame(...)` 推帧,不靠游戏的 tick 循环。

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
   ~~**最便宜:** 约 30 行的 `NavPathLiveIT`,照 `DigLiveIT.java:23-27` 的 skip-gate 写~~
   **别这么做(2026-08-04)。** 那个 skip-gate 已废除(见 §1 的订正横幅),而更根本的是
   `GameAccess` 读的静态单例在 surefire 的 JVM 里恒为 null,所以这样的 IT **只可能 skip
   或 FAIL,永远探不到东西**。上一轮真按这个建议写了一个 `NavPathLiveIT`,它的 surefire
   报告里记着 `mcp.it.live=true` 与两个 skip 并存 —— 那份报告就是这条建议的成本,已删。
   **改走 MCP socket + `eval_java`**:`new ChunkCache(mc.theWorld, from, to, 0)` +
   `new PathFinder(new WalkNodeProcessor())`,断言
   `createEntityPathTo(cache, mc.thePlayer, target, 32f)` 非 null,记节点数与墙钟时间。
   `scripts/nav-astar-probe.py` 已经这么做并**定掉了 Fork B**(见 §5.5)。
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
6. ~~**真实 1.8.9 服务端会拒绝过期的 `actionNumber` 吗(`do_click_slot`)?**~~
   **问错了问题(2026-08-04,读 vanilla 源码逐行确认)。** 服务端在点击路径上**从不校验
   `actionNumber`** —— 它只在 `NetHandlerPlayServer:1031/:1040` 回传它、在 `:1039` 把它存成键。
   接受与否完全取决于 `:1029` 的 `areItemStacksEqual(packet.clickedItem, 服务端自己 slotClick
   的返回)`。所以**过期的号本身无害,而 item 声明不符会锁窗**:`:1041` 置 `setCanCraft(false)`,
   而 `:1012` 把整个点击体门在 `windowId 匹配 && getCanCraft` 上,于是之后每次点击在 `:1012`
   就被挡掉、**连 S32 都不发**,直到客户端用 C0F 把同一个号回echo(`:1144` 是唯一校验 uid 的地方)。
   描述已按此重写(`673ec9b`)。**仍未在真服上观测过**,非 vanilla 服务端(Spigot/Paper/代理)未查。
   原文保留,因为"问错问题"这个形状比答案更值得记 —— 与 Fork B 那次同类(§5.5 顶部)。
7. **吃/拉弓的自动释放陷阱真的会触发吗?** `Minecraft.java:2118-2123` 只做了括号核对,没观测。
   最便宜:手持食物 → `act_set interact{kind:use}` → 40 tick 内轮询饱食度。不涨则第 6 步确认必要。

   > **部分答了,而括号核对本身漏了一层(2026-08-04)。** 陷阱是真的 —— 上一轮实测手动
   > `sendUseItem` 后不维持按键,约 8 tick 内 `useCount` 归零、食物不变(§5.5 ④d),
   > 所以第 6 步(保持通道)确认必要,已实现为 `HoldController` + `act_set interact{kind:"hold"}`
   > (`b94a7d6`/`612b661`)。
   >
   > **但那次括号核对得出的"任何一 tick 键未按下就调 onStoppedUsingItem"是不完整的。**
   > `2118` 整块在 `Minecraft.java:1829` 的 `currentScreen == null || currentScreen.allowUserInput`
   > 里(花括号深度实测,块在 `:2164` 结束),而 `allowUserInput` 是默认 false 的裸字段,
   > 全仓只有 `GuiInventory` 与 `GuiContainerCreative` 设 true。**所以开着聊天框/暂停菜单/
   > 箱子/熔炉时 vanilla 根本不结束使用** —— 清掉按键的那个屏幕同时把结束使用的代码门掉了,
   > 饭继续吃、弓继续拉,屏幕一关就射出去。会话原文不进 git;生产行为以
   > `scripts/live-hold-probe.py` 为准。
   >
   > **教训**:核对括号能证明一行在哪个块里,不能证明那个块什么时候进得去。**要连外层
   > guard 一起读到方法入口。**
   >
   > **全答了(2026-08-04 晚,真机)。** 上面这一整块写于静态阶段。之后 owner 授权起了客户端,
   > `scripts/live-hold-probe.py` 两次可复现 7/7(`bfb12c3`),这一项的每一半都有观测了:
   > 吃真的完成且饱食度上升(`food 6->11`)、**恰好消耗一个不是两个**(`stack 4->3`)、
   > 弓在松手时发射(`arrows 32->31`、`bowDmg 0->1`)、
   > **而"开屏幕清键但不结束使用"从读源码变成了观测**(`usingAtOpen=true countAtOpen=32`
   > → `stillUsing=true count=29`)。那一条是这里最要紧的,因为它此前只是推理。
   >
   > 那轮的三个失败**全部是探针自己的错**,形状记在 `debugging.md` §10,其中一条值得在这里
   > 点名:第一版在**一次** game-thread 提交里连调 controller 120 次,而
   > `getTotalWorldTime()` 在一次提交内不推进 —— 120 次全落进同一个游戏 tick,计数不减、
   > 服务器不答,controller 于是诚实报告"使用没在推进"。**那份诚实的报告看起来像被测代码的
   > 失败。** 真机验证要走生产路径(`act_set` + `act_status`),让 tick 循环自己推。
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
