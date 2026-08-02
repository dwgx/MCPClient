# DWM 架构深研报告

对 Windows Desktop Window Manager 的深度调研,以及对我们 dwm 模块的推论。
上一篇 `dwm-architecture-comparison.md` 做的是逐条对照;本篇往下挖一层:**它为什么这么设计**,
以及哪些设计动机在我们这里成立。

主要来源(逐条附在文末):Microsoft Learn 现行文档、**Greg Schechter 的 DWM 团队博客**
(2006,一手设计说明)、Windows Driver 文档、DirectX 团队博客。

---

## 0. 一句话结论

**DWM 不是"画 UI 的东西",它是一个把"应用绘制"与"屏幕呈现"彻底解耦的中间层。**
所有它的特性(玻璃效果、缩略图、Flip3D、高 DPI 缩放、遮挡免重绘)都不是目标,
而是解耦之后**顺手得到的**。

理解这一点会改变我们的取舍:我们做合成器不是为了"像 DWM",而是为了得到同一个解耦带来的红利。

---

## 1. 起源:它解决的是一个具体的丑陋问题

Vista 之前每个窗口直接写显存。拖动窗口时下面的窗口必须重画,慢一点就拖出残影
——因为两个窗口在写同一块内存。

Learn 文档的表述很朴素:

> When the DWM is enabled, a window no longer draws directly to the display buffer.
> Instead, each window draws to an offscreen memory buffer.

**这就是全部的架构决定。** 其余一切都是推论。

值得注意的是它的强度演进:Vista/7 上 DWM 可以被用户关掉;**Windows 8 起 DWM 永久开启**,
用户和应用都无法禁用,且无 WDDM 驱动时由软件(Microsoft Basic Display Adapter)兜底。
也就是说微软把"合成"从可选特性变成了**平台不变量**。

---

## 2. 它不是新写的引擎 —— 它复用了 WPF 的 milcore

这是最出乎我意料的一点,来自 DWM 团队 Greg Schechter 的说明:

> the DWM itself is not a managed application that directly uses WPF. However, in almost all
> other ways, the DWM really can and should be thought of as a WPF application. Most
> importantly, it does use the same native composition and rendering module, **milcore.dll**,
> used by WPF itself.

**桌面被建模成一棵 visual tree,每个节点是一个窗口**,窗口节点下面再挂
非客户区(边框)和客户区两个子节点;客户区那个节点恰好是一张来自窗口重定向的
共享 DirectX 表面。从合成引擎的视角看,**整个桌面不过是又一棵 visual tree**。

他直接回答了"为什么不直接写 DirectX"这个问题,列出的收益清单(原文照录要点):

| 收益 | 说明 |
|---|---|
| Remoting | DWM 自身可被远程化,复用 WPF 的 remoting |
| 多显示器抽象 | DWM 不必自己处理跨显示器/跨适配器渲染 |
| 2D/3D 集成 | Flip3D、窗口切换动画是 3D 嵌进 2D visual tree |
| 抗锯齿图元 | 由底层负责 |
| **更新管理** | 改动在 visual tree 里做失效与变更传播 |
| **脏区管理** | "an absolutely vital performance requirement" |
| **遮挡剔除** | 被不透明内容覆盖的变化不必渲染 |
| **调度** | 帧调度、帧率维持、帧率波动补偿 |

**对我们的启示**:这份清单里有六项我们已经从 qml4j 白拿了
(visual tree、更新管理、脏区标记、抗锯齿、2D 图元、部分调度)。
真 DWM 选择"站在通用合成系统上而不是直写 DirectX",我们选 qml4j 而不是直写 Skija,
**是同一个判断**。这不是巧合,是同一类问题的同一个正确答案。

---

## 3. 重定向的真实代价:GDI 窗口有两份缓冲

这是全篇最有价值的技术细节,也是我原先完全不知道的。**不同渲染技术的重定向代价不同**:

### GDI 窗口 —— 双缓冲

1. 分配一张**系统内存**表面(窗口大小)
2. 再分配一张**显存**表面(目标 DirectX 像素格式,同样大小)
3. `GetDC(HWND)` 返回的不再是主显示缓冲的 DC,而是**指向那张系统内存表面**的 DC
4. GDI 操作填充系统内存表面
5. 系统"在合适的时机"把系统内存表面**拷进**显存表面
6. 合成器从显存表面取内容合成桌面

为什么要两份?Schechter 给了两个理由,第二个是本质的:

> Many GDI operations (XORs, alpha blending, and text are examples) are **read-modify-write**
> operations. To do that to a native video memory surface would involve reading back from video
> memory into the CPU ... This is typically a horribly slow and pipeline-stalling operation.

**读回显存会拖垮整条 GPU 管线。** 这条约束我们同样受制 —— 详见 §7。

### DirectX 窗口 —— 单缓冲

DX 应用本来就能画成 DWM 期待的格式,而且 `Present()` 明确宣告"我画完了"。
所以只需一份缓冲,通过 WDDM 的**共享表面**机制在应用进程与 DWM 进程之间共享。
`Present()` 发生时 DWM 收到通知:有脏表面需要合成。

WPF 应用属于 DX 应用。

### 一个精妙的细节:最小化窗口

窗口最小化时,应用被要求绘制的表面只有很小一块(约 130x30,够画点边框)。
如果照常把它拷进显存表面,那么 Flip3D 和缩略图可用的那张图**就没了**。
所以 DWM 的做法是:**保持显存表面处于最后一次已知状态**,让缩略图之类的
"次级窗口表示"在最小化后依然有用。

这是"缓存最后一帧"的一个非常实际的用例 —— 与我们缓存 snapshot 的动机同源。

### 只重定向顶层 HWND

> the DWM **only** redirects top-level HWNDs.

MDI 应用(如 mmc.exe)的内部子窗口由应用自己画,整体作为一个实体被合成。
混合 DX/GDI 只要边界在**子 HWND 级别**就没问题;
**对同一个 HWND 同时用 DX 和 GDI 是不支持的** —— 无法保证两者的顺序。

---

## 4. 写屏与读屏:被明确点名为"Baaaad"

Schechter 单开一节讲这个,理由两条:

1. **贵** —— 写屏几乎总伴随读屏(XOR 这类 read-modify-write),而读显存
   "requires synchronization with the DWM, and **stalls the entire GPU pipe**"。
2. **不可预测** —— UCE 不知道你写了什么,你写的东西可能下一帧就被清掉,也可能留很久。

而且有强制手段:

> if you try to access the DirectDraw primary, for instance, **the DWM will turn off**
> until the accessing application exits

**这条对我们是直接的警告。** 我们的 `GlStateGuard` 处理的正是"两个渲染系统争抢同一个
上下文"这类问题,而 DWM 的答案是**从架构上禁止**它。我们做不到禁止(我们是寄生的),
所以只能靠纪律 —— 这也说明为什么那段状态同步代码是必需的而非防御性冗余。

---

## 5. 现代部分:合成器可以被绕过

这是 Vista 之后最大的架构演进,而且**恰恰是关于"什么时候不要合成"**。
术语来自 Learn 的 composition swapchain 词汇表,定义相当精确:

| 模式 | 含义 | DWM 的角色 |
|---|---|---|
| **Composition** | 应用提交的缓冲被**拷进** DWM 渲染并送显的后缓冲 | 全程参与;系统要求最低,但效率最低 |
| **Direct flip** | 应用的缓冲直接送显示硬件(不支持 MPO 的系统上) | 参与编程硬件 |
| **Direct scanout** | 缓冲不被重新渲染进 DWM 的缓冲,直接送 GPU 扫描输出硬件 | 可能只是编程硬件,**也可能被完全绕过** |
| **Independent flip (iflip)** | present 直接送扫描输出硬件,**完全绕过 DWM** | 无。延迟更低、更省电,但系统要求更高 |
| **MPO** | 显示硬件本身能叠加多个平面 | 避免把缓冲拷进 DWM 的后缓冲 |

配套的还有 **hardware flip queue**:GPU 可以自己独立显示 present,不需要 CPU 介入,
省电,代价是 CPU 侧状态更新(buffer available 事件、present 统计)会延迟。

### 全屏优化(FSO):合成器的自我退让

DirectX 团队博客讲了这个演进:

- **FSE(全屏独占)** —— 游戏"complete ownership of the display"。DWM 出局。
  代价:后台进程受限、alt-tab 难受、**覆盖层必须注入自己到渲染与 present 之间**,
  由此带来"performance regressions, instability and issues with anti-cheat"。
- **FSO** —— 把 FSE 请求悄悄换成一个铺满屏幕的无边框窗口。游戏
  "believes that it is running in Fullscreen Exclusive",而 DWM 拿回合成权。
- **开销怎么补回来** —— DWM 学会识别"无边框全屏游戏 + 屏幕上没有其它应用",
  这种情况下把显示和"almost all the CPU/GPU power"交给游戏。
  一旦 Game Bar 之类的覆盖层出现,**DWM 立刻拿回合成权**,重新引入少量开销以便安全地把覆盖层画在上面。

**这一段对我们意义重大**,理由见 §7 最后一条。

---

## 6. Acrylic 的真实配方

我此前在 `fluent-spec.md` 里说"真 Acrylic = 模糊 + 色调 + 噪声",不够准确。
官方给的配方是**五层**:

```
background → blur → exclusion blend → color/tint overlay → noise
```

关键是我漏掉的那层 **exclusion blend mode** —— 它的作用是
"ensure contrast and legibility of UI placed on an acrylic background"。
也就是说 Acrylic 的可读性不是靠调不透明度调出来的,是靠一个专门的混合模式层保证的。

还有两条运行时事实:

- 渲染 acrylic 是 **GPU 密集**的,会增加耗电;**进入省电模式时自动禁用**。
- 用户可在 设置 > 个性化 > 颜色 关闭"透明效果",此时 acrylic 退化为纯色。
  高对比度模式、低端硬件、窗口失焦(仅 background acrylic)同样退化。

**设计上的启示**:Acrylic 从一开始就被设计成**可降级**的,而不是必须的。
我们的"只做色调层不做模糊"因此不是偷懒,而是踩在它自己的降级路径上。
我会把 exclusion blend 这层记为待办 —— 它是可读性的正解,而且不需要模糊。

---

## 7. 对我们 dwm 的推论

### 7.1 我们的架构其实是 FSO 的镜像

这是这次调研最有价值的发现。看 FSO 的逻辑:

> DWM detects a borderless fullscreen game with no other applications on the screen, and in
> that case hands the display plus almost all the CPU/GPU power to the game. The moment an
> overlay like Game Bar shows up, DWM takes composition back.

**我们的处境正是这个场景里的"覆盖层"那一侧。** 而且我们选的实现方式正是微软
在 FSE 时代批评过的那种:注入自己到游戏的渲染管线里
("step into and intercept the rendering process")。

**但差别是关键的**:微软批评的是**外部**进程注入别人的游戏(因此不稳定、撞反作弊)。
我们是**同一个 JVM 内、同一个 GL 上下文**、由游戏自己的 `GuiScreen` 生命周期驱动的。
我们不是注入者,我们是宿主的一部分。这个区分让"注入"从缺陷变成了合法设计。

**由此得到一条设计准则**:既然 DWM 在"没有覆盖层时把资源全交给游戏、
有覆盖层时才拿回合成权",我们也应当**菜单关闭时完全不参与**任何逐帧工作 ——
不是少做,是零做。当前 `QmlGuiScreen` 关闭即销毁 surface,符合这条;
将来若做常驻叠加,必须保留这个"零开销待机"性质。

### 7.2 读回显存的禁令我们同样受制

Schechter 说读显存会 "stall the entire GPU pipe"。这解释了我们的一个既有决定:
`RedirectionSurface` 用 `makeImageSnapshot()` 拿到的 Image 与 surface **共享同一个
DirectContext**,所以 `drawImage` 是 GPU 侧 blit,**没有回读**。

如果当初图省事走"读回像素再上传",按这条准则那是灾难性的。**这一点值得写进注释**,
免得后人"优化"成回读。

### 7.3 GDI 双缓冲的教训:格式匹配比拷贝次数重要

DWM 宁可为 GDI 窗口维持两份缓冲,也不让 GDI 直接画进显存 —— 因为格式不匹配 +
read-modify-write 需要回读。

对应到我们:qml4j/Skija 画进的离屏表面用的是 `N32Premul`,与 MC 的 framebuffer 格式一致,
所以合成是纯 GPU blit 而不需要格式转换。**这是运气好而非设计** ——
应该在代码里把这个前提写明,因为一旦某天两边格式分叉,代价会以"莫名变慢"的形式出现。

### 7.4 最小化窗口保留最后一帧 —— 我们的 snapshot 同理

DWM 在窗口最小化时保持显存表面为最后已知状态,好让缩略图仍然可用。

> **订正(2026-08-02)**:原文写"我们缓存 snapshot 的动机(避免重绘)",**那个动机已经不存在了**。
> 跳过重绘的路径在 `56678e3` / `d07fbca` 被删除 —— 理由不是性能取舍,是循环依赖:
> qml4j 的动画 tick 发生在 `renderFrame` **内部**,靠属性计数器决定是否渲染会让动画冻在第一帧
> (完整推理见 `QmlUiSurface.composite()` 的 javadoc)。现在**每帧都重画**,
> snapshot 只剩一个作用:给合成提供一张同 `DirectContext` 上的 GPU 图像,让 blit 不必回读。

即便如此,**性质相同**这一点仍然成立:一张"内容仍然有效的表面"是有价值的资产。

推论:将来若要做菜单的淡出动画或缩略预览,**snapshot 已经是现成的素材**,不必新增机制。

### 7.5 遮挡剔除我们做不到,也不必做

真 DWM 能剔除被不透明窗口完全覆盖的内容。我们只有一个面板,且它永远在最上层。
但有一个**可以做**的版本:菜单只占屏幕一小块,而我们的离屏表面是**全屏尺寸**。
按面板实际边界分配一张更小的表面,能省下大部分显存和 blit 带宽。

这条记为待办,收益明确(320x310 vs 1708x960 差 17 倍面积),风险是要处理面板移动/改尺寸。

---

## 8. 待办(按价值排序)

| # | 事项 | 依据 | 收益 |
|---|---|---|---|
| 1 | 离屏表面按面板实际边界分配,而非全屏 | §7.5 | 显存与带宽约 17x |
| 2 | Acrylic 补 exclusion blend 层 | §6 | 可读性,不需模糊 |
| 3 | 在代码里写明"禁止回读显存"与"格式必须匹配"两条前提 | §7.2 §7.3 | 防止后人误优化 |
| 4 | 菜单关闭时保证零逐帧开销(现已满足,需测试固化) | §7.1 | 与 FSO 准则一致 |
| 5 | 复用 snapshot 做淡出/预览 | §7.4 | 免费的素材 |

**明确不做的**:多窗口合成、独立合成进程、vblank 自有循环、iflip 类绕过路径
(我们是寄生方,绕过宿主没有意义)。

---

## 来源

- [The Desktop Window Manager](https://learn.microsoft.com/en-us/windows/win32/learnwin32/the-desktop-window-manager) — 离屏表面的基本定义
- [How underlying WPF concepts and technology are being used in the DWM](https://learn.microsoft.com/en-us/archive/blogs/greg_schechter/how-underlying-wpf-concepts-and-technology-are-being-used-in-the-dwm) — milcore 复用、桌面即 visual tree、收益清单(DWM 团队一手)
- [Redirecting GDI, DirectX, and WPF applications](https://learn.microsoft.com/en-us/archive/blogs/greg_schechter/redirecting-gdi-directx-and-wpf-applications) — 重定向机制、GDI 双缓冲、最小化处理、写屏禁令(DWM 团队一手)
- [DirectComposition Architecture and components](https://learn.microsoft.com/en-us/windows/win32/directcomp/architecture-and-components) — 组件构成、9 步合成循环、只写属性
- [Composition swapchain glossary](https://learn.microsoft.com/en-us/windows/win32/comp_swapchain/comp-swapchain-glossary) — composition/direct flip/direct scanout/iflip/MPO 的精确定义
- [Demystifying Full Screen Optimizations](https://devblogs.microsoft.com/directx/demystifying-full-screen-optimizations/) — FSE vs FSO,覆盖层与合成权的交接
- [Acrylic material](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic) — 五层配方、降级路径
- [Direct Flip of Video Memory](https://learn.microsoft.com/en-us/windows-hardware/drivers/display/direct-flip-of-video-memory) — direct flip 的驱动侧定义
- [Desktop Window Manager is always on](https://learn.microsoft.com/en-us/windows/compatibility/desktop-window-manager-is-always-on) — Windows 8 起不可禁用
