# 调试这个客户端:全部可用手段,以及各自的边界

2026-07-31。写这份是因为**我们有一整套调试能力却没用上** —— 前几轮排查 dwm 的渲染问题时,
我一直在用 `eval_java` 手工读像素,而这个内核自带 JVMTI 原生调试器(断点、单步、读写局部变量)、
类热替换、以及一套 GUI 自动化工具。三份交接都写着"JVMTI 缺失",而真实原因只是
**`.dll` 从来没在 macOS 上编译过**。

现在编好了,能力全开。这份文档记:有什么、怎么开、什么时候用哪个、以及每个手段会怎么坑你。

---

## 0. 能力总表

| 手段 | 用来回答 | 需要什么 | 风险 |
|---|---|---|---|
| `eval_java` | "这个字段现在是什么值" | 无 | 低 |
| `glReadPixels` 读目标 framebuffer | "用户真的看得见吗" | 无 | 见 §4 两个能杀客户端的坑 |
| 读离屏层像素(经 Skia) | "场景画出来了吗"(与上一条对比可二分) | 无 | 边界不检查会段错误 |
| **JVMTI 断点 / 单步 / 局部变量** | "这行代码到底跑没跑、当时参数是什么" | 编译 agent + `MCP_JVMTI=1` | **断点会挂住渲染线程** |
| **`redefine_class`** | "在不改源码的前提下插桩计数" | agent(热替换需要) | 改错会让类失效 |
| `gui_snapshot` / `gui_click_element` | vanilla GUI 自动化 | 无 | **看不到 dwm 控件**(§5) |
| `scripts/live-dwm-probe.py` | 30 项真机回归 | 客户端在跑 | 它自己也会有 bug(§6) |
| `scripts/nav-astar-probe.py` | 内核侧:寻路/移动/感知 | 客户端在跑 | 见 §10 |
| `scripts/live-hold-probe.py` | 内核侧:INTERACT hold 通道 13 项 | 客户端在跑 | 自己布置前提(会改世界),见 §10 |
| `scripts/live-nav-probe.py` | 内核侧:四方向/**对角线**/1 格台阶 | 客户端在跑 | 会铺平场地,见 §10 |
| `scripts/live-look-probe.py` | 内核侧:LOOK 追踪(KEEP)11 项 | 客户端在跑 | 会生成/移动实体,见 §10 |
| `scripts/live-route-probe.py` | 内核侧:`act_set move.route` COMPLETE | 客户端在跑 | 见 §10;Windows 5/5 + 突变体 |
| `scripts/live-act-plan-probe.py` | 内核侧:`act_plan` sidecar COMPLETE | 客户端在跑 | 见 §10;Windows 3/3 + 突变体 |
| `scripts/mcp_probe.py` | 以上探针共用的客户端与守卫(不是探针本身) | 无 | 见 §10 |
| `scripts/mutate.py` | 注入变异证明断言不是空转(不是探针) | 无 | 见交接 08-06 §2④ |

---

## 1. 开启 JVMTI 原生调试器

**一次性编译**(跨平台,同一份 C 源码):

```bash
export JAVA_HOME=~/.jdks/jdk-25.0.3+9/Contents/Home     # macOS
core/src/main/native/core-jvmti/build-clang.sh
```

脚本自己判断宿主:Windows 出 `.dll`(头文件走 `win32/`)、macOS 出 `.dylib`(`darwin/`,加 `-fPIC`)、
Linux 出 `.so`(`linux/`)。macOS 上 clang 随 Xcode command line tools 就有,不用装 LLVM。

**启动时挂上**:

```bash
MCP_JVMTI=1 ./scripts/run-mcp.sh
```

成功的标志是这两行:

```
[core-jvmti] agent loaded; JVMTI debugger capabilities acquired.
[core-jvmti] JNI_OnLoad: natives bound to KdBridge.
```

### 为什么必须 `-agentpath` 而不能动态 attach

断点、单步、读写局部变量这些是**onload-only capability** —— JVMTI 规定它们只能在 agent
随 JVM 启动时获取,**动态 attach 拿不到**。所以没有"运行中再打开调试器"这条路。

### 为什么是 opt-in 而不是默认开

两个理由,都实测过:**一个错的 `-agentpath` 会让 JVM 直接拒绝启动**(不是警告,是 abort);
而 agent 是不进仓库的原生产物,默认开会让每个没编译过的 clone 起不来。

`-agentpath` 与 `-Dmcp.core.jvmtiLib` **必须指向同一个文件**:前者加载模块拿能力,
后者让 `DebuggerBridge` 的 `System.load` 把 JNI 原生方法绑到**已加载的那个模块**上。
指向两份拷贝会得到"能力有了但 native 没绑上"。

---

## 2. 断点怎么用(以及为什么我几乎不用它停)

设断点要**四个**参数,签名必须精确(方法有重载):

```python
mcp.call("debug_set_breakpoint", {
    "className": "io.github.timer_err.qml4j.render.Painter",
    "method":    "fillRoundRect",
    "signature": "(FFFFFI)V",     # javap -s 查,别手写
    "location":  0})              # 字节码偏移
```

签名用 `javap -s -cp <jar> <class>` 查出来,别猜。

> **警惕:断点命中会挂住命中它的线程。** 而渲染发生在**游戏主线程**上 ——
> 在 `Painter`/`Renderer` 里设断点并让它命中,等于**冻死客户端**,而且 macOS 上
> GLFW 占着主线程,冻住之后连窗口都不响应。
>
> 我的做法:**设上、立刻确认"设成功了"、然后清掉**,用它验证 agent 可用,
> 而不是真的让它停。要观察"这行有没有跑",用 §3 的插桩。

`debug_watch_field`(字段写入监视)比断点温和,适合"谁把这个值改了"这类问题。

---

## 3. 用 `redefine_class` 插桩 —— 观察而不中断

这是渲染路径上**唯一安全**的"这行跑没跑"手段:热替换目标类,在方法里加一个计数器或
`System.err` 输出,不停线程。适合回答:

- 某个节点的 `paint` 到底被调用了吗
- 一帧里某条分支进了几次
- 参数在真实运行时是什么

比断点安全,比 `eval_java` 有力(`eval_java` 只能看**状态**,看不到**控制流**)。

**这个手段真的解决了问题。** 卡片面那个 bug 骗了四次,靠 `eval_java` 永远查不出来 ——
因为所有**状态**都是健康的。插桩之后第一次拿到关键事实:`paint` **每帧都在被调用**
(9212 次),于是排查方向立刻从"为什么没画"转向"画了为什么没效果",最后定位到
MC 的 `GL_ALPHA_TEST`(§9)。

**两条约束**,都踩过:

- **不能加字段。** `redefine_class` 走标准 JVM 的 `RetransformClasses`,
  加字段是结构性改动 ⇒ `structural change rejected (need JBR + DCEVM)`。
  所以插桩只能用 `System.err` 或改已有方法体,不能加静态计数器。
- **传的是源码不是字节码**,而且**编译时用的是运行中的依赖**。
  我拿改过的 `Rectangle.java`(带我给上游加的新 `Painter` 重载)去热替换,
  编译失败 —— 因为运行中的 `Painter` 还是 0.2.24。**要用与运行版本匹配的源码**
  (`git show v0.2.24:<path>`)。当前 pin 是 0.2.27,同一条纪律。

---

## 4. 读像素:两个能杀客户端的坑(都踩过)

**① 同一次 `eval_java` 里先推帧、再调 GL → `SIGSEGV in libGL glGetError`。**

```
#  SIGSEGV (0xb)
#  C  [libGL.dylib+0xf58]  glGetError+0x8
```

原因:`surf.frame(...)` 结束时 `GlStateGuard` 已经把 GL 状态还原成 MC 期望的样子,
之后再伸手进 GL 就炸。`scripts/live-dwm-probe.py` 的 `target_pixels()` 之所以安全,
**是因为它只读、从不推帧**。

**规则:推帧和读像素分成两次 eval**,让游戏自己在中间渲染。

**③ 在渲染中途查询 GL 状态会崩** —— `SIGSEGV in gleRunVertexSubmitImmediate`。

我在插桩后的 `Rectangle.paint` 里调 `glIsEnabled`/`glGetInteger` 打印状态,崩在顶点提交里:
**状态查询打断了正在进行的 immediate-mode 顶点流**。

要在渲染路径里看 GL 状态,改成**在帧外**用 `GlStateGuard.enter()` / `leave()` 包起来读
—— 那正是 `GlStateGuardLiveIT` 现在用的办法。

**② `SkPixmap::getColor` 不做边界检查 → 越界直接段错误。**

```
#  C  [libskija.dylib+0x37df2c]  SkPixmap::getColor(int, int) const+0x24c
```

离屏层是**设备像素**(如 1708x960),而场景坐标是**逻辑单位**(scale 2.0 时上限 854x480)。
`getColor(x*scale, y*scale)` 只要越界就崩。**读之前必须自己检查
`0 <= px < img.getWidth()` 和 `0 <= py < img.getHeight()`。**

---

## 5. 为什么 `gui_click_element` 驱动不了 dwm

`GuiSnapshotService` 反射读的是 vanilla 的 `GuiScreen.buttonList`,而 QML 场景
**从不往那个 list 里放东西** —— dwm 的控件是 qml4j 的 Item 树,不是 `GuiButton`。
所以 snapshot 看不到任何 dwm 控件。

探针因此走 dwm 自己的 `UiInput` SPI(`pointerDown`/`pointerUp`/`pointerMove`/`key`/`wheel`)。
这不是权宜之计:那**正是游戏自己走的那条路**(`QmlGuiScreen.handleMouseInput` 调的就是它),
所以探针测的是生产代码。

---

## 6. 采样前必须确认前提 —— 我在这上面错了两次

这条是血泪。两次都是"读数看起来是新发现,其实前提早就不成立了":

1. **界面已经不在设置页了**,而我还在按设置页的坐标采样,读到一片面板底色,
   差点当成"卡片面消失"的新证据。真相:`Loader.source=pages/PageChips.qml`。
2. **卡片滚出了视口**(`y=516`,而层高只有 480),读到全黑,当成"面没画"。

**所以每次读像素之前,先用 `item_box(mcp, "<objectName>")` 确认目标存在且在层内:**

- 返回 `NO-ITEM` ⇒ 当前页不对,或者名字错了
- 返回的 y 超出 `层高/scale` ⇒ 在视口外,先滚进来
- 滚完要**推帧到收敛**,因为平滑滚动只移动 `targetY`,`contentY` 靠后续帧追上

### 两套坐标不要混

- `item_box` / `row_centre` 返回的是**命中坐标**(已减掉 Flickable 的 `contentX/Y`,
  因为命中测试是减的)—— 用于 `pointerDown` 这类**输入**
- 而**绘制位置**要沿 parent 链累加、并在每个 Flickable 处减一次滚动偏移

我一度以为这两者不同、并据此宣布"`item_box` 不能用于采样",**那个论断是错的** ——
实测三方一致(`item_box` = 树遍历 = 正确累加 = 372)。当时是我的累加代码重复减了
`contentY` 才得出 155 的错值。**先怀疑自己的算法,再怀疑工具。**

---

## 7. 排查渲染问题的推荐顺序

1. **状态健康吗** —— `isOpen` / `inert` / `lastError` / `fboId`(`eval_java`,最便宜)
2. **场景图对吗** —— 节点的 `visible` / `opacity` / 几何 / 颜色,以及**整条 parent 链的
   累积 opacity**(单看自己不够)
3. **离屏层里画出来了吗** —— 经 Skia 读层像素
4. **屏幕上到了吗** —— `glReadPixels` 读 MC 的 framebuffer
5. **3 与 4 的差异就是二分结果**:层有屏幕没有 ⇒ 合成/blit;两者都没有 ⇒ 场景根本没画
6. **确认"没画"之后,才用 `redefine_class` 插桩**去问控制流:`paint` 调了吗、
   `culled()` 返回什么

**第 3 与第 4 步必须在同一个状态下比较**,而且中间不能改变滚动或页面。

---

## 8. 已知的诊断死角

诚实列出来,别当成已覆盖:

- **断点在渲染线程上不可用**(会冻死客户端),所以渲染路径的控制流只能靠插桩推断
- **`gui_*` 工具对 dwm 无效**(§5)
- **core 的六个 `*LiveIT` 在任何 forked JVM 里只可能 skip 或 FAIL** —— 它们是诚实的墓碑,
  不是能用的测试(理由见 §10 开头)。内核侧真机验证走 MCP socket + `eval_java`
- **`.ai-notes/` 不在这台机器上**;`codegraph` 有本地替代(`tools/codegraph/`,见 `codegraph.md`),
  但它只见**字节码里的静态调用边**,反射/动态注册的边它看不见
- **长时间运行未验**:探针跑完就关,帧率影响、显存增长、几十分钟后的稳定性都未知
- **Windows GL / 真机探针未验**:这条线的 GL 修复只在 Apple GL 2.1 上跑过。
  2026-08-20 起工作区已在 Windows;`headless` Maven 以当场为准,合回主线仍要 GL。
  见 `docs/branch-topology.md`

---

## 9. 案例:卡片面不可见(骗了四次,最后靠插桩定位)

值得完整记下来,因为**每一次误判都对应一个可复用的教训**。

**症状**:随包设置页的卡片面(`#0dffffff`)在真机上完全不可见,而同一张卡的文字正常。

**根因**:MC 画 GUI 时开着 `GL_ALPHA_TEST`(`GL_GREATER`,`ref=0.1`),外来渲染器继承它。
`0.1 × 255 = 25`,**GPU 丢弃所有 alpha ≤ 25 的片元**。卡片面 alpha 13,文字 alpha 255/197。

### 四次误判,以及各自为什么会发生

| # | 当时的结论 | 为什么错 |
|---|---|---|
| 1 | 合成时序问题(`present()` 早 flush) | 层与屏幕**逐点一致** ⇒ 层里就没有,不是 blit 丢了 |
| 2 | Loader opacity 卡在分数值 | 实测 `pageProgress=1`、整链 opacity 1.0 |
| 3 | 测量方法错、"bug 不存在" | 我手动推帧后读到正确值,**误以为是常态** —— 推帧改变了状态 |
| 4 | 渲染器没调用 `paint` | 插桩证明**每帧都调**,9212 次 |

**共同形状:所有可观察的状态都是健康的。** 这与本项目历史上的三个 GL bug 同类
(`live-verification.md` §0),而这次更深一层:连"画了没有"都是健康的,坏在 GPU 丢片元。

### 定位路径(可复用)

```
插桩计数 paint          -> 每帧都调,alpha=1.0,尺寸正确   ⇒ 不是没调用
打印传给 Painter 的颜色  -> argb=0dffffff                  ⇒ 不是颜色算错
culled() 对 8 个子节点   -> 全 false                       ⇒ 不是裁剪
插桩改画不透明红色       -> 屏幕 ff0000                    ⇒ canvas/坐标/裁剪全正常
framebuffer 格式         -> GL_RGBA8, alpha 8 位            ⇒ 不是格式
blend 状态               -> RGB 正确                        ⇒ 不是 blend
alpha 阶梯 8..40         -> ≤24 丢弃, ≥26 生效              ⇒ 阈值在 25
GL_ALPHA_TEST            -> GREATER ref=0.1 => 25.5         ⇒ 命中,8/8 数据点吻合
```

**"画不透明红色"那一步是整条链的转折点**:它一次性排除了 canvas、坐标、裁剪三个嫌疑,
把问题锁死在"alpha 特定值"上。**遇到"该画没画"时,先用一个不可能被忽略的颜色画同一个形状。**

### 为什么整套测试都抓不到

- `GlStateGuardLiveIT` 起点是 alpha test **关闭**(裸 GLFW 窗口的默认),从未复现 MC 的姿态
- 读离屏层的测试画到 **CPU raster surface**,根本不过 GL —— 同一个 `Rectangle.paint`
  在 raster 上能正确画出 `+10`,**这恰恰是把排查一再引向错误方向的原因**
- `CompositeReachesTargetLiveIT` 只断言"有一个非洋红像素",卡片面全丢也照样绿

**只有"在 MC 自己的 GL 状态下真的画一次"才能看见它。** 现在
`GlStateGuardLiveIT.aLowAlphaFillIsNotDiscardedByMinecraftsAlphaTest` 就是那条断言,
探针里也加了 `a card's plate is brighter than the page behind it, on the SCREEN`。

---

## 10. 内核侧真机验证:八条会让你误诊自己代码的规则

前九节都是 dwm/渲染侧。内核侧(act / world_view / hold)的真机验证走**另一条路** ——
MCP socket + `eval_java`,而不是 JUnit。原因写在 `LiveGameGate` 里:`GameAccess` 读
`Minecraft.getMinecraft()`,那是只存在于游戏 JVM 的静态单例,所以 forked 的 surefire/failsafe
JVM 里它恒为 null,那六个 `*LiveIT` **只可能 skip 或 FAIL,永远探不到东西**。

**共用部分现在有单一归属:`scripts/mcp_probe.py`** —— socket 客户端、"等到 id=2 那行能完整 parse"
的分帧读取、游戏线程 eval 包装、`require_ticking` / `allow_unfocused` 守卫、以及
record/report 与 0/1/2/3 退出码约定。内核探针都 `import mcp_probe`,**不要再长一份 socket 客户端**。

> 本节原文写的是"`live-hold-probe.py` 复用 `nav-astar-probe.py` 的 socket 客户端"。那在
> 抽出共享模块之前是对的,现在已过时。而这条为什么值得单独立一节:**读取循环曾存在两份、
> 只修了一份**,于是 dwm 探针带着同一个截断缺陷又活了整整一轮,直到一个 180KB 的回复
> (4 个 recv 块)把它暴露成 `unparseable reply: Unterminated string`。
> 修复住在拷贝里而不是共享处,就是那一轮的代价。
>
> `scripts/test_probe_framing.py` 现在用**身份断言**钉住这件事(`assertIs(probe.Mcp,
> mcp_probe.Mcp)`),而不是比较 `__module__` 字符串 —— 后者放得过一个行为完全相同的
> 子类,实测验过。

范例:`scripts/nav-astar-probe.py`(寻路/感知)、`scripts/live-hold-probe.py`(hold 通道,
13/13)、`scripts/live-nav-probe.py`(导航三条,11/11,**对角线在 2026-08-06 第一次到达**)、
`scripts/live-look-probe.py`(LOOK 追踪,11/11)、`scripts/live-route-probe.py`
(`move.route` COMPLETE)、`scripts/live-act-plan-probe.py`(`act_plan` COMPLETE)。

> **⑤⑥⑦ 三条是 2026-08-05 那轮真机踩出来的**(七条里有两个是当轮作者自己的误诊)。
> 那一轮的测量写在下面八条里。会话原文不进 git。

### ① 一次 `eval_java` 提交 = 一个游戏 tick。循环 tick 只会饿死它自己

实测:同一次 `GameBridge.onGameThread` 提交内 `getTotalWorldTime()` **完全不动**
(246579 → 246579),跨提交才推进。

所以下面这个写法是错的,而且错得很像被测代码的失败:

```java
for (int i = 0; i < 120; i++) o = controller.tick(act);   // 120 次全在同一个 tick 里
```

计数不减、服务器不答、使用永不完成,controller 于是**诚实地**报告"这个使用没在推进"。
我因此追了三轮才发现问题在 harness 而不在 controller。

**正确做法:一次提交推一步,或者干脆走生产路径** —— `act_set` 提交意图,`act_status` 轮询,
让 `ActTickLoop` 每游戏 tick 推一次。后者还顺带验了工具面的接线。

同一形状 dwm 侧记过一次(`live-verification.md` §8 的 `scroll_into_view`)。**这是第二次。**

### ② 单人也有两侧,而服务端才是权威。只布置客户端等于什么都没布置

单人集成服务端在同一 JVM 里,但玩家是**两个对象**:`EntityPlayerSP`(客户端预测)与
`EntityPlayerMP`(服务端裁决)。踩过的三条:

- **只改客户端背包**:服务端手里是空的,于是服务端从不开始进食(`status id 9` 永不到),
  弓的 `hasItem(arrow)` 也失败。看起来像 controller 不工作。
- **直写 `p.inventory.currentItem`**:不发 C09,两侧对"手里是什么"意见不一。
  换手要走生产路径 `act_set interact{kind:"hotbar"}`。
- **前提检查只问客户端**:可以在整轮注定失败时报告"前提成立"。**前提要问服务端。**

拿到服务端玩家:

```java
net.minecraft.server.integrated.IntegratedServer srv = mc.getIntegratedServer();
net.minecraft.entity.player.EntityPlayerMP sp =
    srv.getConfigurationManager().getPlayerList().get(0);   // playerEntityList 是私有的
```

### ③ 布置场景本身的三个坑

- **`canEat` 还要求 `!capabilities.disableDamage`**(`EntityPlayer.java:2088`),而创造模式下
  它是 true —— **创造玩家吃不了东西**,测进食必须切生存。
- **切模式要走服务端 `sp.setGameType(...)`**。客户端直改字段两侧 desync;
  `/gamemode` 在没开作弊的存档里报 `You do not have permission`。
- **别在玩家悬空时关掉创造+飞行** —— 我这么干了一次,`Player0 fell out of the world`,背包全没。
  先确认 `onGround`。

### ④ 取样窗口:平射的箭会被自己捡回去

弓的验证一度全部读作"没发射":箭矢数 32→31→32、实体消失。真因是箭落地后被站在原地的
survival 玩家**捡了回去**,消耗 1 又捡回 1,净变化 0。

两条通用做法:
- **让效果跑远**(朝天射,`pitch=-80`),别在原地取样;
- **找一个不可逆的信号**。这里是弓的耐久 —— `stack.damageItem(1, playerIn)` 那行在产生箭的块
  **内部**,所以即使箭实体已经消失,耐久 +1 也证明代码路径跑到了那里。

**"没观测到效果"和"效果发生过又被撤销了"是两件事**,而这个仓库反复栽在把后者当前者。

### ⑤ headless 全绿之后,第一次真机全绿是最不该相信的结果

**真机上也要做变异。** 改生产侧、重新 `./mvnw -q -pl core -am package -DskipTests`、
重启客户端、重跑探针。实测:把 LOOK 的 `AimMode.KEEP` 改回"到位即终止"(即复现原缺陷),
**11 条里 6 条转红** —— 只有这一步之后,那 11/11 才是证据而不是巧合。

同一条纪律的另一半:**探针的消息字面量是手抄自 Java 的**,所以自检可以 27/27 全绿而每一条
真机断言都因为对不上而失败 —— 两半互相同意,谁也不同意生产代码。
`scripts/test_probe_framing.py` 的 `ProbeMessageLiteralsMatchProductionTest` 把它们钉在
`LookController.java` 的字符串字面量上(Java 会把长消息拆成多行 `+` 拼接,所以先按序拼回
一个 blob 再找)。实测改一句 wording,两条测试红。

### ⑥ `isFullCube()` 不能用来判断"这里有没有地板"

`Block.isFullCube()` **无条件 `return true`**(`Block.java:366`),而 `BlockAir`
**不覆盖它** —— 只覆盖 `isOpaqueCube`。所以 **`air.isFullCube()` 是 `true`**,
用它写的地板检查**从构造上不可能失败**。

真机实测:

| block | `isFullCube` | `isBlockNormalCube` |
|---|---|---|
| air | **true** | false |
| stone / grass | true | true |
| water / tallgrass / lava | false | false |

判"实心可站"用 **`isBlockNormalCube()`**(= `blocksMovement() && isFullCube()`)。
这个坑让 nav 探针的 flatten **从不填坑**、`verify_arena` 在一个真实的坑上报 `bad=0`,
而失败表现为 controller 报"stuck against a wall" —— **报告是诚实的,场地是坏的**。
`core` 里零使用,但那是查出来的不是保证的。

### ⑦ 探针改了世界要自己拆干净,而且要**断言**拆掉了

nav 探针的 step 墙拆除只被 `print` 从未被断言,加上 ⑥ 那个坑把空气数成实心,于是"建墙"
和"拆墙"打印出**完全相同的** `serverSolid=11`。留下的墙污染**下一次运行**的 +X 腿 ——
失败跨运行延迟出现且没有可见来由,**这是探针能留下的最坏的东西**。

同族的另一条:**前提只能 skip 的探针,skip 在 tally 里和 pass 长得一样。** hold 探针的第一个
吃饭检查会把自己的前提吃掉(填满饥饿),于是后两条 skip;实测一次 4/6 带两条
`SKIPPED-NOT-MEASURED`,而输出里没有任何东西说这个通道没被验证。**探针应当自己布置前提**
(hold 探针现在在服务端塞食物/弓/箭并压低饥饿,并在两条吃饭检查之间补回)。

**重启客户端要等端口真的释放。** `pkill` 之后立刻起新的会撞
`BindException: Address already in use`,而新客户端会**没有 transport 地跑起来** ——
看起来像探针连不上,实际上是两个进程抢 25599。踩过一次。

### ⑧ `eval_java` 的 static 不跨提交存活,所以探针持不住多 tick 状态

**实测,一条命令就能复现**:同一份源码、同一个类名连调三次,`static int n` 的自增三次都返回
`count=1`。每次提交都是**全新的类定义**。

这条否掉了一整类看起来很自然的做法:"在探针里 new 一个多 tick controller,每次提交推它一步"。
`CraftController` / `DigController` / `HoldController` 都是跨 tick 有状态的,所以**它们只能由
一个活在 core 里的驱动器推**(act 包是 `ActTickLoop`,走 `act_set` + `act_status` 的生产路径)。
一个还没有生产路径的 controller,**在真机上就是够不着的** —— 那不是没写对探针,是结构。

同一条的另一面:**这也是为什么"走生产路径"不只是更整洁(§10①),而是唯一可行**。
