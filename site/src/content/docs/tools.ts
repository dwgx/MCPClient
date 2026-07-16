import type { DocPage } from "./types";
import type { L10n } from "@/content/glossary";

// 工具名 / Ring / 门控名等技术标识符三语相同，用 same() 减少重复
const same = (s: string): L10n => ({ zh: s, en: s, ja: s });

export const tools: DocPage = {
  slug: "tools",
  title: { zh: "MCP 工具参考", en: "MCP Tools Reference", ja: "MCP ツールリファレンス" },
  description: {
    zh: "按类别分组的核心 MCP 工具，含 Ring 权限等级。",
    en: "The core MCP tools grouped by category, with their Ring privilege levels.",
    ja: "カテゴリ別に整理した主要な MCP ツールと、その Ring 特権レベル。",
  },
  blocks: [
    {
      type: "p",
      text: {
        zh: "以下是 MCPClient 暴露给 LLM 的主要工具，按类别分组。Ring 权威来源是 Ring.BUILTIN_RINGS，数值越低越危险：R-1 HYPERVISOR / R0 KERNEL / R1 SYSTEM / R2 OBSERVE / R3 USER。注意 Ring 只是 7 层约束之一，工具受完整 7 层门控。",
        en: "Below are the main tools MCPClient exposes to the LLM, grouped by category. The Ring authority is Ring.BUILTIN_RINGS; the lower the number, the more dangerous: R-1 HYPERVISOR / R0 KERNEL / R1 SYSTEM / R2 OBSERVE / R3 USER. Note that Ring is only one of the 7 constraints — every tool is gated by all 7 layers.",
        ja: "以下は MCPClient が LLM に公開する主要ツールを、カテゴリ別にまとめたものです。Ring の権威は Ring.BUILTIN_RINGS で、数値が小さいほど危険です：R-1 HYPERVISOR / R0 KERNEL / R1 SYSTEM / R2 OBSERVE / R3 USER。Ring は 7 層制約の 1 つにすぎず、各ツールは 7 層すべてで門を通ることに注意してください。",
      },
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "内建工具总数、测试数等易变数字以仓库内 STATUS.md 为单一真相。本页列出的是稳定的工具面与其权限口径。",
        en: "Volatile numbers such as the total count of built-in tools or tests are governed by STATUS.md in the repo as the single source of truth. This page lists the stable tool surface and its privilege framing.",
        ja: "組み込みツール総数やテスト数などの変動する数値は、リポジトリ内の STATUS.md を唯一の真実源とします。本ページは安定したツール面とその権限の枠組みを示します。",
      },
    },
    { type: "h2", text: { zh: "观测 / 感知", en: "Observation / perception", ja: "観測 / 知覚" }, id: "observe" },
    {
      type: "table",
      head: [
        { zh: "工具", en: "Tool", ja: "ツール" },
        { zh: "Ring", en: "Ring", ja: "Ring" },
        { zh: "作用", en: "Role", ja: "役割" },
      ],
      rows: [
        [same("read_player_state"), same("R2"), { zh: "读活玩家状态", en: "Read the live player state", ja: "稼働中のプレイヤー状態を読む" }],
        [same("scan_surroundings"), same("R2"), { zh: "扫描周围方块/实体", en: "Scan nearby blocks/entities", ja: "周囲のブロック／エンティティをスキャン" }],
        [same("capture_screen"), same("R2"), { zh: "屏幕捕获成 PNG ImageContent", en: "Capture the screen as PNG ImageContent", ja: "画面を PNG ImageContent としてキャプチャ" }],
        [same("dev_probe"), same("R2"), { zh: "连接/世界在场 + GL 上下文诊断", en: "Connection/world presence + GL context diagnostics", ja: "接続／ワールド在席 + GL コンテキスト診断" }],
        [same("world_view"), same("R2"), { zh: "世界语义视图（full|diff）", en: "Semantic world view (full|diff)", ja: "意味的なワールドビュー（full|diff）" }],
        [same("recent_packets / packet_view"), same("R3"), { zh: "近期封包日志 / 结构化封包投影", en: "Recent packet log / structured packet projection", ja: "直近のパケットログ／構造化パケットプロジェクション" }],
        [same("clock_now / timeline_tail"), same("R3"), { zh: "tick 时钟 / 事件时间线", en: "Tick clock / event timeline", ja: "tick クロック／イベントタイムライン" }],
      ],
    },
    { type: "h2", text: { zh: "操控 / 外向效果", en: "Acting / outward effects", ja: "操作 / 外向きの効果" }, id: "act" },
    {
      type: "table",
      head: [
        { zh: "工具", en: "Tool", ja: "ツール" },
        { zh: "Ring", en: "Ring", ja: "Ring" },
        { zh: "门控", en: "Gating", ja: "ゲート" },
      ],
      rows: [
        [same("send_chat"), same("R1"), same("SE_NET_RAW + CAP_NETWORK_SEND")],
        [same("send_raw_packet"), same("R1"), { zh: "SE_NET_RAW（所有出站发包自动过 board veto）", en: "SE_NET_RAW (all outbound sends automatically pass board veto)", ja: "SE_NET_RAW（すべての送信は自動的に board veto を通る）" }],
        [same("act_set / act_cancel"), same("R1"), { zh: "SE_WORLD_WRITE + CAP_WORLD_WRITE（流式真操控）", en: "SE_WORLD_WRITE + CAP_WORLD_WRITE (streaming real control)", ja: "SE_WORLD_WRITE + CAP_WORLD_WRITE（ストリーミングの実操作）" }],
      ],
    },
    { type: "h2", text: { zh: "结构化 GUI（C9）", en: "Structured GUI (C9)", ja: "構造化 GUI（C9）" }, id: "gui" },
    {
      type: "table",
      head: [
        { zh: "工具", en: "Tool", ja: "ツール" },
        { zh: "Ring", en: "Ring", ja: "Ring" },
        { zh: "作用", en: "Role", ja: "役割" },
      ],
      rows: [
        [same("gui_snapshot"), same("R2"), { zh: "把屏幕元素抽成 {id,label,bounds,clickPoint} + epoch/fingerprint", en: "Extract screen elements into {id,label,bounds,clickPoint} + epoch/fingerprint", ja: "画面要素を {id,label,bounds,clickPoint} + epoch/fingerprint に抽出" }],
        [same("gui_snapshot_image"), same("R2"), { zh: "set-of-marks 标注截图（+ SE_SCREEN_CAP）", en: "Set-of-marks annotated screenshot (+ SE_SCREEN_CAP)", ja: "set-of-marks 注釈付きスクリーンショット（+ SE_SCREEN_CAP）" }],
        [same("gui_click_element / gui_type_text / gui_press_key"), same("R1"), { zh: "按元素 id 驱动真实 handler，带 stale 防护", en: "Drive the real handlers by element id, with stale-click protection", ja: "要素 id で本物のハンドラを駆動し、stale クリックを防護" }],
      ],
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "GUI 工具的关键设计：LLM 永不发送像素坐标，只发 gui_snapshot 给出的元素 id。每次操作携带快照 epoch+fingerprint，屏幕变了就拒绝陈旧误点。",
        en: "The key GUI design: the LLM never sends pixel coordinates, only the element ids given by gui_snapshot. Each action carries the snapshot epoch+fingerprint, so a changed screen rejects the stale mis-click.",
        ja: "GUI ツールの要となる設計：LLM はピクセル座標を決して送らず、gui_snapshot が返す要素 id のみを送ります。各操作はスナップショットの epoch+fingerprint を伴うため、画面が変われば古い誤クリックを拒否します。",
      },
    },
    { type: "h2", text: { zh: "调试（C6，全部 R-1）", en: "Debugging (C6, all R-1)", ja: "デバッグ（C6、すべて R-1）" }, id: "debug" },
    {
      type: "p",
      text: {
        zh: "调试工具需要 SE_DEBUG_CONTROL + CAP_DEBUG_CONTROL，完整性 SYSTEM。",
        en: "Debug tools require SE_DEBUG_CONTROL + CAP_DEBUG_CONTROL, at SYSTEM integrity.",
        ja: "デバッグツールは SE_DEBUG_CONTROL + CAP_DEBUG_CONTROL を要求し、完全性は SYSTEM です。",
      },
    },
    {
      type: "list",
      items: [
        { zh: "debug_suspend_thread — 按线程名暂停/恢复", en: "debug_suspend_thread — suspend/resume by thread name", ja: "debug_suspend_thread — スレッド名で停止／再開" },
        { zh: "debug_pop_frame — 弹出已暂停线程栈顶帧", en: "debug_pop_frame — pop the top frame of a suspended thread", ja: "debug_pop_frame — 停止中スレッドの最上位フレームをポップ" },
        { zh: "debug_force_return — 强制方法早返回（void/int/object）", en: "debug_force_return — force an early method return (void/int/object)", ja: "debug_force_return — メソッドを強制的に早期リターン（void/int/object）" },
        { zh: "debug_set_breakpoint / debug_clear_breakpoint — 下/清断点（拒绝 protected 核心类）", en: "debug_set_breakpoint / debug_clear_breakpoint — set/clear breakpoints (refuses protected core classes)", ja: "debug_set_breakpoint / debug_clear_breakpoint — ブレークポイントの設定／解除（protected なコアクラスは拒否）" },
        { zh: "debug_single_step — 开/关某线程单步事件", en: "debug_single_step — enable/disable single-step events on a thread", ja: "debug_single_step — 指定スレッドのシングルステップイベントを有効／無効化" },
        { zh: "debug_read_local / debug_write_local — 读/写（仅 int）已暂停帧局部变量", en: "debug_read_local / debug_write_local — read/write (int only) locals in a suspended frame", ja: "debug_read_local / debug_write_local — 停止フレームのローカル変数を読み書き（int のみ）" },
        { zh: "debug_watch_field — 开/关字段修改监视", en: "debug_watch_field — enable/disable field-modification watches", ja: "debug_watch_field — フィールド変更監視を有効／無効化" },
      ],
    },
    { type: "h2", text: { zh: "热改 / 深控（R-1，最危险）", en: "Hot-swap / deep control (R-1, most dangerous)", ja: "ホットスワップ / 深部制御（R-1、最も危険）" }, id: "mutate" },
    {
      type: "table",
      head: [
        { zh: "工具", en: "Tool", ja: "ツール" },
        { zh: "Ring", en: "Ring", ja: "Ring" },
        { zh: "能力", en: "Capability", ja: "能力" },
      ],
      rows: [
        [same("eval_java"), same("R-1"), { zh: "在游戏 JVM 内跑任意 Java", en: "Run arbitrary Java inside the game JVM", ja: "ゲーム JVM 内で任意の Java を実行" }],
        [same("redefine_class"), same("R-1"), { zh: "C4 热重定义活类", en: "C4 hot-redefine a live class", ja: "C4 稼働中クラスのホット再定義" }],
        [same("install_hook / uninstall_hook"), same("R-1 / R0"), { zh: "C3 运行时可逆 hook", en: "C3 reversible runtime hooks", ja: "C3 実行時の可逆 hook" }],
        [same("write_field / invoke_method / open_module"), same("R-1"), { zh: "C5 深访问", en: "C5 deep access", ja: "C5 深部アクセス" }],
        [same("eval_ephemeral"), same("R-1"), { zh: "C7 一次性隐藏类执行", en: "C7 one-shot hidden-class execution", ja: "C7 使い捨て hidden class の実行" }],
      ],
    },
    { type: "h2", text: { zh: "自省 / 元管理", en: "Introspection / meta-management", ja: "イントロスペクション / メタ管理" }, id: "meta" },
    {
      type: "list",
      items: [
        { zh: "list_classes / describe_class / find_method / list_hooks（R3，只读自省）", en: "list_classes / describe_class / find_method / list_hooks (R3, read-only introspection)", ja: "list_classes / describe_class / find_method / list_hooks（R3、読み取り専用のイントロスペクション）" },
        { zh: "list_compat_patches（R3，列出启动期已加载的签名补丁）", en: "list_compat_patches (R3, list the signed patches loaded at startup)", ja: "list_compat_patches（R3、起動時に読み込まれた署名付きパッチを一覧）" },
        { zh: "create_tool / rollback_tool（R0，AI 运行时自造/回滚工具）", en: "create_tool / rollback_tool (R0, the AI builds/rolls back tools at runtime)", ja: "create_tool / rollback_tool（R0、AI が実行時にツールを自作／ロールバック）" },
        { zh: "enable_privilege / disable_privilege / grant_capability / revoke_capability（R0）", en: "enable_privilege / disable_privilege / grant_capability / revoke_capability (R0)", ja: "enable_privilege / disable_privilege / grant_capability / revoke_capability（R0）" },
        { zh: "drop_privilege / restore_privilege / list_permissions（R3）", en: "drop_privilege / restore_privilege / list_permissions (R3)", ja: "drop_privilege / restore_privilege / list_permissions（R3）" },
      ],
    },
  ],
};
