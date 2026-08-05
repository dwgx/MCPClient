# docs/ — 索引

2026-07-31(2026-08-03 补 agency 区)。`docs/` 之前没有索引,接手的人不知道从哪读。这份就是入口。

> **这些文档一度全是 dwm 与 macOS —— 内核侧一份都没有**,而内核才是这个项目的本体
> (dwm 是可拆卸辅助层)。`agency/command-to-action.md` 是第一份内核侧文档。
> 如果你接手的任务是"让它像人一样在游戏里做事",从那一份开始,不是从 dwm 交接开始。

> **本仓库的 `.ai-notes/` 不在这台机器上**(在 Windows 主线工作站),所以那套
> `STATUS.md` 流程在本线走不了 —— 本线的文档全在 `docs/`。见 `branch-topology.md`。
>
> **读码走 `tools/codegraph/`(铁律⑥)。** 它不是 dwgx 那套 codegraph,是本机替代品:
> 从字节码抽调用图,已覆盖全部模块。用法与**盲区**见 `codegraph.md`。

---

## 0. 五分钟接手路径

按顺序读这四份,别的按需:

0. **任务是内核/游戏行动能力?先读 `agency/handoff-2026-08-09.md`**(最新交接 ——
   **`eval_java` 的实际边界量出来了:门是好的,门里面没有沙箱**;§4 那条 owner 待决
   现在带测量),再读 `agency/handoff-2026-08-08.md`(搭桥的全部真机测量 ——
   §0 有活状态告警:客户端可能还在跑、存档被改过;§4 记着上一轮 Bash 静默的真因是
   heredoc,不是环境毛病),再读 `agency/handoff-2026-08-07.md`(**§1 的三条判据是这个
   仓库最值得读的一节**),再读 `agency/handoff-2026-08-06.md`(真机抓到的七条、craft 的
   缺口、六个 Fork),
   再读 `agency/command-to-action.md`(设计文档 + 岔路 + 真机数字)。**六个 Fork 里 B 与 C 已定**
   (B:不包 vanilla A*,自写邻居生成器;C:`NavIntent` 住 MOVE slot 内),
   **D 仍未定而且它吞掉其余几个** —— 谁持有循环、以什么频率。
   任务是 UI?跳过它们,走下面四份。

   > **然后回头读 `handoff-2026-08-05.md` 的 §3-§5**,它们没有被取代:空转断言的**前七条**、
   > 找它们的唯一可靠办法(注入变异)、窄镜头 vs 宽任务的四次证据、文档梳理的三条规则。
   > 那四条是这个仓库真正的失败模式,08-06 建立在它们之上而不是替换它们
   > (**账本现在是九条**,第八、九条在 08-06 的 §3① §3④,两条都是 AI 自己写的)。
   > **08-05 顶部有横幅列出它已作废的四条,先看横幅。**
1. **`branch-topology.md`** —— 这条线是什么、主线在哪、为什么不合并回去、怎么跑测试。
   **`JAVA_HOME` 必须显式设 JDK 25**,不设会失败得像回归。
2. **`dwm/handoff-2026-07-31.md`** —— 最新交接:当前状态、本轮做完什么、还剩什么。
3. **`debugging.md`** —— 手里有哪些调试能力(JVMTI 断点、类热替换、像素读取),
   以及每个的边界与会杀客户端的坑。**动手排查前必读。**
4. **`dwm/live-verification.md`** —— 真机验证怎么做,以及历史上抓到的、
   headless 原理上抓不到的那些 bug。

---

## 1. 全部文档

### 顶层

| 文档 | 内容 | 何时读 |
|---|---|---|
| `branch-topology.md` | 分支关系、环境差异、合并前提、怎么跑 | **接手第一份** |
| `codegraph.md` | 本机读码入口(铁律⑥)、五个查询、**它看不见什么** | **读码前** |
| `debugging.md` | 全部调试手段 + 边界 + 三个能杀客户端的坑 + 完整案例 | 排查前 |

### dwm(UI 子系统)

| 文档 | 内容 | 状态 |
|---|---|---|
| `dwm/handoff-2026-07-31.md` | **最新交接** | 当前 |
| `dwm/handoff-2026-07-29.md` | 上一份交接(根因分析仍有效) | **已被取代** |
| `dwm/session-2026-07-29.md` | 上一轮总纲(卡片/动画/密钥) | 含订正横幅 |
| `dwm/settings-page.md` | Settings 页结构、动画策略层、分层判断 | 含订正横幅 |
| `dwm/key-ceremony.md` | 两层 TUF 密钥仪式、**私钥在哪**、怎么重建 | 当前 |
| `dwm/entry-point.md` | KI-11 为什么走补丁层、注入点选择 | 当前 |
| `dwm/fluent-spec.md` | Fluent 度量出处([官方]/[WinUI]/[近似]) | 当前 |
| `dwm/live-verification.md` | 真机验证方法 + 抓到的 bug | 当前 |
| `dwm/dwm-deep-dive.md` | DWM 架构深研 + 待办 | 部分过时 |
| `dwm/dwm-architecture-comparison.md` | 与真 Windows DWM 逐条对比 | 含订正横幅 |
| `dwm/research/frame-sequence-verified.md` | 从客户端源码证实的帧序列事实 | 当前 |
| `dwm/handoff-2026-07-28.md` | 更早的交接 | **已被取代** |
| `dwm/session-2026-07-28.md` | 更早一轮总纲 | **已被取代** |

### agency(内核侧 —— 一条命令怎么变成游戏里的动作)

| 文档 | 内容 | 状态 |
|---|---|---|
| `agency/handoff-2026-08-09.md` | **最新交接** —— `eval_java` 的实际边界:**九个探针九个到达、零个被拦**(起进程/任意文件读写/socket/环境),而**门本身按值钉住、三次变异全 CAUGHT**;`SE_CREATE_TOOL` 是唯一开关且**全有全无**;§4 「控制电脑要不要做成能力」现在带测量 | **接手先读这份** |
| `agency/handoff-2026-08-08.md` | 上一份交接,**全部有效** —— 搭桥的全部真机测量(**客户端无速率门、服务端 reach 是 8 格不是 4.5、探针打不中一个 tick**)、MANEUVER 三通道锁相分析、**§0 活状态告警**、§4 一轮偏移的记录 | 当前 |
| `agency/handoff-2026-08-07.md` | 上一份交接,**全部有效**。十一条空转断言(九条在安全内核与补丁签名链上,含**根文档授权自己的根**)、**§1 三条可复用判据(仓库里最值得读的一节)**、§8.0 Fork D 与 craft 的裁决 | 当前 |
| `agency/mutation-candidates-2026-08-05.json` | 12 个子系统的 72 个变异候选,**55 个 `likely-survives` 未跑**。单条跑法见文件内 `_note` | 当前,待变现 |
| `agency/handoff-2026-08-06.md` | 上一份交接。**仍然有效**:三个探针真机 38/38(**对角线第一次到达**)、真机抓到的七条(含**两次当轮作者自己的误诊**)、LOOK 追踪、effects/entities 的"读不到 vs 没有"、craft 活窗口、35 次变异表。§8 的五条 owner 待决一条都没动 | 当前 |
| `agency/handoff-2026-08-05.md` | 上一份交接。**§3-§5 未被取代且仍是本仓库最值得读的**(七条空转断言、注入变异、窄镜头 vs 宽任务、文档梳理三规则);§6 §7 有四条已作废,**顶部横幅列了** | 部分过时,**§3-§5 必读** |
| `agency/handoff-2026-08-04.md` | 上一份交接。§2-§5(空转断言与生产缺陷清单、并行 agent 的四条教训、别再踩)仍有效;§1 已改成不钉数字。§6.6 记着 doc lint 曾恒绿 | 部分过时 |
| `agency/handoff-2026-08-03.md` | 上一份交接。§2-§4(已建好的东西、Fork B/C 的裁决)仍有效;§5 的真机陷阱已被订正 | 部分过时 |
| `agency/command-to-action.md` | 行动/感知层现状、差距、关键路径(§4 带进度横幅)、六个 Fork(**B/C 已定,D 仍未定**)、§5.5 全部真机数字 | 当前,逐节看横幅 |

### macOS

| 文档 | 内容 |
|---|---|
| `macos/known-issues.md` | macOS 专属未解决问题(MK-1 剪贴板) |
| `macos/dwm-qml4j-plan.md` | qml4j 落地计划(历史,阶段已完成) |

---

## 2. 按问题查

| 你想知道 | 去 |
|---|---|
| **接手内核任务,从哪开始** | `agency/handoff-2026-08-09.md`(最新)→ `agency/handoff-2026-08-08.md`(**§0 先看活状态告警**)→ `agency/handoff-2026-08-07.md`(§1 三条判据、§8.0 裁决)→ `agency/handoff-2026-08-06.md`(§3 真机七条、§7 诚实清单) |
| **`eval_java` 实际能碰到什么、怎么撤销** | `agency/handoff-2026-08-09.md` §1(九个探针)§2(开关实测,**全有全无**)§3(门的三次变异) |
| **「控制电脑」要不要做成被设计的能力** | `agency/handoff-2026-08-09.md` §4 —— **owner 待决,测量已齐** |
| **搭桥(telly / god / ninja)现在到哪了** | `agency/handoff-2026-08-08.md` §1(测量)§2(为什么 controller 必须在 core)§3(**三个技术的几何未定,这是拦路的**)§5(MANEUVER) |
| **为什么 Bash 会突然全部静默** | `agency/handoff-2026-08-08.md` §4① —— **是 heredoc,不是环境毛病**。CLAUDE.md 那一节两半都要读 |
| **下一轮做什么最划算** | `agency/handoff-2026-08-07.md` §6 —— 55 个未跑的变异候选,本轮跑的 11 个 11 个全中 |
| **真机验证的两个环境陷阱(必读)** | `agency/handoff-2026-08-04.md` §5(08-03 那版已被订正) |
| **为什么"一条命令"还做不到、下一步做什么** | `agency/command-to-action.md` §0 §4 |
| **有哪些架构取舍在等我拍** | `agency/command-to-action.md` §5 —— 主要是 **Fork D**(模型持环 ~2Hz vs 代码持环 20Hz);另有 **craft 的多 tick 驱动器连带它的 L4(前提已满足,这条到期了)** 与那 8 个诚实墓碑删不删,见 `handoff-2026-08-06.md` §8 |
| **行动/感知层已经建好了什么(别重建)** | `agency/command-to-action.md` §1 |
| **vanilla 自带寻路在哪** | `agency/command-to-action.md` §5 Fork B(`client/src/.../pathfinding/`) |
| 现在该做什么(dwm 侧) | `dwm/handoff-2026-07-31.md` §6 |
| 这条线能不能合回主线 | `branch-topology.md` §6 |
| 私钥丢了怎么办 | `dwm/key-ceremony.md` §0(**没有恢复路径**) |
| 怎么开原生调试器 | `debugging.md` §1(`MCP_JVMTI=1`) |
| **内核侧真机验证怎么写(四条会让你误诊自己代码的规则)** | `debugging.md` §10 |
| **派 worktree agent 前要改的设置,以及为什么编译类 agent 会 stall** | `branch-topology.md` §4.1 §4.2 |
| **怎么证明一条断言不是空转** | `agency/handoff-2026-08-05.md` §3(方法)+ `agency/handoff-2026-08-06.md` §2④(`scripts/mutate.py`,含它自己被抓到的缺陷)+ §4(35 次变异表)+ **`agency/handoff-2026-08-07.md` §1(三条判据:先按判据问,别通读代码)** |
| **审一个从没被变异过的子系统,怎么起手** | `agency/handoff-2026-08-07.md` §1(三条判据)+ §4(并行只读审计的形状,含 workflow 收不到结果时怎么从 transcript 里挖) |
| **为什么 doc lint 在本分支上跑等于没跑** | `agency/handoff-2026-08-04.md` §6.6 |
| 为什么某个颜色画不出来 | `debugging.md` §9 + `dwm/fluent-spec.md` 颜色节的横幅 |
| 某个 Fluent 数值的出处 | `dwm/fluent-spec.md` |
| 真机怎么跑、探针测什么 | `dwm/live-verification.md` |
| 为什么 UI 入口要走字节码补丁 | `dwm/entry-point.md` |
| 我们和真 DWM 差在哪 | `dwm/dwm-architecture-comparison.md`(**先看顶部订正**) |

---

## 3. 读这些文档时要知道的事

**订正横幅是有意保留的。** 几份文档顶部有"已修复/已被取代/已证伪"的横幅,下面的原文
**故意没删**。因为这个项目反复出现同一类错误 —— 状态字段全报健康而结果不对 ——
而**误判的序列本身比最终结论更有教育意义**。看到横幅就以横幅为准,但别跳过下面的推理。

**其中包括我自己的一次错误撤回。** `dwm/handoff-2026-07-29.md` §3 一度被我标成"已证伪、
bug 不存在、是测量方法错" —— **那个判断本身是错的**,bug 是真的。两层结论都留在那里。

**数字以代码为准,不以文档为准。** 文档里的测试数、提交数会漂移。核对方法:

```bash
export JAVA_HOME=~/.jdks/jdk-25.0.3+9/Contents/Home
./mvnw -B -ntp test                                    # 单测总数
./mvnw -B -ntp -pl dwm verify -Ddwm.live.skip=false    # live IT(要显示器)
python3 scripts/live-dwm-probe.py                      # 真机探针(要客户端在跑)
```

**"已知未覆盖"要当真。** 几份文档末尾有诚实列出的未验证项(最要紧的是
**Windows 侧完全未验**)。那些不是谦辞,是事实。
