---
doc: audit
title: 北极星差距分析 — agent 自动对局 + 严格真人输入
layer: audit
status: authoritative
updated: 2026-09-30
read_if: 要决定下一步做什么。每一节都带活机证据，不是推断。
---

# 0. 一句话

**我们离"agent 自动打完一局"最近的缺口不是能力，是可观测性和输入真实度。**
能力面（GUI 驱动、寻路、观察、协议）今天就能跑通一整局；卡住 agent 的是三件事——
工具面把 agent 的 context 吃光、错误消息不告诉它下一步、以及视角是等速 ramp。

本轮全部结论来自**活客户端**（`127.0.0.1:25599`）和 7 个并行侦察，不是读代码推的。

---

# 1. 信任链已轮换并验证

| 项 | 值 |
| --- | --- |
| 旧链 | 2026-07-14 备份里的私钥，与现装 2026-08-26 公钥**不匹配**（已用生产签名器验证） |
| 新链 | 本机 `C:\Users\dwgx1\.mcp-keys\`，**不入库**（仓库外，无 git 跟踪） |
| 根文档 | `version 2`，`expires: 1822271706369`（约 2027-10-30），由 `RootCeremonyCli` 签发 |
| patch | 3 个全部用新 kernel 私钥重签，常量已替换进 `patches/*.java` |
| 验证 | 活机 `[MCP Compat] engine built: 3 patch(es) armed, 0 skipped.` |

**新私钥：`C:\Users\dwgx1\.mcp-keys\{root,kernel}-ed25519.key.b64`。丢失即永久失锁。**
备份到别处时不要放进任何 git 仓库。

---

# 2. 本轮发现的产品缺陷（按严重度）

## 2.1 [已修] `act_set move` 可以被构造成永远假成功的空操作

**这是本轮最重要的发现，且是活机撞出来的，不是读出来的。**

我发出：
```json
{"channel":"move","move":{"to":{"x":-60,"y":78,"z":256}}}
```
返回 `accepted:true, phase:ACTIVE`，然后卡在
`"moving (tick 320), moved 0.00 blocks"` 持续 16 秒，**玩家一动不动，全程无任何报错**。

活机探针逐层查下来，每一层都"看起来正常"：
```
movementInput = ActMovementInput   安装器在工作
moveActive()  = true               view 在工作
moveForward   = 0.0                轴是零  <-- 问题在这
```

两道独立的门通向这里：

1. **`doublesArg` 只认数组。** `"to": {"x":..}` 是 Map → 返回 `null` → 被当成"没传这个参数"
   → `parseMoveSlot` 落到 raw-axes 分支。而 Map 恰恰是本工具面里**其他所有工具**用的坐标形状，
   也是模型最自然会写的形状。
2. **raw-axes 分支构造出全零 intent。** `MoveIntent(0, 0, false, false, false, durationTicks=0)`。
   `MoveApplier` 唯一的终止分支被 `duration > 0` 守着——于是这个 intent **永远走不到终止**，
   而且每 tick 都发布零轴，报告自己是 "moving"。

比两个缺陷单独存在都糟，因为**它报告 ACTIVE**。只检查 phase 的调用方会认为成功。

**修法**（`ActIntentParser`，两处都修了）：
- 新增 `coordArg`：同时接受 `[x,y,z]` 和 `{"x":..,"y":..,"z":..}` 两种自然形状，
  其他任何形状**报错并说清接受什么**；`route` 同理。缺轴报错，不默认 0（默认 0 会把玩家
  规划到 y=0 的地板里）。
- `parseMove` 拒绝"全轴静止且无 duration"的组合，并提示"要瞬时按下就给 durationTicks"。

**验证**：新测试 `AMoveThatCannotHappenIsRefusedTest`（9 个）全绿；活机重跑同一个调用：
```
walking, 135.25 blocks to go (tick 22/400)
...
pos: (-199.50,64.00,253.50) -> (-163.61,59.40,253.79)   8.4 秒走了 36 格
```
距离单调递减，消息换成了 `NavController` 的（之前是 raw 分支的），**修复端到端确认**。

## 2.2 [已修] `move` 的 schema 没有任何属性校验

`act_set` 的 `inputSchema.properties.move` 是 `{"type":"object"}`——**零属性**。
`to` 是数组还是对象、字段叫什么，全靠 description 里的散文。
这正是 2.1 能溜进来的原因：schema 层本该拦住。

**已修**：`ActTools.actSetInputSchema()` 给出完整 `properties`，坐标用
`coord()` 声明成 `oneOf: [array[3], object{x,y,z}]` 两种形状（数组是文档形状，对象是本工具面
其余工具都在用的形状，只声明一种就是让另一种无人检查地溜过去，而溜过去的那一种弄坏了一个活
客户端）。散文不是被删掉，而是**搬到它描述的那个字段上**。

**2026-09-30 续**：剩下的散文枚举也清完了——`world_view` 的 `profile`/`mode`、
`do_client_status` 的 `status`、`do_entity` 的 `action`，全部改成 JSON `enum`
（`ToolRegistry.enumProp`）。注意 `world_view.mode` 的描述里必须保留
「基线是调用者上一次调用、首次 diff 没有基线会返回 full」——
`ToolDescriptionsMatchTheirBoundsTest` 就是守这一句的，把枚举换成短描述时它立刻变红。

## 2.3 [已修] `to` 会把 agent 走进海里淹死

`to` 的文档写明"walk STRAIGHT toward a point and give up if blocked; it never builds and never
routes around anything"。所以它**按设计**不看地形。活机实测：

```
pos=(-199.5, 64, 253.5) -> 一路直走 -> pos=(-142.3, 33.8, 253.9)
height 64 -> 33.8，坠落 30 格，health 20 -> 0
```

两次死掉：一次摔死，一次淹死（`legs=water head=water`）。**全程零预警。**

不是 `to` 的 bug，是**没有任何东西告诉 agent 该用 `route`**。`route`（真寻路）在同一位置
诚实失败并说明了原因（`no route from (-143,32,253) to (-140,64,250) using blockBudget=0`）。

**修法**（这条直接决定北极星能不能达成）——两条都已落地：

**一、名字本身就是缺陷。** `to` 读起来像安全选项，而它是**直线走、不绕障、不建造**；
`route` 才是会绕障搭桥的那个。**名字是 agent 选择之前唯一会读的东西**，
而危险的那个当时戴着更短的名字。所以这是破坏性改名：

- `to` -> **`walk_straight`**，描述第一句就是 "use `go_to` unless you know the line is clear"。
- `route` -> **`go_to`**。
- **不留别名**：旧名直接报错并指出新名（`refuseRenamedMoveKey` 按 key 是否存在判断，
  所以旧名传 `null` 也被拒绝，不会被当成没传）。留别名比破坏更糟——
  调用方会以为迁移完了，其实没有。

两行拒绝文案各带**各自**的行为描述。worker 的第一版用同一个模板套两行，
结果 `route` 的拒绝文案写成「go_to 直线走、不绕障、不建造」——
**正是这次改名要消除的那个误导，却出现在阻止调用方选错的那句话里**。
靠驱动打印两条消息发现，任何断言都发现不了。

**二、危险作为数据提前告知。** 原先危险是粘在运行消息上的散文
（"37% of the way there"）——同一句话在 400 格线上意味着 148 格外，在 6 格线上意味着 2 格。
现在 `NavHazard(Kind, x, y, z, blocksAhead, detail)` 是 `act_status` 上的**结构化字段**：

- `Kind{LAVA, WATER, DEEP_DROP}` 是**封闭枚举**，caller 分支在 `kind` 上，不在文本上。
- `blocksAhead` 是**带符号的格数**，每 tick 从实时位置重算（采样的格子不动，只有距离在缩）。
  取代百分比是因为**百分比的分母 agent 并不知道**，那不是警告。
- 距离为负 = 已经走过。警告**粘住**不回��，因为无符号距离会把玩家正踩着的格子读成「前方 2.4 格」。
- **无法读取的格子绝不臆造危险**——未加载区块曾被读成无底坑，
  于是每条从它出发的直线都报警。一个 caller 会学会忽略的警告比没有警告更糟。

**不改变行走本身**：`walk_straight` 的文档是「从不寻路、从不转向」，
危险是**报告不是否决**。没有任何代码读 `NavController.hazard()` 来决定要不要动——
不声不响地拒绝行走就是另一个工具顶着这个名字。

## 2.4 [部分已修] 错误消息不告诉 agent 下一步

活机拿到的三种错误：
```
[: required property 'entityId' not found, : required property ...     <-- 被截断
unknown status 'start'                                                 <-- 合法值在 description 里，不在 schema
```
第二种尤其糟：MCP 社区最佳实践明确 **enum 优于长描述**，而 `do_dig.status` /
`do_place_block.face` / `act_set` 一堆字段都把合法值写在散文里，schema 上是裸 `string`。

**修法**：所有枚举字段改成 JSON `enum`（schema 层拒绝 + IDE 补全 + 模型不猜）；
校验错误补全被截断的字段列表。这是纯机械改动，收益/成本比最高的一项。

## 2.5 [已修] "发包成功" 被当成 "操作成功"

`do_dig` 返回 `sent dig START_DESTROY_BLOCK`——这是**传输层**确认。活机上玩家同时溺毙，
方块并没有因为这条回执而断。

这正是本项目自己在 harness 修复里守过的线（"JSON-RPC error 不能压平成空成功"），
但 `do_*` 这一层没有同样的纪律。

**已修**：`do_dig` / `do_place_block` 在返回前轮询世界确认结果（`confirmDig` /
`confirmPlace`），方块没变就报失败并给出真实原因——`confirmPlace` 会去问**服务端**当前
持有什么（`NetHandlerPlayServer.processPlayerBlockPlacement` 从 `player.inventory.getCurrentItem()`
取栈，从不读包里的栈），实机证据见 first-night-plan。

**2026-09-30 续**：`transfer_item` 把同一纪律用到容器上，并且把判定抽成可测的纯函数
`ToolRegistry.verdictFor(before, now)`。理由是实测出来的：把确认循环里的「重读比较」换成
「直接报已发送」（也就是本节描述的缺陷），在无客户端的测试里**跑绿**——因为没有容器时
空槽守卫先应答，循环根本进不去。抽成 `TransferVerdict` 后，该变异被
`ATransferIsConfirmedAgainstTheServersContainerTest` 杀死，且枚举里**没有 SENT 这个取值**，
从类型上就不允许把「已发送」当成结果。

## 2.6 [已修] 我们自己的 QML 面板对 agent 不可见

`RefUiDesign` 独立发现：`QmlGuiScreen` **不向 vanilla `GuiScreen` 注册任何 `GuiButton`/`GuiTextField`**，
而 `gui_snapshot` 的提取器只反射 `buttonList`/`labelList`/容器槽位/文本字段。
后果：**我们自己做的 DWM 面板，agent 看不见也点不着**——而 vanilla 的每一个菜单 agent 都能驱动。

**已修（2026-09-30 复核）**：`QmlElementBridge` 遍历活的 QML 树，把每个可见且非零尺寸的
`MouseArea` 发布成一个不可见的 `QmlProxyButton` 进 `GuiScreen.buttonList`——那正是
`gui_snapshot` 唯一反射的通道。`QmlProxyButton.mousePressed` 先调 `super`（vanilla 拥有
enabled/visible/rect），再 `area.clicked.emit()`：**和人点击产生的是同一个信号，场景无法
区分 agent 与人**。

这个解法值得单独指出，因为它没有选择绕过。`QmlProxyButton extends GuiButton`、`drawButton`
是空实现、id 全是 0、不播按键音——它完全伪装成 vanilla 控件，让反射器和输入路径都以为自己在
和普通菜单说话。这比「给我们的面板另开一个 API」强：后者会在场景里留下一条人类走不到的路径，
而这��没有。

**这一条曾经被我写错过一次，更正记录在此**：我一度断言「QML 里的 `TextField`、`Slider`、
`Switch` 不是 `MouseArea`，因此不可见也不可点」。**那是虚构的。**
`qml4j-core-0.2.27.jar` 的 `render/items/core` 只有
`Item`/`Rectangle`/`Text`/`TextWrap`/`Image`/`MouseArea`/`Flickable`/`Drag`/`Canvas`/
`Gradient`/`GradientStop`/`Font`/`Size`/`Border`/`ChildrenRect`/`Binding`/`ColorMath`/`MouseEvent`
——**没有 TextField、Slider、Switch 这三个类型**。面板自己的控件
（`FluentButton`/`FluentToggleSwitch`/`FluentCheckBox`/…）**每一个内部都有 MouseArea**，
而四个没有 MouseArea 的（`FluentElevation` 阴影、`FluentProgressBar` 进度条、
`FluentSettingsGroup` 分组标题、`FluentSettingsItem` 布局行）**都是纯展示或纯布局，
本来就没有交互行为**。

所以 2.6 是**完整关闭**的，不是「大部分关闭」。这次是连续第三次我写下未经核对的论断
（前两次是反应时间阶梯与修正性微调），**记下来当方法论问题**：涉及「某个类型存在吗」的
论断，先 `unzip -l` 看一眼 jar。

---

# 3. 北极星差距：两个轴

## 3.1 「agent 自动完成对局」

**已经能做的**（今天实测跑通）：
- 纯 MCP 驱动从主菜单 → 单人 → 创建世界 → 进入世界（`gui_snapshot` / `gui_click_element`，
  按 **elementId + epoch + fingerprint**，不是按像素——这是很强的原语）
- 世界观察：`world_view` 三档 profile + `sections` 过滤 + `mode=diff`；未知 section 会被拒
- 寻路：`route` 真 A*，失败时**指名**停在哪、为什么
- 挖掘/放置/切槽/背包/合成计划：`craft_plan` 诚实报缺什么
- 权限/安全脊柱：R-1 到 R-3 降权实测生效

**挡路的**：
1. `route` 之前 agent 动不了（2.1，已修）
2. agent 会自杀（2.3）
3. 84 个工具 / **17,332 token** 的 manifest，光描述就吃掉大量 context
4. 错误消息不指导下一步（2.4）
5. 没有"我刚才那步到底成没成"的统一答案（2.5）

**外部世界的坐标（2026-10-01 逐条核对后重写。以下三条原论断均已过期或不成立）：**

原写的三条，核对结果：
- 「整个领域都停在 protocol-level」——**错**。至少三个 mod 型真客户端 MCP 已发布且带渲染：
  `lolifamily/Minecraft-MCP`（Fabric/Forge/Paper，`render` lane 由 `MixinMinecraft#runTick`
  心跳驱动，`take_screenshot` 读游戏自己的离屏渲染目标）、`InventivetalentDev/minecraft-mcp`
  （`player_look_at` / `player_break_block` / `client_reflect_invoke`）、
  `AnctyEnly453/mc-architect-mcp`。**准确的说法**是：*流行与学术*那一层是 protocol-level，
  *而已经发布的那一层有渲染*。
- 「真人输入只在 pixel-to-motor，代价 720 V100 × 9 天」——**两个半都不成立**。
  ~~**720 V100 × 9 天不在 VPT 论文里**，它出自一个博客~~ → **这条撤回是错的，已撤回**。
  VPT §4.2 原文照录：「…which took **9 days on 720 V100 GPUs**」。
  **我对一条真实引用发了撤回**，这是本会话第五次引用错误，也是第一次**撤回本身出错**。
  仍然成立的是**范畴**那半句：那个账单是**基础模型的 BC 预训练**，
  把 VPT 的预训练账单当成「真人级输入的代价」仍然是范畴错误——
  贵的是无条件的视频预训练先验，不是人手的模拟。
  VPT（arXiv:2206.11795）微调用
  **16 张 A100 约 6 小时 / 约 2 天**，RL 阶段 **80 张 GPU 约 6 天**。
  与之并提的 STEVE-1（arXiv:2306.00937）论文自述训练成本是 **"$60 of compute"、4 张 A40**——
  把 VPT 的预训练账单当成「真人级输入的代价」是**范畴错误**：贵的是无条件的视频预训练先验。
- 「没人同时做到 LLM 规划 + 真客户端上的真人级输入」——**按字面为假**。
  **Lumine**（arXiv:2511.08892，ByteDance Seed，2025-11）在原神里以 5Hz 视觉输入产生
  30Hz 键鼠动作，完成五小时蒙德主线、**与人类效率相当**。
  **Game-TARS**（arXiv:2510.23691）把该范式命名为 "Human-Native Interaction"，
  动作空间正是 `mouseMove(dx,dy)` / `mouseClick` / `keyPress` 三个原语，在 Minecraft
  800 任务基准上超过 GPT-5 / Gemini-2.5-Pro / Claude-4-Sonnet，GUI 任务桶 55.0%。

**仍然成立、且值得保留的那一条（更窄，但完全可辩护）：**

> 没有已发表的 agent 在**真实 Minecraft 客户端**上把**显式的人体运动控制模型**——最小 jerk
> 速度剖面、按游戏自身灵敏度导出的量子做 GCD 吸附、修正性子动作调度——实现为输入层。
> pixel-to-motor 路线（VPT、STEVE-1、Game-TARS、Lumine）只通过**模仿人类轨迹**达到人类级
> 运动学；protocol-tool 层能连真服务器但没有渲染器；mod 型 MCP 层有渲染与 GUI 触达，
> 但**直写旋转、绕过鼠标路径**。**没有人同时占住这三样。**

这个版本是关于**工程**的论断，而不是关于算力或底层 ML 的新颖性——后者是原版本核对后剩下的东西。

**一个对本文档论点直接有利的外部旁证**（原本缺失）：Gaia 的 `AimF` 检查把
「旋转增量没有 GCD 残留」判为 `gcdBypass`（https://github.com/itz-winter/Gaia），
TruthfulAC 明确列出 **jerk detection** 作为十一个瞄准检查之一
（https://github.com/TawnyE/truthful-anticheat）。**一条专门的 jerk 检查意味着
非 jerk 形状的旋转本身就是检测信号**——这是「速度剖面不是装饰」目前唯一的一手外部引证。

这也说明为什么 2.1 那种"假成功"格外致命
一条永远 ACTIVE 的空 move 会让整个卖点变成假的。

## 3.2 「严格的真实模拟人类操作」

`WebHumanMotor` 点名了我们代码里最假的一处：
> `LookController.java:137-143` 的等速 `clampMag` ramp 是**第一大假人特征**。

最小抖动模型（Flash & Hogan 1985, [JNeurosci](https://www.jneurosci.org/content/5/7/1688)）的
速度曲线是钟形的，峰值/均值 ≈ 1.75；等速 ramp 是平的。这个差距是**可量化的**。

可直接落地的（`WebHumanMotor` 给的参数）：
- Fitts 定律在 3D FPS：a=0.19/0.46 s，b=0.18/0.19 s/bit，IP 5.5/5.3 bits/s
  （[Looser & Cockburn](https://www.csse.canterbury.ac.nz/andrew.cockburn/papers/fitts-game.pdf)）
- ~~反应时间按熟练度：109/150/150-200/250/300/350 ms（Jiang et al. 2020）~~
  **2026-10-01 核对后撤回**：那条阶梯是 Jiang 论文 Table 1 对**他人**数字的汇编，
  源头是 reference.com 与一个 YouTube 视频；Jiang 自己的 154 人测量里技能组间差异
  **不显著**（p≥0.10），且报的是均值不是中位数。
  可用锚点：~250-300ms 简单视觉反应时间，引 Woods et al. 2015（doi 10.3389/fnhum.2015.0009）。
- 微修正（submovement）发生率 40%，离散+小目标 57±4
  （[Fradet et al. 2008](https://pmc.ncbi.nlm.nih.gov/articles/PMC2600723/)）——
  **数字对，标签错**：该论文的论点正是**反驳**「修正性」解释（类型 1 子动作在**大目标**上
  更频繁且只出现在运动终止模式；类型 2/3 与峰值速度**负相关** R²=0.71/0.75，即低速伪迹）。
  经典修正解释应引 Crossman & Goodeve 1983 / Meyer et al. 1988，Fradet 引作反驳。
- 最小 jerk 推导与 n=2/3/4 的比值 1.5/1.875/2.186
  （[Shadmehr 课程 PDF p.8](https://courses.shadmehrlab.org/Shortcourse/minimumjerk.pdf)）——**引用正确**
- **更正**：本文早先写「Flash & Hogan 1985 … 峰均比 ~1.75」。**1.75 不是 F&H 的数**，
  它出自 Shadmehr 讲义那句「about 1.75」。F&H 1985
  （doi [10.1523/JNEUROSCI.05-07-01688.1985](https://api.crossref.org/works/10.1523/JNEUROSCI.05-07-01688.1985)）
  是**模型给 C=1.875、实测 1.805 ± 0.153（n=30）**，且作者自己指出模型值在 α=0.05 被拒绝。
  **本项目现用 π/2 ≈ 1.571（半周期升余弦），比 1.805 明显平缓——已知偏差，未标定。**

**我们代码侧的现成抓手**（`WebHumanMotor` 已定位）：
- `LookController` 137-143：等速 ramp → 换成逐 tick 最小 jerk/snap 剖面
- `LookIntent`：只带 `slewDegPerTick`（一个上限），没有剖面形状、没有时长、没有子运动预算
- ~~`ActTickLoop`：**20 Hz** tick 驱动~~ → ~~这条是错的，已订正为逐帧 60+ Hz~~
  → **这次订正本身是错的，我已撤回自己的订正。**
  `Minecraft.java:223` 声明 `private Timer timer = new Timer(20.0F)`，
  而 `Minecraft.java:1113-1116` 按它调度：`for (int j = 0; j < this.timer.elapsedTicks; ++j) { this.runTick(); }`
  ——**`runTick` 每 50ms 触发一次**。`TickAdvice.enter()` → `TickBridge.onTick()` 内联在 `runTick` 里，
  所以 `TickEvent` → `ActTickLoop.onTick` → applier → `LookController`
  **整条输入通道就是 20 Hz**。**内联在 runTick 入口不蕴含逐帧。**
  这条同时推翻了我用来否决「离散扫视」的论据里的那个数字：
  人类扫视时长 31.44–68.22 ms（Gibaldi 表 5）除以 **50 ms** 通道 = **0.63–1.36 tick**——
  结论不变，但它现在站在正确的通道宽度上。
- `LivePlayerActuator:191-201` 直接写 `prevRotationYaw/Pitch`，**绕过真实鼠标路径**
  → 真人化必须在角度层合成
- **GCD 量化（`GcdRotation` 查证，这是本条最重要的补充）**：
  鼠标→角度是两段——`EntityRenderer:1099-1100` 算 `f1 = (sens*0.6+0.2)³*8`，
  再由 `Entity.setAngles:392` 乘 **0.15**。默认 sens=0.5 下 `f1*0.15` **精确等于 0.15°**，
  圆上**恰好 2400 个可达朝向**（pitch 1200 个）。
  **服务端不做任何 GCD 校验**（float32 原样上线），1/256 字节量化只存在于**别人眼中的你**。
  我们绕过 `setAngles` 直写浮点，**没有免费量化，GCD 必须自己加**，
  落点是 `LookController:131`（landed 写 targetYaw）和 `:143`（slew 写 wroteYaw）。
  **验证陷阱**：`Summ.java:30-32` 用 `%.1f` 格式化，比 0.15° 粗，
  **包日志原理上无法证明晶格**——要验证必须绕开这一层。
  帧序：我们写 → `updateEntities()` → `onUpdateWalkingPlayer` 发包 → 之后才是
  `EntityRenderer` 应用鼠标。所以我们是 **0 帧延迟，真人 1 帧**。

`RefInputRealism` 从 133 个客户端里挖到的可直接借鉴：
- **二阶递推**：`AccelerationAngleSmooth.computeTurnSpeed` 用 `prevDiff + accel + error`，
  噪声是**乘性**的（幅度正比于加速度本身），`ErrorProvider` = `accel*U(-0.1,0.1)+U(-0.1,0.1)`
- **点击节奏的真人分布**：`NormalDistributionPattern` 双频带——90.9% 概率 N(87.9ms, 13.4ms)
  常规、9.1% 概率 N(179.5ms, 20.4ms) 爆发。**这是 133 个客户端里唯一一处"从真人数据反推分布"的代码**
- **角度→按键离散化**：`getDirectionalInputForDegrees(dgs, deadAngle=20°)`，死区内保持上 tick 按键
- **GCD 量化**：所有角度步进必须过 `Rotation.kt.normalize()` 吸附到 GCD 网格，否则不是物理可能的
- **四态分离**：`playerRotation`(客户端已应用) / `currentRotation`(本 tick 计划) /
  `actualServerRotation`(已发) / `theoreticalServerRotation`(滞后时)
- ⚠️ **133 个客户端里没有任何一个实现了真正的二阶/临界阻尼跟随器**（`AiAngleSmooth` 的神经网络除外）。
  也就是说这一块**没有现成答案，必须我们自己设计**。

## 3.3 1.8.9 协议层（`RefProtocol`）

已确认、项目文档里也记着的：
- 1.8.9 **没有显式的跳跃包**——服务端靠 `onGround` 由 true 变 false + posY 上升**推断**
  （`NetHandlerPlayServer.java:361-364`）。这是我们做拟人输入时必须利用的机制
- 移动发包唯一发送点 `EntityPlayerSP.onUpdateWalkingPlayer:186-273`，
  触发条件是"位移差平方和 > 9.0E-4 **或** 距上次 ≥ 20 tick"，**且角度差 ≠ 0 是独立布尔**
- **C0E 预测契约**：客户端先本地 `slotClick` 再把返回值当 `clickedItem` 发出去，
  服务端独立跑一遍并用 `areItemStacksEqual` 比对，不等就 `setCanCraft(false)`，
  此后**该窗口每次点击被静默丢弃**直到 C0F 确认。这是我们必须防的死锁
- 1.8.9 `C0E` 槽位：`ContainerPlayer` 0=合成产物 / 1-4=合成 / 5-8=护甲 / 9-35=主 / 36-44=快捷栏

寻路方面（`RefPathfinding`）：
- 133 个客户端里**只有 Wurst 系**把 A* + 代价 + 局部重规划 + 按键锁做齐
- **值得抄的一条**：MineBot 把寻路当**编译**——搜索跑在"未来世界"快照上，时间即代价
  （挖方块 TIME_TO_PLACE=5、走一格 timeToWalk=1+hDist/4）
- 反面教材：Meteor 的 `isSolidFloor` 居然 `return isAir`；多处用 `Material.isSolid()`，
  而 1.8.9 里**空气的 isSolid() 恒 true**——等于把空气当地板

---

# 4. 建议的下一步（按"解锁北极星"排序，不是按工作量）

| # | 做什么 | 为什么排这个位置 | 量 |
| --- | --- | --- | --- |
| 1 | `move` schema 补全 + 所有枚举改 JSON `enum` + 错误消息补全截断字段 | 纯机械，直接降低 agent 的试错成本；是 2.2/2.4 的根治 | 小 |
| 2 | `do_*` 返回**结果**而不是"已发包" | 2.5；没有这个，agent 无法自我纠错 | 中 |
| 3 | `to` 改名为 `walk_straight` + 描述第一句"默认别用"；`route` 改名 `go_to` | 2.3；把选择权还给 agent | 小 |
| 4 | `NavController` 直线路径预警（水/岩浆/超限坠落） | 2.3；观察层已有信息，只是没传 | 中 |
| 5 | `LookController` 换最小 jerk 剖面 + `LookIntent` 加"剖面/时长/子运动预算"轴 | 3.2 的**第一块砖**；有可量化指标（Fitts 常数、jerk 峰均比） | 中 |
| 6 | 工具面折叠：`debug_*` 11→1、权限 4+3→1、`seam_*` 6→3 | 3.1；84 个工具 / 17k token 已越过所有已知拐点 | 中 |
| 7 | `QmlGuiScreen` 向 vanilla 注册可反射元素 | 2.6；否则 agent 看不见我们自己的面板 | 中 |
| 8 | 假人感回归测试 | `WebHumanMotor` 要求"至少一个能自动判定的量化指标" | 小 |

**第 1-4 项做完，"agent 能自动完成一局"这件事才算真正成立**；第 5-8 项是"严格真人"的入口。

---

# 5. 本轮实测数据

```
活机：3 patch(es) armed, 0 skipped / socket 25599 / REST 1337 / 84 tools
工具面：84 个工具，description+schema = 69,331 字符 ≈ 17,332 token
  最大：world_view 2,684 tok · act_set 1,797 tok · do_click_slot 752 tok · find_block 507 tok
全量测试：core 1235 / board 233 / dwm 58 / client 13 / lwjgl2-shim 63 / pg-engine 5 = 1607 全绿
Python harness：46 全绿
```

**口径提醒**：surefire 的 "Tests run" 不含 `*LiveIT`，别拿 `grep -c '@Test'` 互校。


---

# 6. 协议面扩展（2026-09-30 续）

侦察把 66 个工具与 vendored 1.8.9 源码逐条对照后，发现四个**有游戏能力但无 MCP 出口**的缺口。
每一条都带 `file:line`，不是推断。

| 缺口 | 游戏侧证据 | 挡住了什么 |
| --- | --- | --- |
| 没有任意位置的查询（碰撞盒/光照/能否生成） | `BlockProbe.java:129` 只是 util，无工具暴露；`EnvView` 只有 4 个字段 | agent 没法问「那里黑不黑」「我站不站得住」 |
| 没有「整叠物品转移到另一个容器」的安全路径 | `Container.java:266` `transferStackInSlot`；`GuiContainer.java:530-643` 里 shift-click 真正触发在 `mouseReleased`，而 `GuiActions.click` 从不调它 | 工作台、箱子、熔炉全用不了 |
| 实体视图只有 id/类型/坐标/距离/hp | `EntityView.java` 无 maxHealth / 护甲 / 敌意 / 服务端 36-9 够不着判定 | 判断不了「这个打不打得过」 |
| 环境不含雨/雷/光照 | `WorldViewCapture.java:400-418` 从不调 `isRaining/isThundering/getLightSubtracted` | 雷暴会改写生成闸门 |

## 6.1 `inspect_block`（新增，第 85 个工具）

刷怪闸门按 `EntityMob.java:145-165` 原样实现，并钉死两件容易写反的事：

- **比较方向是 `<= 7`，不是 `> 7`。** 低光照才生成。写反会让 agent 相信亮的地方危险、
  去黑暗里避难——这不是性能退化，是答案整个反过来。**本仓库自己就抄反过一次**，
  由 `TheBlockInspectorAnswersTheSpawnGateTest` 抓出来。
- **雷暴把 `skylightSubtracted` 强制成 10**（`EntityMob` 先存、再设、再算、再还原），
  于是正午的 15 被读成 5 → **雷暴让白天也能生成**。「夜里危险」只是这个的一半。

`Spawn.NO_FLOOR` 的措辞刻意是「**现在**不行，不是永远不行」：没地板的格子正是怪走进来的地方。

**证据**：8 条测试；其中一条在 32×16×4 全空间上比对「实现」与「测试里独立抄录的 vanilla
规则」，0 处不一致。两个变异各自杀死一条测试（比较反向、忽略雷暴）。
第二版变异（忽略雷暴）最初**跑绿**——因为 `skylightAmountFor` 只从 `World` 读，测试够不到；
把雷暴判定抽成纯函数后才杀掉。

## 6.2 `transfer_item`（新增，第 86 个工具）

`do_click_slot` 本就能发 mode 1，但服务端比对的是**你这次点击的结果**，所以调用方必须自己算出
服务端 `transferStackInSlot` 的结果去填那个字段。**算错不是被拒**：服务端会先把点击应用到
自己的容器，再把每个槽位重同步给你，然后**锁住窗口**——之后这个窗口上的每次点击都被静默丢弃。
这就是箱子/熔炉/工作台全都用不了的根因。

所以这个工具自己从实际打开的容器推出 claim，并且**把结果读回来**：

- 「已发送」是关于线路的事实；槽位内容是关于服务器的事实。只有后者能说明这叠东西动没动。
- 判定抽成纯函数 `ToolRegistry.verdictFor(before, now)` → `TransferVerdict`。
  **枚举里没有 `SENT` 这个取值**，从类型上就不允许把「已发送」当结果。
  这是实测出来的必要性：把确认换成「直接报已发送」在无客户端下**跑绿**（没有容器时空槽
  守卫先应答）；抽成纯函数后该变异被杀死。
- `windowId` 传错时，报错会**说出当前真正打开的是哪个窗口**，而不是留一句神秘失败。

**四道权限护栏全部要求声明行**，`transfer_item` 一样：R1 环、L3 完整性 HIGH、
L4 `SE_NET_RAW`、`NETWORK_SEND_TOOLS`。三条测试
（`RegisteredBuiltinGateCoverageTest` / `SendToolsW6Test` / `PolicySideTableDriftTest`）
在加工具时依次变红，这是它们在正常工作，不是障碍。

## 6.3 撤回一个我自己的错误结论

`Planner.MAX_EXPANSIONS` 曾从 20 000 提到 400 000，依据是活机一句「一格远的目标却耗尽了
20000 状态上限」。补测试后发现**这个测试在旧值上同样通过**——短路线用不到两万次扩展。
真实含义是**目标不可达**，规划器搜完了整个可达空间；抬上限治不了不可达，只是让失败慢 20 倍。
已回退到 20 000，并在 `Planner.java` 里写下这段证据和
`TheSearchCeilingDoesNotFireOnAShortGoalTest`，防止下一个人再抬一次。

## 6.4 顺带发现的一个既有偶发红

`ActRuntimeGatesArePinnedAtTheirBoundaryTest.navAxesReachVanillaInputThroughThePublishedValue`
偶发失败（`forward=0.0`）。原因是它的等待循环上限 8 tick，而 `NavController` 的人化首步延迟
正是 4-8 tick——**边界上相等**，慢一拍就红。不是本轮改动引入，但它是真问题，未修。

## 6.7 实体视图：能问「够得着吗」「该打吗」

`EntityView` 原本只有 id / 类型 / 坐标 / 距离 / hp / 名字。到达目标之后要做的两个判断一个都答不了，
而这两道闸门在 1.8.9 源码里本来就存在，只是没露出来：

- **距离比的是平方，而且门槛取决于能不能看见。**
  `NetHandlerPlayServer.processUseEntity:899-935`：`getDistanceSqToEntity(entity) < d0`，
  `d0` 在 `canEntityBeSeen` 为真时是 **36.0**，为假时是 **9.0**。平方，所以是 6.0 格和 3.0 格。
  四格外的目标，看得见就接受、看不见就**静默拒绝**，两边都不报错。
- **准星夹取是 3.0。** `EntityRenderer.getMouseOver:498-502` 在非创造模式下把超过 3.0 的
  指向目标置空。于是**够得着打 ≠ 准星点得到**：6 格内的目标可能可以按 id 攻击，却无法瞄准。
  所以这是两个方法，不是一个。
- **有些实体根本不能打。** 攻击 `EntityItem` / `EntityXPOrb` / `EntityArrow` 或自己，
  服务端 `kickPlayerFromServer` —— 那是**掉线**，不是点击失败。

线上新增 `reachableSeen` / `reachableBlind` / `hoverable` / `attackable` / `hostile` 五个字段。
**两个 reach 都报而不是报一个**：适用哪一个取决于服务端能否看见玩家，而这件事客户端读不到；
只报乐观的那个，就是猜。

`attackable` 是**拒绝清单**而不是许可清单，这一点我先写反了、由测试纠正：许可清单要枚举游戏里
每一个生物（马、狼、铁傀儡、任何模组加的），会把合法攻击一并拒掉。vanilla 只踢四种，
比 vanilla 拒得更多同样是错的。

**证据**：5 条规则测试 + 4 条线上契约测试；三个变异全被杀（边界改成 `<=`、夹取放宽到 4.0、
忽略可见性恒用 6 格）。写测试时自己也错了两处（3.0 格恰好等于 9.0 不算「接受」、
`assertTrue(attackable)` 方向反了），都由测试报出来。

## 6.8 环境：报天气，因为天气改写生成闸门

`EnvView` 原本只有维度 / 生物群系 / 时段 / 原始时间。现在补 `raining` / `thundering` /
`daytime` / `lightAtPlayer`。

不是装饰：**雷暴会让白天也能生成**（见 6.1），只告诉 agent「正午」而不告诉它有雷暴，
就是在为另一个下午做计划。另外 `World.isDaytime`（`World.java:862-865`）是
`skylightSubtracted < 4`——一个对光照因子的阈值，不是对时钟的判断，所以**从时段推白天
是在用另一个事实**。两个阈值（雷 0.9、雨 0.2）也是独立的，下雨不等于打雷。

`lightAtPlayer` 读的是玩家所在格的光照，用 `floor()` 取整（`int()` 与 `floor()` 在负坐标上不同，
这个坑本仓库踩过一次），且与 `inspect_block` 报的是**同一个量**，所以两处可比。

**证据**：4 条规则测试，101 个天气强度点 × 2 与 16 个日照因子逐点与独立抄录的规则比对。
阈值抽成纯函数 `EnvironmentWeather`，因为只从 World 读的规则测试够不到。

## 6.9 `server_info`（第 87 个工具）

MOTD、协议版本、`MC|Brand` 模组品牌、在线人数、连接状态——全部现成，全部没露出来。
三处最容易骗人的地方已在描述里写死：

- **`browserPingMs` 是入场前的浏览器 ping，不是实时延迟。** 1.8.9 的对局阶段**根本没有**实时延迟
  这个数（`PingResponseHandler` 是服务端回包器，客户端读不到），所以一个裸的 "ping: 42ms"
  是关于过去的数字穿了现在的衣服。
- **在线人数不能从 `ServerData.playerList` 取。** 那是一个 **String**（S2F 的列表头部文本），
  不是数组；真实人数在 `NetHandlerPlayClient.getPlayerInfoMap()`。数字符串长度会得到一个
  看起来合理的错答案，而不是「不知道」——这一点我第一次写时就是这么错的。
- **读不到就说读不到。** 品牌缺失报 "not sent by the server"，**不是** "vanilla"；
  缺失的 MOTD 报 unknown，**不是** 空串。默认值是一个服务器从未做出的断言。

**证据**：4 条契约测试（含一条：无客户端时报错而非报告「一个原版服务器」）。
R3 环已声明；`RegisteredBuiltinGateCoverageTest` 又拦了一次，补齐后绿。

## 6.10 实机验证结果

`_scratch/smoke/verify_protocol.py` 在一个真实存档里跑完，**19/19 条断言全部对着世界验过**：

```
inspect_block   effectiveLight=15  spawn=LIGHT  理由带数字；env.lightAtPlayer=15 与之一致
world_view      entities 带 reachableSeen/reachableBlind/hoverable/attackable/hostile 五个字段
server_info     协议版本在场；延迟标注为入场前；modBrand="vanilla (reported by the server)"
transfer_item   "it held minecraft:dirt x1 and the server's container now holds -
                 confirmed by re-reading the slot, not by the packet having been sent"
```

最后一条是整组的重点：一叠 dirt 从热栏真的移走了，而工具说的是**重读槽位得到的**，
不是「包发出去了」。

### 6.11 顺带修掉一个必然失败的测试

`ActRuntimeGatesArePinnedAtTheirBoundaryTest` 反复红（约三次里一次）。根因不是随机：
`NavController` 的反应延迟是 `4 + rand(5)`（4..8 tick），而测试的等待循环上界**恰好也是 8**——
边界上相等，延迟取到最大值时循环刚好用尽。

**一个上界等于被测值的测试不是边界测试。** 现在循环放宽到 32，并**单独断言延迟落在 4..9**，
这样放宽循环不会顺手掩盖人化延迟被改成 2 秒。

同时更正 `NavController` 里一条**不成立的注释**：它写着延迟是「Seeded per intent so a
replay is reproducible」，而代码用的是未 seed 的 `ThreadLocalRandom`。这里也**故意不 seed**——
固定反应时间正是这套采样要避免的破绽；该修的是测试，不是人化。

### 6.12 计数

1336 测试全绿（本轮从 1293 起，新增 43 条），全量连跑三次稳定。
新增工具三个：`inspect_block`、`transfer_item`、`server_info`；扩字段两组：`entities` 五项、
`env` 四项。**未提交。**

## 6.13 实机验证：三个只有活机能抓到的缺陷

上面所有条目都是「测试绿 + 变异被杀」。这一节是**装上 jar、真进世界**之后才暴露的，
而它们的共同点是**每一个在测试里都是绿的**。

### 6.13.1 工具从来没上线过

`run-mcp.bat` 加载的是 `core/target/core-1.8.9-all.jar`，而本轮只跑过 `mvn test`——
**从没跑过 `install`**。实机第一次调用 `inspect_block` 的回复是：

```
JSONRPC-ERROR: Unknown tool: invalid_tool_name  "Tool not found: inspect_block"
```

「1331 测试全绿」和「工具存在」是两件不同的事，中间隔着一次打包。这不是本轮独有的
失误模式，但本轮把它走完了全程。

### 6.13.2 `Json.write` 不认 record

装上新 jar 后的第一次实机调用：

```
inspect_block -> "Report[x=208, y=64, z=189, block=minecraft:tallgrass, ...]"
```

**Java 的 toString，不是 JSON。** 调用方 `json.loads` 得到一个字符串而不是它被承诺的字段，
而且失败方式很危险：回复非空、看起来合理，只检查「有没有收到回复」的读法会通过。

根因在 `io/http/Json.java`：`writeValue` 处理 null / String / Boolean / Number / Map / List，
`default` 分支是 `String.valueOf(o)`。这个工具是**第一个直接把 record 交给 writer** 的，
所以只有它中招。

**修法**是在 `Json` 里认 record（`getClass().isRecord()` + `getRecordComponents()`），
而不是让每个调用方记得转 Map。写第一版时我把检测写成
`o instanceof RecordComponent[] comps`——那永远为假，测试立刻抓到。
变异（record 退回 toString）杀 3 条。

### 6.13.3 容器槽位与背包索引不是一回事

`world_view` 报背包里有 `log`，`transfer_item` 回「槽位 0 是空的」。**两个都是对的。**

`ContainerPlayer` 的槽位 0 是**合成产物格**；热栏第 0 格是**容器槽位 36**；背包索引 N
对应容器槽位 N+9。调用方读一份背包清单、把数字直接传过来，得到的是一个**自信的错答案**。

修法不是默默平移索引（那会在另一个窗口里再错一次），而是在拒绝时**把两套编号和偏移都说出来**。

### 6.13.4 顺带三件「诚实」在活机上的证据

同一局里还看到三处工具按设计行事，值得留着当正例：

- `do_dig` 对原木的拒绝：**「600ms 后 (-181,72,255) 仍是 log」**——徒手挖不动硬度 2.0 的原木，
  它没有报成功。
- `do_dig` 挖掉脚下方块后：**「已是空气（was dirt）——在世界里确认，不是只在线路��」**。
- `inspect_block` 在正午 Forest 读到 `effectiveLight=12`、`spawn=LIGHT`、理由带数字；
  `env.lightAtPlayer=12` 与之一致。这是本轮抄反过一次的那个比较，对上世界了。

### 6.13.5 记一笔：`gui_snapshot` 看不见世界选择列表

实机进世界时，`GuiSelectWorld` 只反射出 `Create New World` 与 `Cancel` 两个按钮，
**存档列表的行完全不可见**。这与审计 2.6 是同一类缺口（vanilla 界面 vs 反射器覆盖面），
只是这次在 vanilla 自己的界面上而非我们的面板上。未修。


---

# 7. 输入层评审（2026-10-01）

Owner 定下硬约束：**「必须是超越人类而不是降低性能、操作能力」**。已写成
`ADR-0005`，把「像不像人」变成可检查的三分类：**免费真实**（模拟一个本来就会发生的物理约束，
不花钱）/ **有收益的代价**（花钱，换一个可测量的性质并写出它）/ **纯代价**（花钱，换不到任何
可测量的东西，**禁止**）。

六个只读侦察审了整棵树。三个真缺陷，都在**未提交**的工作里，测试当时全绿：

1. **反应延迟每 tick 重抽。** `stop()` 把 `reactionTicks` 重置为 -1，而反应分支每 tick 都调
   `stop()`，于是延迟在玩家站着不动时反复重采样、与递增的 tick 赛跑。这**正是**那条反复红的
   测试的原因，而**我当时把它的等待上界从 8 放宽到 32 就当作修好了**。重置本身也是多余的：
   新意图本来就拿到新控制器。已修，`TheReactionDelayIsOneDrawNotARaceTest` 钉住「抽一次」和
   「第一帧按键落在 delay+1」。

2. **直线危险扫描每 tick 最多 97 次 `blockAt`**（48 采样 x 2 + 一次可读性探针），而直线行走
   可能几百 tick，且世界在直线上行走期间不变——每 tick 都在重算一个没变的事实。
   **这正是 ADR-0005 禁止的第三类，也是 owner「不能降低性能」的直接违反**：一个每 tick 花掉
   一百次世界读取的诊断，等于花掉了它本该描述的那段行走。已改为几何每条线采一次，每 tick 只重算
   「还有多远」这个真正在变的百分比。`TheHazardScanIsNotPaidEveryTickTest` **数调用次数**
   （实测而非断言），并且同时钉住「扫描仍然在跑」和「缓存后危险仍持续上报」——缓存的典型失败
   就是只报一次的警告。

3. **原始移动轴以任意浮点直达 `ActMovementInput`。** 只截断到 [-1,1]、**不吸附到按键**，
   所以 `forward: 0.5` 让玩家以半速走。这一条不只是像不像人的问题：
   **发 ±1 正是让 vanilla 自己的摩擦与加速去爬升的机制**，发 0.3 绕过了那套物理，玩家会瞬间
   加速到没有任何按键能产生的速度。`NavController` 早就吸附了，两条路径对「移动轴是什么」意见
   不一。已统一，并在 `nearestKeys` 里把幅度阈值从 `1e-6`（数值保护，不是死区）改成
   `0.5`：**vanilla 没有部分前进，0.3 只能是「松开」**。要慢就用 sneak，那是另一个 flag。

### 7.1 这次我自己的两个错

- 第一版反应延迟测试是**统计性**的，变异**存活了**。钉不住的测试已删，改为直接钉抽到的值。
  顺带更正我自己的错误判断：我说重抽导致「尾部无界」——**不对**，`ticks` 仍在增长，可观测的
  后果是**分布错了**（等待的不是抽到的那个值），不是无界。
- 侦察说 `contains("class")` 是空断言——**它错了**，那是对返回值 `d.kind()` 的行为断言。
  它说三处源码文本断言是「图省事」——其中两处是**自觉的漂移守卫**，且第二处的 javadoc
  明确写了它防止什么。都没动。

### 7.2 计数

1345 测试全绿（本轮开始时 1341）。**未提交。**

## 6.8 `open_overlay`：面板对人可用、对 agent 不可用（已修，实机）

面板的按钮**早就**通过 `QmlElementBridge` 发布进 `GuiScreen.buttonList`，
点击走 `area.clicked.emit()`——**和人点击走的是同一条路**。
审计 2.6 说「面板对 agent 不可见」，但真正的缺口在**更前面一格**：

**面板本身只有 RSHIFT 热键能开，而 RSHIFT 是 GLFW 事件、不在 vanilla 的按键绑定表里。**
所以 `press_key_binding` 回答 `bindingClaimed=true`，然后什么也不发生（实机实测）。
这是审计 2.6 的反面：不是「看得见但点不到」，而是**门根本不开**——
人能用、agent 不能用的唯一界面。

修法**不是造一条新路**。把 `DwmHotkey.toggleScreen` 变成 public，
**热键和工具调用同一个方法**。否则「拒绝替换另一个屏幕」「切换而不是叠加」
和 `DwmEntry` 的构造行为会漂成两个不同的 UI。

### 元素表改为内容驱动发布

原先是一次性发布（`if (!publishedAfterLayout)`）。两个后果，实机可见：
点导航换页后**元素表完全不变**，而过期矩形继续替已经离开的控件应答点击——
agent 点到了它以为在那里的东西。改为「collect 后比较，不同才发布」。**实机 6 -> 14 个元素，14 个矩形各不相同。**

### 名字必须可读（本轮最隐蔽的一个缺陷）

活机：`gui_snapshot` 列出 6 个可点控件，名字是 **`/0/5/0/0/3/4`**。
面板的 `objectName`（`navHome`/`navKernel`/`navChips`/`navSettings`）**是有的**，
但它挂在 `NavItem`（一个 `Item`）上，而**发布的是它内部的 `MouseArea`**。
索引路径谁也认不出是什么控件——**能点但叫不出名字，等于半个工具**。

- `named()` 改为**沿父链向上**取名（`NAME_LOOKUP_DEPTH = 6`）。
  命名有界的理由：无限上溯会变成每帧每控件一次根遍历。
- 名字唯一性：祖先上溯**不保证**唯一。窗口的两个 chrome 按钮是匿名 MouseArea，
  都继承了 `window`——**6 个控件 5 个名字**，agent 分不清关闭和最小化。
  规则改为：**先统计每个名字被几个控件认领，被多于一认领的全部作废**，
  每个持有者按自己的结构路径发布。**绝不用坐标**（窗口会移动、uiScale 会变），
  也不让第一个保留干净名字（否则同一个 id 会因 z 序指向不同控件）。
- 实测：主页 6 个控件从「6 个控件 5 个名字」变成全部唯一；
  设置页 20 个控件从「3 个索引路径 + 3 个重名」变成全部可读唯一；
  芯片从 `/2/1/1/3` 变成 `chip-alpha`；展开两个 expander 后 24 个。

### 一个我自己写坏的 bug，被 agent 抓到

`Hit` 没有 `equals`。所以 `hits.equals(lastPublished)` 是**引用相等**——
我当天写的「只在内容变化时重发」**实际等于每帧重建 buttonList**，
而它看起来完全正常。补上 `equals`/`hashCode` 后这个检查才第一次真正成立。
**一个防抖机制在没有 `equals` 的值上，是假的。**

### 测试从「读源码找字符串」改成「加载真实 QML」

`QmlElementNamesAreReadableTest` 原来只是检查 `NavigationView.qml` 里含四个名字字符串——
那是**代理，不是性质**，而且改一下格式就红。现在它**加载每一个出厂文档、枚举真实发布表**，
检查可读 + 唯一；另加 `ShippedShellNamesEveryControlIT` 走真实合成 shell、切页、展开 expander。
**七个变异全部被杀**，其中「删掉 `Hit.equals」」会红在差异检测那条上。

顺带修掉一条**钉格式不钉性质**的断言：它用
`bridge.contains("target.clear();\n        for (Hit h : hits) {")` 钉「先无条件清空」，
我在两行之间插了名字去重的代码，于是**什么都没变却变红**。改成检查**顺序**
（clear 在前、`target.add` 在后、之间没有 return）。
这是本轮第三次遇到「断言钉的是格式不是性质」。

### 6.9 既有测试套件**分不清「提前警告」和「事后验尸」**

把警告改成**只在终局 outcome 里出现**（即退回原状：agent 移动完才读得到），
`AStraightLineNamesWhatIsOnItTest` **依然 9/9 全绿**。

这条不是新测试写得不好，是**旧套件本身有这个盲区**：危险一直在消息里，
测试断言的是「消息提到了危险」，而**没说危险什么时候可读**。
一个声称测了危险警告的套件，实际上对警告的**时效性**零覆盖——
所以「我们有测试」这件事本身在这里是假的。

新测试因此必须区分「发布了一个危险」和「危险**及时**送达」这两件不同的事：
`TheEarlyHazardWarningReachesActStatusTest` 驱动**真实的** `ActTickLoop` + 真实的 `MoveApplier`
+ 真实的 `act_status` handler，因为**丢掉一次传递会让所有更近的测试保持绿色**。

还有一个变异陷阱值得留给下一个人：
`reposition` 的 sameLine 分支和 `tick` 里的 fallback **两条路径都在重测距离**，
所以只冻结其中一条，**变异存活了**——另一条把活干了。
两条独立路径通向同一个性质，意味着单独任何一条都不是那个机制。
最终 `theDistanceFallsOnEverySingleTickNotOnlyOnTheTicksThatReSample`
断言**相邻 tick 之间**严格递减，因为 `SCAN_DRIFT=2` 让重扫每 8 tick 才落地一次。

## 6.10 输入层按 ADR-0005 逐条复核：两条机制**站不住**

对照 `ADR-0005` 的三类（免费真实 / 有收益的代价 / 纯代价）重新推导，
**不采信之前的结论**。结论：两条免费真实的机制（GCD 量化、按键掩码）成立；
两条**花时间**的机制，一条被误分类、一条是纯代价。

**（一）最小 jerk 剖面被误分类，且分类错误是承重的。**
ADR §2 把它列为「免费真实」，理由是「这三个约束不花任何代价」。两半都不成立：

- **它不免费**：同样峰值速率下，最小 jerk 需要恒速爬坡 1.17-2.0 倍的 tick
  （180°@30°/tick：7 tick vs 5；180°@5°/tick：42 vs 36），这还不含 4-8 tick 的反应延迟。
- **它也没买到那个性质**，因为 `shaped` 是沿曲线的**绝对位置**，却被加到**已经前进过的**
  `curYaw` 上（`LookController:285-288`）。ADR §2:40 自己就把这种形状列为
  **「最典型的假人特征」**——而实测轨迹正是单调爬坡进限速，
  不是预期的钟形。**性质从来没有在它存在的地方被测过。**

  正确的分类是**有收益的代价**，前提是把写入修好：可命名的性质是
  峰值/均值比 1.805 ± 0.153（F&H 1985, n=30），可测量的代价是 1.17-2.0 倍 slew tick。
  **照现状它是纯代价：花时间，什么也没买到。**

**（二）LOOK 通道的反应延迟是纯代价，且无人认领。**
ADR §3 只审了 `NavController.reactionDelayTicks()`（MOVE 通道）。
`LookController:166-186` 是**同一份代价的第二个实例**：没有可命名的性质，
**全项目没有任何测试**（`LookControllerTest:70` 只在注释里提到它），
理由写成「人的第一次转动在决定后 214-416 ms」——这正是 ADR §1 禁止、
§3.1 为 MOVE 通道撤回的「因为看起来像人」论证。而且在 KEEP 模式下它**连延迟都不是**。

**（三）按键吸附**：可达性约束成立（`MovementInputFromOptions:19-46` 可达掩码是 {-1,0,1}²，
`Entity.moveFlying:1226-1239` 归一化后速度由掩码决定），代价有界（≤22.5° 瞬时航向误差）。
但配套的「遵守它就拿回了 vanilla 物理」这句话在 **sneak 路径上是假的**。

**（四）GCD 0.15°** 成立且免费：从源码重推（`MouseHelper.deltaX` 是 int 鼠标计数，
`EntityRenderer:1097-1100` 算 `f = sens*0.6+0.2`、`f1 = f³·8`，`Entity.setAngles` 乘 0.15），
默认灵敏度 0.5 时 `f1 = 0.5³·8 = 1.0` 整，所以一计数 = 0.15°，可达集真是 `0.15·Z`。
代价 ≤0.075° 瞄准误差 = 5 格处 0.006 格、20 格处 0.026 格，低于任何方块面或怪物碰撞箱。
**已知偏差不是缺陷**：0.15 只在灵敏度 0.5 精确，真值是 `0.15·(sens·0.6+0.2)³·8`
（0.25 时 0.0515，1.0 时 1.2）。

**（五）不做「纠正性次级动作」的决定是对的**，但它依赖的前提现在不成立：
论证站在「最小 jerk 剖面末端速度本就归零」上，而控制器照现状**也没有末端速度**——
这加强了（一），不是独立结论。

**一份以仲裁为职责的文档，不能自己有过期内容**：ADR §3.1 的标题仍写
「反应延迟为什么是纯代价」，而 §3.1.1 已把它改判；`LookController:426` 仍写
「F&H 测得 1.75」，而 40 行后的常量说 1.75 是 Shadmehr 课程页上的数字。
**过期的标题和过期的数字，会让裁决者变成装饰。**

## 6.11 MCP 表面实测：**81 个工具**，不是我以为的 30 个

对着**活的**服务器（127.0.0.1:25599）`initialize` + `tools/list` 测量，不是读源码：

- **81 个工具 / 53,323 描述字符**（描述+schema 共 81,560，约 20.4k token）。
  此前记的 61,469 对不上任何一种组合口径。
- **`world_view` 不是最大项**：10,901 字符（13.4%），排第 2。
  **`act_set` 排第 1**，14,543（17.8%），其中 8,559 是 schema 而非描述。
- 成本集中：前 5 名占 40.6%，前 10 占 49.8%，前 20 占 63.4%。

**三个会让模型「选错」的缺陷**，每一个都活机复现 + 源码确认，都是真 bug：

**(a) `do_select_slot` 指向不存在的字段。** 它让 agent 去
`read_player_state` 或 `world_view` 的 `self` 段比较 **`heldSlot`**——
两处都没有这个字段，真正的字段是 `world_view sections=['inventory'] -> selectedSlot`。
而这恰好是那个**回复明说自己不是确认**的工具：agent 按指引走，就没有任何办法
确认热栏选择是否生效，只能假设它生效了。

**(b) `do_click_slot` 让 agent 用一个不存在的工具去确认。**
「Confirm with read_inventory」——81 个工具里**没有 `read_inventory`**。
这是那个**两种失败模式在协议层都静默**的操作（服务端锁窗口、windowId 不匹配变成 no-op）
所声明的唯一确认路径。**而现有测试 `ClickSlotDescriptionMatchesVanillaTest:144`
断言 `desc.contains("read_inventory")`——它正因为这个幽灵引用存在才通过。
一个钉住 bug 的测试，在 bug 存在期间无法失败。**

**(c) `packets_tail` 和 `packet_view` 在 tap 缺失时都回答
「Install it via the seam netty-tap tool first」。没有叫 `netty-tap` 的工具**，
真名是 `seam_netty_install` / `seam_netty_uninstall`。

**不做的事，以及为什么**：
不折叠 `do_*` 家族（闸门同质，但 11 个工具有 27 个不同属性名、每个动作的必填集不同，
折叠后大部分属性在多数动作下是死的，而且逐动作的说明文字仍要保留——
ADR-0004 已经记过这笔账：「结构化 schema 退化成散文」）；
不折叠 `world_view` 的六个 section（它们是**一个工具的参数**，不是同质家族）；
**不删 `scan_surroundings`**（它看起来是子集、且自称已被取代，但活机对比显示它做的是
**体积普查** dirt=162/grass=81/stone=72，而 `world_view` **只统计地表** grass=49/tallgrass=23。
它不冗余，**它对自己的描述是错的**——改描述比删工具便宜得多）；
不为了省上下文砍掉陷阱散文（每一段都命名了一次活机真实踩到的失败，
砍掉就是能力损失，必须如实记为能力损失）；
**在修掉 (a)(b)(c) 之前不要先加 resources 层**——给一个有三条错误交叉引用的表面写文档，
只会让错误更难被发现。

**resources / prompts 目前完全没有实现**：`initialize` 只宣告
`{logging, tools{listChanged}}`，`resources/list`、`prompts/list`、
`resources/templates/list` 全部返回 `-32601 Method not found`。
SDK 自带 `SyncResourceSpecification` 和 `SyncPromptSpecification`，所以这是接线选择，不是 SDK 限制。

## 6.12 原生操作缺口矩阵：**122 行，53 COVERED / 40 PARTIAL / 29 ABSENT**

读完 vanilla 全部 23 个 clientbound-C 包类、`EntityPlayer`/`EntityPlayerMP`/`InventoryPlayer`、
`PlayerControllerMP`、`GuiContainer` 面板家族，对 25 + 5 个工具逐行映射。

**关键边界：29 个 ABSENT 里，19 个需要服务器管理员**
（`/tp`、`/gamemode`、`/give`、`/kill`、`/summon`、`/effect`、`/weather`、`/time`、
`/difficulty`、`/setworldspawn`、给经验）。
**「玩家能做」和「服主能做」是两种风险等级**，而 Owner 要的「像人一样操作」属于前者。
真正「玩家能做但现在做不到」的只有约 **10 行**。

工具的**真实分层**（缺口矩阵只有对着这个分层才成立）：
1. **观察层**（R3 只读）：`world_view` 六个 section 是骨干。基本完整。
2. **裸包层**（R1）：11 个工具对应 11 个 C 包类。
   **12 个未映射的恰好是 vanilla 在 tick 内部或 GUI handler 内部发出的那些**——
   不是巧合，客户端自己不构造它们。
3. **tick 接缝驱动层**（R1/R3）：`act_set`/`act_plan`/`act_cancel`/`act_status`/`press_key_binding`。

**五个头号缺口**：

**(a) 附魔完全不可达。** 没有工具构造 `C11PacketEnchantItem`；
而 `GuiEnchantment.mouseClicked:85-99` 把三个附魔选项当作**原始矩形**做命中测试，
**从不加进 `buttonList`**，所以 `GuiReflect` 永远不会发布它们。

**(b) `GuiActions` 只驱动 `mouseClicked` + `keyTyped`，从不驱动 `mouseReleased`。**
所以 `GuiContainer.handleMouseClick` 的 **mode 4/5/6 全部 GUI 不可达**：
丢下、拖拽分割、双击收集；连同选项滑块和horse 背包按钮一起。
（`do_click_slot` 能裸发这些模式，但 GUI 路径不行。）

**(c) 移动词汇表只有四个词**：`Move.Kind{WALK,STEP_UP,DROP,BRIDGE}`。
没有攀爬、游泳、飞行、鞘翅、载具。`NavController` 自己写着
「walk_straight 不会游泳，走进深水会淹死玩家」。

**(d) 没有入站聊天读取器。** agent 能说话，**永远读不到回复**——
看不到服务器消息、玩家回应、死亡消息、加入/离开、插件警告、任务提示。
**这是「自动完成对局」最致命的一条**：agent 是聋的。

**(e) 五个 R-1 逃逸口**（`send_raw_packet`、`eval_java`、`invoke_method`、`write_field`、
`eval_ephemeral`）**可以表达所有非管理员的 ABSENT 行**，
代价是它们没有 schema、无法确认、不在沙箱内。

**已知的死代码**：`ActActuator.instantBreak:121` **在 core 里没有任何调用者**，
所以创造模式瞬间破坏不可达。

## 6.13 面板缺口矩阵：**27 个屏幕，今天只有 9 个可达**

对照 vanilla `GuiScreen` 全家族（不是只看我自己的 QML 面板）逐屏列出。

**可达性分三类**：
1. **KeyBinding 派生的**：背包 `E`、聊天 `T`、命令 `/`、快捷栏 `1`-`9`、旁观者菜单。
   `press_key_binding` 能到，因为它复现了 vanilla 自己的两次调用
   （`KeyBinding.setKeyBindState` + `onTick`）。
2. **方块右键派生的**：箱子、熔炉、工作台、酿造台、附魔台、铁砧、交易、传送门…
   `act_set interact kind='use'` 或 `do_use_entity action='INTERACT'` 能到。
3. **原始 LWJGL 键事件派生的——ESC 之后的整棵菜单树，全部不可达。**
   `Minecraft.runTick` 里的 `k==1 -> displayInGameMenu()` 位于 `while (Keyboard.next())` 循环内，
   **不在任何 KeyBinding 后面**，而 ESC（code 1）本身不在任何绑定里。
   `press_key_binding` 结构上就送不到它。**这一整棵都不可达**：
   暂停、选项、视频/控制/语言/音效/聊天/潜行者、资源包、统计、成就、开局域网。
   统计和成就**连第二个门都没有**：vanilla 自己的 `GuiInventory.initGui()` 清空 `buttonList`
   之后什么都不加，所以 `actionPerformed` 的 id 0/1 是死代码。

**这和 RSHIFT 面板是同一个结构性缺陷的两次出现**：UI 若由原始 LWJGL 键事件守门，
`press_key_binding` 就送不到。修法也已经证明过一次有效——
**不要造新路，去调那个键本来就会调的方法**。

**第二个更根本的缺陷：`GuiActions` 只调用 `mouseClicked` 和 `keyTyped`**，
从不调用 `mouseReleased`、`mouseClickMove`、`handleMouseInput`，也不按修饰键、不滚轮。
于是 `GuiContainer` 计算点击模式所需的输入有一半拿不到，
**每个容器面板都结构性地缺七个交互原语**：
shift 点击、点击外部丢弃、拖拽分割、双击收集、中键选取方块、**全部滚动**、创造模式标签页切换。
（shift 点击在包层被 `transfer_item` 部分补上，其余没有。）

**第三个：`GuiReflect` 只读四样东西**——
`buttonList`、声明的 `GuiTextField` 字段、容器槽位、`labelList`。
**凡是在 `drawScreen` 里画出来的东西都是不可见的**，包括：
**村民的交易报价（单项最大缺口）**、三个附魔报价、熔炉与酿造进度、信标金字塔状态、铁砧经验花费。
而信标和商店按钮的 `displayString == ""`，所以 `gui_snapshot` 只能给出**无标签按钮**，
agent 只能靠编码过的 `buttonId` 区分它们。

**第四个：快照指纹只哈希按钮标签和槽位数量，不含槽位内容**，
所以服务器重同步或一次熔炼完成**不会让旧快照失效**——
agent 会拿着一份已经过期的界面去点击。

## 6.14 输入层：两条机制落地 + **最小 jerk 剖面根本没进游戏**

上一节说剖面的分类错误是承重的。修完之后，**实测写入流**：

- **修之前**（150° 瞄准、40°/tick 上限）：
  `1.8, 10.1, 26.0, 40.1, 40.1, 32.1 °/tick`
  ——单调加速、两次顶死在限速上，正是 ADR §3 判为「最典型的假人特征」的形状。
- **修之后**：`1.8, 8.3, 15.9, 22.7, 26.4, 26.4, 22.7, 26.0 °/tick`

原因是剖面的**绝对位置**被加到了上一 tick 已经推进过的 `curYaw` 上，
等于每 tick 把整条累积剖面重复相加；再被限速夹平成恒速爬坡。
**它为什么活过了评审**：既有测试直接调用 `shapedProgress`，
所以它们对自己测的东西是对的——**缺陷活在曲线和写入之间的接缝里**。
修法是写入**增量**（`shaped - previous`），并把轴向比例**冻结在动作第一 tick**，
不再每 tick 按不断缩小的误差重算（那会让轨迹弯折）。

**新机制一：扫视主序列作为转动的速率律（有收益的代价）**
性质：一次瞄准的每 tick 转角**峰值低于**调用方的 `slewDegPerTick` 上限，
且被该幅度对应的扫视主序列界定——所以长转和短转**不会以同一个平速率执行**。
来源：Gibaldi & Sabatini 2020（doi 10.3758/s13428-020-01388-2），
`PV(A) = V_A + V*sqrt(A - A_th)`，`A_th = 1°`、`V_A = 40°/s`，
被试拟合 V 45.4-140.2，取中位 100.4。
交叉验证：该律在 9° 处预测 323°/s，对应他们表 5 的实测均值 160-414，
且拟合最低的被试就是实测最低的。
**承重的一半是到达判据**：光有上限时，调用方传 40，一个 35° 的瞄准会因为「数字到了」
而被判定为已到达，最后 35° 在一个 tick 里写完，速率律根本无从触发。
实测代价：最多多 2 个 tick（100 ms），且只在真的要转的瞄准上；
2° 和 5° 的瞄准仍然是 1 tick，代价为零。

**新机制二：反应延迟改为右偏抽样（有收益的代价，代价为零）**
性质：延迟是**有硬下界的右偏抽样**而非均匀带，所以跨多次行走的分布
低频端比高端更厚、偏度为正——均匀区间做不到这件事。
理由：延迟是时长，不能为负，而人类 RT 有不应期下界（~110 ms，
这也是 RT 实验要把过快反应剔除的原因）。能表达「下界 + 非负长尾超出」的最小模型
是一参数半正态 `|N(0,sigma)|`。
实测代价：**零 tick**，而且**更快**——均值 6.00 -> 5.54 tick（300 -> 277 ms），
每次行走快 23 ms。支撑集仍是 4..8。
刻意的取舍：没有把尺度拟合到文献的个体内标准差 40.0 ms——
那是 0.8 tick，而带宽本身的标准差就有 1.29 tick；
**比人自己还规律的延迟，和没有延迟是同一种破绽**。

**被否决的三条方向（按测量否决，不是按口味）**：
- **次级动作**：前提核实通过——剖面确实从静止出发、静止到达（`itStartsAtRestAndFinishesAtRest`），
  现象所关联的**速度过零**已经在曲线里，另建机制是花代价买不到东西。
- **把扫视做成离散跳跃 + 中间注视**：Gibaldi & Sabatini 表 5 给人类扫视**时长 31-68 ms**，
  而 `EntityRenderer:1094-1100` 每帧施加一次累积鼠标增量、`Minecraft:223` 跑 20Hz——
  一次扫视是 0.6-1.4 个 tick，**它的内部结构在写入通道里根本无法表达**，
  而 200 ms 的注视就是 4 个 tick 站着不动。**纯代价，且是通道承载不了的形状。**
- **手部微颤**：纯代价。它唯一买到的性质是「不是一个完美积分器」，
  而它花掉的正是 ADR-0005 §4 列出的**agent 相对人类的优势**——精度。
  十字线会飘，右键就可能点不中方块面。
  **把 agent 推向人类更差的那个数，正是「超越人类而不是降低性能」的反面。**

**一处诚实记录**：实现者自己的第一版速率测试是**空过的**——
它拿平均速率去比该律的**峰值**速率，而平均就是峰值除以 1.805，所以不可能失败。
变异检查抓住了它，改写成「时长差 + 写入流峰值」才真正有区分力。

## 6.15 「发包成功 ≠ 操作成功」：审计 2.5 **现在真的关闭了**

`do_set_creative_slot` 原先直接 `sendTyped(...)` 回答 "sent set_creative_slot 0"。
现改为读回槽位并给出 `CreativeSlotVerdict{SET, NOT_SET, UNREADABLE}`——
**刻意没有 `SENT`，也没有 `UNCONFIRMED`**，与 `TransferVerdict` 同一形状，
测试把常量个数钉在 3。

**读不回时它拒绝发送**（返回 error，明说没有发出任何东西）。
这是一个真实的设计失败带来的正确结论：实现者第一版测试只断言「它是个 error」，
而**无客户端时传输层两种代码都失败，所以那条断言固定版和回退版都过**。
他因此测出了三个存活的变异，**改的是设计而不是断言**：
一个永远无法观察结果的写入就不该发出去。

**vanilla 根因（从仓库内源码克隆，不是凭记忆）**：
`NetHandlerPlayServer.processCreativeInventoryAction:1078` 把整个处理函数
**门控在 `isCreative()` 上**——非创造模式下 C10 不是被拒绝、不是被应答、
是**根本什么也做不了**。另外 :1104 要求 `slotId >= 1 && slotId < 45`，
所以我那个实机复现用的 **slot 0（合成输出槽）无论是否创造模式都不可写**。
**我的复现是双重无效的。**

### 全量清点：七个 `sendTyped` 站点

- **会确认**（以结果命名）：`transfer_item` / `do_dig` / `do_place_block` / `do_set_creative_slot`。
- **裸奔但描述里明说了**（照写不算缺陷）：`do_click_slot`（"reports only that the packet was SENT"）、
  `do_set_abilities`（"NOT CONFIRMABLE"）、`do_select_slot`、`do_client_status`。
- **裸奔且毫无声明——同类缺陷的最后两处**：`do_use_entity`（"sent use_entity <ACTION> #<id>"）、
  `do_entity_action`（"sent entity_action <ACTION>"）。已派工逐动作定夺：
  潜行/疾跑/开背包能从玩家自身状态读回，攻击能从目标血量读回，
  而 `INTERACT`（开箱、骑船、和村民交易）通常读不回——**所以答案不是统一的**。

### 一条方法论：**脚手架自己骗人**出现过三次

变异检查本身也会说谎，而且每次都差点被我当成证据报上去：

1. **编译不过的变异被当成了测试结果**（应当是硬失败）。
2. **锚点匹配 0 次或 2 次时，静默测的是基线**——变异根本没生效。
3. **classpath 顺序让未变异的类赢了**（`core/target/classes` 被并发 Maven 重写）。

还有第四个不是他的错、但同样让结果不可信：
`core/target/classes` 正被同伴的 Maven 跑重写，导致编译间歇性失败，
所以他改为对**冻结的 `_scratch/core-deps.jar`** 编译。
**一个变异检查必须先证明它自己跑的是变异后的代码，否则它测的是空气。**

## 6.16 面板读取：把「只能点」和「只能读」拆成两半

上一节说 `GuiReflect` 只读 `buttonList` / 声明的 `GuiTextField` / 容器槽 / `labelList`，
**凡是在 `drawScreen` 里画出来的都不可见**——而村民交易报价是单项最大缺口。

现在 `gui_snapshot` 返回**两半**：
- `elements`：**能点**的（原有语义）。
- `panel`：**只能读**的。它作为**顶层 JSON 键**而不是 element——
  给它一个 id 就是在邀请一次并不存在的点击。

**panel 是通用优先的**：先问每一个 `Container` 它持有哪些 `IInventory`
（真实显示名、大小、内容），**然后**才由各类型的 overlay 补上**不是背包**的那部分
（铁砧的重命名栏与经验花费、附魔台的三项报价、熔炉与酿造的进度、
信标金字塔状态、horse 的装备槽与属性）。
分派是对真实 vanilla `Container` 类做 `instanceof`，
所以**服务器自己的 `ContainerChest` 子类仍然读作箱子**。

**槽位读取用 `GuiStack` 取代了原来的数字 itemId**：
注册名（`minecraft:diamond`——**和 `SlotView`、`world_view` 用的是同一个身份**）、
显示名、数量、damage/meta、最大堆叠、附魔、NBT 键列表、封顶的 NBT blob。

**点击的判定：`ClickVerdict{CONFIRMED, NOT_CONFIRMED, UNREADABLE, REFUSED_STALE,
NO_ELEMENT, REFUSED_DESTRUCTIVE}`——同样没有 `SENT`。**
点击会等服务器的 resync 再判定，并拒绝落在面板之外的点击。

**陈旧性判据（这一条是设计里最有价值的部分）**：
`epoch` 是**屏幕身份**（换了 `GuiScreen` 对象就变），`fingerprint` 是**地址空间**
（类名、按钮数、按钮标题的顺序敏感哈希、槽位数）。
**指纹故意不含槽位内容**——因为槽位的身份是它的**索引**，
所以**移动一个物品是点击的正常后果，不是陈旧**。
把内容算进指纹会让 agent 每做一次背包操作都必须重新快照——
**那不是更安全，那是把一个正确行为误判成错误。**
两个半边各由 `theEpochAndTheFingerprintAreBothNeeded` 和
`movingAnItemDoesNotInvalidateTheReference` 分别钉住。

## 6.17 「发包成功」的最后一处，和一个更深的同类错误

七处 `sendTyped` 站点已全部有结论。`do_use_entity` / `do_entity_action`
**逐动作**判定，而且**刻意不统一**——因为可观测性本身就不统一：

**`do_use_entity`**
- `ATTACK` — **确认**。轮询 `getHealth()`（datawatcher 索引 6）。
  **目标离开世界算命中而不是读不到**——对一个生物实体来说，那就是致命一击在客户端的样子。
  发送前先拒：物品/经验球/箭/自己（`processUseEntity:932` 会踢人）、以及任何非生物目标。
- `INTERACT` / `INTERACT_AT` — **裸奔但声明**。理由值得记：
  `processUseEntity:920` 调 `interactWith` 并**丢弃它的 boolean**，什么都不会回来。
  但**这个动作是有效的**——拒绝它等于删掉开箱、骑船、和村民交易，
  那是这个工具存在的全部理由。所以诚实的答案是「声明为未确认，并告诉调用方该去读什么」。
  `INTERACT_AT` 另有自己的死路：基类 `Entity.interactAt` 对除盔甲架外的一切都返回 false。

**`do_entity_action`**
- `START/STOP_SNEAKING` — **确认**。**读 datawatcher 索引 0 的位 1**。
  ⚠️ **不能用 `EntityPlayerSP.isSneaking()`**：它在 `:684` 覆写成返回
  `movementInput.sneak`，**也就是按键绑定本身**——用它「确认」等于**拿自己的输入确认自己的输入**。
  这是「发包成功」那一族更深的一层：**一个读回读到了自己写出的那个通道。**
- `START/STOP_SPRINTING` — **裸奔但声明**。服务端写入和潜行**完全相同**，
  但 `EntityPlayerSP.onLivingUpdate:800-819` **每 tick 从移动按键重算**这个标志，
  所以**任何重读都无法把服务端的答复和键盘分开**。
  **这就是为什么两个在 vanilla 里长得一样的动作，在这里得到了相反的待遇**——
  不是随意，是可观测性的差别。
- `STOP_SLEEPING` — 确认（`isPlayerSleeping()`）。
- `RIDING_JUMP` — 裸奔 + **发送前拒绝**：除非骑着**已上鞍**的马，
  因为 `EntityHorse.setJumpPower` 整个函数体都在 `isHorseSaddled()` 里。
- `OPEN_INVENTORY` — 确认 + **发送前拒绝**（除非骑着**已驯服**的马，`openGUI` 由 `isTame()` 门控）。
  **并且描述里明说：它开的是马匹的箱子，绝不是玩家自己的背包**（后者是 C16，E 键发的）。
  **旧描述暗示了后者**——又一个活机上会让人选错的谎。

## 6.18 上下文预算：按规则推导，不是按变红的测试名

第一次削减声称 −62.8%，打破 8 条既有测试后**诚实降到 −49.8%**；
第二次又回归，因为**保护集悄悄变成了「某个测试恰好断言过的事实」**——
**没有任何测试断言的事实，就自动可延后**。错误的机制。

修复是**回到规则本身**，对每一项延后内容写出这句话：
**「一个模型若永远不去取用它，会做出什么不同的行动？」**
写不出来就说明它不可延后。写出这句话并因此回到内联的七项：

1. `sections` 的六个名字（不取用就**只能全价轮询，或者猜一个名字吃拒绝**）。
2. `inventory` 的 `{'unsampled':true}` **同时意味着读取失败**，不是背包空。
3. 相信威胁消失前，**必须用相同 profile 和 radius 重新观察**（否则以为苦力怕死了）。
4. `'side'` 是放置所倚靠的那个面；`'pos'` 是**绝对坐标不是网格坐标**。
5. 读不到的列上的 `'drop'` 是**未测量**，不是测到了竖井——所以那里的 `"deep"` 不致命。
6. 缺失列的 `profile` 意味着**该 profile 没输出它**，不是这列没有高度。
7. `air` 显式为 null = **刚刚变得读不到**（缺失 = 没变）。

**写不出那句话的九项保持延后**：每动作伤害点数、「41 次耐久 ≈ 1.5 棵树」、
碰撞盒底边距离的注意事项、xpProgress 无死区等。

**最终诚实数字：world_view 10,234 → 5,985（−41.5%）**，注册表整体 −16.3%，
真正**删掉**的只有 1,204 字符，其余是延后或确实无用的删除。
**它第二次把自己的预算上限从 5,600 调高到 6,400，而不是把事实砍回去凑一个自己发明的数字。**

## 6.19 移动词汇表从 4 个词变成 6 个，以及一个「守卫红了两次才发现」的收尾

`Move.Kind` 原先是 `{WALK, STEP_UP, DROP, BRIDGE}`。现在加上 `CLIMB` 和 `SWIM`。

**CLIMB**：沿梯子/藤蔓上下。到达判据问 `act.onClimbable()` 而不是 `onGround()`——
**梯子能把人撑住却从不设置 `onGround`**。

**SWIM**：横向和上浮一格。**空气是搜索状态里的第二份预算**（姿态、方块、空气），
空气不够的游泳**被拒绝，而不是被定价**。

### 危险警告没有被删掉——这是本条最重要的部分

`NavHazard.Kind.WATER` **原封不动**，因为 `walk_straight` 仍然不会游泳。
`SwimSteering` **自己**每 tick 发布一个 WATER 危险，**同类**，
所以现有那些「遇到 WATER 就取消」的调用方仍然会取消；但 `detail` 现在是关于**游泳**的事实
（剩余空气、每个水下 tick 减一、-20 时溺水），而不是关于行走的。
**删掉这条警告被 `aSwimIsRefusedWithoutWarning` 钉成失败**，
另一个方向由 `aWalkOnDryLandCarriesNoWaterHazard` 守着（不臆造危险）。
planner 另外拒绝超过 300 空气 tick 的游泳——**这个能力不能规划一次溺水**。

### 一个自己引入的真缺陷，被变异逼出来

`NeighborGen.addWalk` 拒绝任何持有梯子的目标格。**这恰好破坏了功能**：
横向走进梯子柱的**底部**正是玩家上梯子的方式，而那个格子确实可站（下面有地面）。
守卫删掉了这条入口边，于是梯子只有当路线**恰好从柱内起步**时才可达——
而它**每一个**原始攀爬测试都是从 (0,64,0) 即柱内起步的，所以全都看不见。

守卫已从 `addWalk` 移除，并保留在 `addStepUp`/`addDrop`/`addBridge` 上——
那里跳跃、坠落和搭建才是错误动作。真正阻止玩家走进**柱中**格子的是 `Stance.hasFloor`：
下面的格子是梯子，梯子不是地板，于是姿态不可站，那条边根本不会被提供。
**这正是正确的接缝，因为那是唯一必须被回答的问题。**

### 为什么原来的变异存活了

`addStepUp` 产生的是 `from.offset(dx, 1, dz)`——**正交邻格上一格，绝不是正上方**。
**梯子柱无论守卫如何都不可能被规划成「一串跳跃的台阶」**。
所以这是生成器**形状**的事实，不是守卫的事实。

### 最终判定：两个守卫是**互相冗余的纵深防御**

单独去掉任一个：**存活**（全绿）。两个一起去掉：**被杀**（2 条红）。
所以测试钉的是**行为**（「梯子柱绝不被规划成跳跃，且梯子可从岸边走进」），
**而无法指明是哪个守卫提供的**——这是正确的粒度：
**一个指名具体某行的测试，会在那两行被合法互换时每次都失败。**

顺带核实了一个我差点搞错的事实：**vanilla 对梯子格子真的给出两个都为真的答案**。
`WalkNodeProcessor.func_176170_a:209-265` 里梯子的材质是 `circuits`，
`Block.isPassable:371-374` 是 `!material.blocksMovement()`，
而 `Material.circuits` 的 `blocksMovement()` 是 false，所以 `isPassable` 为真、
`return 0` 分支被跳过、方法返回 1（WALK_CLEAR）；
同时 `Block.getCollisionBoundingBox:499-502` 对每个方块都返回盒子，所以 `BlockProbe` 判它 SOLID。
**两个答案都对，而 `Stance.hasFloor` 的存在意义就是解决这个分歧。**

## 6.20 活机复验：agent 现在能听、能说、并且不肯撒谎

对着真实客户端（`run-mcp-overlay.bat` + `verify_protocol.py`）：

- **`chat_read` 在 tap 未安装时**回答
  「packet tap not installed — this is NOT an authoritative 'nobody spoke'.
  Install it with **`seam_netty_install** first.」
  ——**既没有假装「没人说话」，又指向了修复后的真名**。
  这条同时活机验证了审计 6.11(c)：旧名 `netty-tap` 已经不在任何回复里。
- **装上 tap 后**：`chat_read` 读回 agent 自己发出的话，结构化且不靠解析散文：
  `{"kind":"PLAYER","channel":"CHAT","key":"chat.type.text","text":"<Player0> hello from ..."}`
  `kind` 与 `channel` 正交（死亡消息是 DEATH 但走 SYSTEM 通道）。
- **未读优先**：第一次读到 1 条并推进游标，第二次读返回 `entries: []`。
  **空闲回合的代价是一个空列表，不是整段历史。**
- `do_enchant_item` 已在活机表面存在。
- 旧 key `to` 被明确拒绝并指出改名。

**这是「agent 是聋的」这条缺口的关闭证据。** 在此之前，
agent 看不到服务器消息、玩家回应、死亡消息、加入/离开、插件警告——
北极星「自动完成对局」在这条上是不可达的。

## 6.21 面板与原生操作的剩余缺口（面板那一半还没做完）

审计 6.13 说 27 个屏幕里只有 9 个可达。`GuiDrive` 已经补上了**读取**的一半
（`gui_snapshot` 的 `panel` 半边，通用容器 + 各类型 overlay），
但**下面三条仍然是缺口**，没有 agent 在做：

1. **ESC 菜单树整体不可达**：ESC 由 `Minecraft.runTick` 里 `while (Keyboard.next())`
   的原始 LWJGL 键事件派发，不在任何 KeyBinding 里，所以 `press_key_binding` 结构上送不到。
   暂停、选项（视频/控制/语言/音效/聊天/潜行者）、资源包、统计、成就、开局域网——
   **整棵**。修法已经证明过一次有效（RSHIFT 面板 -> `open_overlay`）：
   **不要造新路，去调那个键本来就会调的方法。**
   统计和成就连第二个门都没有：vanilla 的 `GuiInventory.initGui()` 清空 `buttonList` 后什么都不加。

2. **七个交互原语在每个容器面板上都缺**：`GuiActions` 只调 `mouseClicked` 和 `keyTyped`，
   从不调 `mouseReleased`/`mouseClickMove`/`handleMouseInput`，也不按修饰键、不滚轮。
   于是 `GuiContainer` 的 mode 4/5/6 全部 GUI 不可达：
   **丢下、拖拽分割、双击收集**，连同中键选取方块、**全部滚动**、创造模式标签页切换。

3. **村民报价是单项最大缺口**——`GuiDrive` 的 overlay 已经覆盖它，
   但需要**活机验证**，而这一轮没有跑（`GuiDrive` 的活机 IT 需要显示器）。

### 6.22 活机最终状态：**19/19**

`_scratch/smoke/verify_protocol.py`，真实客户端（`run-mcp-overlay.bat`），**每条断言都对着世界而不是对着工具回复**。

**QML 面板从「能看见但叫不出名字」到完整闭环**。设置页实测 **14 个控件，全部具名且互不相同**：
`navHome` / `navKernel` / `navChips` / `navSettings` / `windowClose` / `windowMaximize` /
`settingsFullbright` / `settingsGamma` / `settingsName` / `settingsAdvanced` /
`settingsAdvancedChevron` / `fxDetails` / `fxDetailsChevron` / `fxMasterToggle`
—— **零个索引路径**，14 个矩形各不相同，翻页后 14 -> 16 个元素。

**点击现在诚实报告 `verdict=NOT_CONFIRMED` 并说明原因**（按钮没有服务端可观测的状态），
而不再是含糊的「成功」。

**这一轮修掉的三个自己犯的错**，都是同一条纪律的不同侧面：
1. 快照取在 `open_overlay` **之前**，所以报告的是「屏幕曾经是什么」而不是「现在是什么」——
   **陈旧引用**，和面板指纹修的是同一类。
2. 判定成功时去 grep 回复里的 `"error"` 字样，**结果匹配到了 `isError` 字段本身**——
   这正是本项目付出过代价的那个错误（把一次诚实的失败判成工具失败）。
   正确做法是**读标志**。
3. `do_set_creative_slot` 我连着两轮用 `slot 0`，而
   `processCreativeInventoryAction:1104` 要求 `slotId >= 1 && slotId < 45`，
   **slot 0 是合成输出槽，本来就不可写**。工具的拒绝是对的，我的复现是错的。

**新增了一个第三种结果 `[NOT-EXERCISED]`**：
这个单人世界没开作弊，而 `processCreativeInventoryAction:1078` 把整个处理门控在
`isCreative()` 上，所以 C10 在这里**根本不可能被执行**——工具自己就把原因说了出来
（"This client is NOT in creative mode"）。
**为环境原因报红会训练读者忽略红色，报绿则是本项目一直在付的那种费。**

## 6.23 一个自称过时的工具，方向是反的（已修 + 变异验证）

`scan_surroundings` 的描述写着：「world_view 是更丰富的后继……详尽决策请优先用它，
这个只留作紧凑心跳。」

**这不是措辞问题，它把模型指到了一整类问题的错误工具上。**

两个工具的**计数集合不同**：
- `WorldScanner.census` 遍历**整个采样立方体**（`dx`、`dy`、`dz` 三重循环），
  所以它能回答「我附近有多少泥土」。
- `LocalGrid.blockCounts` 是**按列**直方图，因此**只统计地表**：
  地板下的泥土、屋顶下的矿、任何在地表以下的东西**完全不在里面**，
  而 `world_view` 自己的描述就警告过它的直方图是 surface-only。

所以把「有多少」这个问题送去 `world_view`，得到的是一个**自信的错误答案**：「这里没有」。
`find_block` 的描述其实早就承认了这点——它的存在理由就是 `world_view` 的缺失**不能证明任何事**。

**已修**：描述现在明说「问**有多少**时用这个，不用 `world_view`」，
并说明 `world_view` 在其他方面更丰富、以及 `find_block` 回答「最近的一个在哪」。

**测试钉的是行为不是措辞**：`ScanSurroundingsCensusesTheCubeNotTheSurfaceTest`
用一块铺满立方体底部的泥土板，断言计数是 `5*9*9`——
**那块板完全在地表以下，地表普查永远看不到它**。
变异验证：把普查限制成只扫顶层（`if (dy != r) continue;`，即 `world_view` 的形状）
-> **测试变红**。已恢复。

**顺带记一个我自己犯的错**：我的采样器第一版返回 `minecraft:air`，
测试红了并报 `{minecraft:air=125}`——**正确地指出 `countable()` 排除的是裸 `"air"`**。
但生产走的是 `LocalGrid.wireName`，它**剥掉命名空间**，所以生产从不产出 `minecraft:air`。
**是我的采样器谎报了契约，不是守卫有缺陷。**
（不过这个守卫确实很窄：它只排除裸 `"air"`。如果将来有任何路径产出命名空间拼写，
空气就会被计入。值得知道，不是现在要改的缺陷。）

## 6.24 联网核对文献：**宽泛的新颖性主张是假的**

方法：Crossref DOI 解析、出版方/PMC/arXiv 全文、GitHub 原始源码，
以及仓库自己的 vanilla 客户端。**每一条「成立」都是在原文里读过的，不是读摘要。**

### 成立的（且其中一条我们说保守了）

- **Fradet 2008 的三个数全是他们的**：§3.1 原文「Submovements were found in **40%** of all
  recorded movements」，表 1 Small×Discrete = **57 ± 4**。而且它确实**反驳**「corrective」这个标签
  （type 1 = 运动终止，大目标更多见；types 2/3 与峰值速度**负相关** R²=0.71/0.75）。
- **F&H 1985 我们说保守了**：原文是「C = 1.875 … mean **1.805**, SD **0.153**, n=30 …
  It was **accepted at the 0.01 level but rejected at the 0.05 level**」。
  所以**这不是一次干净的失败**——它在 α=0.01 下是接受的。代码里的措辞
  （「accepted at alpha=0.01 and REJECTED at alpha=0.05」）比 ADR 的更准确，ADR 应采用。
- 1.75 确实是 Shadmehr 课程页上的（第 **9** 页，不是两份文档都写的第 8 页）；
  `10.3389/fnhum.2015.00009` 确实是催产素遗传学论文。

### 新查出的失败（本会话第五、六次引用错误）

1. **ADR 把 250–300ms 归给 Woods et al.，而他们报的是 231ms**（实验一，n=1469；
   去眼动校正 213ms，SD 26.8）。**把一个合理数字焊在正确 DOI 上**——和前四次同一个形状。已改。
2. **ADR §4「超越人类」表格仍在用 109ms**——**正是 §3.1 花 12 行撤回的那个数**。
   一份用自己撤回的数字去论证自己的文档，是**自我拆台**。已换成 231/213ms。
3. **我自己上一轮对 VPT 的撤回是错的**（见 6.23 前的更正）：「720 V100 × 9 天」
   **确实在 VPT §4.2**，原文照录。**我对一条真实引用发了撤回。**
4. **我上一轮把写入通道「订正」成 60+ Hz，那个订正也是错的**：
   `Minecraft.java:223` 是 `new Timer(20.0F)`，`:1113-1116` 按它调度 `runTick()`，
   所以**整条输入通道是 20 Hz**。**内联在 runTick 入口不蕴含逐帧。**
5. **Game-TARS 把两张基准表混成一张**：基准是 **MCU**（">800 diverse and easy-to-verify tasks"），
   不是「MC-800」；Minecraft 表 3 的基线是「Policy-based Agents in Minecraft」。

### 新颖性：宽泛主张为假，窄主张成立但**必须写成合取式**

**A 格 —「用 LLM 驱动真实 Minecraft 客户端（鼠标+键盘）」：确实为空。**
实际存在的：Mineflayer/mindcraft/mc-agents（protocol-level，**无渲染、无 GUI**）；
MineRL（环境）；VPT + STEVE-1（BC/RL 策略，20Hz 环境，**回路里没有 LLM**）；
JARVIS-VLA（ACL Findings 2025, doi 10.18653/v1/2025.findings-acl.920，"first VLA model in Minecraft"，
1000+ 原子任务，+40%——**模仿学习，不是 LLM 规划**，且未能确认真实客户端而非环境）；
Game-TARS（Minecraft，但论文没有确立是真实未修改客户端）；Lumine（Genshin）。

**Game-TARS §2.1 原文**：「This design obviates the need for special modeling of action durations
or complex temporal dependencies」——**那是一篇声明自己不做我们做的事的论文。**
上一次失败在于**把先前工作当成反驳**；这一次先前工作**基本是互补的**。

**B 格 —「人类输入模拟层」：拥挤，而且这里有一条对我们直接相关的发现。**
反作弊系统在检测**旋转的 GCD 模式作为瞄准辅助的标志**：
Gaia 的 `AimA.java` 原文「Detects rotation snapping. Checks for GCD (Greatest Common Divisor)
patterns in rotations that indicate aimbot/aim-assist modifications.」
**我们刻意做的 GCD 量化，正是真人产生的那个特征，也正是反作弊拿来找机器人的那个特征。**
这不是反对我们做它的理由——真人就是这样被识别的，而作弊器不是——
但它意味着**「像真人」在这个领域有对抗性含义**，值得写进 ADR。

**所以诚实的表述是**：不是一个最高级说法，而是**一个合取**——
没有人把显式的人类运动控制模型（最小 jerk 速度剖面、在游戏自身灵敏度导出的量子上做 GCD 吸附、
扫视速率律）作为**真实 Minecraft 客户端**上的输入层来实现。

## 6.25 ESC 菜单树：**通了，而且每屏都走和人一样的路**（已修）

**根因**（核实过）：ESC 是 `Minecraft.runTick` 里 `while (Keyboard.next())` 中的
**原始 LWJGL 事件**（`Minecraft.java:1939-1944`，在 `currentScreen == null` 分支上）。
`press_key_binding` 送不到它——**ESC=1 不在任何 vanilla `KeyBinding` 里**：
整个默认集合是 `17/30/31/32/57/42/29/18/-99/16/-100/-98/20/15/53/60/63/0/87/64/65/0/0/10-18`，
所以 `KeyBinding.onTick(1)` 分派到任何东西都没有，它回答 `bindingClaimed=false`。
而 `gui_press_key` 走 `GuiScreen.keyTyped`，vanilla 只在**另一条分支**（屏幕已开时）才到那里。

**修法（用的是本仓库已证明一次的模式）**：一个新接缝 `EscMenu` + 一个新工具 `open_pause_menu`，
它**调用 ESC 本来就会调的那个方法** `Minecraft.displayInGameMenu()`（`Minecraft.java:1478`）。
**不是合成键事件。**

**下面每一屏都不需要第二个门**，因为 `gui_snapshot`/`gui_click_element`
本来就能驱动 vanilla 的真实按钮：

| 屏 | vanilla 方法 | agent 走的路 | 与人同路 |
|---|---|---|---|
| ESC -> 暂停菜单 | `displayInGameMenu()` | `EscMenu.openPauseMenu()` | 是 |
| 暂停 -> 选项 (0) | `displayGuiScreen(new GuiOptions(...))` | 点真实 `GuiButton` | 是 |
| 暂停 -> 统计 (6) | `displayGuiScreen(new GuiStats(...))` | 同一个按钮、同一个 id | 是 |
| 暂停 -> 成就 (5) | `displayGuiScreen(new GuiAchievements(...))` | 同一个按钮 | 是 |
| 暂停 -> 开局域网 (7) | `displayGuiScreen(new GuiShareToLan(this))` | 同一个按钮；vanilla 在非单人世界禁用它 | 是 |
| 选项 -> 视频/控制/语言/聊天/潜行者/资源包/音效/皮肤/完成 | `saveOptions()` 然后 `displayGuiScreen(...)` | 真实按钮，每个 id 都核实已发布 | 是 |

**统计与成就用的是什么门**：暂停菜单自己的按钮（id 6 / id 5）。
**审计的前提对了一半**：它们**从背包面板**确实不可达
（`GuiInventory.initGui()` 清空 `buttonList` 后什么都不加，所以 `actionPerformed` 的 id 0/1
从那个方向是死代码）——**但 ESC 树有一扇真门，用的就是它。没有另造第二条路**，
这正是上面四屏都不需要第二个门的原因。

**一处我自己的描述错误，被测试抓住**：`GuiIngameMenu.initGui` 在
Share-to-LAN 那行**之前**声明 `GuiButton guibutton;`，所以 `guibutton` 是**按钮 7**，
末尾那句 `guibutton.enabled = isSingleplayer() && !integratedServer.getPublic()`
门控的是**开局域网**，不是统计/成就。**统计(6) 和 成就(5) 是无条件启用的，
多人服务器上也能用。** 我的第一版描述写反了，代码和描述现在都按实测来。

**仍然不可达（诚实列出，不是打包票）**：
- **控制面板的每一行按键**：`GuiControls.keyBindingList` 是 `GuiKeyBindingList`（`GuiListExtended`），
  **从不在 `buttonList` 里**，所以 agent 能开控制面板、能点 Reset/Done、能翻 INVERT/灵敏度/触摸屏，
  但**不能滚动、不能改键**。
- **资源包的包行**：同样是 `GuiListExtended`；Open Folder 和 Done 可用。
- **统计/成就的网格**：General/Blocks/Items/Mobs 四个标签（id 1-4）是按钮、可达；
  **网格本身是画出来的或 `GuiSlot`，所以没有元素 id、也没有滚动**。
- 直播（选项 107）在没有直播时打开 `GuiStreamUnavailable`——**那是 vanilla 自己的拒绝，不是缺口**。

## 6.26 删掉一处纯代价：LOOK 通道的反应延迟（已删，量化）

审计 6.10 判定它是纯代价，**但没有人删**——它在代码里又活了一整轮。现在删了。

`LookController` 的反应延迟：`reactionTicks` 字段、它的计费点、`heldYaw`/`heldPitch`、
它依赖的 `capLanded` 闸门，以及那条「reacting, aim at yaw=… (first move at N)」的 outcome，
全部删除。**`NavController` 那一处完全没动**——它有独立理由、独立测试，
把两个通道混为一谈正是这次要拆的误会。

**理由**（ADR-0005 §1 禁止的那种论证）：「人的第一次转动落在决定后 214-416 ms」——
**没有出处**，而且这个项目的文献核对刚刚证明「合理的数字 + 正确的形状」正是它撤过四次的那种错误。
审计说它「没有可命名的性质、全项目零测试、KEEP 模式下连延迟都不是」。
我核实过：`TheReactionDelayIsOneDrawNotARaceTest` 和
`AMoveIsKeyPressesAndWaitsLikeAPersonTest` **都不引用 `LookController`**——
**这个性质确实从未被测量过**。所以它按 ADR-0005 自己的分类是**纯代价**，必须删。

### 量化的能力变化（这是重点，不是附注）

| | 首次移动发生在 |
|---|---|
| **删之前** | tick **5–12**（随 4–8 的延迟抽样，加上子格增量量化为 0 的 tick） |
| **删之后** | **每一个朝向组合都在 tick 1** 进入剖面 |

实测样本（角度 @ 速率 -> tick）：45°@40 -> 6；150°@40 -> 9；90°@40 -> 6；
45°@5 -> 8；150°@5 -> 9；90°@5 -> 8；45°@2 -> 10；90°@2 -> 11；150°@2 -> 12；10°@5 -> 5。
**一个真实转向快 200–550 ms。** 这不是「更像人」，这是**在战斗中更快地转到位**。

### 微校正路径**未变**

原代码只在 `!capLanded` 时收费，理由是「179 到 -179 的 2 度回绕过去是一个 tick 落地的，
必须继续这样——真手做这种微校正不需要先决定任何事」。
删掉延迟不改变这个行为，测试确认。

### 测试钉的是**缺席**而不是存在

`TheLookChannelHasNoReactionDelayTest`（5 条）的不变量是：
**转向中的瞄准在 tick 1 必须发出 `setRotationInterp`（剖面在花它的弧），
绝不能发 `setRotation`（那是一次保持）。**
收费的延迟会在整个 4–8 tick 里每 tick 发一次 `setRotation(cur)`，
所以**在任何抽样下 tick 1 都是一次 snap**——这就是判别式。

**变异验证**：把字段、计费点和 `capLanded` 闸门按原形恢复 -> **5 条里 4 条变红**，
主断言在第 0 次迭代就报「10.0-degree aim at a 2.0 deg/tick cap HELD the rotation on its
first tick … tick one said "reacting, aim at yaw=10.0 (first move at 4)"」。

**一条纪律**：它把自己的改动和同模块另一处失败做了 **A/B 对照**
（把自己的改动 stash 掉，`SelfPlayEvalTest` 的失败消息**逐字节相同**），
所以它能说「那条红在我来之前就在」而不是猜。这比任何自我判断都可信。
