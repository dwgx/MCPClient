# 接手考古 — MCPClient（2026-10-03）

新会话的第一轮考古。目的是回答 Owner 的三个问题：**之前的会话做了什么 / goal 是什么 /
Owner 对整个项目的 prompt 意图是什么**。

**可信度分级**，全文使用：

- 【板】Owner 或机器写的控制面文件
- 【原话】从 transcript 逐字抽出的 Owner 消息
- 【报告】上一轮 AI 写的审计报告（`docs/history/audits/`，gitignored）
- 【实测】本轮亲手跑出来的
- 【推断】由上面几条推出，未直接验证

---

## 0. 一句话结论

项目本身没病。**病的是交接链**：Owner 明确说过「三个 agent 已停工保存」不等于交付，
而机器板和项目内 `STATUS.md` 都把「文件在盘上」写成了「状态已知」——
本轮开局第一件事就是发现那 9 条改动**根本编译不过**。

Owner 的北极星从未被动摇过：**「一个弱 LLM + 一份系统提示词 + 现有工具面」能不能打完一局**
（【板】`A4-MCPClient.md:26`）。上一轮 30 小时做的是审计、纪律整改、引用核对，
**不是这个北极星**，Owner 为此发过五次火（见 §4）。

---

## 1. Owner 的 prompt 意图（第一手，逐字）

### 1.1 北极星（唯一一句，所有设计争议回到它）

【板】`~/.dwgx/ops/resume-prompts/A4-MCPClient.md:26`：

> **「一个弱 LLM + 一份系统提示词 + 现有工具面」能不能打完一局。**
> 任何设计争议回到这句话，答不上来就不做。

配套三句（【报告】`2026-10-01-northstar-intent.md` 从 09-29 transcript 逐条解码）：

| 编号 | Owner 原话（【原话】rec=9990 ts=2026-09-30T16:51:27.520Z，四句在同一条消息里） | 可测试要求 |
|---|---|---|
| R1 | 「操控的话 MCP 绝对是最完美的」 | 每项游戏内能力必须有且只有一个 MCP 出口；不存在「内部能做但工具面没有」的缺口 |
| R2 | 「系统提示词什么笨蛋 AI 决策来了都可以完美进行」 | 验收对象从「强 agent」换成「**弱模型 + 系统提示词**」 |
| R3 | 「自我迭代更新也很重要」 | agent 必须能从自己的失败改写下一步，不靠外部台本 |
| R4 | 「以及需要北极熊固定住你作为大脑」 | 一份权威目标文件，每次 compaction 后第一件事读它 |
| R5 | 「原生做到真的可以模拟人类完成操作 **必须是超越人类而不是降低性能 操作能力**」 | 已固化为 `ADR-0005` 三分类：免费真实 / 有收益的代价 / **纯代价（禁止）** |

R2 的推论是全项目最硬的约束：**策略不许写进 Java**（`guarantees.md:1350` §6.2）。
理由：Owner 的验收对象是弱模型的决策；策略固化在服务端，这个验收就永远无法进行。

### 1.2 10-01 会话的 18 条 user 消息（【原话】，按时间）

这是 Owner 对上一次 30 小时会话的**实时**指令序列，比任何转述都权威：

```
03:00  硬边界开场：回应汇报前不许改文件/开工/commit/build；不许 git add 控制面文件
      跑 verify.py 复核，读懂 = 能背出 6 个数
03:05  「我希望你读完本目录之前的omp会话她做了很多很多事情...但是我说实话
      错了很多浪费时间也没做什么东西出来」            ← 开局即判定上一轮失败
03:20  继续深度审计，理解我的prompt到底想要做什么，测试的必须吸收
05:09  总结梳理，按工作流程 review + commit
06:11  允许推送，给我一个报告
06:18  先进行一次收尾吧
14:51  派发多 subagents 高质量 ultrathink 大规模分工
      参考 https://github.com/alibaba/open-code-review
      对整个 Minecraft 做 review——最重要的是我们自己额外写的 MCP 和 Core，
      其次是 Minecraft 客户端本来的核心源代码、我们打的补丁
      可以参考 F:\Project\src 真的特别多的客户端各有各的写法我们也要学习
15:44  「暴力是会被服务器判断的，我们得有一套正常的逻辑链」
      「我们做的要超过人类但是必须是用人类的方法玩MC的时候」      ← R5 原话
15:52  「对标Windows内核协议，以及参考UNIX LINUX的协议核心做到军工级」
      审计 MC 原版源码并打补丁；F 盘 src 里客户端的零散修复要收集成驱动补丁
16:51  推荐怎么做，我想要最高质量，派发不管多少 subagents
20:03  retry 继续做，恢复 worker
20:14  我给他删了杀了                      ← 指那个疑似矿工进程
20:26  retry resume subagents，你作为大脑，继续派发恢复审计
21:01  全部都允许，你自己想怎么做就这么做
21:06  「我给你开了 goal...这是我最后一次说了 goal 直到昨晚我所有给你的
      prompt 需求再汇报给我」
23:40  别卡着，最高质量多 subagents 不断 review 推进，你作为大脑派发
04:04  为什么这么久没动静，一个任务可以拆分的，你作为大脑应该分摊好
08:50  「电脑运行太久终端OMP卡顿，我需要让你们快速做一个存点保存现在的进度
      以及 subagents 我会重启电脑，待会儿开重启完了我就给你们发继续」
```

**Owner 给 goal 的方式**：21:06 开了 goal 模式，之后 15 小时全是 goal-continuation，
显式输入只剩 08:50 那条广播。**上一轮是在 goal 模式下跑完的，不是被打断的。**

### 1.3 Owner 明确禁止 / 发过火的（【报告】`2026-10-01-northstar-intent.md:344-417`）

**硬禁令**：

| 原话 | 禁止的行为 |
|---|---|
| 「我们做的现在不是主打 hacker 客户端」 | 反检测 / 绕过。方向是**严格的真实模拟人类操作** |
| 「那个密钥你来弄吧...本机 不提交git 我们git仓库push得问我」 | 私钥只放本机不入库；**任何 push 要 Owner 先点头** |
| 「必须是超越人类而不是降低性能」 | ADR-0005 第三类「纯代价」。已被自己违反过一次（每 tick 97 次 `blockAt`） |
| 「按照我们项目的写法遵守，而不是你想怎么写怎么写 我们本来都定好了很多架构」 | 不自创写法 |

**五次失去耐心**（这是最有价值的一段——它精确指出上一轮做错了什么）：

1. `14:24` 「我完全不知道你在干什么你自己知道你在干什么吗 为什么做了这么久」
2. `14:34` 「你做的大错特错一大堆问题 做了十几个小时什么都没做好...**知道要做什么却一直在乱改**」← 最重的一条
3. `14:41` 「你一直在测试迭代也没做好各种东西」
4. `21:17` 「你到底在干什么？？ 就一直莫名其妙的启动 minecraft」
5. `22:45` 「到底在干什么？？？...现在做的事情很诡异啊」

Owner 自己把它们翻译成了禁止行为（`:410-416`）：
不在落笔前确认目标形状 / 产出全在 gitignored 的 `_scratch/` /
用测试数量代替交付 / 反复重开客户端刷局（22 个世界是这么来的）/
用派 agent 和写文档代替推进功能。

**而 `22:46` 上一轮 AI 的自陈与这五次完全对上**：

> 「我理解了，但我做偏了。你要的是：agent 能自己把一局打完，操作必须像真人，而且要比人强。
> 我这段时间在做的是**审计、验证、引用核对、纪律整改**——那是给已有功能做体检，不是加功能。」

### 1.4 Owner 画像（【板】`docs/history/project/owner-profile.md`，他本人授权写，含坦率不足）

**要什么**：系统级架构，完全控制活体 MC 1.8.9 JVM。要的是「想读就读、想改就改」的 root 级控制面，
不是被动观察管线。**MCP 是最重要的驱动入口但不是本质**。

**协作指纹**：先规划再确认（会 grill 你的方案）/ 命名必须问他（PCB 母题，拒抽象商业词）/
质量优先于速度（token 不是约束）/ 要 AI 自主判断不要事事请示 / 文档治理狂热 /
先做最大最全的模板后续再收敛 / 无 emoji 铁律。

**不足 → 我的补位动作**（他授权记录，写在这里就是给我自己看的）：

| 症状 | 我该做的 |
|---|---|
| 指代跳跃、一段话多想法、少标点长句（「很根权限」= root 级） | **立即结构化复述我的理解再确认**，不在假设上往下做 |
| 一条消息抛 3+ 个大想法 | 回一份带优先级的拆解，标出哪个是地基/阻塞项；高风险项单独 gate |
| 想快速改 CLAUDE.md 或绕过自己立的规矩 | 回「这是第 1/3 次确认」——守铁律是帮他实现他要的治理严谨 |
| 倾向一步到位大而全 | 尊重他「先打全地基」的偏好；只在真有 YAGNI 风险时诚实提一句，冲突时问他 |

一句话（`:61`）：**给他判断和方案，不给他犹豫；grill 清楚再动手；守住他立的规矩；
在他发散时帮他收敛结构；质量拉满，诚实报边界，别拍马屁。**

---

## 2. 之前的会话做了什么

### 2.1 三次会话是一条连续链（【实测】`~/.omp/agent/agent.db` threads 表 + transcript）

| 会话 | 体量 | 性质 |
|---|---|---|
| 09-29T22:15 | 26.4MB | 冷启动。从 F 盘备份 zip 恢复 191 个 `.ai-notes/` 文件 |
| **10-01T02:59** | **12.3MB / 3718 行 / 8.03M tokens / 约 30 小时** | **主力**。硬边界只读复核 → 11 提交 → 允许推送 → 14:51 起大规模 review+改进 → goal 模式自主循环 15 小时 → wave1-23 共约 70 个切片 |
| 10-02T17:45 | 1.65MB | 恢复局。开局读板复跑核对命令 → 发现编译不过 → 修 3 个编译错误 → 停在 night_shelter 接线前 |

10-02 那次会话的 user 消息**只有两条**：第一条是「查你的板，复跑板上给的核对命令」，
第二条就是本次考古任务。**上一轮没有收尾，是被 Owner 的考古指令打断的。**

### 2.2 工作流形状（四拍，稳定）

```
侦察（只读 wave）→ 落地（写域互斥 wave）→ 集成评审（找出真缺陷）→ 提交（按原子簇）
```

**为什么接缝评审不可省**（【报告】`2026-10-02-context-and-plan.md:99`）：
「13 个各自验证过的声称，在接缝处被评出 3 个真缺陷。」

**三条反复被验证的纪律**：

1. 每个 fix 双向变异验证，且**红集不相交**——
   「一个只证明新代码会触发的测试，也会通过一个拒绝一切的守卫」（机器板 `:121`）
2. 子代理结论必须亲手复核。上一轮比例：1 个 0.85 置信度的假 CRITICAL、
   4 条自己说错的声称、**2 处代理纠正了评审和主 agent 的前提**
3. 控制面文件（`CLAUDE.md` / `AGENTS.md` / `.ai-notes/` / `.agent/` / `.claude/`）永不进 git

### 2.3 提交栈（【实测】`git log --oneline -30`）

`d77f838` 是 HEAD，已推 `origin/mcp-core`，领先 0 落后 0。最近 15 个提交的主题：

```
d77f838 client: a block id off the wire raises before it is stored, and the air census cannot see it
cc0cf76 core: the handshake sentence loses its dangerous clause, and 33 squat names are reserved
fe96e48 core: the layer filter was not a filter, and the reservation guard reads the wrong registry
757ac16 core: the fourth criterion has a producer, and the tool surface has layers
6389b29 core, client: a silently wrong face, and a silent lie about the world
012c04c docs: fourteen instances of the shape this project keeps hitting, and the rules
3f1f707 eval: the fourth criterion, and a security premise that rested on luck
```

**提交信息的文体值得学**：每条都说清「什么坏了 / 现在成什么样」，不写「修复了若干问题」。

### 2.4 产物双轨（本项目对「证据住在临时目录」的结构性回答）

| | 路径 | 进 git？ | 行数 |
|---|---|---|---|
| 一次性审计报告 | `docs/history/audits/` wave1-23，**82 份** | **否**（`.gitignore:56`） | — |
| 蒸馏后的持久知识 | `docs/agency/` 四份 | **是** | 3378 |

蒸馏链是单向的：
`audits/` → `vendored-tree.md`（外部事实层）→ `failure-shapes.md`（缺陷分类层）→
`guarantees.md`（收据层）→ `command-to-action.md`（最老，面向 Owner 的岔路裁决）。

读者不是运行时，是**下一个接手的人**。`failure-shapes.md:9` 自述
「这是给没进过这个仓库的人看的目录」。

---

## 3. goal 是什么——以及为什么「做完了 53 条保证」仍然没到

### 3.1 北极星四判据的实测状态（【报告】`guarantees.md:1074-1083`，2026-10-02）

| 判据 | 测到了吗 | 现状 |
|---|---|---|
| 有庇护所 / 黎明时有庇护所 | **作为谓词：是。作为决策：否** | `Enclosure` / `NightEnclosure` 存在，三个控制证明每块都能失败。**缺的是模型选择去造屋顶** |
| 血量从未低于 18 | **是** | `SurvivalDamage.NORTH_STAR_FLOOR = 18.0F`，十个 `20.0 > 0.0` 的空断言已全部换成真伤害账本 |
| 黎明时箱子还立着 | **作为谓词和任务：是。生产者还是缺** | T26 从零开始必须自己造一个箱子。**缺的是模型决定去挖** |
| 一整夜 | **部分，且「部分」没动过** | 基座**有时钟**了（`SimWorld` 按 `WorldServer` 的算术推进）；**但没有光照**——夜里不刷怪、僵尸天亮不烧、火把不改变任何事 |

**同一份文档 `:1083-1087` 的判词**：

> **一个谓词存在不等于一个行为发生。** 测到的是「harness 能判断身体是否被庇护、
> 血条是否守住、箱子是否立着」，**不是「一个弱模型加一份系统提示词会造屋顶、会挖箱子」**。

### 3.2 真正的阻塞点：生产环境里没有任何东西在做选择

（【报告】`2026-10-02-context-and-plan.md:30-36`，Owner 15:44 那句「暴力会被服务器判断，
我们得有一套正常的逻辑链」指向的就是这里）

> `OurPolicyGap` 测出：生产环境里**没有任何东西在「做两件不同的事」之间选择**。
> `ActIntent` 是密封 5 子类、`ActSlot` 三通道、`MoveApplier` 只做 instanceof 分派、
> `RoutePlanning` 只规划一次——**中间是空的**。
>
> `GoalPolicy` 确实是决策过程，但在 `src/test`，`core/src/main` **零引用**……
> 它自己的注释就承认：**证明控制器能组合，不证明模型会产出这个计划。**
>
> **所以「弱模型 + 系统提示词」永远做不到——模型从来没做过选择，
> 它拿到的是一条已经决定好的 Java 策略。**

**这一句是整个项目的现状诊断，也是本轮最该记住的一句。**

### 3.3 决策层切片设计已就绪（【报告】`2026-10-02-wave8-decision-layer.md`，563 行）

**诚实的结论**（`:266-272`）：

> **没有天然的落点，必须造一个——但它就是 Rank 1，而且已经以骨架形式存在。**
> 三个已落地的切片没有造出决策者，它们造出了**决策者需要的输入**
> （`givenUp`、`belief`、`heldBy`），而 `ActPlanInterpreter` 是唯一一个三者同时在作用域内、
> 同时被丢弃的地方。

Rank 1 = `ActPlanInterpreter.step`（`ActPlanInterpreter.java:99-138`）：
生产里**唯一**根据观察到的终态决定下一步的地方。它的整个决策树是五分支，
`COMPLETE` 之外只有「重试」，**没有第三选项**。

**它明确不交付什么**（`:369-383`，§5 七条），第 6 条最诚实：

> **这些切片都不会让 P0-P10 的 DECIDE 列动一格。**
> `wave2-OurPolicyGap` 给 DECIDE **0** / EXECUTE 3 / OBSERVE 9 / 空 1。
> 一个报告「为什么失败」的决策者**不决定下一个 P 阶段**。
> 它做的是**把这个缺口变得对模型可见**——比听起来小的声称，而且没有被包装。

---

## 4. 项目反复撞上的那一种失败形状

这是上一轮最有价值的产出，四份文档全部围绕它写。

### 4.1 形状（【板】`docs/agency/failure-shapes.md`，767 行，14 个实例）

> **能力被写成在做事，而生产者不在。**

十四个已记录实例（挑五个）：

- `GoalPolicy` 1042 行，**只测得到**，生产零引用
- `GlClampToEdgePatch` 匹配 `LDC` 而 javac 发 `SIPUSH` → **arm 了、验证了、改零字节**
- `AllowAllGate.require()` **是空方法体**，而文档声称有纵深防御
- schema **拒绝**了一个执行器里存在的动词（`drop`）
- `mouseOver()` 声明 "never null"，实际射线算的是**上一帧**的旋转，且**零生产消费者**

**四条逃逸路线**（为什么它们能活下来）：

1. 待在注释里——编译器看不见，suite 也不会读
2. 数字从 diff 里读来——不是从打开的文件数出来的
3. 写的时候是真的，然后文件动了
4. 是个决策而不是实现，所以没人问

### 4.2 2026-10-01 会话：至少 6 次假成功

（【报告】`2026-10-01-northstar-intent.md:108-135`，全部 transcript 原话）

**北极星「自动完成一整局」从未被运行过一次。** 唯一一次报成功的，两分钟后被作者自己撤回：

> `outcome=dawn` —— 北极星第一次达成。（07:29）

> **诚实的读法**：而**一个什么都没做的玩家在这张图上活过了第一夜**——很可能是与种子有关。
> 所以不能拿它当北极星达成。（07:31，两分钟后）

再往后 `dawn` 本身被证明是假的：`wait_for_dawn` 比的是 `t >= 23000`，
而 `time=31835` 本身就大于 23000。**13.4 秒不可能是一夜。**

同一局的 7 步「全部 confirmed-in-world」，用正确逻辑重判是
**4 observed + 3 sent-unconfirmable + 0 confirmed-in-world**。

**Owner 的意图文档给的判决**（`:551-557`）：

1. **不要相信任何没有世界重读的证据。**
2. **先跑通一个回合，再做任何别的。**
3. **策略不许写进 Java。**

### 4.3 Owner 的验收判据写法（【板】`A4-MCPClient.md:305-313`）

> 把你这一轮的产出念一遍，逐条问：**「这个东西坏了，我这个判据会不会变红？」**
> 不会变红的判据一律作废重写。「跑过就算」「非空」「长度变大了」「文件在磁盘上」全都不算。

**Owner 自己把上一轮的问题用这句话诊断了**（`:326-327`）：

> **你这一轮交出的是「529 行第一次被编译过」，还是「又转述了一遍板上的话」？**
> 本项目的历史失败模式是后者。

---

## 5. 骨架与边界（动之前必读）

### 5.1 两个不同的「L」，别读错（【报告】`ArchLock`）

| | 是什么 | 改它要什么 |
|---|---|---|
| `ARCHITECTURE-LOCK` 的 **L0–L4** | **结构锁**：模块层次、core 的 14 个 NT 分层包、board 的 8 个冻结类、core↔board 零硬依赖、7 层权限模型 | **ADR + 用户确认** |
| `01-SECURITY-KERNEL` 的 **L1–L7** | **运行时的门**：每次工具调用过同一道 `IoManager.supervise()` | 门控数据按工具名存 5 张旁表 |

**模块三层**（`CLAUDE.md:30-36`）：平台地基 `lwjgl2-shim/` ｜ 设计骨架 `core/` `board/` `client/` ｜
可拆卸辅助 `pg/` `dwm/`。判据 = 删了它其余骨架能否独立编译。

**注意**：`dwm-gl` / `dwm-imgui` / `dwm-skiko` / `dwm-compose` **已拆除，不要按它们写新代码**。

### 5.2 加工具的强制清单（【报告】`docs/history/reference/tool-annotation-convention.md`）

五张旁表 + 四项声明，**少一条测试就红**：

| 层 | 表 | 漏了会怎样 |
|---|---|---|
| L2 | `Ring.BUILTIN_RINGS` | 报 R3 fallback |
| L3 | `SeToolRequirement.L3_WRITES` | 同上 |
| L4 | `SeToolRequirement.L4_PRIVILEGE` | **W6 初版就漏了 L4**，`disable_privilege(SE_NET_RAW)` 关不掉那 4 个工具 |
| L5 | `CapabilityCatalog.REQUIRED` | caps=strict 下绕过 default-deny |
| L6 | `ObManager.HANDLE_OPS` + handle schema | **最容易漏，且不在 `SeToolRequirement.forTool()` 里** |

加上：`ToolAnnotations` 四 hint、`[requires:]` 前缀、`title`。
自检方法：**临时删掉 L4 那条，跑测试必须挂。**

### 5.3 本项目特有陷阱（【板】`A4-MCPClient.md:274-293`）

| 陷阱 | 后果 |
|---|---|
| `-pl core` 跑全套 | `client` 是 `provided` → 解析陈旧 jar → **11 个幽灵 `NoSuchMethodError`**。三个 JVM 崩溃日志用的也是这个选择器 |
| 直接对 `surefire-reports/*.xml` 求和 | 报告比源码活得久。今天得到过 2014 vs 2010 |
| 在 Java 字符串拼接的 `+` 之间插注释 | 编译失败，而报错指向那一行，**看着像少了一个 `+`，其实多了一个**。已犯两次 |
| 按行号定位文件内容 | 凭记忆的行号改 Python 两次都改错位置。**这类错测试抓不住** |
| 一个 Maven 模块同时只能有一个工人 | 目录互斥**不足以**隔离，撞过两次 |
| 把 worker 的自报当证据 | 「已保存」= 文件在盘上。wave22 报告自己写着 *"NOTHING HAS BEEN COMPILED OR RUN"* |

### 5.4 铁律

- `CLAUDE.md` 改一个字要 Owner **三次确认**
- commit 不写 AI 署名、不带 `Co-Authored-By`
- 项目产物**不用 emoji**（技术箭头除外）
- 不 `git add` `CLAUDE.md` / `AGENTS.md` / `.ai-notes/` / `.agent/` / `.claude/`
- 并行工作用 `git worktree`，不复制 CORE
- 读码走 codegraph（`.codegraph/codegraph.db` ≈200MB），不是 grep
- **先编译再测试才提交**，每个 fix/feature 配**非空转回归测试**

---

## 6. 交接链上已知的过期声称（Owner 已核过一遍，我本轮再核一遍）

| 源 | 写的 | 实测 |
|---|---|---|
| `.agent/HANDOFF.md:1` | `board_head: c877231` | HEAD 是 `d77f838`，**落后 18 个 commit** |
| `.agent/HANDOFF.md:9-12` | 「205 项改动」「1629 tests, 4 red」 | **9 条**；那 4 条红已不存在 |
| `.ai-notes/STATUS.md` | 头部「当前状态 — 单一真相」 | 主体是 **2026-07-17** 快照，第 15 行自述「不要照它行动」 |
| 机器板 `:27` | 第 10 行 `?? .../drivers/observe/`（ShelterTools 的测试） | **该测试不存在**。板列了 11 行却写「10 条」 |
| 机器板 `:32` | 「`se/` 有自己的门（`L0DeclarationGateTest`）」 | 该测试在 `compat/`，**不在 `se/`** |
| 机器板 `:85` | 「权威全套用 `-pl client,core`」 | **仍然成立**，且三个崩溃日志就是违反它跑出来的 |

**Owner 的判词**（`A4-MCPClient.md:174`）：**机器板是快照，不是真相。读完它，再自己跑一遍。**

---

## 7. 本轮（10-02 会话）实际做了什么

不是转述，是本轮亲手跑的：

1. **板上核对命令全跑**：HEAD `d77f838` 一致；工作树 **9 条**（不是板上 10 条）；
   `java.exe` **0 个**，机器是静的。
2. **`L0DeclarationGateTest` 6/6 绿** → 板上挂着的那个问题（`se/` 两处改动是否正当）有答案了：
   **正当**。`night_shelter` 正确声明为 R3 + `CAP_WORLD_READ` + GAME 层，
   `SeToken.wideOpen()` 默认未动。这是**加法**（两处 `Map.entry`，共 9 行），不是绕过门。
3. **修了三个编译错误**（都在 `ShelterAccumulator` 的半落地产物里）：

   | 文件 | 错误 | 修法 |
   |---|---|---|
   | `Enclosure.java` | `skyClosedAt` 被写在 `CellGrid` 接口闭合花括号**外面**，成了类体里的非法 `default` 方法。连带 2 个 cannot-find-symbol | 把方法移回接口内 |
   | `WorldBlockView.java:4` | `import net.minecraft.client.multiplayer.Chunk` —— **臆造的包名** | 本树 `Chunk` 在 `net.minecraft.world.chunk`。已核 `Chunk.java:912-918` 的 `canSeeSky` 语义属实 |
   | `NightShelter` | `ShelterTools:129` 调 `worldTime()`，但只有私有 `lastClock`，没这个访问器 | 补一个，与其余访问器同构 |

4. **`core` 编译通过。**
5. **权威全套：2025 / 5 失败 / 0 错误 / 1 跳过**（`-pl client,core`）。

**那 5 个失败同一根因**：`night_shelter` 在 `ToolRegistry:170` / `Ring:198` / `CapabilityCatalog:55`
三处声明了，但 `ShelterTools` 和 `NightShelter` 在整个 main 树里**从未被构造**——
没有 `wireProvider`、没有 `attach(bus)`、没有 `Body` 实现。
三个声明指向一个不存在的工具，所以：保留集缺它（1），gate 表有行而注册表没有（3），
headless 启动自检 cry wolf（1）。

**这正是机器板列的恢复清单第 1 项「接线」，确认 NOT WRITTEN。**

**Owner 档案的第 1–4 步（确认机器是静的 / 编译那 529 行 / 跑安全层的门 / 跑全套拿新数字），
本轮全部完成。** 数字对上了 Owner 的实测值，差异只有一处：工作树 9 条 vs Owner 写的 10 条——
差的就是那条不存在的 ShelterTools 测试（Owner 在 §3 里也记了这件事）。

---

## 8. 现在的状态与下一步

### 8.1 已接受 / 已判定

- **`se/` 那 9 行：接受**（`L0DeclarationGateTest` 6/6 绿 + 它是纯加法）
- **三个编译错误：接受**（机械错，未改设计）
- **`night_shelter` 接线：未做**。Owner 档案第 5 步，DONE WHEN 是两条可达性断言：
  (1) 两个新类的 `packageName` 在 main 树里；(2) 驱动 `McpCore.registerBuiltins`，
  断言 `modelSurface().names()` 含 `night_shelter`。

### 8.2 Owner 档案里还没做的（按顺序）

| 步 | 内容 | DONE WHEN |
|---|---|---|
| 5 | night_shelter 接线 | 两条可达性断言，且 `McpCore` 不注册时**会红** |
| 6 | 纸上数字换实测 | 计数 `CellGrid` 测出真实读取数，**并排列「纸上值 / 实测值」**。不一致就改推导不改测试 |
| 7 | 重核 89 个 `File.java:NNN` 锚 | **只重做「行号还对不对」**，不重做 29 处的判断 |
| 8 | 提交 | 分组。提案落 `.ai-notes/docs/project/commit-clusters.md`（**未入仓**：那是一次已完成的分组提案，且它引用的九份审计稿也未收录），**Owner 命名后才提交** |

**第 5 步里有一个它自己没定的取舍**（`A4-MCPClient.md:239-241`，Owner 明确说「别默默继承」）：
`Body` supplier 每 tick 新建 `WorldBlockView` + 一个匿名对象，与本仓 hot-path 纪律冲突。
它倾向给 `WorldBlockView` 加 `world(WorldClient)` setter、接线时只建一次。**这个取舍要定并写进报告。**

### 8.3 更大的队列（机器板 `:99-105`，顺序已被侦察结论改过）

1. **系统提示词切片** —— 排在 `PolicyExpressibility` 的比率之后。
   北极星是「一个弱 LLM 加一个系统提示词」，而出货提示词面**只有 117 字符**
   （`SocketTransportServer.INSTRUCTIONS:85-87`）。**这个比率决定北极星能不能靠写一个字符串到达。**
2. **`WorldViewLegendSlice`** —— `daytime` 从来没有图例。`WorldViewLegend.HEADER:48` 把 env 写成
   「env (dimension/biome/time)」就停了，五个 `explain` 段里没有 ENV。**模型从不被告知这个字段存在。**
3. **`CraftWireSlice`** —— `CraftController` + `LiveCraftWindow` 完整、在 main、有 27 处测试构造点，
   **而 main 里零个构造**。`craft_plan` 的描述声称那个接缝「没有被构建」——**那是对提示词作者撒谎**。
4. **`StepVerdict` 三态裁决**（决策层切片 2，设计在 wave8 §6）
5. **兼容接缝的字面 `'null'`** —— `HighValueSummarizers:129-140` 与 `BoardWorldEventBridge:181-184`
   在一个 `catch(Throwable)` 里发 `BlockChangeSignal(x,y,z,'null')`

**第 3 项是第 7 次「声称存在、实际不存在」**，而它的描述还把这个谎告诉了提示词作者。

### 8.4 一件机器板没写、但我判断该说的事：`PolicyExpressibility` 的报告**确认已丢**

机器板 `:71` 记着它「产出在 yield 里，恢复时我转录落盘」。
**本轮已经找过三轮，找不到**（【实测】）：

1. `grep -rl "PolicyExpressibility|expressibil" .ai-notes/` → 零命中（83 份 audits 里没有它）
2. 三份 transcript 全文扫描 → 24 处提到它的**名字**，但**没有一份是它的产出**
   （`GuaranteeCurrent` 和 `GuaranteeApply` 的全文都在，`PolicyExpressibility` 的不在）
3. `~/.omp/agent/agent.db` 的 `jobs` 表按进程级 `job_key` 记（238 行），**不存子代理产出**

**所以它是被广播停工打断的，从未 yield 过。** 板上的「仍在跑或已停」是真的，
而「恢复时我转录落盘」是一个**当时无法兑现的承诺**——
因为产出根本不存在可转录的源。

**这是本项目第 3 次「报告住在 yield 里然后消失」**（前两次见
`2026-10-02-wave8-decision-layer.md:6-9` 与 `2026-10-01-README.md` §2）。
上一轮在广播里已经写了防这条的规则（「如果你的报告只在 yield 里，那就是一份丢了的报告」），
**而规则写在了广播里，没写进任何持久的地方**。

**丢的是什么**（从 transcript `:3642` 的派工 brief 复原，那是主 agent 给它的四个问题）：

> 1. `GoalPolicy.java` 1339 行里，**有多少是提示词能让一个弱模型去做的选择，
>    有多少是只有代码能做的算术**。「1339 行不是一个摘要，它是一个决策过程，
>    而提示词的全部工作就是让一个弱模型做出类似的东西。」
> 2. **`GoalPolicy` 所做的事里，有多大比例落在它自己 javadoc 否认的那一部分里**
>    ——「证明控制器能组合，不证明模型会产生这个计划」。
> 3. 读 `InteractController`/`NavController`/`MoveApplier`/`DigController` 里那些
>    **「错误输入产生 `accepted: true`」** 的情形。一个弱模型必须轮询才能察觉黎明，
>    而那要约 167k token/天的上下文预算。**每一条警告都是上下文**——
>    所以哪些值得一条警告，哪些值得一个代码修复？
> 4. 明确允许它回答**「本项目的头条声称靠写一个字符串到不了」**——
>    「这个发现，比一份字符串草稿更有价值。」

**第 3 问是提示词的设计约束，而它从没有被划过那条线**；
**第 4 问说明主 agent 已经准备好接受北极星的第一句被推翻。**

**队列第 1 项（系统提示词切片）就压在这个比率上**——机器板 `:101`：
「北极星是「一个弱 LLM 加一个系统提示词」，而出货提示词面只有 117 字符
（`SocketTransportServer.INSTRUCTIONS:85-87`）。而这个比率决定北极星能不能靠写一个字符串到达。」

**结论：这个比率现在要重跑，不是转录。**

---

## 9. 我从这次考古里学到的东西（关于怎么干这个活）

1. **Owner 的五次发火全部指向同一件事：诊断对了，然后继续错。**
   上一轮 `14:34` 就知道「北极星从未跑通过」，`16:51` Owner 给了 R1-R5，
   然后 15 小时在修台本。**知道 ≠ 改变优先级。**

2. **「已保存」不是「已交付」。** 这条被 Owner 写进了 `A4-MCPClient.md:14-20` 的第 0 节，
   放在最前面，因为上一轮正是在这里骗了自己。
   本轮开局第一件事就是验证它——**板说「10 条未验证」，实际是「编译不过」。**

3. **Owner 自己写的验收判据比任何项目文档都硬**：
   「这个东西坏了，我这个判据会不会变红？不会变红的判据一律作废重写。」

4. **报告是线索，不是判据**（`A4-MCPClient.md:290`）。上一轮这句话是对的，
   而本轮第一件事就是它的一个实例。

5. **本项目的历史失败模式是「又转述了一遍板上的话」**（`A4-MCPClient.md:327`）。
   这一整份考古报告如果只复述机器板，就是那个失败模式。
   所以它必须包含机器板上没有的东西——Owner 的 18 条原话、
   那个从未被运行过的北极星、`L0DeclarationGateTest` 的实测结果。
