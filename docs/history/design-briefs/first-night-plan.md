# 第一夜任务分解（北极星的形状）

本文是「活过第一个夜晚」的阶段计划。来源：`CrazyBadger` 侦察（对照 `client/src` 反编译源码逐条核实，
带 file:line），2026-09-30。

**每个阶段的 `doneWhen` 都必须是世界中可观察的事实，不是 agent 的自述。** 这条是硬要求：本项目已经
两次因为"工具说成功"而误判（`act_set move` 永远 ACTIVE 而玩家没动；`do_dig` 回执 "sent" 而方块没断）。
agent 的自述永不算证据。

---

## 阶段表

| # | 阶段 | 状态 | 关键判据 |
|---|---|---|---|
| P0 | 武装 tick seam，证明驱动权 | 可跑 | `act_status.tickNow` 在两次相隔 1s 的调用间**严格递增** |
| P1 | 情境快照与威胁时钟 | 可跑 | 维度/生物群系已知；`worldTime % 24000` 可算到夜 13000 |
| P2 | 选一个站得住的工地 | 可跑 | 3x3 足迹无 lava、无 >3 格落差；`find_block` 确认有可徒手挖的填充块 |
| P3 | 取原材料与食物 | 可跑 | 背包 ≥16 个填充块；每次挖掘都由**世界重读**确认方格已变 |
| P4 | **把原材料变成可用物品** | **今日解除** | 2x2 栏现可达（`press_key_binding`） |
| P5 | 先墙后顶，搭一个带顶盒子 | 可跑 | 每个顶格在重读的网格里是实心；自身 y 未被顶起；hp 未掉 |
| P6 | 封口并自证防刷怪 | 可跑 | 自身格与四邻的顶是实心；光照 >7 即**不算**成功 |
| P7 | 守夜 | 可跑 | `worldTime % 24000` 越过 23000 且 hp > 0；整夜 hp 从未低于 18 |
| P8 | 进食保住饥饿值 | 可跑 | 之后的 `world_view` 里 `self.food` 严格上升 |
| P9 | 床上跳过夜晚 | **仍然不可达** | 需要世界里已有床；正常开局造不出来 |
| P10 | 天亮后确认并撤离 | 可跑 | 天亮 + 人在盒外 + **盒子还立着**（重读） |

---

## 几个被反复强调的判据（都是踩过的坑）

**1.8.9 的刷怪条件**：`EntityMob.java:147-177`——合成光照必须 ≤ `rand.nextInt(8)`（即 ≤7），
下方有实心顶面、且上方有 2 格空气，且距每个玩家 >24 格。所以**任何不透明的顶 + 一个洞就同时是
"拒绝刷怪"和"寻路死路"**。光照 >7 的地方**感觉上被围住了也不算成功**——这是典型的假完成。

**挖掘时间**：`Block.java:590-594` 的 `getPlayerRelativeBlockHardness`，`PlayerControllerMP.java:312`
每 tick 累加，所以 `ticks = 30*hardness/speed`（正确工具）或 `100*hardness/speed`（错误工具）。
硬度：泥土 .5、草方块 .6、石 .1.5、圆石 2.0、木板 2.0、原木 2.0（`BlockLog.java:21`）、树叶 .2
（`BlockLeaves.java:32`）。**徒手挖石头/圆石不掉任何东西且要 7.5-10 秒，永远不划算。**

**采矿距离**是 4.5 格（眼睛到方块中心）。够不着就靠近；**把报告的失败当答案，不要拿数字当答案**。

**进食**：`interact kind 'use'` **吃不了**——那是一次右键，vanilla 两 tick 后取消。只有
`kind 'hold'` 能持续使用。而且**失焦的客户端（脚本控制下的常态）里，hold 不会因为开屏幕而清除**，
所以一个箱子或暂停菜单会**把世界按停**，进食直接冻住。回复再生需要 `food >= 18`。

**睡觉的四道门**（`EntityPlayer.java:1494-1522` + `BlockBed.java:35-99`）：地表世界、非白天、
3/2/3 距离内、床周围 8x5x8 内无 `EntityMob`。**在床上下地狱会炸**（`BlockBed.java:95`，强度 5.0）。
而且 `act_status` 报 COMPLETE **不是**睡着的证据——客户端 `BlockBed.onBlockActivated` 无条件返回 true，
真正的判定在服务端。

**diff 模式的三种谎言**：未采样的 section 报 `unsampled`（读作"再问一次"，不是"没变化"）；
实体列表被截断会带 `capped:true`；`entities.left` 可能是驱逐而不是死亡。**相信威胁消失之前，
用同样的 profile 和半径重读一次。**

**`move.to` 从不引导 y**："arrived" 只意味着水平距离闭合。从 2 格高的盒子出来后，人比进来的地面高 1 格，
**要重读 `self.y`**。我们没有自动跳，唯一的上台阶原语是 `jump+forward` 加有限 `durationTicks`。

**雷暴**：`EntityMob.isValidLightLevel` 在打雷时把 `skylightSubtracted` 强制为 10，地面天光掉到 5，
**刷怪比晴夜自由得多**。夜里下雷是压力更大，不是更小。

**沼泽不可建**：史莱姆死亡分裂成 2-4 个小的，会啃穿一夜。

---

## P4 为什么曾经不可达，以及现在为什么可达

`CrazyBadger` 的判定是正确的：`craft_plan` 按其自身描述是只读的（"This tool plans; it does not act"），
而**没有任何工具能打开玩家自己的 2x2 合成栏**——`gui_press_key` 只映射 Escape/Return/Tab/Backspace
和单字符（keyCode 0），驱动的是 `GuiScreen.keyTyped`，而背包键是在 `Minecraft.runTick` 里读的
`KeyBinding`。

`log -> planks` 是 1x1 配方，**合法地放得下 2x2 栏**，所以失去 2x2 栏就同时失去
木板 → 木棍 → 工作台 → 火把 → 木镐 → 床。

**2026-09-30 已解除**：新增 `press_key_binding`，它发出 vanilla 在 `Minecraft.java:1899-1907`
消费真实按键时的**同样两个调用**（`KeyBinding.setKeyBindState`，按下时再 `KeyBinding.onTick`），
在游戏线程上。活机验证：`press E` → 屏幕变 `GuiInventory`，45 个元素。

**P9 仍然不可达**：床需要 3 羊毛 + 3 木板，羊毛要剪刀（没有）或大量手剪线。只能靠世界里本来就有床。

---

## 与 harness 的对应

`first_night.py` 目前实现的是：`ensure_running`（时钟闸门）、`arm`、`survey`、`inventory`
（开背包 + 点击合成）、`craft_one`、`dig_shelter`（挖掘 + 封顶）、`wait_for_dawn`。

**它每次都会在某个阶段被 `blocked` 记录下来，而不是假装成功** —— 这是它存在的理由。
第一局（`night-03`）的报告里，`dig` 阶段被标 blocked 而其余照常推进，读者能一眼看出它死在哪。

尚未实现的阶段（按 harness 的推进顺序）：P2 选点、P5 先墙后顶的建造顺序、P8 进食、P10 撤离与自证。


---

## 实跑记录：第一次达成 `dawn`（`night-04`，2026-09-30）

```
outcome=dawn
steps: 11 全部 confirmed-in-world，0 refused，0 sent-unconfirmable
dawn {"time": 23071, "hp": 20.0, "food": 20}
```

11 步全部由**独立世界采样**确认，没有一步是"工具说成功"。

### 但它证明了什么、没证明什么

**没做到的事（记录里写着，不因为结局是 dawn 就消失）**：

1. **庇护所没建成。** `dig` 阶段被标 blocked：挖到 y=63.536，我要求 63.0，差半格就判短。
   挖掘本身是**成功的**（三次 `dig_result`：66.0 → 65.536 → 64.536 → 63.766），是判据太死。
2. **背包全程没开。** inventory 阶段**根本没进 harness 文件**——补丁脚本的 `str.replace`
   没匹配上，却照样打印"已添加"。我又一次没验证补丁是否真的落地。
3. **因此没有合成、没有火把、没有箱子。** 玩家是**站在露天**撑过整夜的。
4. **它没有证明"一局可通"。** 它证明的是：时钟闸门、挖掘驱动、守夜循环和结局判定是对的，
   而**一个什么都没做的玩家可以在这个存档里活过第一夜**（可能与地图种子有关）。

**这个"活过"很可能是偶然的**：`light: 15` 的检查点都在日间（time 12046 < 夜 13000），
夜里没有记录光照；也可能是这片地图的刷怪点离出生点远。所以**不能拿它当北极星达成**。

### 下一步（按此记录，不再靠猜）

- 修 harness：判据按"挖到目标或差 1 格内"而不是死差 0.5；inventory 阶段**写进去并验证存在**；
  夜间检查点补记 `light`，这样"露天撑夜"与"有庇护所撑夜"能被区分。
- 加 P8 进食（当前 food 恒为 20，是因为这个存档里没有消耗）。
- 换一个已知会刷怪的位置（树林平原实测刷怪），否则"撑过夜"永远测不出真东西。


---

## `night-05`：又抓到两个假成功（2026-09-30）

`outcome=dawn`，但这一局**在 13.4 秒就结束了**。13 秒不可能是一夜——记录本身抓到了它。

### 假成功一：`t >= DAWN` 用了原始世界时间

```
survive_start {"time": 31835}
dawn          {"time": 31836}      ← 相隔 1 tick 就宣布天亮
```

`worldTime` 是**原始值，会一直累加超过 24000**。所以 `t >= 23000` 在时钟越过 23000 之后**永远为真**，
起点落在 31835 的局第一帧就判成功。

**这和 map 形状的 `act_set` 是同一种失败**：一个会报成功的工具。已修为 `t % DAY_LENGTH >= DAWN`，
并核验全仓只有这一处原始时间比较：

```
raw=  31835  day_time=7835   dawn? False    ← 修复前是 True
raw=  48355  day_time= 355   dawn? False
raw=  23071  day_time=23071  dawn? True     ← 真正的黎明
```

教训：**凡是与世界时间有关的比较，都要问"这是日内相对值还是绝对值"。**

### 假成功二：光照探针恒为 15

上一轮我说"夜间检查点的 light 都是 15"，那是**探针错了**，不是世界错了：
`chunk.getLightFor(SKY, pos)` 是 chunk 里存的**原始天光**，只要能看到天空就是 15；昼夜变暗是之后
由 `getLightSubtracted` 扣减天体角度才生效的。

**而合成光照 ≤7 正是刷怪闸门**（`EntityMob.java:147-177`）。一个恒报 15 的探针等于永远告诉 agent
"这里安全"。已修为 `getLightSubtracted`，实测 `light: 0`。

**两个假成功都是"我自己的测量工具在骗我"，而两次都是记录先发现的。** 这就是这套审计产物存在的意义。

### 这一局的真实收获

- `bindingClaimed=true` —— 反射字段名从 `keybinds` 改成 `keybindArray` 之后修好了。
- **背包打开了**（`screen: GuiInventory`），inventory 阶段这次真的在文件里（十项逐条验证过）。
- **挖掘被石头挡住**：`600ms later (233,62,259) still reads stone`。这正是分解里 P3 写的
  "徒手挖石头不掉任何东西，且要 7.5-10 秒"。玩家上一局挖到了 y=63，下面就是石头。
- 没有原木，所以 `craft_one` 如实 blocked——**它没有假装合成了木板**。

**下一步不是继续刷局**：这个存档的出生点下面就是石头，且第一夜没有任何敌对生物接近。
要先解决"选一个脚下是泥土/树叶、且真的会刷怪的位置"，否则再多局也只是在测一个空转的循环。


---

## 重大更正：`confirmed-in-world` 曾经是空的（PreliminaryGoose 独立审出）

**我此前向 owner 报告的"11 步全部 confirmed-in-world"、"6 步全部 confirmed"是错的，现在撤回。**

独立审查（PreliminaryGoose）指出 `night-05` 的 step 13「hold dig」是靠
`delta {"time": 1}` 判定的。我复核了 `night-06` 的**全部 6 步**：

```
step 1 eval_java           confirmed-in-world   delta={"time": 3}   <-- 只有时钟
step 2 world_view          confirmed-in-world   delta={"time": 2}   <-- 只有时钟
step 3 press_key_binding   confirmed-in-world   delta={"time": 2}   <-- 只有时钟
step 4 press_key_binding   confirmed-in-world   delta={"time": 2}   <-- 只有时钟
step 5 do_close_container  confirmed-in-world   delta={"time": 2}   <-- 只有时钟
step 6 find_block          confirmed-in-world   delta={"time": 2}   <-- 只有时钟
```

**6/6。** 根因：`world_delta()` 把 `time` 算成"变化"，而世界时钟每 tick 都走，
所以**每一步都会被判成功**。这与 map 形状 `act_set` 报 ACTIVE 而玩家不动、
`do_dig` 回执 sent 而方块没断，**是同一种失败第三次出现在我自己的代码里**。

### 修法：判定必须与意图相关

1. **`time` 永远不是证人。** 证人必须能保持不动。
2. **按工具名指定证人字段**：移动/视角 → `x,y,z,yaw,pitch,onGround`；
   挖掘/放置/攻击 → `y,x,z,onGround,yaw,hp`；界面类 → `screen`。
3. **观测类工具新增 `observed` 判定**：`world_view`/`find_block`/`eval_java` 不改世界，
   它们**改什么世界都是错的**；它们的证据就是自己逐字记录的返回值。
   之前把它们叫 confirmed 或 sent-unconfirmable 都不对。
4. **探针新增 `screen` 采样**，实测 `none → GuiInventory`：
   ```json
   {"screen": "none"}  ->  press E  ->  {"screen": "GuiInventory"}
   ```
   于是"打开背包"第一次有了真证人，而不是靠时钟蒙混过关。

### 同一个审查还查出的其他问题（尚未全部修）

- **哈希链只被计算，从未被验证**——没有任何东西会重算它并比对。
- `_scratch/smoke/_patch.py` 是遗留的一次性补丁脚本，仍可运行；重跑它会插入第二个
  `ai_summary()` 覆盖现有的。
- `smoke_tools.call_raw` 对 JSON-RPC 层错误返回 `(None, [])`，**丢掉了错误文本**，
  于是记录里写的是 "no reply" 而真正原因消失。
- 步骤回复被截断到 4000 字符（`night-04` step 2 就是）。
- `night-05/SUMMARY.md` 的标题 "Outcome: dawn | survived to_dawn: true" 属于 13.4 秒的一局。
- `night-03/SUMMARY.md` 仍带那句已过时的"2x2 合成栏不可达"散文（生成器已改为派生，
  但旧产物没重渲）。


---

## 三个"读代码看不出来"的测量缺陷（2026-09-30 晚）

这三个都不是逻辑写错，而是**量错了东西**。它们的共同点：返回值都是数字，看起来完全合理。

### 1. 光照探针错了三次才对

`Chunk.getLightSubtracted(pos, amount)` 的正常分支是：

```java
l = storedSkylight - amount;
l = max(l, storedBlockLight);
return l;
```

**存储天光已经是传播过的**，它考虑了头顶。所以 `amount` 既不是遮光量也不是天光——
要拿这个值本身，就该传 **0**。

走过的两个错法：

| 传法 | 结果 | 症状 |
|---|---|---|
| 原始天光 15 | `15-15 = 0` | **大白天草地上报 0** |
| 方块遮光量（玩家站在空气里 → 0） | `天光-0` | **地下 7 格仍报 15** |

两者合起来，刷怪闸门（合成光照 ≤7）上的读数**正好是事实的反面**。

**地面真相验证**：草地正午 **15**；在头顶放方块封顶后 **0**。
中间还有一次误判：挖了 7 格到 y=64 仍是 15，我以为还错——**其实 1 格宽竖井本就把天光传到井底**，
是测试不对，不是探针不对。

### 2. `int()` 而不是 `floor()` 让挖掘打偏一列

`int(-123.5)` 是 `-123`，但 -123.5 所在的方块是 **-124**。正坐标下两者恰好相同，
所以**之前每一局都正常**，直到新世界的出生点 X 是负数。

症状极具误导性：工具报"(-123,69,242) 已是空气，世界确认"——它**诚实报告了自己挖的那一列**，
而 harness 早就瞄错了列。两个各自正确的读数，合成一个错误的动作。

### 3. 探针静默死掉

重写 `recorder.py` 文件头时删掉了 `from smoke_tools import call_raw`，而探针的调用
位于 `except Exception: return None` 之内——`NameError` 被吞掉，从那以后探针**一直返回空**。

**一个静默失败的诊断，看起来就像一次测量。** 现在 6/6 可靠，且导入处写了注释，
防止下一次文件头重写重演。

> 这三条的共同教训：**量错的东西不会自己暴露。** 它们返回数字，数字没有错误标记。
> 只有拿地面真相（截图、方块直读、世界确认）去对，才能发现。


---

## 折叠 debug_* 时查出的越权(2026-09-30,P3)

ADR-0004 决定把 11 个 `debug_*` 按 ring 折成两簇。实施并跑全量测试后,
`L6DebugGateThroughRegistryTest` 报红,查出**折叠本身关掉了一道授权检查**。

### 发生了什么

同质性论证覆盖了 L2–L5,**漏了 L6**。`ObManager.checkRequest` 按 `req.toolName()` 查
`HANDLE_OPS` 表。折叠后 `toolName()` 是 `debug_manage`,**该名字不在表里**,于是:

| 折叠后 | 后果 |
| --- | --- |
| `strictHandles` 下无句柄的 handle-op | **不再被拒**——硬化姿态的 TOCTOU 防护静默失效 |
| 带句柄的调用 | 落到 `getOrDefault(name, READ.bit())` 默认值 |
| `debug_suspend_thread`(需 EXECUTE) | 被一个**只读句柄**放行 |

两条同源:**折叠改变的不只是 manifest,还有"工具名"的含义**,而 L6 是按工具名索引的。

### 修法

`checkRequest` 解析 `arguments().action` 得到真实操作名,工具名退回为未折叠调用者的兜底。
表本身一个字没改。未知 action **拒绝**,不降级为 READ——落到最弱掩码必须不可达。

变异验证:忽略 `action`(即折叠原本造成的状态)杀 3 条;把未知 action 降级为放行杀 1 条
——第二条一开始**存活**,补测试后才杀掉,补的测试还必须同时覆盖带句柄与不带句柄两条分支。

### 教训

> ADR-0004 自己写过:侧表是安全边界,任何"这些工具权限相同"的断言都要回表逐行核对。
> **光核对那三张声明性侧表还不够,还要问"谁在按名字索引别的表"。**
> `HANDLE_OPS` 是行为性的,不在原检查清单里,而它恰好是唯一一张会被折叠**静默破坏**的——
> 没有报错,没有告警,只有一个 READ 掩码的默认值。

### 另一项真实代价:schema 发现能力

折叠后 manifest 只剩 `{action}`,agent 读不到 `debug_read_local` 接受可选 `handle`。
已把各 action 的必填参数与 `handle` 语义写进折叠工具的 `description`,
但**信息从结构化 schema 降级成了散文**——这是真实损失,记下来而不是假装没有。


---

## 根因:我们一直在把玩家挖进逃不出来的坑(2026-09-30 收尾)

把整轮串起来看,几乎每一次"某工具说它接受了但什么也没发生",最后都落到同一件事。

### 证据链

`act_status` 说的是 `stuck against a wall for 24 ticks, moved 0.00 blocks`——**工具是诚实的**。
玩家卡在 `(78,70,252)` 的 1 格竖井里,朝南走就是井壁。跳跃只能上约 1.25 格,
**1 格宽、3 格深的竖井没有出口**,而那正是 `dig_shelter` 一直在挖的形状。

于是链条是这样断的:

| 环节 | 表面症状 | 真实原因 |
| --- | --- | --- |
| `gather_wood` 说"没找到原木" | 找得到,`find_block` 回的是纯文本 | 读格式假设错 |
| `craft_one` 说"背包里没有原木" | 背包写着 `Oak Wood` | 显示名不是 `log` |
| `act_set move` 报 accepted 却不动 | "stuck against a wall" | 玩家在坑里 |
| 挖了石头挖不动 | y=59 是石头 | 真实约束(需木镐) |
| 挖不到原木掉落物 | 掉落物在 4.7 格外 | 走不过去,因为在坑里 |

### 已修

- `dig_shelter` 改为 **2×2 口袋**,其中一列只挖 1 格深,留出跳出的台阶;
- `collect_drop`:走过去 + 轮询背包,并把"挖掉了"与"捡到了"当成**两件事**分别记录;
- `item_matches`:按 1.8.9 **显示名**匹配(`Oak Wood` 而不是 `log`);
- `slot_id`:gui_snapshot 给 `"s36"`,`do_click_slot` 要整数 `36`;
- `place_cursor`:产物落在**光标**上,关窗会把它**丢在地上**,必须放进空格。

> 连续三次的教训是同一个:**一个不崩溃、只返回合理值的东西,比崩溃更容易骗人。**
> 探针被 `except` 吞掉时像一次测量;`accepted: true` 配 0 格位移时像一个动作;
> `craft_one -> True` 而背包里什么都没多时像一个产物。
> 每一处都要**把两个事件分开记账**才算数。

### 仍未验证

新世界上的端到端一局(2×2 庇护所 → 捡到原木 → 合成木板 → 木板→木棍→工作台→木镐→挖石头)。
在拿到那一局的记录之前,上面的修复都是 `[UNVERIFIED]`。


---

## `do_place_block`:根因是服务端与客户端的快捷栏槽位不同步(2026-09-30 终局)

### 结论

**放置是能用的。** 之前"一直不放置"的真正原因是:

```java
// NetHandlerPlayServer.processPlayerBlockPlacement:587
ItemStack itemstack = this.playerEntity.inventory.getCurrentItem();
```

服务端从**自己**的快捷栏选中槽位取方块,**从不读 C08 包里带的栈**。活机上找到的状态是:

```
client curItem=0 held=Dirtx1  ||  server curItem=8 held=(EMPTY)
```

于是每个放置包都到达了服务端、`hasMoved` 通过、距离 3.25 < 64 通过、保护/边界/gameType 通过、
`canPlayerEdit` 通过、`canBlockBePlaced` 通过(**直接在服务端世界调用它,返回 true**),
然后 `activateBlockOrUseItem` 拿到 `stack == null`,**静默 return false**。

同步槽位之后立刻成功:

```
sent place_block, and (15,71,251) is now dirt
client cell=tile.dirt | server cell=tile.dirt
```

### 我在这一格上犯的三个错

1. **把"工具说空手"当成谎报。** 那是 `resolveStack(null)` 的如实描述,但那句
   "empty-handed ... changes nothing by design" 在失同步时**是错的**。
2. **把包里的栈放进 C08 当成修复。** 服务端根本不读它,那处改动行为惰性,已撤回。
3. **把诊断写在错误的分支上。** 真正会走的是 `wanted == null` 那条,不是我改的拒绝分支。

### 修掉的是诊断,不只是症状

`do_place_block` 现在自己读服务端槽位并指名真因,活机验证:

> The SERVER's selected hotbar slot is empty (slot 0 holds nothing), and the server places from
> THAT slot, not from the client's: this placement could not have happened. Select a hotbar slot
> with do_select_slot and re-read the server's held item before retrying -- **chasing reach or
> aim is wasted effort while the server holds nothing.**

此前它说的是 `out of reach (vanilla's 64)`——**这句是真的,而且完全无用**。

> 这和 harness 自己犯的错是同一种:**一个只是"附近为真"的阻塞理由,把寻找引向错误的方向。**
> 只不过这一次它住在工具里,而我花了很久才发现我一直在追一个不存在的距离问题。

### 顺带暴露的两件事

- **世界本身可能是不可用的。** 连续几局测不出任何东西,因为出生点是海��或一个坑,
  每次都是事后手工才发现。已加 `fresh_world.py` 的起点条件检查(32 格内有树、
  玩家所在层有可建方块)与 `usable_world.py` 自动重摇。
- **采集方块必须遵守"不挖低于自己所在层"。** 手工尝试几乎每次都是挖脚下,
  掉落物掉进自己刚挖的坑,然后人就站在出不来的柱子上。已固化为 `gather_blocks` 的规则,
  并在找不到时**如实说明为什么不去挖下面**。


---

## 第三次同型错误:数了会增长的量,而不是意图所在的那个量(2026-09-30)

`gather_blocks` 报告 **`collected: 6`,背包是空的**。

掉落物实测在 `Dirt@3.00` … `Dirt@3.60`——**全都超出约 1 格的拾取半径**。
六次挖掘全部在世界侧确认(`was dirt → now air`),一次也没捡到。

而 `collected` 数的是**挖掘次数**。挖掘是可靠增长的:挖下去,世界就变。
所以这个计数器**永远显示进展**,哪怕进展和意图无关。

这是本轮第三次同一个形状:

| 次数 | 统计的量 | 意图指向的量 |
| --- | --- | --- |
| 1 | `time`(时钟每 tick 都走) | 世界是否按意图改变 |
| 2 | `dig` 的成功回复 | 那一方块是否真的离开世界 |
| 3 | **挖掘次数** | **背包里真的多了东西吗** |

> **一个可靠增长的量,是最容易被当成进展的量。**
> 三次里两次的量是工具自己的回复或时钟,一次是动作次数——
> 全部都是"发出去了/动了",没有一个是"成了"。

已改:每次挖掘后读背包,`collected` 只按**背包增量**增加,
并在不够数时如实阻塞:

> harvested 1 block(s), wanted 4: the digs were confirmed in the world but the drops did not
> reach the inventory (out of pickup range, or spawned where the player cannot stand).
> **Digging is not collecting**, and this run reports what was collected


---

## 本轮的方法论:一个可靠增长的量,最容易被当成进展(2026-09-30 收束)

这一整轮反复撞上同一个形状,值得单独记下来。

### 五次同型错误

| # | 统计/检查的量 | 意图指向的量 | 为什么看不出来 |
| --- | --- | --- | --- |
| 1 | `time`(时钟每 tick 都走) | 世界是否按意图改变 | 时钟**总是**在动 |
| 2 | `do_dig` 的成功回复 | 那一方块是否真的离开世界 | 工具**如实**报告了它发出的包 |
| 3 | 放置的"已发送" | 方块是否出现 | 发送**总是**成功 |
| 4 | 挖掘次数 | 背包里是否真的多了东西 | 挖下去世界**总是**会变 |
| 5 | `y` 是否升高 | 是否离开了那个 cell | 同层横着走,y **本该**不变 |

共同点:**被检查的量都会稳定地朝"成功"的方向增长**,所以它在失败时依然显示进展。
第 5 次最隐蔽——脱困是从同层的竖井**横着**走出来的,而 `climb_out` 用 y 判定,
于是三面墙都挖通了、每面都世界确认,函数仍然报"没上升"。

### 三次"诚实但无用"的拒绝理由

- `do_place_block`:「out of reach (vanilla's 64)」——真的,放置距离不到 2 格。
- `do_place_block`:「an empty-handed click, which changes nothing **by design**」——真的,
  它发的确实是空栈包;错的是**它发的那个包**,不是这句话。
- `canBlockBePlaced` 返回 true 时距离、保护区、槽位全部通过——每一道闸门单独看都通过,
  真因是**服务端读的是它自己的选中槽位**,而那一格是空的。

> 三次都在**工具的输出**里,而不在我的代码里。工具没有撒谎;是我把它的诚实当成了答案。

### 两次静默的空读

`HOLD_NAMES` 和 `NEIGHBOURHOOD` 都用 `List<String>.toString()` 拼 JSON,
产出 `[Dirtx12]` / `[84,61,260,air]` ——**不是合法 JSON**。`json.loads` 抛异常,
被 `except` 吞掉,返回 `{}`,而 `{}` 被读成"什么都没带"。
于是采集计数器报 **0 collected,玩家实际拿着 12 块土**。

修法不只是加引号:**读不出来时必须返回 `{'readable': False}`**。
一个读不出的测量,伪装成一个否定答案,比崩溃更贵。

### 真正管用的三招

1. **量结果,别量动作。** 背包增量 > 挖掘次数;离开原 cell > y 升高;`is now dirt` > `sent`。
2. **让被测对象自己回答。** `do_place_block` 现在自己读服务端槽位并指名真因——
   这是把一次几个小时的追查压缩成一条消息。
3. **每次怀疑先问:这个数在失败时会不会也显示成功?** 会,就换一个数。


---

## 门洞必须是两格:一格洞正好是脚的尺寸(2026-09-30)

脱困流程:挖身旁的墙 → **水平**朝向 → 按住 `forward`。

第一次试,四面墙**一格一格全部挖通**,每一格都世界确认 `was stone → now air`,
而 `forward` **一次都没动过**。

原因不是移动,是几何:**玩家高 2 格**。在墙上挖一个洞,那个洞正好是玩家**脚**的尺寸——
头顶那一格仍然是石头,身体过不去。

改成 `open_two_cells`:挖 (x,y,z) **和** (x,y+1,z),再走。
立刻生效:`origin (-92,54,316) → now (-91,54,316)`,`verdict: escaped`。

> 这和"把时钟当证人""数挖掘次数""用 y 判横向脱困"是同一个家族的问题,
> 但这次不是**检查**错了,是**动作**错了:我们一直在挖一格的门,
> 而一格的门在物理上就不存在。

## 采集把玩家送下 8 格

`gather_blocks` 的"回落到下层"原本对深度不设限,而 `find_block` 的 radius
**只约束水平方向**。于是它为了捡一块方块,把玩家从 y=63 路由到了 y=55。

用一个可解的问题换成一个不可解的。现在回落到下层严格限制 1 格,
更深的直接跳过并写明原因:

> `gather_blocks_skip` -- more than one below; **the climb back does not work**


---

## 阶段顺序错了:先挖的东西,后面全在还账(2026-09-30)

这一整轮的几何故障——规划器在一步移动上耗尽 20000 状态、
`every reachable stance was explored`、掉落物掉进够不着的坑——
**全部追溯到同一个动作**:在备好材料之前就开始挖。

### 为什么挖掘是那个不可逆的动作

挖掘**只能移除,永远放不回去**。而 agent **没有办法爬出自己挖的坑**
(向上必须放置,放进自己所在的格子必被拒;石头虽可徒手挖但每格 28 秒)。

于是「挖」把一个可解的局面换成了一个不可解的局面,然后后面每一步都在还账:

- `gather_blocks` 把玩家带下一两格 → `can_build_anything` 才被问到,已经晚了
- 挖脚下 → 掉落物掉进刚挖的坑 → 玩家够不着
- 坑底也是空气 → 规划器为一个不存在的立足点搜到上限
- 玩家被树丛围住 → 连 9 格外的树也走不到

### 改法

**采集只挖玩家所在行**(旁边的墙/土坡),**完全不挖脚下**;
`gather_wood` → `craft_one("log")` → `craft_cells("planks", cells=(1,3))` 全部在**地面**完成,
挖掘推迟到手上有木板**且**已验证有两格出口之后。

> 这不是又一个 bug 修复,是**顺序**修复。之前我一直在调试"挖掘的后果",
> 而没有问"为什么要先挖"。
