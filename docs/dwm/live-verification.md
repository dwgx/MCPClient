# dwm 真机验证:为什么必须有,以及怎么自动化

headless 测试证明逻辑,**真机证明它真的出现在屏幕上**。这两件事在 dwm 上差得很远 ——
2026-07-27 进游戏跑第一次,抓到三个 bug,**headless 断言原理上一个都抓不到**。

自动化入口:`scripts/live-dwm-probe.py`(先 `./scripts/run-mcp.sh` 起客户端)。

---

## 0. 真机抓到的三个 bug(记录下来,别再犯)

| # | 症状 | 根因 | 为什么 headless 测不到 |
|---|---|---|---|
| 1 | 开界面瞬间 SIGSEGV | Skija 留下 `ARRAY_BUFFER` 绑定;MC 用**客户端顶点数组**,`glVertexPointer` 传的 Java 堆指针在有绑定时被当成缓冲区**偏移**解引用 | 原生崩溃,不是 Java 异常。裸 GLFW 窗口不画世界,所以没有 `glDrawArrays` 去踩它 |
| 2 | JVM 直接 abort | 我修 #1 时顺手还原 VAO,但 `glBindVertexArray` 是 GL 3.0,Apple 的 2.1 兼容 profile 没有该入口 → LWJGL `jni_FatalError` | `jni_FatalError` 越过所有 Java handler,任何 try/catch 都拦不住 |
| 3 | 界面**完全不可见**,而所有状态都报健康 | `compositeLayer` 只把 `drawImage` 排进队列,从不 flush。`present()` 才 flush,而 qml4j 在 `renderFrame` **内部**调它 —— 那在 blit **之前**,且空闲帧根本不跑 | `isOpen=true`、`inert=false`、`lastError=null`、`fboId` 正确。**没有任何状态字段能暴露它**;读离屏层也不行(层里内容是对的),只有读**目标 framebuffer** 的像素才行 |

第 3 条是最重要的教训:**「所有字段都正常」和「用户能看见」是两回事。**

---

## 1. 为什么不能用 `gui_click_element`

内核已经有一套 GUI 自动化工具(`gui_snapshot` / `gui_click_element` / `gui_type_text`),
但它们**驱动不了 dwm**。原因具体:

`GuiSnapshotService` 反射读的是 vanilla 的 `GuiScreen.buttonList`(`:129`),
而 QML 场景**从不往那个 list 里放东西** —— dwm 的控件是 qml4j 的 Item 树,不是 `GuiButton`。
所以 snapshot 看不到任何 dwm 控件,`gui_click_element` 也就无从下手。

**探针因此走 dwm 自己的 `UiInput` SPI**(`pointerDown`/`pointerUp`/`pointerMove`/`key`)。
这不是权宜之计,反而更好:那**正是游戏自己走的那条路**(`QmlGuiScreen.handleMouseInput` /
`keyTyped` 调的就是它),所以探针测的是生产代码,不是测试专用的旁路。

---

## 2. 探针断言什么

17 条,全部在真实游戏帧内:

- 进世界 → `DwmEntry` 构造并显示 `QmlGuiScreen` → surface 健康(`isOpen`/`inert`/`lastError`)
- **窗口在 MC 的 framebuffer 里真的有像素** —— `glReadPixels` 读目标,不经 Skia
  (经 Skia 就等于问那个正在丢帧的队列"你还好吗")
- 点击三个导航行 → 每次断言 `currentPage` 真的变成对应页面
- 往聚焦的输入框打 `abc` → **读回来必须等于 `abc`**
- resize 到 1280x720 再回来 → surface 存活、无错误(历史"resize 后世界变黑"那条路)
- 关闭界面 → 客户端**仍然存活**

每一步之后都检查进程还活着 —— 因为这里最要紧的失败不是断言失败,而是**硬崩溃**:
三个 bug 里两个直接杀掉 JVM,而死进程和挂住的调用从外面看一模一样。

退出码沿用 `smoke-live-gl.sh` 的约定:`0` PASS · `1` FAIL · `2` TIMEOUT · `3` SETUP。

---

## 3. 探针自己的一个 bug(值得记)

第一版把导航行的 y 坐标**硬编码**成 152/192/232,结果每次点击都落在**下面一行**,
探针报了两条"导航失败" —— 而那是探针自己的错,UI 是对的。

实测真实几何是 y=64/104/144/184(高 40,中心 84/124/164/204)。

改成 `row_centre()` 从活场景读绝对坐标(沿 parent 链累加)。
**布局拥有的几何必须问布局要**,否则测试harness 会自己造出 bug 来。

---

## 4. 已知未覆盖

诚实列出来,别当成已验证:

- **KI-11 热键**没验过 —— 它带占位符签名,内核原话 `SKIP unverified patch MCP-KI0011 —
  signature not trusted`,所以打开界面目前走 `eval_java`。签名 ceremony 之后才能验 RSHIFT。
- **控件的交互态**(hover/press 的视觉变化、拖 slider、勾 checkbox)只在 headless
  画廊测试里按像素验过,真机只验了"渲染出来了"。
- **长时间运行**没验 —— 探针跑完就关。帧率影响、显存增长、几十分钟后的稳定性都未知。
- **Windows 侧完全没验** —— 这批修复全部只在 macOS / Apple GL 2.1 上跑过。
  尤其 buffer 绑定那条,不同驱动的行为可能不同。
