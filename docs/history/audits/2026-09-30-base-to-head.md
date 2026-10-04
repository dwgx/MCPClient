# 全面审计报告 — BASE 主线后的全部改动

审计范围：`5eec5b9`（"clean base (v1.0.0)", 2026-07-10）→ `46249ca`（HEAD）。
**302 commits · 726 files · +105,849 / −125 行**。core +71,080 · dwm +11,441 · board +9,071 ·
pg +1,346 · lwjgl2-shim +1,122 · client +423/−68。

方法：12 条只读审计轴并行（安全内核 / compat 信任链 / act 运行时 / 寻路 / 感知 /
craft+gui / fabric+传输 / client 冻结 / dwm 契约 / board+pg+shim / 探针脚本 / 文档+测试），
每条轴的发现必须逐条对源码取证才准上报。**本报告顶部的每一条 CRITICAL 与 HIGH 都由主 agent
独立复核过源码行**（复核方式见每条的"复核"标记）。

测试基线（本窗口实跑）：core 1125 · board 229 · dwm 42 · lwjgl2-shim 63 · client 13 ·
pg-engine 5 = **1477 条，0 失败 / 0 错误 / 0 跳过**。所以红测试不是可用信号，全部缺陷都是
现有套件抓不到的。

---

## 0. 结论

**代码质量高于同规模项目的常见水平，缺陷集中在"新写的功能自己骗自己"这一个家族。**
安全脊柱（签名链、fail-closed 传输、reference-free 边界）在受攻击面是干净的——
没有找到任何能让未签补丁 arm 的路径。问题全在别处：

1. **两个 CRITICAL 都在寻路**：搭桥确认和可通行性判定共用一个"不是空气就算有"的谓词，
   于是水、岩浆、高草、火把全被当成"地板已就位"和"可以走进去"。这不是边角料——
   `RouteExecutor` 存在的全部意义就是搭桥过河。
2. **家族特征 A：图例与代码脱钩**（本仓库自己记录过三次的病）。共 14 处，
   包括 `platformCondition` 签了名却没人读、`dev_probe` 标称 R2 实际按 R3 放行、
   `find_block` 静默截断半径却回报调用方要的那个值。
3. **家族特征 B：真机探针会给出假证据**。`is_ticking` 取了世界时钟却不比较它；
   `mutate.py` 在本机对**每一个**变异都报 SURVIVED；`live-dwm-probe` 在 Windows 上根本跑不起来。
4. **家族特征 C：声称存在但没接线**。`RenderFrameInjector` / `FileWatchDeployer` /
   HUD / 按键绑定 / 持久化 / craft 执行全部零生产调用者。

---

## 1. CRITICAL（2 条，已复核）

### C1 · 搭桥的"确认"谓词把水和岩浆当成已铺好的地板
- **位置**：`core/.../drivers/plan/RouteExecutor.java:287`
- **代码**：
  ```java
  if (act.blockPresent(cell.x(), cell.y(), cell.z())) {
      blocksSpent++;
      beginWalk(m);
      return ActOutcome.running("floor confirmed at " + cellOf(cell) + "; walking onto it");
  }
  ```
- **自证**：`ActActuator.java` 自己的 javadoc 写着「`blockPresent` 是
  `getMaterial() != Material.air`……实测对水、流动水、岩浆、砂砾、高草、火把都返回 true」。
  规划器**故意**挑这类格子去搭桥，于是第一 tick 就走"已确认"分支，
  `rightClickBlock` 永远不调用，桥不存在，块预算照扣。
- **后果**：过河变成游泳（深水溺水），过岩浆变成被推进岩浆烧死。整轮输出都在报
  "floor confirmed … walking onto it"。
- **复核**：已直接读 `RouteExecutor.java:283-290` 与 `ActActuator.java:50-58` 确认。

### C2 · 规划器把岩浆当成可通行空间，违反自己接口里写着的契约
- **位置**：`core/.../drivers/plan/LiveBlockView.java:86` + `core/.../util/BlockProbe.java:103`
- **代码**：
  ```java
  public boolean isPassable(int x, int y, int z) {
      BlockProbe.Solidity s = BlockProbe.at(world, x, y, z);
      if (!s.wasRead()) { unread++; return false; }
      return s.isEmptySpace();
  }
  ```
  而 `BlockProbe.decide` 的最后一行是 `return hasCollisionBox ? Solidity.SOLID : Solidity.AIR;`
  —— 任何没有碰撞盒的非空气方块（含岩浆）都被判为 `AIR`。
- **自证**：`BlockView.java:38-48` 的 javadoc 明写「不是简单的 `!isSolid`……把"不实心"当成
  "可以走进去"的规划器会走进岩浆」。实现正是它警告的那一种。
- **旁证**：同仓库的读侧 `LocalGrid` 已经把岩浆标成 `walk = -2`（取自 vanilla
  `WalkNodeProcessor.func_176170_a`）。**同一个问题，仓库里两个答案互相矛盾。**
- **复核**：已直接读 `LiveBlockView.java:76-89` 与 `BlockProbe.decide` 确认。

---

## 2. HIGH（21 条，全部已复核）

### 寻路 / 操控

| # | 位置 | 缺陷 |
|---|---|---|
| H1 | `RouteExecutor.java:234` | 坠落守卫拿 `moves.get(index).from().y()` 当基准，1.5 格容差——而 `NeighborGen` 会规划 1~3 格的合法 DROP。于是任何 2~3 格下降都被判定"掉出路线"而中止，消息还说假话。`Move.Kind.DROP` 实为死代码。 |
| H2 | `MoveApplier.java:216` | `LocomotionAxes(forward, strafe, false, false, false)`——跳跃轴硬编码 false。`NeighborGen` 生成的 `STEP_UP` 没有任何东西能执行它。1 格台阶是最常见地形，路线在第一步就死，且报错指向几何而不是缺跳跃。 |
| H3 | `ActTools.java:230/240/254` | `act_set` 逐通道「解析即提交」。`{"move":…,"look":{"mode":"spin"}}` 先提交了 move，再在 look 报错——调用方看到 `isError` 且无载荷，玩家却已经在朝目的地走。`act_plan` 先全量校验，不犯这个错，两个工具原子性不一致。 |
| H4 | `ActTickLoop.java:68` ↔ `:95` | `record()` → `applier.apply()` → `store()` 是读-改-写，而 `submit`/`cancel`/`store` 全是无条件 `records.set(...)`。工作线程落在 `apply()` 窗口里的写入被游戏线程用旧记录覆盖：`act_set` 回 `accepted:true` 而槽里还是旧意图；`act_cancel` 同理。 |

### 安全脊柱

| # | 位置 | 缺陷 |
|---|---|---|
| H5 | `SeProtectedObjects.java:121` + `SeRemoteMonitor.java:232` | **L1 P-SECURE 的裁决解析器不在受保护集里。** `SeRemoteMonitor` 用 `Json.readObject(line)` 解析裁决再 `if (allow) return allowed()`；而受保护清单里 `io.http` 包**只列了 `HttpFacade`，没有 `Json`**。`redefine_class`（R-1）可以热替换 `Json` 让它永远返回 `{"allow":true}`，整个 L1 墙失效。文件自己的注释记录过同族缺口（"alpc 前缀漏了"，已修）。 |
| H6 | `ValueCodec.java:69-73` | 收窄修复覆盖了 int/short/byte，**漏了 long**：`return n.longValue();` 无范围检查、无整数性检查。`3.7` 写进 long 字段静默变 `3`，`1e300` 变 `Long.MAX_VALUE`。`ValueCodecNarrowingTest` 只测了 byte/short/int。 |
| H7 | `CompatEngine.java:232-239` | L0 内容绑定门**失效即放行**：`hasCanary` 由 `canaryClassBytes()` 推导，catch 里也置 false。一个钉了 `expectedCanaryHash` 但 canary 为空/抛异常的补丁，会在零行为校验下 arm。`ContentHash.matchesExpected` 的 javadoc 说的正是相反的规则。 |
| H8 | `SeToolRequirement.java:56` | `dev_probe` 是**唯一**不在三张侧表里的注册内建工具。门控用 `Ring.forBuiltin(name, R3)` 回退 → 实际按 R3 放行且无任何 capability，而注册/文档/REST 列表全都说 R2。自降到 R3 的主体仍能读活游戏与 GL 状态；`revoke_capability(CAP_WORLD_READ)` 关不掉它。 |
| H9 | `DebugTools.java:660-700` + `Ring.java:201-202` | `debug_open_thread` / `debug_close_handle` 只在 Ring 里（R0），三张侧表全无，且 `openThread` 是九个 `debug_*` 里唯一不跑 `guard()` 的。可铸造任意活线程的冻结 L6 句柄而不带 L4/L5 与 `CAP_DEBUG_CONTROL`。 |
| H10 | `IoManager.java:123/135/144` | L6 判权与 L7 校验读的是**调用方的活 map**，之后才 `freezeArgs` 拷贝。被检查的值不是被使用的值——破坏了文档承诺的 L7 不变式与 L6「只读句柄永不升级」。 |
| H11 | `McpCore.java:170` | `AllowAllGate` 是唯一的 `AccessGate` 实现，且是生产接线。`MmAccess` / `HookTools` / `DebugTools` 里所有 in-handler 的 L4/L5 `require()` 全是空操作——文档承诺的「与门组合 / 纵深防御」不存在，H8/H9 因此没有第二道防线。 |

### 感知

| # | 位置 | 缺陷 |
|---|---|---|
| H12 | `WorldViewCapture.java:275-276` | `inventory()` 中途抛异常时**返回已累积的部分列表**，differ 按槽位序号比较 → 失败点之后的所有槽位被报成 `cleared`。同文件里 `effectsOrNull` 的 javadoc 明写"半份列表才是危险的那种"，而 inventory 没做这个处理。模型会以为自己刚丢了镐、食物和火把。 |
| H13 | `ToolRegistry.java:700/717` | `find_block` 把 radius/limit 原样传下去（`BlockFinder` 静默夹到 32 / 64），**未命中消息却用调用方要的那个值**。`radius=200` 时实际只搜了 32，回复是「200 格内没有 diamond_ore」。这正是仓库自己写过 `ToolDescriptionsMatchTheirBoundsTest` 要禁的形状。 |

### 探针 / 启动脚本

| # | 位置 | 缺陷 |
|---|---|---|
| H14 | `mcp_probe.py:245-250` | `is_ticking` 取了世界时间 `t=`、打印、**从不比较**。判定只看 `isGamePaused()==false`。游戏线程挂起（`debug_suspend_thread`、集成服务器卡住、资源重载）时守卫放行，探针量的是冻结的世界并把它报成代码缺陷。同仓库的 `require_act_ticking` 两采样比较才是对的形状。 |
| H15 | `nav-astar-probe.py:233-251` | 「一次带 duration 的 use 不被报成拒绝」断言的是**字符串不存在**。传输错误 / 空回复都满足"不存在" → 记 **PASS**。根因在 `Mcp.call:134-136` 把 JSON-RPC error 回复压成 `{"text":"","isError":false}`。这是"全绿且每条断言都是空转"的一个实例。 |
| H16 | `mutate.py:50/64/80` | 硬编码 macOS `JAVA_HOME` 并**无条件覆盖**；命令是 POSIX 的 `./mvnw`（本机是 `./mvnw.cmd`）；`FileNotFoundError` 未捕获 → 退出码 **1 = SURVIVED**。本机上它对**每个**变异都报"断言没覆盖这个行为"——正是它 docstring 说绝不能产生的结果。而且 `finally` 里的字节码还原也跟着炸，变异留在 `target/classes`。 |
| H17 | `run-mcp.bat:22-33` | 主启动器**什么都不检查**：jar、JBR、argfile、游戏目录全部不测，结尾 `pause` 丢弃退出码。兄弟脚本 `run-mcp-overlay.bat:28-40` 三个 jar 全查并 `exit /b 3`。 |
| H18 | `live-dwm-probe.py:657-660` | `alive()` 调 `pgrep`——Windows 没有，未捕获 `FileNotFoundError`，第一次调用就崩。而且它不 import `require_ticking` / `allow_unfocused`，却依赖窗口有焦点。**在项目的主目标机器上不可运行。** |

### dwm

| # | 位置 | 缺陷 |
|---|---|---|
| H19 | `QmlGuiScreen.java:72-76` | caption 的 minimise 设 `keepSurfaceOnClose=true` 保留 surface，理由是"下次打开是瞬时的"。但唯一的打开路径 `DwmHotkey.java:134` 每次 `DwmEntry.createScreen()` 都 `newInstance` 一个**全新** screen。保留的 Skia `DirectContext` 永远不可达 → 点击最小化会**泄漏**一个 Skia 上下文，而用户可见行为与关闭完全相同。 |
| H20 | `PageHome.qml:62-72` | 两个 enabled、可命中、有高亮的按钮（"Open kernel" / "Reload scene"）**没有任何 onClicked**。仓库专门为此写过 `SettingsOfferNoDeadSwitchesTest`，但那个守卫只扫 `PageSettings.qml`，所以同样的缺陷就摆在隔壁一行。 |

### 契约

| # | 位置 | 缺陷 |
|---|---|---|
| H21 | `client/src/.../TextureUtil.java:58` 与 `:245-248` | `client/src` 里有**两处真实的行为性 vanilla 改动**（透明像素扫描边界 `p_147949_2_.length` → `[0].length`；`GL_CLAMP` → `GL_CLAMP_TO_EDGE`），直接改了冻结真相源，而没有任何 compat 补丁覆盖它们。**同一批里另外两次同类改动被刻意回滚**（`78ec865` KI-4、`c677a7e` GuiScreen），理由都写着规则。GL_CLAMP→CLAMP_TO_EDGE 恰好是 `platformCondition("lwjgl3")` 那种平台条件补丁的教科书案例，不是基线编辑。 |

---

## 3. MEDIUM（节选 24 条中的高价值项）

- `CompatEngine.java:162/182` — 一个从未 arm 的后继补丁会**先把已被 disarm 的前任移除**再失败（签字与 VERIFIED 之外的门不反馈回 supersede 判定）。
- `CompatEngine` 之外的图例脱钩：`platformCondition` 签了名但 `appliesToRuntime()` 根本不看它；票据的 `minClientVer` 签了名、双方都不比较、且由被约束方填写。
- `WorldViewDiff.java:408-410/94/75-77` — `env`/`grid`/`target` 三段在"未采样"时静默，diff 模式读作**没变**。实体/效果/空气都有显式的未采样标记，就差这三段。
- `WorldViewDiff.java:94` + `LocalGrid.java:22` — 网格 record 带玩家脚下坐标，所以**走一步整张网格就全量重发**。"diff 省 token"对移动中的 agent 不成立，而注释说的是相反的话。
- `LocalGrid.java:250/375` — 网格与体积普查读方块**不查 `isBlockLoaded`**。未加载区块 → 报 `drop:"deep"`（"必死"）且无 walk 键：一个被肯定断言的、可通行的无底洞。`BlockProbe` 就是为这条规则写的，其他七个读方块的类一个都没遵守。
- `LiveBlockView` 起点：`Plan.blocksNeeded()` 零生产调用者 → 需要 N 个方块而手上是空的，计划照样启动，缺口在半个桥的尽头才发现。
- `IoSupervisor.java:88-92` — 容量拒绝被记成**工具故障**，把无辜工具送进熔断；`abandoned` 只在真正返回时递减，卡住的一批永不清零。
- `IoSupervisor` 超时 5000ms 与其包裹的 `GameBridge.onGameThread` 默认 5000ms **相等** → 内层的诚实超时不可达，正常的重活被判故障、反复重试、反复熔断。
- `CraftController` — `CraftController`/`LiveCraftWindow` **零生产调用者**（只有 `craft_plan` 接线，且它自己的描述就说了没有执行工具）；`settle()` 的 `paysFor` 只比物品+元数据，看不见"放进了已有堆"这个唯一的分歧。
- `RouteExecutor.java:471` — 路线**成功收尾**时无条件 `releaseUseKey()`，那是 INTERACT hold 通道自己的状态。同时提交路线与 hold 时，拉弓场景下这等于**射出一支没被要求的箭**。
- `ActIntentParser.java:137-138` — `kind:"use"` 永远返回空中使用，即使给了 `block`；`InteractController` 里那条对块使用的分支因此不可达。
- `LookController.java:144-146` — `AimMode.ONCE` 的未命中分支**没有截止时间**（`durationTicks` 只在 KEEP 路径）。追不上的目标会让 LOOK 槽永远 ACTIVE。
- `MovementInputInstaller.java:119` — 空闲时每 tick 写 `setSprinting(false)`，而类 javadoc 承诺"空闲时行为与被包裹的输入完全一致"。
- `FltManager.java:105` — 丢弃 `installOn` 返回的 transformer，三个内建网络 advice **永远无法卸载**；`McpCore.stop()` 连引用都没有。
- `FltDynamicManager.java:213-225` — 安装失败路径不留句柄，`installOn` 已注册的 transformer 永久附着且 `list_hooks` 看不到。
- `Trace.java:72-73/90-94` — `cancel()` 释放订阅记录但**不失效派发缓存**，被取消的订阅者连同监听器（及 chip）永久驻留。测试注释把这条记成"故意"。
- `Board.java:66` + `OfficialChips.java:95-98` — `shutdown()` 后 `init()` 的 `addAndEnable` 因 id 已存在而**不重新启用**：整套官方 chip 永久禁用且不再订阅。
- `DwmContract` — `open()`/`close()` 不在 `GlStateGuard` 内（只有 `frame()` 在）；`frame:150` 门控使"合成永不被跳过"这句文档为假；caption 的 X 在自己的指针派发栈上重入销毁。
- `Pg` — `HardenEngine.java:135` 的 `CheckClassAdapter(cw, false)` 只做结构检查，检测不出 JVM 验证器会拒绝的字节码，却报 `HARDENED` 并覆盖原类。

---

## 4. LOW（节选）

`docs/dwm/README.md:134-137` 宣传两个**不存在**的恢复分支；`docs/macos/known-issues.md:3-4` 指向一个从未存在的文件；
CI 注释里的模块测试数是 core 253 / board 172（实为 1125 / 229）；`live-dwm-probe` 的检查数在三篇文档里是 30 和 33；
`client/pom.xml:114` 的 Guava 注释说"锁在 17.0"而版本是 33.6.0；两个新测试文件里有 emoji（CLAUDE 红线）；
`UiScaleContractTest:145` 是 `assertEquals(..., 1, 1)`；`NativeDebugOpLiveIT:109` 是 `assertTrue(..., true)`；
`DisplayTest:111` 与 `SelfEffects…Test:157` 是 `assertTrue(true)`；`OfficialChipsTest:53` 是被前一行蕴含的 `>= 5`；
`Ki11DwmHotkeyPatch.java:256` 引用不存在的 `Ki11ContentBindingTest`；`L3 SnapshotVerifier` 是死代码。

---

## 5. 各轴"查过且干净"（同样重要的证据）

- **compat 信任链**：唯一的 arm 点是 `if (!signer.verify(m)) skip`；`defaultTrustAnchors()` **无任何回退分支**；
  根阈值强制（需同时在文档 rootKeys 与烘焙集内、SPKI 字节相等、keyId 去重）；四份随包资源互相一致
  （`root-metadata.json` 的 targets key 与 `kernel-ed25519.pub` 逐字节相同）；受保护类规范化
  （数组描述符、`$Inner`、斜杠形式）无法绕过；`supersedes` 链校验真实；`PatchLease` fail-closed。
- **L2/L3/L4/L5 算子与边界**：每条相邻层边界都被 `EveryGateIsPinnedAtItsBoundaryTest` 钉住。
- **L6**：掩码上限、区间、归属、每主体配额、确定性回收、`subset` 方向全部正确。
- **P-SECURE 传输**：拒绝/掉线/畸形帧/超时全部 fail-closed；服务端仅回环、共享密钥常量时间比较、
  帧长上限、默认 `DenyAllCompatAuthority`。
- **HTTP**：鉴权门在任何路由分发之前；常量时间比较；非回环无 token 拒绝启动；无法解析的 host 当暴露。
- **`KeGameDispatcher.awaitOrCancel`**：超时与中断**都**取消已排队的 future（H7 那条泄漏确实修好了）。
- **`LdrRedefiner`**：两道独立守卫，无法被 talks 诱导去改写受保护类。
- **SSE**：队列有界 1024，所有出口 `finally` 退订，`MAX_STREAMS=4`，换行注入已转义。
- **board ↔ core 零硬依赖**：board 无一处 import `net.marcloud.mcp.core`；所有游戏接触都是
  `Class.forName` 加全捕获；Matrix 全部先快照再迭代，无 CME 路径。
- **pg 真的 fail-safe**：pass 异常与校验失败都保留原字节；Mojo 写临时文件 + `ATOMIC_MOVE`；
  重复加固被 `pg$dec` 指纹挡住且字节相同。
- **shim 每帧零分配**：`Display.update` = swapBuffers + pollEvents + Mouse.poll + Keyboard.poll，
  全部走定长环形缓冲。
- **dwm 模块契约**：适配包之外**零** qml4j/Skija 类型；**零** core import；qml4j 是 pin 不是 vendor；
  帧内 `enter/leave` 配对且 `leave` 在 finally；坐标契约端到端正确（Y 只翻一次、uiScale 只上一次）。
- **dwm 的 25 个 QML 文件没有一个是已删后端的残留**（唯一未被引用的是 `FluentProgressBar.qml`）。
- **感知"一 tick 一快照不撕裂"是真的**：Minecraft 在 `runTick` 之前排空 `scheduledTasks`，
  时钟在 `runTick` 入口推进。共享地址空间真的共享（六个地方都在第一个冒号处剥离命名空间）。

---

## 6. 没做的事（12 条轴的"not done"合并 + 恢复出来的私有工作区记录）

### 6.1 声称存在但零生产调用者

| 项 | 证据 |
|---|---|
| `RenderFrameInjector` / `RenderBridge` / `RenderFrameAdvice` | 渲染帧 seam 全套实现并有测试，唯一调用者是它自己的测试；`RenderBridge` 的 javadoc 说"dwm 在场时反射接线"，而 dwm 里没有 `ComposeCompositor` |
| `FileWatchDeployer` | `ldr/` 里实现了"保存文件即热部署"的正式开发工作流，无人构造或启动 |
| HUD 子系统 | `HudMatrix` / `Panel` / `RenderSignal` 有 headless 测试，但没有人发布 `RenderSignal`，`CoordinatesHudChip` / `FpsMeterChip` 说自己是"渲染层的活"而渲染层不消费 |
| 按键绑定 | `PinMatrix` 路由 `KeySignal`，除测试外无发布者；`DwmHotkey` 走的是兼容补丁直读 `Keyboard` |
| 持久化 | `Store` / `Persistable` / `DataView` 实现并测试，`ChatLogChip` 也实现了 `Persistable`，但游戏里没有任何主代码 `new Store(...)` → 聊天计数跨会话不落盘 |
| craft 执行 | `CraftController` / `LiveCraftWindow` 零生产调用者；只有 `craft_plan` 接线且它自己说"没有执行合成的工具" |
| `L3 SnapshotVerifier` | 整层未被接线，文档描述的回滚/冻结保护不在运行系统里 |
| `McpCore.stop()` | 无生产调用者，它实现的拆卸逻辑从未在真实生命周期跑过 |

### 6.2 从恢复出来的私有工作区（`.ai-notes/`，2026-07-17 快照）对 HEAD 的裁定

**仍未交付：**
1. **P.8 包→高层事件 reducer**（PROGRESS.md 显式 defer）——`core/src/main/java` 里不存在任何 Reducer 类型。
2. **KI-1 远景天蓝接缝**——WON'T-FIX-NOW，真因被 2026-07-15 的调研重新判定为几何/UV 接缝，零填充补丁 live 证伪。
3. **重操作可配更长 marshal 超时**——`GameBridge.onGameThread(task)` 仍是硬编码 5s 默认。
   （审计独立发现了它的后果，见 MEDIUM「超时相等」。）
4. **KI-10 残留**（L0 canary 未被签名）——仍在，且审计发现它现在还会**失效即放行**（H7）。

**已在 HEAD 交付（快照说没做，其实做了）**：E.3 官方 chip 装机、W6 六个 typed 工具、
S2/S6/F3/F4/F5 全部 tech debt。

**已撤回而非未完成**（不要当 backlog）：Desktop ClickGui 启动器、FakeSoftware phase-2、
skiko 真模糊、三后端真机验证、A.9 overlay —— 以及 `dwm-gl` / `dwm-imgui` / `dwm-skiko` /
`dwm-compose` 四个模块，全部被 owner 决策删除。

**快照本身已失效**：`STATUS.md` 描述 9 个模块（含四个已删后端），HEAD 的 reactor 是
`lwjgl2-shim, client, core, board, dwm, pg/*`。任何只在 STATUS 每模块段落里记的待办，
必须重新推导而不是相信。

### 6.3 本窗口实测的三个环境阻塞（非产品缺陷，但挡住继续）

1. **CI 在 `main` 与 `mcp-core` 上仍红**（2026-09-06 起未再跑）。根因已定位：
   `dwm/pom.xml:73-84` 只随包 `skija-windows-x64` 与 `skija-macos-arm64` 原生库，
   ubuntu runner 上 `libskija.so` 找不到 → `SvgTintPaintsAtEveryAlphaTest` 3/3 失败。
   `io.github.humbleui:skija-linux-x64:0.143.16` 在 Central 存在（已 HTTP 200 验证），
   加一条 runtime 依赖即可，且符合那份 pom「一个 jar 跑所有平台、不用 os-activated profile」的既定意图。
2. **文档写的 Windows 启动命令跑不起来**：`run-mcp*.bat` 默认 `JBR_HOME` 指向 gitignored 且
   不存在的 `_tools\jbrsdk-25.0.3-...`，而 `scripts/jvm-args-mcp.txt:23` 的
   `-XX:+AllowEnhancedClassRedefinition` 是 JetBrains Runtime 专属 flag，Temurin 25 直接拒绝启动。
   （同仓库的 `jvm-args-mcp-macos.txt:28-31` 早就把这件事记下来了。）
3. **`build-jars.bat` 第 2 步在干净机器上做不出 classpath 缓存**：`dependency:build-classpath`
   从仓库而非 reactor 解析 client/board。本窗口实测失败，`-am install` 后成功。
4. **签名私钥无恢复路径**（关键风险）：owner 备份 `F:\Project\MCPClient-backup-20260717-1852.zip`
   里的 `scripts/secrets/{kernel,root}-ed25519.key.b64` 是 **2026-07-14 那一轮的**，
   与随包的 2026-08-26 信任链**不匹配**（用生产签名器验证：派生签名对烘焙公钥验不过）。
   已放到 `~/.mcp-keys/*.stale-2026-07-14`。**当前信任链的私钥在本机没有副本**——
   换密钥就没有恢复路径。
5. **一个测量口径的提醒**（不是缺陷，但它是审计里两个数字差 11 的全部原因）：`@Test` 声明数
   和 surefire 的 "Tests run" 是**两种测量**：core 有 7 个 `*LiveIT` 类共 11 个 `@Test`，文件名
   不匹配 surefire 的默认 includes（`Test*` / `*Test` / `*Tests` / `*TestCase`），因此由 failsafe
   收（`core/pom.xml` 自己写着这一条），而 `skipITs` 默认 true —— 所以 `mvn test` 的 "Tests run"
   比 `grep -c '^\s*@Test'` 少这 11 个。两个数都对，别拿一个去校另一个；DwmAndEnvFix 顺手把
   `.github/workflows/build.yml` 里写死的 "core (253) + board (172)" 删了，换成产生实时数字的命令。

（上面第 5 条与"环境阻塞"不是一类，只是同一次实测里顺带定下来的口径说明。）

---

## 7. 裁定：已修 / 已驳回 / 仍未完成

本节写于修复与第二轮 review 之后。审计正文（第 1–6 节）保持原样，描述的是 **BASE 时的状态**；
这一节描述 **现在**。两者不一致的地方，以本节为准。

### 7.1 已修，且有会变红的回归测试

每条都做过「改回旧写法 → 测试变红 → 恢复 → 测试变绿」的变异验证，不是靠阅读判断。

| 编号 | 修法 | 变异验证 |
| --- | --- | --- |
| C1 | 搭桥确认改用**身份**（方块是否为计划所铺的那一种）而非"不是空气" | 已验 |
| C2 | 可通行性统一到 vanilla `WalkNodeProcessor` 判定；岩浆永不通行、不可作桥面 | 已验 |
| C2 补 | 坠落守卫：3 格以内无伤（对照 `EntityLivingBase.java:233`） | 已验 |
| 寻路 | 1 格台阶必须 jump（`stepHeight = 0.6`，对照 `EntityLivingBase.java:208`） | 已验 |
| 安全 | `io.http` verdict codec、`ValueCodec` long 收窄、L0 canary 声明门、TUF §5.3 检查 | 已验 |
| 安全 | `dev_probe` 门、`debug_*` 门 | 已验，真机 `R3` 下正确拒绝 |
| 安全 | `RootCeremonyCli` 现在**一定**签发带 expiry 的根文档 | 已验 |
| act | CAS 防 submit/cancel 丢更新；`act_set` 全量校验后提交 | 已验，真机 |
| act | 拒绝 `kind` 承载不了的 block target | 已验，真机 |
| 感知 | 背包"全有或全无"；clamp 后回显真实范围 | 已验 |
| harness | 真比较 tick clock；JSON-RPC error 不压平；skip 走 `EXIT_SETUP` | 已验 |
| dwm | 标题栏 X 在 dispatch 内 dispose `QmlView`（use-after-dispose）→ 改为出栈后再释放 | 已验 |
| board | `Board.shutdown()` 同时清 matrix 与 Trace | 已验 |

### 7.2 第二轮 review 抓到的、且已修的新问题

这几条不在第 0 节的 2 CRITICAL + 21 HIGH 里，是 review 阶段新发现的：

1. **我自己的修复引入的回归**：`RouteExecutor.verify()` 用 `floor(y) == planY` 判到位，
   而 `Stance.isStandable` 认为半砖/楼梯有碰撞盒就是地板，于是规划出的 y=64 路线在世界
   里落在 63.5，**被自己的执行器判为未到达**。已改为带宽判定
   （`ARRIVE_HEIGHT_SLACK = 0.55`，容下半砖顶面、仍拒绝整整一格的落坑）。
2. **`isPassable` 的两个代码没人钉**：`WALK_FENCE`、`WALK_OPEN_TRAPDOOR` 改判为通行，
   1214 个 core 测试全绿。已补整张判定表的枚举测试。
3. **我自己引入的 act 回归**：`refuseUnusableTarget` 测的是 `containsKey`，
   于是模型习惯性填的显式 `null` 被当成"提供了一个方块目标"而拒绝——
   `{"kind":"hotbar","hotbarSlot":3,"block":null}` 本该选槽位 3。已改为测**值**。
4. **`craft_plan` 把"读不到背包"说成"背包是空的"**：`CraftInventory.from(null)` 得到空包，
   于是输出一份自信的、具体的、错的"去捡 4 根木棍"。已改为报读取失败。
5. **`find_block` 在未加载区块里读空气**：矿石被报成"那里没有东西"，而新图例刚承诺
   miss 可信。已加 `isBlockLoaded` 前置检查，并改写图例说明 miss 的真实含义。
6. **第三份 time-of-day 归一化**：`Math.abs(wt) % 24000` 不是取模——
   世界时间 -1 与 +1 归到同一 tick。已提取 `WorldViewCapture.timeOfDay()` 单一副本。
7. **`scripts/mutate.py` 的模块推导用 `/` 切路径**：Windows 下 `board\src\...` 整条路径
   匹配不上模块表，静默退回 `core`——正是它自己注释里抱怨的那个失败模式。
8. **`live-dwm-probe` 的缓存 PID 分支没保护**：`_pid_alive` 在 `tasklist` 缺失时抛
   `FileNotFoundError` 逃出 `alive()`，杀死探针——与它自己"降级而不是崩溃"的设计相反。
   同时修了 `EPERM` 被读成"进程已死"。
9. **`world_view mode=diff` 的 "token saver" 说法不成立**：`LocalGrid` 锚定在玩家身上，
   一移动就整块重发。已改正类注释与 grid 分支注释，说明真实成本；**没有**改编码格式，
   因为那会改所有调用方的线上格式，属于契约决定而不是 diff 修复里的顺手改动。

### 7.3 已驳回

- **DWM minimise 保留路径**：审查后确认无法重新显示，`DirectContext` 会一直泄漏。删除，
  不保留。
- **`is_ticking` 等一批"过度纠正"型测试**：单独加的测试不构成回归证据（改前也绿），
  不计入 7.1 的变异验证表。

### 7.4 仍未完成（不是缺陷，是欠账）

这些是**功能没接线**，不是**代码写错了**，审计里归在第 6.1 节：
P.8 packet→高层事件 reducer、`CraftController` / `LiveCraftWindow` 生产接线、
`RenderFrameInjector` / `RenderBridge` / `RenderFrameAdvice`、`FileWatchDeployer`、
HUD 子系统、keybind 发布者、persistence/Store 接线、L3 `SnapshotVerifier`、
`McpCore.stop()` 生命周期接线、可配置长任务 marshal timeout、
KI-1 远景天蓝接缝（真因已改判为几何/UV）、KI-10 残留。

### 7.5 仍然阻塞

见 6.3，未变：JBR/DCEVM 缺失、当前信任链私钥无副本、CI 未重跑（已加 `skija-linux-x64`，
待验证）、`client/src` 契约问题（`TextureUtil.java` 两处真实 vanilla 行为改动，
尚未决定是回退做 compat patch 还是追签补丁）。

### 7.6 本轮测试数

```
lwjgl2-shim    63
client         13
board         233
core         1226
dwm            58
pg-engine       5
合计         1598      0 失败 0 错误
Python harness 46      全部通过
```

口径提醒见 6.3 第 5 条：surefire 的 "Tests run" 不含 `*LiveIT`，两个数别互校。
