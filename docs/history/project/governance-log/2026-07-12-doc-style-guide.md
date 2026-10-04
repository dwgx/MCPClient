---
doc: governance-2026-07-12-doc-style-guide
title: 治理变更 — 文档写作规范 + doc_lint 加固(加粗盲区 + 可读性 advisory)
layer: archive
status: archived
updated: 2026-07-12
parent: README.md
read_if: 你想追溯"为什么建了 doc-style-guide、doc_lint 为什么加了剥强调符和 [4] advisory"。
---

# 治理变更 2026-07-12 — 文档写作规范 + doc_lint 加固

## 改了什么
- **新建 `docs/reference/doc-style-guide.md`**(authoritative):md 写作规范,8 条速查规矩
  (结论先行 / 事实进表 / 一个数字一个家 / 就地更新 / 加粗是路标 / 不重复只链接 /
  引代码带 file:line / 诚实标状态)+ before-after 实例 + "什么该留散文"边界 + 与 doc_lint 分工。
- **扩 `tools/doc_lint.py`**:
  - 修真 bug:[2] 陈旧数字检查匹配前先 `strip_md_emphasis`(剥 `**`/`` ` ``)——此前
    `**178**` 这类给数字加粗的写法把数字和限定词隔开、骗过正则,让陈旧数字蒙混过关(实证)。
  - 新增 [4] 可读性 advisory:>400 字且非代码坐标密集的散文行提示考虑拆表/结论先行;
    **不计入退出码**(软提示),`--no-advisory` 关。豁免表格/引用/标题/file:line 密集行。
- **STATUS.md 排版**:测试数(一行 mega-sentence)→ 四行表;提交栈(箭头长链)→ 表;
  删一行重复的 LiveIT 说明;`updated` 07-11→07-12。纯排版,零事实改动。
- **接进路由**:`docs/README.md` reference 小节 + `_templates/README.md` 抬头
  (写正文前读 style-guide)+ 本 governance-log 的"要记清单"加 doc-style-guide。

## 时间 / 谁
2026-07-12;本会话(codegraph-mandate 之后)。

## dwgx 为什么授权这么改
dwgx:"你自己全部看一遍……你喜欢怎么读可改成你喜欢的……你还可以优化这个以后 AI 写入 md 文档不乱写 ultrathink"。
两层意图:①把现有 md 排版优化到"AI 读得顺";②**立标准 + 让它部分自执行**,治本地防止以后 AI 乱写。
所以不止改现有文档(治标),更建 style-guide(标准)+ 扩 doc_lint(机械自执行)。

## 思维链(为什么这么设计而不是别的)
- **标准与机械分家**:判断类规矩(结论先行、事实/散文之分)靠 style-guide + review;
  机械可查的(陈旧数、超长行)交 doc_lint。不把判断硬塞进正则(会误报),不把机械留给人(会漏)。
- **advisory 而非硬拦**:超长行不总是错(file:line 枚举、不变量列表长是合理的)。
  硬拦会逼人把好散文切碎。所以软提示 + 保守阈值(400)+ 豁免代码密集行——宁少报不吵人。
  第一版阈值 300 报了 37 条(多是合法技术行),实测后收紧到 400 + 豁免 `.java:`/多反引号行 → 0 噪音。
- **加粗 bug 是真修不是洁癖**:`**178**` 骗过 lint 是本会话亲历的检测盲区,修它 = 堵一个
  让陈旧数字漏网的洞,不是风格问题。
- **known-issues 不转表**:它的调查叙事是逻辑链,转表会断推理——style-guide 明文把这类列为
  "必须留散文",避免下一个 AI 矫枉过正。
- **未碰 CLAUDE.md**:给铁律加"写文档读 style-guide"的指针属于改定死骨架,需 3 次确认。
  只在此 flag:若要把 style-guide 提到铁律级,需 dwgx 明确 3 次确认。

## 确认轨迹
不涉及 CLAUDE.md(未改)。新建/改的都是被索引治理文档,按 governance-log 要求留本记录。

## 验证
`doc_lint.py` 四项:[1] 死链 0(修了 style-guide 里字面双方括号链接触发的误报)、
[2] 陈旧数 0(剥强调符后现有文档仍干净)、[3] 地图一致 0、[4] advisory 0 处、退出码 0。
加粗 bug 修复用临时 probe 文档实证:一个加粗的陈旧 core 测试数现能被抓到(修复前漏)。未动代码,无需编译。
