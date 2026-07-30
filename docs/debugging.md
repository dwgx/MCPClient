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
  (`git show v0.2.24:<path>`)。

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
- **`.ai-notes/` 与 `codegraph` 不在这台机器上**,所以读码只能 Read/Grep
- **长时间运行未验**:探针跑完就关,帧率影响、显存增长、几十分钟后的稳定性都未知
- **Windows 侧完全未验**:这条线的 GL 修复只在 Apple GL 2.1 上跑过,
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
