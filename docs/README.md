# docs/ — 索引

进 git 的是产品设计与验证方法。AI 交接 / session 笔记不进 git
(2026-08-20 已从 `feat/dwm-qml4j` 历史拿掉),活进度(HEAD SHA、测试条数)在工作站 `.ai-notes/`。

> **2026-08-22:** GitHub 默认分支 `main` 已快进到产品 tip,与 `mcp-core` /
> `feat/dwm-qml4j` 同 SHA。Mac qml4j GuiScreen **就是**产品 UI。日常开发分支名仍是
> `mcp-core`;合主线只用 `--ff-only`。细节 `branch-topology.md`。

> **读码走 codegraph(铁律⑥)。** 用法与盲区见 `codegraph.md`。

---

## 0. 接手路径

**内核 / 游戏行动:**

1. `agency/command-to-action.md` — 已建好什么、关键路径、Fork。逐节看订正横幅。
2. `branch-topology.md` — 分支关系。GitHub `main` = 产品主线(已含 qml4j dwm)。
3. `debugging.md` — 排查前必读,§10 真机纪律。

**UI (dwm):** 仓库根 `dwm/README.md`(活架构,qml4j 底层) → `dwm/live-verification.md` →
`debugging.md` → `dwm/fluent-spec.md` / `dwm/key-ceremony.md`。
dwm **不是**已完结:底层钉死,产品页还可以写。

**审计:** 2026-09-30 对 v1.0.0 干净基线(`5eec5b9`)→ HEAD 做过一次全量审计,
报告在工作站 `.ai-notes/docs/audits/2026-09-30-base-to-head.md`(gitignored)。
结论一句话:**安全脊柱是干净的(找不到任何让未签补丁 arm 的路径),缺陷集中在
"新写的功能自己骗自己"** —— 2 条 CRITICAL 都在 `drivers/plan/`:搭桥的"确认"谓词把
水/岩浆当成已铺好的地板,以及规划器把岩浆当成可通行空间(同一个问题,读侧 `LocalGrid`
已经标了 `walk = -2`,两边答案矛盾)。改任何 `plan/` 之前先读那两条。

---

## 1. 全部文档

### 顶层

| 文档 | 内容 | 何时读 |
|---|---|---|
| `branch-topology.md` | 分支关系、环境、怎么跑 | 接手 |
| `codegraph.md` | 本机读码入口、盲区 | 读码前 |
| `debugging.md` | 调试手段 + 三个能杀客户端的坑 + §10 | 排查前 |

### agency(一条命令怎么变成游戏里的动作)

| 文档 | 内容 |
|---|---|
| `agency/command-to-action.md` | §1 已建好什么(别重建) · §4 关键路径 · §5 Fork |
| `agency/guarantees.md` | 53 条保证的 `file:line` + **强制它的测试类**(每个 `file:line` 都亲自开过文件数过行,查不到就写 unenforced;引测试类前还要确认它**读的就是这一行讲的东西**)· **九个**测量仪器各自抓什么/瞎什么 · §0 的命令是 `-pl core -am test`(`-pl core` 不重建 `client`,会解到**过期 jar**,报出十一个查无此源的假错误)· §0.1 测试数 **2025** 的分解,哪几条是实测、哪几条是历史、**哪一段标了 [UNVERIFIED]** · §1e 时钟:客户端曾经**永远**回答「白天」,dusk 13807 / dawn 22193 实测 · §1f 伤害 / 围蔽 / 语料 / **箱子行 / 工具分层**五波 · §1g + §1g.1 两次全量锚点扫描(**31 个错锚点**,第二次的 15 个**全在 §1f**,因为那正是当时在改的文件)· §1d 的 **no seam / 没有负对照** 已经**反过来了**:seam 现在在 main 树里,`Policy` / `PolicyRun` / `BrokenPolicies` 都在 `core/src/main` · §5.5「删掉那句而不是改写那句」(`-Dmcp.core.promote=create_tool` 一设,改写过的否定句就变成假话)· **§6.10 引用行号的规矩** · **§6.11 合取测试只有删掉任一半才红才算数** · **§6.13 别重开你没动过的那一节** · **§6.14 正在被改的文件里的行号是半衰期不是引用** · **§6.15 不发行数** · **§6.16 说「还开着」的句子不是稳定动词** · **§6.17 求和前先跟源码树对一下报表集** · **surefire 收不到测试的坑**(实测 346/323/23)· §3.4 `.lwjgl/` 等无产出物的忽略项 · 四条北极星判据现在**测到三条**(围蔽、18 血线、箱子);没测到的是**决策**· 「不要做这个」清单 |
| `agency/vendored-tree.md` | 内嵌 Minecraft 树的**查阅手册**(不是叙事):1612 个 `.java` / 25 个顶层包 / 97 条包路径 · **两个注册表的边界**(方块 `0..197` 连续,**198–255 全 58 个无人注册**;物品 187 个显式注册,`426` 是洞,`432–2255` 空 1824 个;ItemBlock 挂在方块自己的 id 上 `1..192`)· **一份「我能不能引这个文件」的对照表**:`BlockLava` 是**替换**不是遗漏(`Blocks.java:49-50`;熔岩照样流动、照烧、照和水反应,审计说它不流动是错的)、木台阶 2 家族整个没进、`client/util` 只有两个 Json 所以追按键必须离开 `net/minecraft` 走 `lwjgl2-shim`、`C02PacketUseItem` **本来就不该在**(1.8.9 的使用物品动词是 `C08`)· 1.7 命名与 1.8 编码并存(**代价在读者不在线路**,`C03PacketPlayer.java:123-125` 与 `:192-196` 可证)· §4 两条规矩组:引用的 7 条(引你没打开过的行号 = 缺陷,不是引用;文档攒数字就会攒过期数字)+ 树本身的 5 条(熔岩伤害的权威是 `Entity.setOnFireFromLava` 不是 `BlockLava.java`;伤害常数是**半心**,所以 `LAVA_CONTACT_DAMAGE = 8` 对应原版 `4.0F` 是对的)· §5 记了审计里 4 处错锚点(含 1 处**结论性错误**)和 1 个树上活着的错锚点 |
| `agency/failure-shapes.md` | **一种形状的十三处目录,给没进过这个仓库的人看**:能力被写成在做事,而**生产者不在**——只测得到的 `GoalPolicy`、arm 了却改零字节的 `GlClampToEdgePatch`、`require()` 空体的 `AllowAllGate`、拒绝已存在动词的 schema、工具描述里不存在的参数、零调用者的常量、只测够得到的 tactic 记录、谎报 lease 的可观测字段、声明了却无人执行的 schema 边界、写着 "never null" 却返回一个帧前旧值的接口方法、javadoc 里引用不存在的测试类、引了一个根本不读那行代码的测试类 · **四条逃逸路线**(待在注释里 / 数字从 diff 里读来 / 写的时候是真的然后文件动了 / 是个决策而不是实现所以没人问)· **§2.3 是写这份文档时现场抓到的第四处**:`TheSuiteGoesRedThroughTheSeamTest.java` 曾写着 `GoalPolicy` 有 1317 行,而同一个提交里它已经是 1339 行——**那句话现在已改成「No line count」**(`:42-45`),事实是 **10** 个 `new GoalPolicy` 代码构造点 + 5 处全在 javadoc 里的 `main` 引用 · §3 十条例句**全部来自本仓已有规矩**(`guarantees.md` §6.4/6.9/6.10/6.11/6.12、`vendored-tree.md` R1-R3),无一条新造 · §4 **被工人抓到而不是被整合者抓到**的三件(没有测试的 commit `d5a82b6`、归因给一次没发生的删除的测试数、归罪给一个什么都没碰的只读侦察兵的编译失败——真因是两个工人共用一个 Maven 模块) |
| `agency/test-census.md` | **统计口径定死了**,一条命令复现全部数字(`python scripts/test-census.py --run`,已接进 CI)· **两个总体永不合并**:surefire 的 `*Test` 与 failsafe 的 `*IT`(22 个,三个 pom 的 `skipITs` 默认全 true,普通 `verify` 一个不跑还 BUILD SUCCESS)· **「绿」的定义**:skip 不算绿,连报告都没有(absent)更不算——所以 2543 里是 **2542 绿 + 1 skip**· **去幽灵 join**:报告对不上源码 = 一次删除,不是测试;`pg-engine` 当年被漏的确切机制在 §4.1· §3 记着**当天关掉的四个假绿**:pom 里写了却无人读的 `-Ddwm.live=false`(关后实测 45 个 IT 全绿)、`-pl core` 不带 `-am` 解到过期 jar 报 11 个假错(带 `-am` 后 11 个 skip 正常跑)、`SmokeIT` **结构上不可能通过**(fork 没带 `-javaagent`,KI-4 补丁没生效;argfile 路径也写错,CI 上被两个 `Assume` 遮住——**关掉之后客户端第一次在 headless fork 里真的进了世界**)· §5 列出这仓库发过的**每一个**测试数与它真实的口径——**2543/2547/2548/2146 在整棵树里查无出处** |

会话交接不进 git。工作站:`.ai-notes/docs/project/handoff/`。

### dwm(UI,qml4j 底层)

| 文档 | 内容 | 状态 |
|---|---|---|
| `../dwm/README.md` | **活架构**:qml4j substrate、包、契约、帧循环 | 当前 |
| `dwm/live-verification.md` | 真机验证方法 + 抓到的 bug | 当前 |
| `dwm/key-ceremony.md` | TUF 密钥仪式、私钥在哪 | 当前 |
| `dwm/entry-point.md` | KI-11 为什么走补丁层 | 当前 |
| `dwm/fluent-spec.md` | Fluent 度量 + alpha 陷阱 | 当前 |
| `dwm/settings-page.md` | Settings 页结构、动画策略 | 当前 |
| `dwm/research/frame-sequence-verified.md` | 客户端源码证实的帧序列 | 当前 |
| `dwm/dwm-architecture-comparison.md` | 与真 Windows DWM 对照 | 当前(有订正横幅) |
| `dwm/dwm-deep-dive.md` | 真 DWM 设计动机研究 | **研究笔记**;模块地图以 `dwm/README.md` 为准 |
| `macos/dwm-qml4j-plan.md` | 当年落地方案 | **历史**;pin 以 `dwm/pom.xml` 为准 |
| `macos/known-issues.md` | macOS 专属(MK-1) | 当前 |

`dwm-gl` / imgui / skiko / Compose **已拆除**。不要按它们写新代码。

---

## 2. 按问题查

| 你想知道 | 去 |
|---|---|
| DWM 现在怎么分层 | `dwm/README.md`(仓库根,不是本目录) |
| 接手内核任务 | `agency/command-to-action.md` + `debugging.md` §10 |
| `act_set` `route` 真机 | `scripts/live-route-probe.py --allow-unfocused`(Windows COMPLETE 2026-08-21) |
| `act_plan` 真机 | `scripts/live-act-plan-probe.py --allow-unfocused`(Windows COMPLETE 2026-08-21) |
| 算路代码在哪 | `core/src/main/java/net/marcloud/mcp/core/drivers/plan/` |
| 「这条保证有人测吗」 | `agency/guarantees.md` §1(每行给 `file:line` + 测试类,查不到就写 unenforced) |
| 我新写的测试会被收集吗 | `agency/guarantees.md` §3(**契约测试文件名必须以 `Test` 结尾**;helper 和 `*LiveIT` 不许改) |
| 「现在几点、天亮了吗」 | `agency/guarantees.md` §1e(别读 `World.isDaytime()`,客户端上它是冻结的) |
| 我要往 `guarantees.md` 里写一条保证 | `agency/guarantees.md` §6.10 / §6.11 / §6.12(**行号必须开文件数**;合取测试要证明删掉任一半会红;引的测试类必须真读这一行讲的东西) |
| 「我这行号明天还在吗 / 该重开哪一节」 | `agency/guarantees.md` §6.13(**别重开你没动过的那一节**)+ §6.14(**正在被改的文件里的行号是半衰期不是引用**——第二次扫描 15 个错锚点全在正在改的 `EvalSuite.java`;引用符号,行号只是顺手) |
| 「我能写 `451 lines` 这种数吗」 | `agency/guarantees.md` §6.15(**不发行数**:行数是这里唯一没有符号可以退化的引用;要引就引类名) |
| 「文档里说『还没做』的那条,现在到底做了没」 | `agency/guarantees.md` §6.16(**"open" 不是稳定动词**;§1d 的 no-seam 已经反转,同一份文档两个小节曾经互相矛盾) |
| 「我的测试数对不上 / surefire 报个数我信吗」 | `agency/guarantees.md` §0 + §6.17(**求和前先把报表集跟源码树 join 一次**,没有源码的报表是删除不是测试;命令要带 `-am`) |
| 「模型能不能看见 `create_tool` / 能不能自己造工具」 | `agency/guarantees.md` §1f row 49-50(`ToolRegistry.layerOf` 默认 KERNEL;`MetaTools.isReserved` 现在查的是**审计过的**注册表)+ §5.5(握手那句是**删掉**不是改写的,理由在 §5.5) |
| 「`net/minecraft` 里有这个文件吗 / 这个 id 归谁管」 | `agency/vendored-tree.md` §2 / §3(方块只有 `0..197`,`198–255` 无人注册;缺文件先查 §3 的原因表再当缺口报) |
| 「我要引的这行还在不在 / 这个数字过期了吗」 | `agency/vendored-tree.md` §4.1 R1–R3 · `agency/guarantees.md` §6.10 |
| 「熔岩伤害算在哪 / 为什么常数是 8」 | `agency/vendored-tree.md` §4.2 T1–T3(权威是 `Entity.setOnFireFromLava`;本项目用**半心**) |
| 「我这份能力有谁读 / 生产者在哪 / 我怎么知道它不是空转」 | `agency/failure-shapes.md` §2(**只测得到的能力就是缺陷**,哪怕它本身是对的)+ §3.8(问「and who reads this?」) |
| 「我的测试是不是只在证明新代码会触发」 | `agency/failure-shapes.md` §3.5(删掉反向的一半也要红;只会证明「守卫会触发」的测试,对着「什么都不许」的守卫一样绿) |
| 「这行注释里的测试类真的存在吗」 | `agency/failure-shapes.md` §2.13 / §2.14 · `agency/guarantees.md` §6.12(注释里的名字编译器看不见,也没有任何 suite 会读它) |
| 「玩家有没有掩体 / 血有没有跌破 18 / 天亮时有箱子吗」 | `agency/guarantees.md` §1f row 39-52 与 §4 的北极星表(三个**测量**都已经有了;还没有的是**决策**,见 §6.2) |
| 空转断言怎么证伪 | `scripts/mutate.py` |
| 真机陷阱(熔断 / 失焦 / 死玩家) | `debugging.md` §10 |
| Fork 还没拍的 | `agency/command-to-action.md` §5 |
