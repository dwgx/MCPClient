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

0. **任务是内核/游戏行动能力?先读 `agency/handoff-2026-08-04.md`**(最新交接),
   再读 `agency/command-to-action.md`(设计文档 + 六个待定岔路 + 真机数字)。
   任务是 UI?跳过它们,走下面四份。

   > 那份交接的 §3 是最值得先看的一节:本轮抓到的空转断言与生产缺陷,
   > 全部靠注入变异确认。它同时记着一件事 —— **本轮零真机验证**。
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
| `agency/handoff-2026-08-04.md` | **最新交接** —— 空转断言与生产缺陷清单、并行 agent 的四条教训、**零真机验证** | **接手先读这份** |
| `agency/handoff-2026-08-03.md` | 上一份交接。§2-§4(已建好的东西、Fork B/C 的裁决)仍有效;§5 的真机陷阱已被订正 | 部分过时 |
| `agency/command-to-action.md` | 行动/感知层现状、差距、关键路径、**必须 owner 定的六个岔路**、§5.5 全部真机数字 | 当前 |

### macOS

| 文档 | 内容 |
|---|---|
| `macos/known-issues.md` | macOS 专属未解决问题(MK-1 剪贴板) |
| `macos/dwm-qml4j-plan.md` | qml4j 落地计划(历史,阶段已完成) |

---

## 2. 按问题查

| 你想知道 | 去 |
|---|---|
| **接手内核任务,从哪开始** | `agency/handoff-2026-08-03.md` |
| **真机验证的两个环境陷阱(必读)** | `agency/handoff-2026-08-03.md` §5 |
| **为什么"一条命令"还做不到、下一步做什么** | `agency/command-to-action.md` §0 §4 |
| **有哪些架构取舍在等我拍** | `agency/command-to-action.md` §5(六个 Fork) |
| **行动/感知层已经建好了什么(别重建)** | `agency/command-to-action.md` §1 |
| **vanilla 自带寻路在哪** | `agency/command-to-action.md` §5 Fork B(`client/src/.../pathfinding/`) |
| 现在该做什么(dwm 侧) | `dwm/handoff-2026-07-31.md` §6 |
| 这条线能不能合回主线 | `branch-topology.md` §6 |
| 私钥丢了怎么办 | `dwm/key-ceremony.md` §0(**没有恢复路径**) |
| 怎么开原生调试器 | `debugging.md` §1(`MCP_JVMTI=1`) |
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
