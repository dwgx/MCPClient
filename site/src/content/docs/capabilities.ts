import type { DocPage } from "./types";
import { CAPABILITIES } from "@/content/kernel";

export const capabilities: DocPage = {
  slug: "capabilities",
  title: { zh: "能力包 C1-C9", en: "Capability Packs C1–C9", ja: "能力パック C1–C9" },
  description: {
    zh: "从自省到调试器级执行控制的完整能力谱。",
    en: "The full capability spectrum, from introspection to debugger-grade execution control.",
    ja: "イントロスペクションからデバッガ級の実行制御まで、能力の全域。",
  },
  blocks: [
    {
      type: "p",
      text: {
        zh: "能力包是 MCPClient 暴露给 AI 的能力分类。C1-C8 是黄金意图口径，C9 GUI-INTERACT 是扩展出的结构化 GUI 操作面。每个能力包对应内核里一组包与工具。",
        en: "Capability packs categorize what MCPClient exposes to the AI. C1–C8 are the canonical set; C9 GUI-INTERACT is the structured GUI surface added on top. Each pack maps to a group of packages and tools inside the kernel.",
        ja: "能力パックは、MCPClient が AI に公開する能力の分類です。C1–C8 が正統な区分で、C9 GUI-INTERACT はその上に加えた構造化 GUI の操作面です。各パックはカーネル内のパッケージ群とツール群に対応します。",
      },
    },
    {
      type: "table",
      head: [
        { zh: "编号", en: "ID", ja: "番号" },
        { zh: "能力", en: "Capability", ja: "能力" },
        { zh: "package", en: "Package", ja: "パッケージ" },
        { zh: "作用", en: "Role", ja: "役割" },
      ],
      rows: CAPABILITIES.map((c) => [
        { zh: c.id, en: c.id, ja: c.id },
        c.name,
        { zh: c.pkg, en: c.pkg, ja: c.pkg },
        c.duty,
      ]),
    },
    { type: "h2", text: { zh: "为什么 C6 是分水岭", en: "Why C6 is the watershed", ja: "なぜ C6 が分水嶺なのか" }, id: "c6" },
    {
      type: "p",
      text: {
        zh: "C1-C5 大多可以纯 Java 实现（反射、ByteBuddy、DCEVM 热重定义）。C6 CONTROL-EXEC 则需要一个用 LLVM/clang 编出的原生 JVMTI agent，才能做到暂停线程、PopFrame、ForceEarlyReturn、下断点、单步。它已在运行中的真实 MC 客户端里实机验证。",
        en: "C1–C5 can mostly be done in pure Java (reflection, ByteBuddy, DCEVM hot-redefine). C6 CONTROL-EXEC, however, needs a native JVMTI agent built with LLVM/clang to suspend threads, PopFrame, ForceEarlyReturn, set breakpoints, and single-step. It has been verified on a real, running MC client.",
        ja: "C1–C5 の多くは純 Java（リフレクション、ByteBuddy、DCEVM のホット再定義）で実現できます。一方 C6 CONTROL-EXEC は、スレッド停止・PopFrame・ForceEarlyReturn・ブレークポイント・シングルステップを行うために、LLVM/clang でビルドしたネイティブ JVMTI エージェントを必要とします。これは稼働中の実 MC クライアントで実機検証済みです。",
      },
    },
    { type: "h2", text: { zh: "C7 合成的安全性", en: "The safety of C7 synthesis", ja: "C7 合成の安全性" }, id: "c7" },
    {
      type: "p",
      text: {
        zh: "C7 SYNTHESIZE 编译 AI 提供的 Java 为隐藏类（hidden class）并执行：用完即可 GC、对自省不可见、不可被 redefine。这是 eval_ephemeral 的底座——既给 AI 最大灵活性，又保证生成代码不会污染可观测面。",
        en: "C7 SYNTHESIZE compiles AI-supplied Java into a hidden class and runs it: GC-able after use, invisible to introspection, not redefinable. It is the foundation of eval_ephemeral — giving the AI maximum flexibility while keeping generated code from polluting the observable surface.",
        ja: "C7 SYNTHESIZE は、AI が渡した Java を hidden class にコンパイルして実行します。使用後は GC 可能で、イントロスペクションからは見えず、再定義もできません。これは eval_ephemeral の土台であり、AI に最大の柔軟性を与えつつ、生成コードが観測面を汚さないようにします。",
      },
    },
    {
      type: "callout",
      tone: "danger",
      text: {
        zh: "AI 生成的代码危险度等同 eval_java。因此凡 create_tool/eval 造出的工具，无条件盖上最强门：R-1 HYPERVISOR + SYSTEM 完整性 + SE_RUN_GENERATED + CAP_TOOL_CREATE。它不查按名侧表——一次降权即可把所有生成工具全锁死。",
        en: "AI-generated code is as dangerous as eval_java. So any tool built via create_tool/eval gets the strongest gate unconditionally: R-1 HYPERVISOR + SYSTEM integrity + SE_RUN_GENERATED + CAP_TOOL_CREATE. It does not consult a by-name side table — a single privilege drop locks down every generated tool at once.",
        ja: "AI が生成したコードは eval_java と同等に危険です。したがって create_tool/eval で作られたツールには無条件で最強の門を被せます：R-1 HYPERVISOR + SYSTEM 完全性 + SE_RUN_GENERATED + CAP_TOOL_CREATE。名前ベースのサイドテーブルは参照せず、権限を一度下げるだけで生成ツールをすべて封鎖できます。",
      },
    },
    { type: "h2", text: { zh: "C8 接缝的可逆性", en: "The reversibility of C8 seams", ja: "C8 シームの可逆性" }, id: "c8" },
    {
      type: "p",
      text: {
        zh: "C8 SEAM 提供三种运行时接缝：Netty pipeline MITM（只发只读 ByteBuf dup 保持 wire 冻结）、GLFW 输入回调链、tick 注入（retransform Minecraft.runTick 每 tick 发 TickEvent）。三者都可逆装卸——装上是观察/干预，卸下恢复原状。",
        en: "C8 SEAM offers three runtime seams: Netty pipeline MITM (emitting only a read-only ByteBuf dup, keeping the wire frozen), the GLFW input callback chain, and tick injection (retransforming Minecraft.runTick to emit a TickEvent each tick). All three install reversibly — installed they observe/intervene; removed they restore the original state.",
        ja: "C8 SEAM は 3 種類の実行時シームを提供します：Netty パイプラインの MITM（読み取り専用の ByteBuf dup のみを流し、wire を凍結したまま保つ）、GLFW 入力コールバックチェーン、tick 注入（Minecraft.runTick を retransform し、毎 tick で TickEvent を発火）。3 つとも可逆的に着脱でき、取り付ければ観察・介入し、外せば元の状態に戻ります。",
      },
    },
  ],
};
