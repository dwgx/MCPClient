# macOS 已知问题

本文只记录 macOS 特有的、**尚未解决**的问题。已解决的看提交历史;移植计划看
`mcp-core-port-plan.md`。

---

## MK-1 — vanilla 文本框在 macOS 上没有剪贴板

**状态:** OPEN(有意为之的取舍,不是 bug)
**影响:** `GuiScreen` 派生的所有 vanilla 输入框(多人服务器地址、世界名、命令方块、告示牌…)
在 macOS 上复制粘贴无效果。读到空字符串,写入静默丢弃。游戏其余部分不受影响。

### 为什么这样

`GuiScreen.getClipboardString()` / `setClipboardString()` 走 `java.awt.Toolkit`
(vanilla 1.8.9 就是这么写的)。macOS 上 `-XstartOnFirstThread` 已经把主线程交给 GLFW,
此时 AWT 再去初始化 AppKit,**JVM 就再也退不出去** —— 复制本身成功,之后退出游戏卡死,
只能强杀。

三种组合的实测行为(macOS 26.5 / M2 / Temurin 25):

| 配置 | 剪贴板 | 退出 |
|---|---|---|
| vanilla,无 flag | 读到 25 字符 | **卡死** |
| vanilla + `-Djava.awt.headless=true` | 读到 0 字符 | 干净退出 |
| 改写 `GuiScreen` 走 GLFW | 正常 | 干净退出 |

`run.sh` 采用第二种:headless 让 `Toolkit` 直接抛异常,而 vanilla 的
`getClipboardString()`/`setClipboardString()` 本来就各自包着 `catch (Exception)`,
异常被原样吞掉 —— **一行客户端源码都不用改**。

第三种能同时拿到剪贴板和干净退出,但要改 `client/` 的 vanilla 源码。基线冻结,不走这条。

`ImageIO`/`BufferedImage`(截图、纹理加载)在 headless 下正常,窗口图标在 macOS 上本来就是
no-op(`Display.setIcon` 有 `isMac()` 短路),所以这个 flag 不带来其它退化。

### 正确的解法(等 qml4j 落地后自然消失)

剪贴板应当由**补丁层**接管,而不是改基线:

- `lwjgl2-shim` 的 `Sys.getClipboard()` 已经实现(走 `glfwGetClipboardString`)。
  它是 LWJGL2 本来就有的 API,补进垫片属于垫片本职,不算越界。
- 缺的是"让 `GuiScreen` 用它"这一步。该由 `core` 的 ByteBuddy 织入 / `pg` 的构建期字节码
  改写 / `board` 芯片来做,把 vanilla 源码留在原处。
- 而 dwm 引入 qml4j 之后,文本输入本身会走 qml4j 的输入栈,它自带剪贴板实现
  (且 qml4j 上游已修过 macOS 剪贴板安全性,见 `fix/clipboard-safety`),
  vanilla 输入框会逐步退出主路径。

结论:**不要为此改 `client/`**。要么等补丁层,要么等 qml4j。

### 相关位置

- `client/src/main/java/net/minecraft/client/gui/GuiScreen.java:118-152`(vanilla,勿动)
- `lwjgl2-shim/src/main/java/org/lwjgl/Sys.java`(`getClipboard`/`setClipboard` 已就位)
- `run.sh`(Darwin 分支设 flag)
