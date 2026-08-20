# docs/ — 索引

`docs/` 的入口。进 git 的是产品设计与验证方法。AI 交接 / session 笔记不进 git
(2026-08-20 已从 `feat/dwm-qml4j` 历史拿掉),活进度在工作站 `.ai-notes/`。见 `branch-topology.md`。

> **读码走 `tools/codegraph/`(铁律⑥)。** 它不是 dwgx 那套 codegraph,是本机替代品:
> 从字节码抽调用图,已覆盖全部模块。用法与**盲区**见 `codegraph.md`。

---

## 0. 接手路径

**任务是内核 / 游戏行动能力(本线的主线):**

1. `agency/command-to-action.md` —— 设计:已建好什么、关键路径、Fork。逐节看订正横幅。
2. `branch-topology.md` —— 这条线是什么、主线在哪、为什么不合并回去。
   **`JAVA_HOME` 必须显式设 JDK 25**,不设会失败得像回归。
3. `debugging.md` —— 调试能力 + §10 内核侧真机验证纪律。**动手排查前必读。**

**任务是 UI(dwm):** `dwm/live-verification.md` → `debugging.md` → `dwm/fluent-spec.md` /
`dwm/key-ceremony.md`。dwm 是已完结的子系统。

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
| `agency/command-to-action.md` | 设计文档:§1 已建好什么(别重建)· §4 关键路径 · §5 六个 Fork · §5.5 早期真机数字。**逐节看订正横幅** |

> 会话交接与变异账本不进 git。工作站副本在 `.ai-notes/docs/project/handoff/archive/macos-excised-2026-08-20/`。

### dwm(UI 子系统,已完结)

| 文档 | 内容 | 状态 |
|---|---|---|
| `dwm/live-verification.md` | 真机验证方法 + 抓到的 bug | 当前 |
| `dwm/live-verification.md` | 真机验证方法 + 抓到的 bug | 当前 |
| `dwm/key-ceremony.md` | 两层 TUF 密钥仪式、**私钥在哪**、怎么重建 | 当前 |
| `dwm/entry-point.md` | KI-11 为什么走补丁层、注入点选择 | 当前 |
| `dwm/fluent-spec.md` | Fluent 度量出处([官方]/[WinUI]/[近似])+ **两个 alpha 陷阱** | 当前 |
| `dwm/research/frame-sequence-verified.md` | 从客户端源码证实的帧序列事实 | 当前 |
| `dwm/settings-page.md` | Settings 页结构、动画策略层、分层判断 | 含订正横幅 |
| `dwm/dwm-architecture-comparison.md` | 与真 Windows DWM 逐条对比 | 含订正横幅 |
| `dwm/dwm-deep-dive.md` | DWM 架构深研 + 待办 | 部分过时 |

### macOS

| 文档 | 内容 |
|---|---|
| `macos/known-issues.md` | macOS 专属未解决问题(MK-1 剪贴板) |
| `macos/dwm-qml4j-plan.md` | qml4j 落地计划(历史,阶段已完成) |

---

## 2. 按问题查

| 你想知道 | 去 |
|---|---|
| **接手内核任务,从哪开始** | `agency/command-to-action.md` + `debugging.md` §10 |
| **下一轮做什么最划算** | 起客户端,跑一次 `act_set` 的 `route`,看 COMPLETE。`RouteIntent` 已在树上。 |
| **AI 自己算路是怎么实现的、代码在哪** | `core/src/main/java/net/marcloud/mcp/core/drivers/plan/` |
| **怎么证明一条断言不是空转** | `scripts/mutate.py` —— 修前 SURVIVED、修后 CAUGHT。退出码 2/3 = 没裁决 |
| **变异跑完为什么必须 `git diff` 核生产侧** | `scripts/mutate.py` —— 被杀的进程走不到 `finally`,变异留在磁盘上 |
| **为什么「读不到」必须和「是空气」分开** | `core/util/BlockProbe` |
| **有哪些架构取舍在等我拍** | `agency/command-to-action.md` §5 —— Fork A / E / F 未裁决;B / C / D 与 craft 已定 |
| **真机验证的环境陷阱(熔断 / 失焦 / 死玩家)** | `debugging.md` §10 |
| **fan-out 怎么派才不 stall** | `branch-topology.md` §4.1 §4.2 |
| **怎么给一条「从外部驱动不了」的分支写行为测试** | `ABrokenRootChainDisarmsTheProductionCompositionTest` |
| **行动/感知层已经建好了什么(别重建)** | `agency/command-to-action.md` §1 |
| **为什么"一条命令"曾经做不到** | `agency/command-to-action.md` §0 §4 |
| **vanilla 自带寻路在哪、为什么不用** | `agency/command-to-action.md` §5 Fork B |
| 这条线能不能合回主线 | `branch-topology.md` §6 —— 硬前提是 Windows GL / 真机探针 |
| 怎么开原生调试器 | `debugging.md` §1(`MCP_JVMTI=1`) |
| 现在该做什么(dwm 侧) | 已完结。验证看 `dwm/live-verification.md` |
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

其中包括一次错误的撤回:卡片面不可见曾被标成"测量方法错" —— **那个判断本身是错的**,
bug 是 MC 的 `GL_ALPHA_TEST`。两层结论都留在 `debugging.md` §9 与 `dwm/settings-page.md`。

**"已知未覆盖"要当真。** 合回 `mcp-core` 的硬前提仍是 **Windows 上的 GL / 真机探针**,
不是「代码已经在这台机器上」。2026-08-20 工作区已在 Windows 的 `feat/dwm-qml4j`;
headless Maven 以当场为准。别把 GL 未验写成「这台机器上做不到」。
