import type { DocPage } from "./types";

export const timeline: DocPage = {
  slug: "timeline",
  title: { zh: "时间轴脊柱", en: "Timeline Spine", ja: "タイムライン脊柱" },
  description: {
    zh: "GameClock / Timeline / PacketJournal——包与事件观测的底座。",
    en: "GameClock / Timeline / PacketJournal — the foundation for packet and event observation.",
    ja: "GameClock / Timeline / PacketJournal——パケットとイベント観測の土台。",
  },
  blocks: [
    {
      type: "p",
      text: {
        zh: "时间轴脊柱（Timeline Spine）为所有事件与封包观测提供统一的时间坐标。核心是一个唯一的 GameClock 与挂在其上的 tick 事件流。",
        en: "The Timeline Spine provides a unified time coordinate for all event and packet observation. At its core is a single GameClock and the tick event stream hung off it.",
        ja: "タイムライン脊柱（Timeline Spine）は、すべてのイベントとパケット観測に統一的な時間座標を与えます。中核となるのは唯一の GameClock と、それにぶら下がる tick イベントストリームです。",
      },
    },
    { type: "h2", text: { zh: "GameClock 与 TickEvent", en: "GameClock and TickEvent", ja: "GameClock と TickEvent" }, id: "clock" },
    {
      type: "p",
      text: {
        zh: "系统只有一个 GameClock（删除了各处的私有 counter）。TickEvent 同源挂 phase；GameEvent 基类构造时附带 tickId，所有子类零改动即获得时间戳。这保证了每个事件都能定位到确切的 tick。",
        en: "There is exactly one GameClock in the system (the scattered private counters were removed). TickEvent attaches phases from the same source; the GameEvent base class carries a tickId at construction, so every subclass gets a timestamp with zero changes. This guarantees every event can be pinned to an exact tick.",
        ja: "システムには GameClock が 1 つだけ存在します（各所にあった private な counter は削除済み）。TickEvent は同一ソースから phase を付与し、GameEvent 基底クラスは構築時に tickId を持つため、すべてのサブクラスが無改修でタイムスタンプを得ます。これにより、あらゆるイベントを正確な tick に紐づけられます。",
      },
    },
    { type: "code", lang: "text", code: "clock_now      -> 当前 tick 计数（R3）\ntimeline_tail  -> 近期事件时间线（R3）" },
    { type: "h2", text: { zh: "PacketJournal", en: "PacketJournal", ja: "PacketJournal" }, id: "journal" },
    {
      type: "p",
      text: {
        zh: "PacketJournal 是包专用的 tick 戳环形缓冲，只订阅 Seam 的包事件（而非 GameEvent 基类）。它记录 dir/class/byteLen/summary + 单调递增的 seq（作为 packet_get 的寻址）。整个设计 reference-free——不持有活 Packet 引用。",
        en: "PacketJournal is a packet-specific, tick-stamped ring buffer that subscribes only to the Seam's packet events (not the GameEvent base class). It records dir/class/byteLen/summary plus a monotonically increasing seq (the address for packet_get). The whole design is reference-free — it holds no live Packet references.",
        ja: "PacketJournal はパケット専用の、tick スタンプ付きリングバッファで、Seam のパケットイベントのみを購読します（GameEvent 基底クラスは購読しません）。dir/class/byteLen/summary に加えて単調増加する seq（packet_get のアドレス）を記録します。設計全体が reference-free で、稼働中の Packet 参照を一切保持しません。",
      },
    },
    { type: "h2", text: { zh: "结构化封包投影", en: "Structured packet projection", ja: "構造化パケットプロジェクション" }, id: "projection" },
    {
      type: "p",
      text: {
        zh: "PacketSummarizer.project() SPI 把活 Packet 投影成有序的、JSON-ready 的键值视图（PacketView）。tap 时就地算好、存进 journal，成为单一真相。packet_view 工具只吐有 typed 投影的包，支持按 dir/class/sinceSeq/limit 过滤。",
        en: "The PacketSummarizer.project() SPI projects a live Packet into an ordered, JSON-ready key-value view (PacketView). It is computed on the spot at tap time and stored into the journal, becoming the single source of truth. The packet_view tool emits only packets that have a typed projection, and supports filtering by dir/class/sinceSeq/limit.",
        ja: "PacketSummarizer.project() の SPI は、稼働中の Packet を順序付きで JSON 対応のキー・バリュービュー（PacketView）へ投影します。tap の時点でその場で計算して journal に格納し、唯一の真実源となります。packet_view ツールは typed な投影を持つパケットのみを出力し、dir/class/sinceSeq/limit によるフィルタに対応します。",
      },
    },
    {
      type: "callout",
      tone: "info",
      text: {
        zh: "全部约 105 个顶层 Packet 类通过分层混合方式暴露：A 层约 46 个 typed 摘要器（world/movement/entity/inventory/session）、B 层 fx/move、C 层用 simpleName 兜底覆盖全部。",
        en: "All ~105 top-level Packet classes are exposed via a layered hybrid: tier A has ~46 typed summarizers (world/movement/entity/inventory/session), tier B covers fx/move, and tier C falls back to simpleName to cover everything.",
        ja: "約 105 個のトップレベル Packet クラスはすべて、階層ハイブリッドで公開されます。A 層は約 46 個の typed サマライザ（world/movement/entity/inventory/session）、B 層は fx/move、C 層は simpleName でフォールバックしてすべてを網羅します。",
      },
    },
  ],
};
