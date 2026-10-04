/**
 * "Knöpfchen", the lobby narrator villager (DESIGN.md §12): a small apprentice with a big head, a big nose,
 * one raised brow, a mustard robe that is far too big, notebook and quill. Cartoon style with a thick ink outline,
 * lit from the top left. The quill scribbles as the menu's single calm sign of life (§16); it stands still with
 * reduced motion.
 */
export function Knoepfchen({ className, title = "Knöpfchen" }: { className?: string; title?: string }) {
  const ink = "var(--ink, #2a160a)"
  return (
    <svg viewBox="0 0 220 262" className={className} role="img" aria-label={title} xmlns="http://www.w3.org/2000/svg">
      <title>{title}</title>
      <style>{`
        @keyframes knoepfchen-scribble { 0%,100% { transform: rotate(0deg) } 30% { transform: rotate(-7deg) } 60% { transform: rotate(4deg) } }
        .knoepfchen-quill { transform-box: fill-box; transform-origin: 10% 95%; animation: knoepfchen-scribble 1.6s ease-out infinite; }
        @media (prefers-reduced-motion: reduce) { .knoepfchen-quill { animation: none; } }
      `}</style>
      <g strokeLinejoin="round" strokeLinecap="round">
        {/* ground shadow */}
        <ellipse cx="110" cy="250" rx="78" ry="9" fill={ink} opacity="0.25" />

        {/* shoes peeking out under the robe */}
        <ellipse cx="86" cy="246" rx="16" ry="8" fill="#5a3418" stroke={ink} strokeWidth="5" />
        <ellipse cx="134" cy="246" rx="16" ry="8" fill="#5a3418" stroke={ink} strokeWidth="5" />

        {/* robe — mustard and much too big: wide hem, long sleeves */}
        <path d="M64 146 C56 186 46 214 34 240 C84 252 136 252 186 240 C174 214 164 186 156 146 Z"
          fill="#e0a420" stroke={ink} strokeWidth="5" />
        {/* robe lit from the top left, shaded bottom right */}
        <path d="M70 156 C64 186 56 212 46 234" fill="none" stroke="#ffcf4a" strokeWidth="7" />
        <path d="M150 160 C156 190 164 214 174 236 C160 240 150 241 140 242 C142 214 146 186 150 160 Z" fill="#c87a10" />
        <path d="M58 236 C92 245 128 245 162 236" fill="none" stroke="#c87a10" strokeWidth="6" />
        {/* button strip — the little buttons he is named after */}
        <path d="M110 160 L110 236" stroke="#c87a10" strokeWidth="10" />
        {[176, 200, 224].map((y) => (
          <g key={y}>
            <circle cx="110" cy={y} r="6.5" fill="#ffcf4a" stroke={ink} strokeWidth="3.5" />
            <circle cx="108" cy={y - 2} r="1.8" fill="#fff0a8" />
          </g>
        ))}
        {/* collar */}
        <path d="M74 146 C90 160 130 160 146 146 L146 140 L74 140 Z" fill="#ffcf4a" stroke={ink} strokeWidth="5" />

        {/* left sleeve holding the notebook */}
        <path d="M66 152 C50 170 50 196 64 208 C76 214 92 210 96 200 L88 168 Z" fill="#e0a420" stroke={ink} strokeWidth="5" />
        {/* notebook: red cover, parchment pages */}
        <g transform="rotate(-8 100 192)">
          <rect x="70" y="170" width="58" height="44" rx="6" fill="#d8382e" stroke={ink} strokeWidth="5" />
          <rect x="76" y="166" width="50" height="40" rx="4" fill="#fffaf0" stroke={ink} strokeWidth="4" />
          <path d="M84 178 H118 M84 187 H114 M84 196 H108" stroke="#d8c08e" strokeWidth="3.5" />
          <path d="M84 178 H100" stroke={ink} strokeWidth="3" />
        </g>
        {/* left hand */}
        <circle cx="74" cy="204" r="9" fill="#e0b088" stroke={ink} strokeWidth="4.5" />

        {/* right sleeve raised with the quill */}
        <path d="M154 152 C172 164 178 186 170 202 C162 212 146 210 140 200 L146 168 Z" fill="#e0a420" stroke={ink} strokeWidth="5" />
        <path d="M160 166 C168 178 170 190 166 198" fill="none" stroke="#c87a10" strokeWidth="5" />
        <g className="knoepfchen-quill">
          <path d="M146 194 C156 168 176 138 202 116 C206 140 190 172 154 196 Z" fill="#fffaf0" stroke={ink} strokeWidth="4.5" />
          <path d="M150 192 C166 170 182 148 198 124" fill="none" stroke="#d8c08e" strokeWidth="3" />
          <path d="M146 194 L140 208" stroke={ink} strokeWidth="4.5" />
        </g>
        <circle cx="148" cy="198" r="9" fill="#e0b088" stroke={ink} strokeWidth="4.5" />

        {/* head — big */}
        <rect x="46" y="22" width="128" height="124" rx="36" fill="#e0b088" stroke={ink} strokeWidth="5" />
        {/* light from the top left, shadow bottom right */}
        <path d="M150 34 C164 44 168 60 168 80 L168 112 C168 128 158 140 142 140 L122 140 C146 130 156 112 156 86 C156 64 154 46 150 34 Z" fill="#c99068" />
        <ellipse cx="72" cy="44" rx="12" ry="6" fill="#f2cca4" transform="rotate(-25 72 44)" />
        {/* cheeks */}
        <ellipse cx="68" cy="116" rx="11" ry="7" fill="#ff6a8a" opacity="0.4" />
        <ellipse cx="152" cy="116" rx="11" ry="7" fill="#ff6a8a" opacity="0.4" />

        {/* eyes — big, irises set slightly inwards (friendly squint) */}
        <ellipse cx="84" cy="88" rx="16" ry="15" fill="#fffaf0" stroke={ink} strokeWidth="4.5" />
        <ellipse cx="136" cy="88" rx="16" ry="15" fill="#fffaf0" stroke={ink} strokeWidth="4.5" />
        <circle cx="90" cy="91" r="7.5" fill="#4ac85e" stroke={ink} strokeWidth="2.5" />
        <circle cx="130" cy="91" r="7.5" fill="#4ac85e" stroke={ink} strokeWidth="2.5" />
        <circle cx="91" cy="92" r="3.6" fill={ink} />
        <circle cx="129" cy="92" r="3.6" fill={ink} />
        <circle cx="87.5" cy="88" r="2" fill="#fffaf0" />
        <circle cx="126.5" cy="88" r="2" fill="#fffaf0" />

        {/* one continuous brow, raised and slightly tilted — curious */}
        <path d="M64 66 C80 52 100 58 110 62 C122 56 142 50 158 60" fill="none" stroke={ink} strokeWidth="12" />
        <path d="M64 66 C80 52 100 58 110 62 C122 56 142 50 158 60" fill="none" stroke="#5a3418" strokeWidth="5" />

        {/* mouth — a small questioning "o" next to the nose tip */}
        <ellipse cx="128" cy="138" rx="6" ry="5" fill="#5a160e" stroke={ink} strokeWidth="3.5" />

        {/* nose — the most important feature: big, long, hanging over the mouth */}
        <path d="M100 84 C98 104 92 128 94 142 C96 154 124 154 126 142 C128 128 122 104 120 84 Z"
          fill="#d49c72" stroke={ink} strokeWidth="5" />
        <path d="M104 96 C102 110 99 124 100 136" fill="none" stroke="#f2cca4" strokeWidth="5" />
        <path d="M118 120 C121 128 121 138 116 146" fill="none" stroke="#b07a50" strokeWidth="4" />

        {/* a floating question mark: he is never quite sure */}
        <text x="182" y="40" fontFamily="'Lilita One', sans-serif" fontSize="40" fill="#ffcf4a" stroke={ink}
          strokeWidth="6" paintOrder="stroke fill" transform="rotate(12 182 40)">?</text>
      </g>
    </svg>
  )
}
