---
name: diagnose
description: 诊断 bug 的闭环——复现→最小化→假设→插桩→修→回归测试。对应铁规矩"先写会失败的测试再修";复现不了的 bug 多半不是 bug,别发无法验证的修复。
---

# Diagnose — bug 诊断闭环

灵感来自 mattpocock/skills 的 diagnosing-bugs。本项目已有强纪律:见记忆 feedback-verify-before-fixing。

## 什么时候用

- 报告了一个 bug / 异常 / 测试红。
- 移植类问题(LWJGL2→3、Netty、驱动差异)——本项目 KI-1/KI-4 就是这么定位的。

## 闭环(按序,别跳)

1. **复现**:先让 bug 稳定重现。**复现不了 = 多半不是 bug**,别猜着改。
2. **最小化**:砍到能触发的最小场景,排除无关变量(KI-1 用 profileMask=2 证伪 core-profile)。
3. **假设**:写下"我认为根因是 X",可证伪。
4. **插桩**:加日志 / 探针 / 读运行时状态验证假设。本项目有 `dev_probe` MCP 工具(见 STATUS,live 可用)
   读 GL 状态/线程栈/JVM 内部而不改代码;新探针加进 DevProbeTools 走 R3(只读)。内核代码别用 System.out,用现有 logger。
5. **修**:改动最小、只碰根因。
6. **回归测试**:写一个**在旧代码上会失败**的测试锁住它(铁律#2 的非空转要求)。

## 铁规矩

- 同一路子失败两次就停,别递增打补丁——退一步找根因,换根本不同的路(见 CLAUDE.md 全局规矩)。
- 移植 bug 的正式归宿是 compat 补丁系统,**不改 client 源码**(client 纯 vanilla)。
- 修完 STATUS.md 的测试计数要同步更新。
- 真机/live 才能验的(GPU、渲染)别假装 headless 测过——诚实标注验证边界。
