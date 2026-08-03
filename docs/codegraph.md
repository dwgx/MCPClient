# codegraph(本机版)—— 读码入口

CLAUDE.md 铁律⑥要求"读代码必须先走 codegraph"。**dwgx 那套 codegraph(tree-sitter → SQLite、
带 MCP 工具 `codegraph_explore` / `codegraph_node`)在 Windows 工作站上,这台 Mac 上没有。**

本机有的是**另一套同名工具**:`tools/codegraph/`,三个 Python 脚本,从 **javap 反汇编**抽调用图。

> **不要把两者当成同一个东西。** 索引格式、CLI、能力都不兼容。上一个上下文自己写了它,
> 却没在文档里说清楚是替代品 —— 那次遗漏本身就是这份文档存在的原因。

---

## 0. 三十秒上手

```bash
export JAVA_HOME=~/.jdks/jdk-25.0.3+9/Contents/Home     # javap 要 JDK 25
./mvnw -B -ntp -DskipTests package                      # 索引读 target/classes,先编译
python3 tools/codegraph/build_codegraph.py              # 建索引 -> .codegraph/(gitignored)

python3 tools/codegraph/cg.py class NavController
python3 tools/codegraph/cg.py callers 'ActActuator#position'
python3 tools/codegraph/cg.py path 'Minecraft#runGameLoop' 'NetworkManager#sendPacket'
```

**索引陈旧就重建**(约 25 秒,全量;没有增量模式)。改过代码而没重建 = 铁律⑥意义上的骨架漂移。

---

## 1. 五个查询

| 命令 | 回答 |
|---|---|
| `class <正则>` | 类名匹配,带 `extends` / `implements` |
| `methods <类名子串>` | 该类声明的全部方法 |
| `callers '<Class#method>'` | **谁调用它**(反向边) |
| `calls '<Class#method>'` | 它调用谁(正向边) |
| `subs <全限定类名>` | 直接子类 / 实现者(**要全限定名**,不是子串) |
| `path '<起点>' '<终点>'` | 两点间最短调用链(BFS) |

`callers` / `calls` / `path` 的参数按 **`Class#method` 子串**匹配,所以 `#drawScreen`
命中每个重写,`GuiScreen#` 命中该类全部方法。

---

## 2. 覆盖范围

本轮(2026-08-03)把 `CLASS_ROOTS` 从"只有 client + shim"扩到**全部 reactor 模块**:

```
core  board  dwm  client  lwjgl2-shim  pg/{pg-api,pg-engine,pg-maven-plugin}
```

**扩之前 `core` 一个类都不在索引里** —— 而 core 就是这个项目的本体,内核侧的活全在那儿。
现在:**2993 个类 / 119,563 条唯一边**(扩之前 2534 / 104,092)。

只索引 `target/classes`,**不含 `target/test-classes`**。所以 `SecurityKernelTest`
这类默认包里的测试查不到,找测试用 Grep。

---

## 3. 它看不见什么(**先读这条再下结论**)

字节码静态调用边 —— 边界是真的,不是谦辞:

1. **反射 / 动态注册的边不存在。** 工具注册、hook 安装、`MethodHandle` 这些路径断在调用点。
   `NavController` 显示"零调用者"时要先想这个(那次是真的只有 `MoveApplier#applyNav`,
   但形状一样的假阴性会出现)。
2. **虚调用记的是静态接收者类型。** `EntityLivingBase#moveEntityWithHeading` 调
   `moveFlying`,javap 把 owner 记成 `EntityLivingBase`,**不是**声明它的 `Entity`。
   所以 `callers 'Entity#moveFlying'` 返回"no node matching",而方法确实存在 ——
   查不到时**换成子类名或只用 `#方法名` 再试一次**,别当成"没人调用"。
3. **lambda 是独立节点。** 显示为 `Foo#lambda$bar$0`,不会归到 `Foo#bar` 名下。
4. **字段访问也是边**(`kind: "field"`),和调用混在一起。
5. **行号没有。** 拿到符号后要行号还得 Read/Grep。

---

## 4. `validate_docs.py`

把 `docs/mc189/*.md` 里"挂钩点"表的方法签名逐条对字节码解析,有不解析的就退出 1(可挂 CI)。
**`docs/mc189/` 在 `docs/mc189-source-map` 分支上,本分支没有**,所以在本分支跑它输出
`0/0`,是空转而不是通过。它在那条分支上的最后成绩:`944/947 (99.7%)`,0 条编造。

---

## 5. 什么时候还是得 Grep

- 找测试(不索引 test-classes)
- 要行号
- 追反射/动态注册的边(§3①)
- 字符串常量、注解、配置

其余情况先走 codegraph:它读的是**编译后真实解析的调用点**,比 grep 名字可靠 ——
尤其在 client 这种反编译命名里。
