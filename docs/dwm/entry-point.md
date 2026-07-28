# dwm 的入口:KI-11 为什么走补丁层

菜单此前**没有任何方式打开** —— `dwm` 有一个真正的 `GuiScreen`,而 `DwmEntry` 全仓零调用者。
本文记录为什么这件事必须走 compat 补丁,以及为什么最初想的两条路都是错的。

---

## 0. 结论

**给冻结的 `client/` 加一个按键入口,只能走 compat 补丁层。** 这不是绕路,
恰恰是该层存在的本义 —— 它是 NT AppCompat 的类比:**拦截我们不拥有的代码里的调用,而不是去改它**。

注入点:`Minecraft.dispatchKeypresses()`(`Minecraft.java:3115`)。

---

## 1. 先否掉两条错的路

### 甲:tick 轮询(曾在 `backup/overlay-guiscreen` 上存在,已删)

那份实现每个 `TickEvent`(20Hz)反射轮询 256 个 scancode 的 `Keyboard.isKeyDown`。
它**有一个独立于铁律的技术缺陷**:

> `isKeyDown` 采样的是**电平**(此刻是否按下),而按键是**事件**。
> 一次按下若在两个 tick 之间完成(< 50ms),轮询根本看不到它 —— **丢键**。

也就是说,即便不考虑"不许碰 client"的约束,这条路本身就是错的形状。

### 乙:board 的 `PinMatrix` / `KeySignal`

board 有现成的 keybind 注册表(`PinMatrix.route(KeySignal)`)。但 grep 全仓:
**没有任何代码发布 `KeySignal`**,只有定义处命中。所以这条路要先补一个发布者,
而那个发布者本身还是得从某处拿到按键事件 —— 问题只是被推后了一层,没有被解决。

---

## 2. 为什么 `dispatchKeypresses` 是对的注入点

| 条件 | 是否满足 | 依据 |
|---|---|---|
| 每个按键事件恰好被调用一次 | ✓ | `Minecraft.java:1926`,在 `while (Keyboard.next())` 循环内 |
| 事件流是权威的(不丢键) | ✓ | vanilla 自己就在这里读事件,我们搭同一趟车 |
| 注入是栈中性的 | ✓ | `public void ()V` —— 无参无返回,注入一条无操作数 `INVOKESTATIC` |
| 有现成先例 | ✓ | KI-1 就是往 vanilla 注入一个到 core 帮助类的 `INVOKESTATIC` |

注入在**方法入口**而非出口,理由具体:vanilla 自己的方法体里会调 `displayGuiScreen`
(推流开关的确认对话框),在那之后跑意味着我们对"当前是哪个 screen"的判断
取决于 vanilla 刚刚把它改成了什么。

---

## 3. 帧结构纪律(照搬 KI-1/KI-4,不是新发明)

注入序列只有一条指令,不推不弹、不加局部变量、不产生新跳转目标,
所以**原有的 stack map frames 保持有效**,可以用 `ClassWriter(0)` 原样写回。

这条不是洁癖。用 `COMPUTE_FRAMES` 会让 ASM 调 `getCommonSuperClass`,
而那会**在 `ClassFileTransformer` 内部触发类加载** —— 引导期极易撞出
`ClassCircularityError`,或者更阴的:某些类被静默跳过、根本没被 transform。
KI-1 的注释已经把这条纪律写明,KI-11 照搬。

---

## 4. 安全姿态:它**现在 arm 了**(2026-07-28 更新)

引擎只有一条 arming 规则,无 bypass:

> A patch arms if and ONLY if `signer.verify(manifest)` passes.
> In-code registration confers NO trust.

**这一节此前写的是"它不 arm,这是对的"** —— 那时 `KERNEL_SIGNATURE` 是占位符,私钥不在机器上,
打开界面只能走 `eval_java`。现在密钥仪式做完了,KI-11 携带真实签名并**确实 arm**,
按右 Shift 即可开界面。真机自报:

```
[MCP Compat] engine built: 3 patch(es) armed, 0 skipped.
```

(此前是 `2 armed, 1 skipped`。)

**仪式是两层的,这一点是踩出来的**:换 kernel 密钥**不够**。
补丁验签的锚点不是直接读 `kernel-ed25519.pub`,而是 `Compat.defaultTrustAnchors()`
经 `RootTrust` → `TufTrust` 从 `root-metadata.json` **派生** —— 那份文档声明哪个 targets 密钥
有权签补丁,并且它自己由 **root 密钥**签署。只换下层的结果是三个补丁全部
`signature not trusted`,而诊断信息仅此一句。

完整链条与工具见 `key-ceremony.md`。

### 测试必须测两个方向

只测"签名不受信任就不 arm"是**空转的**:一个因为完全无关的原因(状态不对、目标是受保护类、
L0 不匹配、runtime 条件永不成立)而永久失效的补丁,也会通过那条断言。

`Ki11SigningContractTest` 因此断言两个方向,并且**它的第一半在本轮被迫改写** ——
原来的前提是"占位符签名不验证通过",而仪式一跑那个前提就消失了,测试也确实红了。
现在用**空锚点**表达同一条规则(无可信密钥即不 arm),与 shipped 常量的内容无关。

第二半改成断言 **shipped KI-11 在真实派生链下必须 arm**。那条正是能抓住"仪式只做一半"的守卫:
kernel 密钥换了、root 文档还授权旧的,症状就是静默不 arm。
另外仍断言:L0 canary 哈希与源码里钉住的常量一致;以及**换掉 transform 后即使签名有效也会被 L0 拒绝**。

---

## 5. 默认不改变任何行为

注入的调用是无条件的,但 `DwmHotkey.onKeyEvent()` 在**没有绑定 scancode 时立即返回**:

- `-Dmcp.dwm.hotkey=<scancode>` 显式绑定;
- 或 `-Dmcp.core.overlay=true` 取默认键(RSHIFT `0x36`,与旧 launcher 一致)。

所以 arm KI-11 本身不改变任何可观测行为;dwm 缺席时代价是每次按键一次 no-op 调用。
一个拼错的 flag 值会**禁用**热键并报错,而不是静默退回默认 —— 启动参数的手误应该可见。

热键是 toggle:已经开着就关掉;若当前是**别的** screen(菜单、聊天框)则不动它,
因为替换掉会丢弃玩家正在做的事。
