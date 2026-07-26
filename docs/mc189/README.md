# docs/mc189 — MC 1.8.9 源码结构分析

51 篇分包文档，覆盖 `client/` + `lwjgl2-shim/` 全部 1640 个文件 / 283,184 行，
每个文件恰好被分配到一篇文档（分桶清单见 `.analysis/buckets/`）。

## 每篇的固定结构

`定位` · `类清单`（每个文件一行）· `核心类详解` · `时序与生命周期` ·
**`挂钩点（Hook Points）`** · `数据与协议` · `不变量与陷阱` · `交叉引用` · `覆盖声明`

`挂钩点` 是给功能层 / UI 层用的：方法签名 + `文件:行号` + 调用时机 + 线程归属 + 风险。

## 生成方式与可信度（重要）

- 生成模型 Fable 5，两条并发工作流，每个 agent 拿到显式文件清单并逐文件 Read。
- 要求所有方法签名、字段名、常量逐字来自源码，结论标 `文件:行号`。
- **对抗式复核阶段未跑完**（因成本中止）。已复核的文档中错误已就地修正，
  无法验证的断言标了 `(未验证)`；未复核的文档只经过一次生成。
- 每篇末尾的 `覆盖声明` 记录了该 agent 自报的精读/浏览范围，可能偏乐观。

**但签名层面已经全量机器校验过。** `tools/codegraph/validate_docs.py` 把 51 篇里全部 947 条
挂钩点签名逐条对编译后的字节码解析：

```
944/947 hook-point signatures resolve against the bytecode (99.7%)
```

**零条编造方法。** 剩下 3 条都在 `mc-world-gen-feature.md`，经人工 grep 确认是
`TileEntityMobSpawner#getSpawnerBaseLogic` / `World#spawnEntityInWorld` /
`WeightedRandomChestContent#generateChestContents` 这类跨对象调用——文档引的是**调用点**而非声明处，
对挂钩点表来说是更有用的引用，不算错。

所以待人工核实的不是"方法存不存在"，而是字节码答不了的部分：**调用时机、线程归属、字段语义、协议字段含义**。

## 配套工具

`tools/codegraph/` 从编译后的字节码抽调用图（2534 类 / 104,092 条边），
用来核对文档里的调用关系——它是确定性的，与文档冲突时以它为准：

```bash
./mvnw -q clean package -DskipTests          # 先编译出 target/classes
python3 tools/codegraph/build_codegraph.py   # 生成 .codegraph/
python3 tools/codegraph/cg.py callers 'GuiScreen#drawScreen'
python3 tools/codegraph/cg.py path 'Minecraft#runGameLoop' 'NetworkManager#sendPacket'
python3 tools/codegraph/validate_docs.py     # 校验本目录所有挂钩点签名(失败返回 1,可挂 CI)
```

`.codegraph/` 是 gitignored 的生成物,换机器后先跑一次 `build_codegraph.py` 重建。

## 未完成

- 汇总索引、跨包挂钩点总表、完整封包目录（原计划的第二阶段，未执行）。
