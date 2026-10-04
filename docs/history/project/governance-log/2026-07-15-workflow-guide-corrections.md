---
doc: governance-record
title: 治理记录 — 工作流手册 3 处更正 + 加工具门控清单(2026-07-15b)
layer: reference
status: authoritative
updated: 2026-07-15
parent: README.md
read_if: 你想知道 2026-07-15b 为什么改了 cc-workflow-guide 的 fan-out 规矩 / 测试教义 / 骨架速查。
---

# 治理记录:工作流手册 3 处更正 + 新增加工具门控清单

**日期**:2026-07-15(b 会话,全 108 包 MCP 暴露收尾后)
**改了什么**:`docs/reference/cc-workflow-guide.md`(治理教义,§1/§3/§4/§6/附录)+
`docs/reference/tool-annotation-convention.md`(新增 §0)+ `docs/architecture/02-CAPABILITIES.md`(一处表述)

## dwgx 为什么授权

dwgx 原话:"我需要你回顾梳理做的任何写的工具 确保他们是最好的高质量按照项目说明的样子 以及给出意见优化我的 指定md文档 工作流",
随后明确"现在压根不需要你写代码 做什么 就是来梳理好项目各种工作方式 MD文档"。
= 授权对工作方式类 MD 做实质梳理。

## 改动与依据(全部来自本会话实测,不是臆测)

### 1. §3 fan-out 规矩:**旧版是错的**

- 旧文:"workflow / fan-out 只做研究/纯文本产出 ... 让 workflow 吐大代码**必卡死**"。
- **证伪**:本会话严格照做(两轮 fan-out 都是只读研究/纯文本产出),**仍卡死 4 个 agent**
  (bp-entity 433k / bp-tools-security 671k / w3-btier 274k / w6-sendtools 243k,全部 retry 到爆)。
  同会话成功的 agent(编目 108 包 5 路、核验 getter 3 路)做的是**同类**研究工作。
- **真变量**:单 agent 的**输出体量与 schema 形状**。嵌套(数组→对象→数组)+ 长自由文本字段 = 挂;
  扁平一行一条 = 稳。
- 新文给出可操作规则(schema ≤2 层 / 长文本与数组不混 / ≤10-15 条目 / 大段 Java 仍主线自己写)
  + 止损信号(retry 2 且 >200k tok 就 TaskStop,journal.jsonl 有缓存可取)。

### 2. §6 新增"非空转 ≠ 正确"(新失败模式,原教义没有)

- 真事:W6 加 4 个 `send_*` 工具时漏登记 L4 特权表。测试 `SendToolsW6Test` 断言"三张表齐全"——
  它在改动前**确实会挂**(工具不存在),**完全满足铁律的"非空转"**,但断言的是**我数错的规矩**,
  于是把漏洞钉成"预期行为",绿着推上 origin。双轴 code-review 才抓出。
- 根因:**测试写给了代码,不是写给规格**(先实现再写测试描述实现)。
- 新增做法:写断言前回权威文档确认规格;"必须同时改 N 处"的规矩配**反向不变式**测试;
  并要求**实证**(git stash 撤掉修复看测试真挂)而非在提交信息里声称。

### 3. §4 新增 maven 陈旧 jar 陷阱(本会话坑 3 次)

`-pl core test` 单跑会用陈旧 board jar → `BoardTraceLinkTest cannot find symbol` **假失败**。
正解 `-pl board,core test`。附症状识别法(报错文件是你没改过的测试 → 是它,不是你的代码)。

### 4. 附录骨架速查:修陈旧(**非本会话引入**)

旧文"4 模块边界(core/client/lwjgl2-shim/board)"与 **ADR-0002(2026-07-14)**冲突——
该 ADR 已把 shim 重新分类为"client 的平台地基",设计骨架**恒为 3**。按 ADR 与 STATUS 对齐。

### 5. `tool-annotation-convention.md` 新增 §0 门控登记清单(**最高价值**)

- **结构性缺口**:该文 `read_if` 明写"你要新增/改一个 MCP 工具",是加工具时唯一会读的文档,
  却只讲 LLM 可见的标注,**一个字没提那 4 张安全门控表**,只在文末"相关"塞了个链接。
  门控知识全在 `02-CAPABILITIES.md`/`01-SECURITY-KERNEL.md`——**放在了不会被读到的地方**。
- 这就是 W6 漏 L4 的结构性根因(不是"没读书",是"书没放在该放的地方")。
- 新增 §0:4 张表 + 漏登记后果 + 同族照抄表 + "ToolRegistry 注册是暴露不是门" + 自检法。

### 6. `02-CAPABILITIES.md` 一处表述

原文"按工具名从**三张** side-table 组合"却列了 4 条(L2/L3/L4/L5)——按文件数说 3、按表数是 4,
易被读成"只有 3 张"(很可能就是 W6 数错的来源之一)。改为"**4 张 side-table(分布在 3 个文件)**"
并前置"漏登记 = 缺省放行"的警告 + 指向 §0 清单。

## 待 dwgx 定(未擅自动)

1. **`CLAUDE.md` 命令段的 `./mvnw -pl core test` 是错的**(同 §4 陷阱),应改 `-pl board,core test`。
   CLAUDE.md 是锁定骨架,**改需 3 次确认** → 只提出,未动。
2. **治理清单漏收 `tool-annotation-convention.md`**:它是 `status: authoritative` 却不在
   governance-log README 的"要记"清单里,而该 README 自己写了"新增权威治理文档要加进清单,
   否则以后漏记"。建议补收。本次对它的改动因此**未强制留痕**(但已记在本篇)。
3. **既存安全缺口**:`act_set`/`act_cancel` 是 L3 HIGH 写入者却无任何 L4 特权(PHASE A 遗留,
   与 W6 同类)。修它要定"该挂哪个特权"=安全语义决策,按红线必须 dwgx 定。

## 相关

- 被改文档:`../../reference/cc-workflow-guide.md` · `../../reference/tool-annotation-convention.md` ·
  `../../architecture/02-CAPABILITIES.md`
- 证据来源:本会话 W1-W6 提交栈(`0e62955`…`98fad76`)+ critical 修复 `5f00c84`
