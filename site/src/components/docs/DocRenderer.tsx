import type { DocBlock } from "@/content/docs/types";
import type { Loc } from "@/content/glossary";

const calloutStyle: Record<string, string> = {
  info: "border-l-diamond-500 bg-diamond-500/10",
  warn: "border-l-gold-400 bg-gold-400/10",
  danger: "border-l-danger-500 bg-danger-500/10",
};

export function DocRenderer({ blocks, loc }: { blocks: DocBlock[]; loc: Loc }) {
  return (
    <div className="space-y-6">
      {blocks.map((b, i) => {
        switch (b.type) {
          case "p":
            return (
              <p key={i} className="text-[0.95rem] leading-7 text-stone-300">
                {b.text[loc]}
              </p>
            );
          case "h2":
            return (
              <h2
                key={i}
                id={b.id}
                className="font-pixel scroll-mt-24 border-b-2 border-stone-700 pb-3 pt-6 text-lg text-white text-shadow-mc-sm"
              >
                {b.text[loc]}
              </h2>
            );
          case "h3":
            return (
              <h3
                key={i}
                id={b.id}
                className="scroll-mt-24 pt-4 text-base font-bold text-grass-300"
              >
                {b.text[loc]}
              </h3>
            );
          case "code":
            return (
              <pre
                key={i}
                className="overflow-x-auto border-2 border-black bg-stone-900 p-4 shadow-[inset_2px_2px_0_rgba(0,0,0,0.5)]"
              >
                <code className="font-mono text-[0.8rem] leading-6 text-grass-300 whitespace-pre">
                  {b.code}
                </code>
              </pre>
            );
          case "callout":
            return (
              <div
                key={i}
                className={`border-2 border-black border-l-8 p-4 ${calloutStyle[b.tone]}`}
              >
                <p className="text-sm leading-6 text-stone-200">{b.text[loc]}</p>
              </div>
            );
          case "list":
            return (
              <ul key={i} className="space-y-2">
                {b.items.map((it, j) => (
                  <li key={j} className="flex gap-3 text-[0.95rem] leading-7 text-stone-300">
                    <span className="mt-2.5 inline-block h-2 w-2 shrink-0 bg-grass-500" />
                    <span>{it[loc]}</span>
                  </li>
                ))}
              </ul>
            );
          case "table":
            return (
              <div key={i} className="overflow-x-auto border-2 border-black">
                <table className="w-full border-collapse text-sm">
                  <thead>
                    <tr className="bg-stone-700">
                      {b.head.map((h, j) => (
                        <th
                          key={j}
                          className="border border-black px-3 py-2 text-left font-pixel text-[0.6rem] text-white text-shadow-mc-sm"
                        >
                          {h[loc]}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {b.rows.map((row, j) => (
                      <tr key={j} className={j % 2 ? "bg-stone-800" : "bg-stone-600/40"}>
                        {row.map((cell, k) => (
                          <td
                            key={k}
                            className="border border-black px-3 py-2 align-top leading-6 text-stone-300"
                          >
                            {k === 0 ? (
                              <span className="font-mono text-grass-300">{cell[loc]}</span>
                            ) : (
                              cell[loc]
                            )}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          default:
            return null;
        }
      })}
    </div>
  );
}
