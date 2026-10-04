---
doc: commit-convention
title: Commit 规范 — 定死骨架(从真实历史提炼)
layer: reference
status: authoritative
updated: 2026-07-12
parent: ../README.md
next:
  - path: cc-workflow-guide.md
    when: 你要看整体怎么高效干活(commit 只是其中一环)
read_if: 你要提交 commit——subject 怎么起、一次装什么、body 怎么写、结尾惯例。以后所有会话照此,永不变(除非重大特例)。
---

# MCPClient Commit 规范(定死骨架,除非重大特例不改)

本规范从本仓库全部真实 commit 历史逆向提炼而成。所有会话、所有 AI、所有人类
贡献者一律照此提交。风格与项目的系统架构审美(NT Executive 分层 + PCB 命名母题)
保持一致:简洁、有体系、可审计。

约定:全文英文提交(subject + body)。除技术箭头 `->` `<-` 外禁用一切图形符号。

## 1. 风格总则

- 一条 commit = 一个可独立验证的主张(one verifiable claim)。读者看 subject
  就知道"改了哪个子系统、做了什么",看 body 就知道"为什么、动了哪些类、测试证明"。
- 英文。祈使句或名词短语,不写口水话。讲改动本身,不讲过程流水账。
- 措辞跟随项目术语:posture / seam / choke point / gate / fail-closed /
  additive / byte-identical / teeth-verified / non-vacuous / green /
  "zero MC source edits" / "Verified live in a running MC client"。
- 诚实优先:没 committed 的产物要点名说明(gitignored);没测到的要说没测到。
- 项目已弃用中文代号隐喻(见 commit "Drop the shenqi nickname"),一律用中性的
  NT 架构英文用语("the Kernel" 等)。不要再引入旧昵称。

## 2. Subject 规范

### 2.1 两种开头,二选一

- **(a) `scope: 摘要`** — 改动落在一个已知的具名子系统/层/能力包/基建区,想把
  "落在哪"顶到最前面时用。冒号后是摘要(祈使动词或名词短语皆可)。
- **(b) 祈使动词开头** — 改动天然是"对某个产物做某个动作"时用,尤其新增、依赖
  升级、删除/搬移、跨切面修复。

两者都可再带一个 `: 副标题` 展开(与是否有 scope 前缀正交):
`Add Board: client-feature framework as a peer module to core`

### 2.2 Scope 前缀表(仅收录历史真实出现过的)

| 类别 | 写法 | 真实例子 |
|---|---|---|
| 安全层 (NT 7 层) | `L<n>[ 描述]:` / `Phase <n>:` | `L6:` · `L1 VTL:` · `L7 boundary validation:` · `Phase 0:` |
| 能力包 (C1-C8) | `C<n>[ 代号]:` | `C6 CONTROL-EXEC:` |
| 具名工具/模块 | `<name>[ 描述]:` | `dev_probe:` · `Board wave-A hardening:` |
| 基建 / 元文件 | `<name>:` | `CI:` · `CLAUDE.md:` |
| 批量修复 | `<批次> fixes:` | `Audit fixes:` |
| 概念里程碑 | `<Concept>:` | `Keystone:` · `Kernel data model:` |

取名规律:前缀原样保留标识符的真实大小写(`L6`/`CI`/`C6` 大写,`dev_probe` 小写
=真实工具名,`CLAUDE.md`=真实文件名)。descriptor 用小写短语接在标识符后再打冒号。
不要发明历史里没有的 scope(别写 `feat:`/`chore:` 这类 Conventional Commits 前缀,
本项目不用)。

### 2.3 祈使动词表(历史真实出现过的)

| 动词 | 用途 | 真实例子 |
|---|---|---|
| `Add` | 新模块/功能/工具/测试/文件(最常用) | `Add REST facade: ...` |
| `Fix` | 缺陷 / 审计 finding / bug | `Fix completeness-audit bug/honesty gaps (GAP-1/3/4/5/9/16)` |
| `Upgrade` | 依赖版本升级(每个依赖单独一条) | `Upgrade guava 17.0 -> 33.6.0-jre (protocol verified unchanged)` |
| `Restructure` | 包/布局重组 | `Restructure core into NT Executive layout + ...` |
| `Integrate` | 把并行成果并进 gate 后面 | `Integrate C1/C3/C5/C7/C8 capability packages behind the 7-layer gate` |
| `Activate` | 打通此前 dead/dormant 的路径 | `Activate L3/L4/L5 (GAP-2) + ...` |
| `Close` | 堵安全洞 / 收掉审计 CRITICAL | `Close audit CRITICALs: gate generated tools at R-1, ...` |
| `Unblock` | 解阻塞 | `Unblock C6 native JVMTI debugger: MSVC-free clang build + ...` |
| `Move` / `Drop` / `Rewrite` / `Tidy` | 搬移 / 删除 / 整体重写 / 清理 | `Move run scripts + jvm-args into scripts/ ...` |
| `gitignore` | 加忽略规则(小写,取命令的动词形) | `gitignore release asset bundles (mc-assets-*.zip)` |

### 2.4 格式规则

- 时态:祈使动词 / 名词短语 / 现在时陈述句(`L6: strict-handle posture closes
  the voluntary-gating bypass`)三种历史都有,择其一,别过去时。
- 大小写:动词首字母大写(`Add`/`Fix`/`Upgrade`);例外 `gitignore` 小写。冒号后
  一律小写,除非专有名词/缩写(`P-SECURE`)。
- 长度:目标 <=72;当 scope 确实要打包多项时可到 ~90;绝不换行成第二行。
- 标点(结尾不加句号):

  | 符号 | 用途 | 例 |
  |---|---|---|
  | `:` | 分隔 scope / 副标题 | `L6: strict-handle posture` |
  | `+` | 连接一并交付的多项 | `agent + tests` |
  | `->` | 迁移 / 改名 / 版本 | `4.1.124 -> 4.2.16`,`R2 -> R-1` |
  | `/` | 归组同类 | `L3/L4/L5`,`GAP-1/3/4/5/9/16` |
  | `;` | 分隔一条里的两处相关改动 | `wire observation; lifecycle` |
  | `(...)` | 收尾放状态 / 限定 | `(opt-in)` `(protocol verified)` `(phase 1 skeleton)` |

## 3. 一次提交装什么

判据:一条 commit 打包成一个"能作为整体去验证的正确性主张"。

- 一个依赖升级 = 一条(主张是"协议/行为不变",自带验证)。历史上 gson/jopt/oshi/
  guava/netty 各自独立成 commit,绝不合并。
- 一轮审计 = 一条(主张是"这 N 个 finding 已修 + 测试绿"),finding 编号列 body。
- 一个安全层 = 一条(`L1 VTL:` / `L7 ...:` / `Kernel data model:`)。
- 一个功能 = 一条,连同它的接线(wire 进 registry/gate)、测试、live 验证一起。
  别把"加类""接线""加测试"拆成三条,它们是同一个主张。
- 纯搬移/改名/文档尽量与行为改动分开成独立 commit。
- 反过来:互相无关、各自带不同正确性主张的改动塞进一条(升依赖+修 bug+改文档)就该拆。

## 4. Body 结构

散文 + 列表,按行宽 ~72-80 折行。三段式:

1. **开场 what/why(1-2 句)**:先讲问题/动机/威胁,再讲这次做了什么。
2. **主体:bullet 或编号 finding**
   - Bullet 用 `- Name: 说明`,Name 是具体类/工具/子系统(粗体引导):
     `- ObManager: additive strictHandles posture (default false = unchanged).`
   - 多个审计发现用编号 + 严重度:`GAP-1 (HIGH): ...`、`HIGH#3/MED#9 NettyTap: ...`、`1. CI (build.yml): ...`。
   - 可用分组小标题:`Security / guard:`、`Correctness:`、`Major:`/`Minor:`/`Cleanup:`。
   - 通篇点名真实类/方法/测试名(`McpCore.buildObjectManager`、`L6ObjectHandleTest.strictHandlePostureDeniesHandleLessHandleOp`)。
3. **收尾行**:测试数 + 绿 + 适用的诚实声明(见第 5 节)。

## 5. 结尾惯例(必须 vs 视情况)

**必须(改到代码/测试时):**
- 测试数 + `green`。历史格式任选(下面 N 是占位,填真实数):`core N/N green` ·
  `board N/N, core N/N green` · `N core tests green` · `core N -> M green (2 new regression tests)`。
- fix 类必须声明回归测试为真:`teeth-verified` / `non-vacuous` / `fails on pre-fix code`。

**视情况(适用才写,不硬凑):**
- live 验证:`Verified live in a running MC client: <工具> <具体证据>`。
- gitignored 声明:`The compiled core-jvmti.dll is gitignored (rebuild via build-clang.sh).`
- 影响面保证:`Frozen contract unchanged (additive only).` / `Default posture unchanged.` / `zero MC source edits`。
- 构建声明:`Fat agent jar builds.`

**可省 body / 免测试数**:纯 gitignore、纯文档/CI-config 的小改可只有 subject 或极短 body。

## 6. 铁律(违反即拒绝提交)

1. **绝不加 AI 署名**:无 `Co-Authored-By`,无 "Generated with" 之类任何行。只署用户本人。
2. **先编译、再测、才提交**。
3. **每个 fix/feature 配非空转回归测试**(旧代码上会失败那种,teeth-verified)。
4. **诚实**:gitignored / 未测 / 未接线 如实点名,不谎报 green。
5. 英文提交;除 `->` `<-` 外禁用图形符号。
6. 一条 commit = 一个可独立验证的主张;无关改动拆开提。

## 7. 好范例(选自真实历史)

- **`L6: strict-handle posture closes the voluntary-gating bypass`** — scope 顶前;
  body 先讲威胁再讲修法;`- Name:` 引导;点名回归测试声明 teeth-verified;测试数收尾。
- **`Upgrade guava 17.0 -> 33.6.0-jre (protocol verified unchanged)`** — 一个依赖=一条;
  subject 用 `->` 表版本 + 括号打"验证过";body 每处 API 适配用 `->`;收尾给可审计证据。
- **`Add structured GUI interaction: expose the whole clickable GUI to the LLM`** — 动词
  + `:` 副标题点意图;why 用对比;live 验证带状态转移 `GuiMainMenu -> GuiOptions`。
- **`Fix completeness-audit bug/honesty gaps (GAP-1/3/4/5/9/16)`** — subject 括号收拢
  finding 编号;body 每条 `GAP-n (SEV):`;收尾 non-vacuous + 测试数。
- **`Board wave-A hardening: subscription bag, dispatch cache, persistence, cancel reason`**
  — scope+descriptor 后逗号列交付项;收尾"契约不变(仅新增)"+ 双模块测试数。

## 8. 反例(不要这样)

- `update stuff and fix some bugs` + 塞进"升依赖+修 bug+改文档" + 带 `Co-Authored-By` —
  该拆三条;subject 空洞无 scope;无测试数;违铁律 1 的 AI 署名。
- `Add gui_scroll tool` / body 只复述 subject — 新增能力却无非空转回归测试(违铁律 3)、
  无测试数、不提如何接进 gate、无 why、不点名实现类。

### 真实历史违规(记录在案,不改写)

- `c7d1bf9 Add self-extending, self-healing capability registry (神经网络式架构)` —
  subject 结尾中文括号,**违反铁律 5(English commits;除 `-> <-` 无图形符号)**。
  应作 `(neural-net-style architecture)`。body 本身扎实(点了 Voyager/DGM 血缘 + breaker
  参数),纯 subject 瑕疵。**不 rebase 修**:它距 HEAD 40+ 个 commit、且是 `assets-1.8`
  的共同祖先,改写要 force-push 重写 40+ hash 并让 assets-1.8 分叉——收益(改个老标题里的
  括号)远小于风险。历史违规**记录在案即可**,规范是"从今往后"约束。教训:提交前对 subject
  过一遍铁律 5,中文只留给正文都不该有的场合。

## 相关
- 模板:仓库根 `.gitmessage`(已 `git config commit.template` 挂上)。
- 工作流手册:`cc-workflow-guide.md` · 变更记录:`../project/governance-log/README.md`
