import type { DocPage } from "./types";

export const native: DocPage = {
  slug: "native",
  title: { zh: "原生 C6 与构建", en: "Native C6 & Build", ja: "ネイティブ C6 とビルド" },
  description: {
    zh: "JVMTI 调试器的原生实现、构建方式与许可洁净。",
    en: "The native implementation of the JVMTI debugger, how to build it, and license cleanliness.",
    ja: "JVMTI デバッガのネイティブ実装、ビルド方法、そしてライセンスのクリーンさ。",
  },
  blocks: [
    { type: "h2", text: { zh: "C6 原生 JVMTI agent", en: "The C6 native JVMTI agent", ja: "C6 ネイティブ JVMTI エージェント" }, id: "jvmti" },
    {
      type: "p",
      text: {
        zh: "C6 CONTROL-EXEC 的能力（暂停线程、PopFrame、ForceEarlyReturn、断点、单步、读写局部变量、字段监视）无法用纯 Java 实现，需要一个原生 JVMTI agent：core-jvmti.dll。",
        en: "The C6 CONTROL-EXEC capabilities (suspend threads, PopFrame, ForceEarlyReturn, breakpoints, single-step, read/write locals, field watches) cannot be done in pure Java — they need a native JVMTI agent: core-jvmti.dll.",
        ja: "C6 CONTROL-EXEC の能力（スレッド停止、PopFrame、ForceEarlyReturn、ブレークポイント、シングルステップ、ローカル変数の読み書き、フィールド監視）は純 Java では実現できず、ネイティブ JVMTI エージェント core-jvmti.dll を必要とします。",
      },
    },
    {
      type: "code",
      lang: "bash",
      code: "# 用 LLVM/clang 编译，无需 MSVC / Windows SDK\nwinget install LLVM.LLVM\ncore/src/main/native/core-jvmti/build-clang.sh\n# 或\nscripts/build-c6-local.bat",
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "用 clang 的 GNU driver（clang -I\"path\"）而非 clang-cl。DLL 通过 -agentpath 加载——onload-only 的 JVMTI 能力必须在启动时申请。编译产物被 gitignore，不进仓库。",
        en: "Use clang's GNU driver (clang -I\"path\"), not clang-cl. The DLL is loaded via -agentpath — onload-only JVMTI capabilities must be requested at startup. The build artifact is gitignored and never enters the repo.",
        ja: "clang-cl ではなく clang の GNU ドライバ（clang -I\"path\"）を使います。DLL は -agentpath で読み込みます。onload-only の JVMTI 能力は起動時に要求しなければなりません。ビルド成果物は gitignore され、リポジトリには入りません。",
      },
    },
    {
      type: "callout",
      tone: "warn",
      text: {
        zh: "DLL 缺失时，C6 工具优雅降级为干净的拒绝，绝不静默死掉——这符合「杜绝宣传了却是死的工具」的设计原则。",
        en: "When the DLL is missing, the C6 tools degrade gracefully into a clean refusal and never die silently — in line with the \"no advertised-but-dead tools\" design principle.",
        ja: "DLL が無い場合、C6 ツールはクリーンな拒否へ穏やかに縮退し、決して静かに死にません。これは「宣伝されているのに動かないツールを排す」という設計原則に沿ったものです。",
      },
    },
    { type: "h2", text: { zh: "构建命令", en: "Build commands", ja: "ビルドコマンド" }, id: "build" },
    {
      type: "code",
      lang: "bash",
      code: "./mvnw -pl board,core test          # 跑 core 测试（用 board+core 避免陈旧 jar）\n./mvnw -q -pl core -am package -DskipTests   # 打 fat agent jar\nscripts/run-mcp.bat                  # 启动游戏 + MCP Core（Windows）",
    },
    { type: "h2", text: { zh: "许可洁净", en: "License cleanliness", ja: "ライセンスのクリーンさ" }, id: "license" },
    {
      type: "p",
      text: {
        zh: "产品 jar 里绝无 GPL。HotSwapAgent（GPLv2）永不进产品 jar；DCEVM 是 JBR 内建的外部运行时，不入 jar。打包库都是宽松许可：ByteBuddy（Apache-2.0）、MCP SDK（MIT）、Jackson、Reactor。",
        en: "There is absolutely no GPL in the product jar. HotSwapAgent (GPLv2) never enters the product jar; DCEVM is an external runtime built into the JBR and does not go into the jar. The bundled libraries are all permissively licensed: ByteBuddy (Apache-2.0), the MCP SDK (MIT), Jackson, and Reactor.",
        ja: "製品 jar には GPL は一切含まれません。HotSwapAgent（GPLv2）は製品 jar に決して入りません。DCEVM は JBR に内蔵された外部ランタイムで、jar には入りません。同梱ライブラリはすべて寛容なライセンスです：ByteBuddy（Apache-2.0）、MCP SDK（MIT）、Jackson、Reactor。",
      },
    },
    { type: "h2", text: { zh: "热加载依赖", en: "Hot-reload dependencies", ja: "ホットリロードの依存関係" }, id: "hotswap" },
    {
      type: "p",
      text: {
        zh: "热重定义（加字段/加方法）依赖 JBR 内建的 DCEVM。需要三个 JVM 参数：-XX:+AllowEnhancedClassRedefinition、-XX:+EnableDynamicAgentLoading、-Djdk.attach.allowAttachSelf=true。",
        en: "Hot-redefine (adding fields/methods) relies on the DCEVM built into the JBR. It needs three JVM flags: -XX:+AllowEnhancedClassRedefinition, -XX:+EnableDynamicAgentLoading, and -Djdk.attach.allowAttachSelf=true.",
        ja: "ホット再定義（フィールド・メソッドの追加）は JBR 内蔵の DCEVM に依存します。3 つの JVM フラグが必要です：-XX:+AllowEnhancedClassRedefinition、-XX:+EnableDynamicAgentLoading、-Djdk.attach.allowAttachSelf=true。",
      },
    },
  ],
};
