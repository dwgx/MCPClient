// 三语术语表——手工翻译的统一口径，落地内容与文档翻译都据此，避免同词多译。
// zh / en / ja
//
// reference monitor        引用监视器 / reference monitor / リファレンスモニタ
// integrity level          完整性 / integrity level / 完全性レベル
// privilege                特权 / privilege / 特権
// capability (SID)          能力 / capability / ケイパビリティ
// object handle            对象句柄 / object handle / オブジェクトハンドル
// hot-swap / redefine      热重定义 / hot-swap (redefine) / ホットスワップ（再定義）
// seam                     接缝 / seam / シーム
// fail-safe / fail-closed  失效即拒 / fail-safe (fail-closed) / フェイルセーフ（拒否側）
// bytecode injection       字节码注入 / bytecode injection / バイトコード注入
// breakpoint / single-step 断点/单步 / breakpoint / single-step / ブレークポイント / シングルステップ
// substrate                基底 / substrate / 基盤
// spine (module)           骨架 / spine / 骨格
// opt-in                   opt-in（保留原词，各语言一致）

export const LOCALES = ["zh", "en", "ja"] as const;
export type Loc = (typeof LOCALES)[number];

export type L10n = Record<Loc, string>;
