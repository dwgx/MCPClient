# 已从源码证实的三条帧序列事实

主线在并发研究期间自行核实的,不依赖任何 agent 报告。全部给出 file:line。

---

## 1. drawScreen 运行时 framebufferMc 是绑定状态 —— 方案方向正确

这是整个 FBO 方案最承重的一条。链路:

| 位置 | 动作 |
|---|---|
| `Minecraft.java:1127` | `this.framebufferMc.bindFramebuffer(true)` |
| `Minecraft.java:1141` | `this.entityRenderer.updateCameraAndRender(...)` |
| `EntityRenderer.java:1191` | `this.mc.currentScreen.drawScreen(k1, l1, partialTicks)` |
| `Minecraft.java:1164` | `this.framebufferMc.unbindFramebuffer()` |
| `Minecraft.java:1167` | `framebufferRender(displayWidth, displayHeight)` — 把 FBO 内容画到屏幕 |

`drawScreen` 位于 1141 的调用栈内,因此落在 1127 与 1164 之间。
`updateCameraAndRender` 内部唯一的 framebuffer 操作是 `EntityRenderer.java` 相对第 92 行的
`this.mc.getFramebuffer().bindFramebuffer(true)` —— **绑的是同一个 framebufferMc**,不是切到别处。

**结论**:`QmlGuiScreen.currentFramebufferId()` 取 `mc.getFramebuffer().framebufferObject` 是对的,
而且我们画进去的内容会随后被 `framebufferRender` 一起呈现。

## 2. framebufferMc 没有 stencil attachment —— 我们传 0 是正确的

`Framebuffer.createFramebuffer`(`Framebuffer.java:89`)在 `useDepth` 为真时
只挂一个 renderbuffer,且是 `GL_DEPTH_ATTACHMENT`(`Framebuffer.java:120`)。
全文件搜不到任何 `STENCIL`。构造处 `Minecraft.java:490` 传 `useDepth=true`。

**结论**:`McpFboSurfaceBackend` 里 `BackendRenderTarget.makeGL(w, h, 0, 0, fb, ...)` 的
stencil=0 由源码证实,不再是从 backup 注释继承的说法。**对默认 framebuffer 用 8 能成功,
对 framebufferMc 必须是 0** —— 这正是那个陷阱。

## 3. drawScreen 之前 MC 已经建立了 GUI 状态 —— 我们必须精确还原它

`EntityRenderer.java` 在调用 drawScreen 前依次做:

```java
GlStateManager.viewport(0, 0, this.mc.displayWidth, this.mc.displayHeight);
GlStateManager.matrixMode(5889);   // GL_PROJECTION
GlStateManager.loadIdentity();
GlStateManager.matrixMode(5888);   // GL_MODELVIEW
GlStateManager.loadIdentity();
this.setupOverlayRendering();      // GUI 正交投影
...
GlStateManager.clear(256);         // GL_DEPTH_BUFFER_BIT,只清深度不清颜色
```

三点推论:

1. **视口是全 framebuffer 尺寸**(displayWidth/Height,即设备像素),与我们的
   `frameTarget(widthPx, heightPx, ...)` 一致。
2. **投影是 setupOverlayRendering 建立的 GUI 正交投影**,不是世界投影。
   Skija 会改投影,所以必须还原 —— 而 `glPushAttrib(GL_ALL_ATTRIB_BITS)`
   **不保存矩阵**,只保存 `GL_VIEWPORT_BIT` 之类的状态位。我们另外压了
   PROJECTION/MODELVIEW 矩阵栈,方向是对的。
3. `clear(256)` 只清深度,所以世界画面在 drawScreen 期间仍在颜色缓冲里 ——
   这确认了我们"不能 clear 颜色,否则擦掉游戏"的判断。

**仍未证实**:`glPushAttrib`/`glPopAttrib` 在 Apple 的 GL 2.1-on-Metal 上是否可靠。
这是 `apple-gl-limits` 那条研究线的核心问题,也是 GlStateGuard 的立足点。
