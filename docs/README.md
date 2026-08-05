# docs/ — 索引

`docs/` 的入口。**本仓库的 `.ai-notes/` 不在这台机器上**(在 Windows 主线工作站),所以那套
`STATUS.md` 流程在本线走不了 —— 本线的文档全在这里。见 `branch-topology.md`。

> **读码走 `tools/codegraph/`(铁律⑥)。** 它不是 dwgx 那套 codegraph,是本机替代品:
> 从字节码抽调用图,已覆盖全部模块。用法与**盲区**见 `codegraph.md`。

---

## 0. 接手路径

**任务是内核 / 游戏行动能力(本线的主线):**

1. `agency/HANDOFF.md` —— **唯一活的交接**。现状、唯一的下一步、四条判据、全部真机测量、
   环境坑、owner 待决、诚实未验。
2. `branch-topology.md` —— 这条线是什么、主线在哪、为什么不合并回去。
   **`JAVA_HOME` 必须显式设 JDK 25**,不设会失败得像回归。
3. `debugging.md` —— 手里有哪些调试能力,以及 §10 内核侧真机验证的八条纪律。**动手排查前必读。**

**任务是 UI(dwm):** 跳过上面,读 `dwm/handoff-2026-07-31.md` → `debugging.md` →
`dwm/live-verification.md`。dwm 是已完结的子系统,没人在推进它。

---

## 1. 全部文档

### 顶层

| 文档 | 内容 | 何时读 |
|---|---|---|
| `branch-topology.md` | 分支关系、环境差异、合并前提、怎么跑 | **接手第一份** |
| `codegraph.md` | 本机读码入口(铁律⑥)、五个查询、**它看不见什么** | **读码前** |
| `debugging.md` | 全部调试手段 + 边界 + 三个能杀客户端的坑 + **§10 真机八条纪律** | 排查前 |

### agency(内核侧 —— 一条命令怎么变成游戏里的动作)

| 文档 | 内容 |
|---|---|
| `agency/HANDOFF.md` | **唯一活的交接。** §0 现状与下一步 · §1 未提交那批 · **§2 四条判据(最被引用)** · §3 全部真机测量 · §4 验证纪律 · §5 命令与环境坑 · §6 owner 待决 · §7 诚实未验 · §8 出处对照 |
| `agency/command-to-action.md` | 设计文档:§1 已建好什么(别重建)· §4 关键路径 · §5 六个 Fork · §5.5 早期真机数字。**逐节看订正横幅** |
| `agency/mutation-candidates-2026-08-05.json` | 72 个变异候选的逐条锚点、`secondPassVerdict`、`fixed` 标记。**账本已结清(70 SURVIVED → 70 CAUGHT,0 待修)** |

> **2026-08-03..08-12 的十份 handoff 与 `telly-test-plan.md` 已合并进 `HANDOFF.md` 并删除**
> (3,398 行)。原文在 git 里,旧文件名 → 提交号的对照表在 `HANDOFF.md` §8。
> 合并的理由也写在那里:那十一份互相指着对方的订正横幅,四天写了十份,然后需要专门的提交去修
> 那些横幅 —— 文档系统开始自己喂自己。

### dwm(UI 子系统,已完结)

| 文档 | 内容 | 状态 |
|---|---|---|
| `dwm/handoff-2026-07-31.md` | dwm 侧最新交接 | 当前 |
| `dwm/live-verification.md` | 真机验证方法 + 抓到的 bug | 当前 |
| `dwm/key-ceremony.md` | 两层 TUF 密钥仪式、**私钥在哪**、怎么重建 | 当前 |
| `dwm/entry-point.md` | KI-11 为什么走补丁层、注入点选择 | 当前 |
| `dwm/fluent-spec.md` | Fluent 度量出处([官方]/[WinUI]/[近似])+ **两个 alpha 陷阱** | 当前 |
| `dwm/research/frame-sequence-verified.md` | 从客户端源码证实的帧序列事实 | 当前 |
| `dwm/settings-page.md` | Settings 页结构、动画策略层、分层判断 | 含订正横幅 |
| `dwm/dwm-architecture-comparison.md` | 与真 Windows DWM 逐条对比 | 含订正横幅 |
| `dwm/dwm-deep-dive.md` | DWM 架构深研 + 待办 | 部分过时 |
| `dwm/session-2026-07-29.md` | 上一轮总纲(卡片/动画/密钥) | 含订正横幅 |
| `dwm/handoff-2026-07-29.md` `handoff-2026-07-28.md` `session-2026-07-28.md` | 更早的交接与总纲 | **已被取代** |

### macOS

| 文档 | 内容 |
|---|---|
| `macos/known-issues.md` | macOS 专属未解决问题(MK-1 剪贴板) |
| `macos/dwm-qml4j-plan.md` | qml4j 落地计划(历史,阶段已完成) |

---

## 2. 按问题查

| 你想知道 | 去 |
|---|---|
| **接手内核任务,从哪开始** | `agency/HANDOFF.md` §0 |
| **下一轮做什么最划算** | `agency/HANDOFF.md` §0 —— 起客户端跑一次 `act_set` 的 `route`。那一条命令就是未提交那批成不成的全部判据 |
| **AI 自己算路是怎么实现的、代码在哪** | `agency/HANDOFF.md` §3 末;`core/src/main/java/net/marcloud/mcp/core/drivers/plan/` |
| **怎么证明一条断言不是空转 / 四条判据** | `agency/HANDOFF.md` §2 —— **先按判据问,别通读代码** |
| **审一个从没被变异过的子系统,怎么起手** | `agency/HANDOFF.md` §2 + §4;逐条锚点在 `mutation-candidates-2026-08-05.json` |
| **那批变异候选跑完了吗** | 跑完并全部修完:**70 SURVIVED → 70 CAUGHT,0 条待修**。见 `HANDOFF.md` §2 末与 JSON 的 `fixed` 字段 |
| **变异跑完为什么必须 `git diff` 核生产侧** | `agency/HANDOFF.md` §4 —— 被杀的 `mutate.py` 走不到 `finally`,变异留在磁盘上 |
| **`mutate.py` 的退出码 2 / 3 是什么意思** | `agency/HANDOFF.md` §4 —— **既不是 survivor 也不是 catch,是「什么都没被验证」** |
| **搭桥的几何、reach、跳跃弧线、放置包络** | `agency/HANDOFF.md` §3 —— **跳买到的是身体腾出的空间,不是 reach** |
| **为什么「读不到」必须和「是空气」分开** | `agency/HANDOFF.md` §3 末;`core/util/BlockProbe`(**七个文件读方块、没一个问过区块加载**) |
| **`eval_java` 实际能碰到什么、怎么撤销** | `agency/HANDOFF.md` §6 第 2 条 —— 门按值钉住,**门里面没有沙箱**;唯一开关全有全无 |
| **「控制电脑」要不要做成被设计的能力** | `agency/HANDOFF.md` §6 第 2 条 —— **owner 待决,测量已齐** |
| **有哪些架构取舍在等我拍** | `agency/HANDOFF.md` §6 —— Fork A / E / F 从未裁决;**B / C / D 与 craft 已定,别重问** |
| **为什么 Bash 会突然全部静默** | `agency/HANDOFF.md` §5 —— **是 heredoc,不是环境毛病**。CLAUDE.md 那一节两半都要读 |
| **真机验证的环境陷阱(熔断 / 失焦 / 死玩家)** | `agency/HANDOFF.md` §5 + `debugging.md` §10 |
| **fan-out 怎么派才不 stall** | `agency/HANDOFF.md` §5 末 + `branch-topology.md` §4.1 §4.2 |
| **怎么给一条「从外部驱动不了」的分支写行为测试** | `agency/HANDOFF.md` §2 附带条 + `ABrokenRootChainDisarmsTheProductionCompositionTest` |
| **行动/感知层已经建好了什么(别重建)** | `agency/command-to-action.md` §1 |
| **为什么"一条命令"曾经做不到** | `agency/command-to-action.md` §0 §4 |
| **vanilla 自带寻路在哪、为什么不用** | `agency/command-to-action.md` §5 Fork B;结论在 `HANDOFF.md` §3 末 |
| 这条线能不能合回主线 | `branch-topology.md` §6 —— **唯一硬前提是 Windows 侧验证,而本机做不到** |
| 怎么开原生调试器 | `debugging.md` §1(`MCP_JVMTI=1`) |
| 现在该做什么(dwm 侧) | `dwm/handoff-2026-07-31.md` §6 |
| 私钥丢了怎么办 | `dwm/key-ceremony.md` §0(**没有恢复路径**) |
| 为什么某个颜色画不出来 | `debugging.md` §9 + `dwm/fluent-spec.md` 颜色节的横幅 |
| 某个 Fluent 数值的出处 | `dwm/fluent-spec.md` |
| 为什么 UI 入口要走字节码补丁 | `dwm/entry-point.md` |
| 我们和真 DWM 差在哪 | `dwm/dwm-architecture-comparison.md`(**先看顶部订正**) |

---

## 3. 读这些文档时要知道的事

**数字以代码为准,不以文档为准。** 文档里的测试数、提交数会漂移,而这个仓库钉过的每一个数都在
钉下之后没几轮作废。核对方法:

```bash
export JAVA_HOME=~/.jdks/jdk-25.0.3+9/Contents/Home
./mvnw -B -ntp test                                    # 单测总数看它自己的输出
./mvnw -B -ntp -pl dwm verify -Ddwm.live.skip=false    # live IT(要显示器)
python3 scripts/live-dwm-probe.py                      # 真机探针(要客户端在跑)
```

**订正横幅是有意保留的。** 几份文档顶部有"已修复/已被取代/已证伪"的横幅,下面的原文**故意
没删** —— 因为这个项目反复出现同一类错误(状态字段全报健康而结果不对),而**误判的序列本身
比最终结论更有教育意义**。看到横幅就以横幅为准,但别跳过下面的推理。

其中包括一次错误的撤回:`dwm/handoff-2026-07-29.md` §3 一度被标成"已证伪、bug 不存在、是测量
方法错" —— **那个判断本身是错的**,bug 是真的。两层结论都留在那里。

**"已知未覆盖"要当真。** 最要紧的一条是 **Windows 侧完全未验**,而这台机器上做不到 ——
所以它在本线是结构性不可执行的,不是"还没排到"。别再把它当计划重列一遍。
