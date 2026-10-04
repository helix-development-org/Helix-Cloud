import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"
import { api, type ActionResult } from "@/lib/api"
import lilitaOne from "@fontsource/lilita-one/files/lilita-one-latin-400-normal.woff2?inline"
import nunito500 from "@fontsource/nunito/files/nunito-latin-500-normal.woff2?inline"
import nunito700 from "@fontsource/nunito/files/nunito-latin-700-normal.woff2?inline"

/** Bridge runtime injected into every addon panel iframe, defined before the
 *  panel's own scripts so `Helix.action/ready/toast` are always available. */
const RUNTIME = `
  window.Helix = {
    _seq: 0, _pending: {},
    action: function(name) {
      var args = Array.prototype.slice.call(arguments, 1).map(String);
      var id = ++this._seq, self = this;
      return new Promise(function(res) {
        self._pending[id] = res;
        parent.postMessage({ type: "helix:action", id: id, name: name, args: args }, "*");
      });
    },
    toast: function(message) { parent.postMessage({ type: "helix:toast", message: String(message) }, "*"); },
    ready: function(fn) {
      if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn);
      else setTimeout(fn, 0);
    }
  };
  window.addEventListener("message", function(e) {
    var d = e.data || {};
    if (d.type === "helix:result" && window.Helix._pending[d.id]) {
      window.Helix._pending[d.id](d.result); delete window.Helix._pending[d.id];
    }
  });
`

interface Palette {
  scheme: "dark" | "light"
  bg: string; surface: string; surface2: string; surfaceHi: string; surfaceLo: string; zebra: string
  line: string; outline: string; ink: string; ink2: string; ink3: string
  accent: string; accentInk: string; accentHi: string; accentLo: string; accentShadow: string
  field: string; fieldTop: string; tableHead: string
}

/*
 * Addon panels render in a sandboxed iframe, so they cannot see the dashboard's CSS variables.
 * The two palettes mirror the design tokens (DESIGN.md §3, §17): wood = dark (default), parchment = light.
 */
const DARK: Palette = {
  scheme: "dark",
  bg: "#2a1a10", surface: "#3b2617", surface2: "#4a3020", surfaceHi: "#5a3418", surfaceLo: "#2a1a10", zebra: "#422b1b",
  line: "rgba(241,226,194,0.16)", outline: "#1a0e06", ink: "#f1e2c2", ink2: "#e6d2a8", ink3: "#c9b89a",
  accent: "#ffcf4a", accentInk: "#2a160a", accentHi: "#fff0a8", accentLo: "#e0a420", accentShadow: "none",
  field: "#2a1a10", fieldTop: "rgba(90,52,24,0.7)", tableHead: "#1a0e06",
}
const LIGHT: Palette = {
  scheme: "light",
  bg: "#f1e2c2", surface: "#fff8e6", surface2: "#e6d2a8", surfaceHi: "#fffaf0", surfaceLo: "#d8c08e", zebra: "#f6ead0",
  line: "rgba(42,22,10,0.18)", outline: "#2a160a", ink: "#2a160a", ink2: "#4a2e1c", ink3: "#5a3418",
  accent: "#d8382e", accentInk: "#ffffff", accentHi: "#ff7a6a", accentLo: "#9a2018", accentShadow: "0 1px 0 #2a160a",
  field: "#fffaf0", fieldTop: "rgba(216,192,142,0.7)", tableHead: "#2a160a",
}

/** Bundled display/text fonts as data URLs: the sandboxed iframe has an opaque origin and cannot load them itself. */
const PANEL_FONTS = `
  @font-face{font-family:"Lilita One";font-weight:400;font-display:swap;src:url(${lilitaOne}) format("woff2")}
  @font-face{font-family:"Nunito";font-weight:500;font-display:swap;src:url(${nunito500}) format("woff2")}
  @font-face{font-family:"Nunito";font-weight:700;font-display:swap;src:url(${nunito700}) format("woff2")}`

/** Stylesheet implementing the panel component classes (card/btn/table/badge/…) with the network design
 *  tokens (ink outlines, top highlight + bottom edge, hard shadows, Lilita/Nunito), so addon panels match. */
function panelCss(p: Palette): string {
  return `${PANEL_FONTS}
  :root{color-scheme:${p.scheme};
    --bg:${p.bg};--surface:${p.surface};--surface-2:${p.surface2};--line:${p.line};--outline:${p.outline};
    --ink:${p.ink};--ink-2:${p.ink2};--ink-3:${p.ink3};--accent:${p.accent};
    --good:#4ac85e;--warn:#ffb43a;--bad:#ff5a4a;--info:#5ab4ff;--gold:#ffcf4a;
    --radius:20px;--radius-sm:12px;
    --display:"Lilita One","Nunito",ui-rounded,system-ui,sans-serif;
    --mono:"JetBrains Mono",ui-monospace,SFMono-Regular,Menlo,monospace;}
  *{box-sizing:border-box}
  body{margin:0;padding:20px 20px 28px;background:var(--bg);color:var(--ink);
    font-family:"Nunito",ui-rounded,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;font-weight:500;font-size:15px;line-height:1.55}
  ::selection{background:#ffcf4a;color:#2a160a}
  h2{font-family:var(--display);font-weight:400;font-size:18px;letter-spacing:.02em;margin:0}
  label{display:block;font-size:12px;color:var(--ink-3);margin-bottom:5px;font-weight:700}
  a{color:var(--ink);text-decoration:underline;text-decoration-color:#e0a420;text-decoration-thickness:2px;text-underline-offset:3px}
  .card{background:var(--surface);border:3px solid var(--outline);border-radius:var(--radius);margin-bottom:18px;overflow:hidden;
    box-shadow:inset 0 4px 0 ${p.surfaceHi},inset 0 -6px 0 ${p.surfaceLo},0 6px 0 var(--outline)}
  .card-head{display:flex;align-items:center;gap:10px;padding:16px 16px 12px;border-bottom:1px solid var(--line)}
  .card-body{padding:16px 16px 20px}
  .sub{font-size:12px;color:var(--ink-3)}
  .right{margin-left:auto;display:flex;gap:8px}
  .row{display:flex;flex-wrap:wrap;align-items:center;gap:10px}
  .grid{display:grid;gap:14px}
  .grid.c2{grid-template-columns:repeat(auto-fit,minmax(240px,1fr))}
  .field{display:flex;flex-direction:column}
  .wide{grid-column:1/-1}
  .muted{color:var(--ink-3)}
  .mono{font-family:var(--mono);font-size:12.5px}
  .empty{padding:26px 12px;text-align:center;color:var(--ink-3);font-size:14px;font-weight:700}
  input,textarea,select{width:100%;background:${p.field};color:var(--ink);border:1px solid var(--outline);
    border-radius:var(--radius-sm);padding:9px 11px 8px;font:inherit;outline:none;box-shadow:inset 0 3px 0 ${p.fieldTop};
    transition:box-shadow .15s ease-out}
  input:focus,textarea:focus,select:focus{box-shadow:inset 0 3px 0 ${p.fieldTop},0 0 0 3px #ffcf4a}
  input[type=checkbox],input[type=radio]{accent-color:#4ac85e;box-shadow:none}
  textarea{resize:vertical;font-family:var(--mono);font-size:12.5px}
  .btn{--hi:${p.accentHi};--lo:${p.accentLo};display:inline-flex;align-items:center;justify-content:center;gap:6px;cursor:pointer;white-space:nowrap;
    border:3px solid var(--outline);border-radius:13px;padding:8px 15px 11px;font-family:var(--display);font-weight:400;font-size:15px;
    letter-spacing:.02em;line-height:1;background:var(--accent);color:${p.accentInk};text-shadow:${p.accentShadow};
    box-shadow:inset 0 4px 0 var(--hi),inset 0 -6px 0 var(--lo),0 4px 0 var(--outline);
    transition:transform .12s ease-out,box-shadow .12s ease-out,filter .16s ease-out}
  .btn:hover{filter:brightness(1.06)}
  .btn:active{transform:translateY(2px);box-shadow:inset 0 4px 0 var(--hi),inset 0 -3px 0 var(--lo),0 2px 0 var(--outline)}
  .btn:disabled{opacity:.55;cursor:default;transform:none}
  .btn.sm{padding:5px 10px 8px;font-size:13px;box-shadow:inset 0 3px 0 var(--hi),inset 0 -4px 0 var(--lo),0 3px 0 var(--outline)}
  .btn.sm:active{box-shadow:inset 0 3px 0 var(--hi),inset 0 -2px 0 var(--lo),0 1px 0 var(--outline)}
  .btn.subtle,.btn.neutral{--hi:#fffaf0;--lo:#d8c08e;background:#f1e2c2;color:#2a160a;text-shadow:none}
  .btn.danger{--hi:#ff9a8a;--lo:#b8261a;background:var(--bad);color:#fff;text-shadow:0 1px 0 #2a160a}
  .btn.good{--hi:#8cf09a;--lo:#2a8a3e;background:var(--good);color:#fff;text-shadow:0 1px 0 #2a160a}
  table{width:100%;border-collapse:collapse;font-size:14px}
  thead tr{background:${p.tableHead}}
  th{text-align:left;padding:9px 12px;color:#ffcf4a;font-family:var(--display);font-weight:400;font-size:14px;letter-spacing:.03em}
  td{padding:9px 12px;border-bottom:1px solid var(--line)}
  tbody tr{background:var(--surface)}
  tbody tr:nth-child(even){background:${p.zebra}}
  tbody tr:hover{background:color-mix(in srgb,#ffcf4a 15%,var(--surface))}
  tr:last-child td{border-bottom:0}
  strong{font-weight:700}
  .badge{display:inline-flex;align-items:center;border:2px solid var(--outline);border-radius:999px;padding:3px 10px;
    font-family:var(--display);font-weight:400;font-size:12px;line-height:1;letter-spacing:.03em;margin-right:6px;
    box-shadow:0 2px 0 var(--outline);background:var(--surface-2);color:var(--ink)}
  .badge.good{background:var(--good);color:#fff;text-shadow:0 1px 0 #2a160a}
  .badge.warn{background:var(--warn);color:#2a160a}
  .badge.bad,.badge.danger{background:var(--bad);color:#fff;text-shadow:0 1px 0 #2a160a}
  .badge.info{background:var(--info);color:#fff;text-shadow:0 1px 0 #2a160a}
  .badge.neutral{background:var(--surface-2);color:var(--ink)}
  .chip,.tag{display:inline-flex;align-items:center;border:1px solid var(--outline);border-radius:999px;padding:2px 10px;font-size:12px;font-weight:700;
    background:var(--surface-2);color:var(--ink-2);margin-right:6px}
  .chip button,.tag button{background:none;border:0;color:inherit;cursor:pointer;margin-left:5px;font-size:13px;opacity:.7}
  .chip button:hover{opacity:1}
  .hidden{display:none!important}
  .backdrop{position:fixed;inset:0;background:rgba(26,14,6,.72);display:grid;place-items:center;z-index:50;padding:20px}
  .modal{background:var(--surface);border:3px solid var(--outline);border-radius:22px;width:100%;max-width:440px;overflow:hidden;
    box-shadow:inset 0 4px 0 ${p.surfaceHi},inset 0 -6px 0 ${p.surfaceLo},0 8px 0 var(--outline)}
  .checkbox{display:flex;align-items:center;gap:8px;color:var(--ink-2);font-size:14px;font-weight:700}
  .checkbox input{width:auto}
  @media (prefers-reduced-motion:reduce){*{transition-duration:.01ms!important;animation-duration:.01ms!important}.btn:active{transform:none}}
  `
}

/** Renders an addon-contributed dashboard panel in a sandboxed iframe with a
 *  postMessage action/toast bridge; the session token never enters the iframe. */
export function AddonPanelView({ panelId }: { panelId: string }) {
  const [html, setHtml] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const frameRef = useRef<HTMLIFrameElement>(null)

  useEffect(() => {
    setHtml(null)
    setError(null)
    api<{ html: string }>(`/panels/${panelId}`).then((p) => setHtml(p.html)).catch((e) => setError(e.message))
  }, [panelId])

  useEffect(() => {
    const handler = async (e: MessageEvent) => {
      // Only trust messages from this panel's own iframe — anything else (a
      // window holding a stale reference to this tab, another frame) must
      // not be able to forge helix:action calls against the real session.
      if (e.source !== frameRef.current?.contentWindow) return
      const d = e.data || {}
      if (d.type === "helix:toast") {
        toast(String(d.message))
        return
      }
      if (d.type !== "helix:action") return
      let result: ActionResult
      try {
        result = await api<ActionResult>("/actions", { method: "POST", body: { action: d.name, arguments: d.args } })
      } catch (err) {
        result = { success: false, lines: [(err as Error).message] }
      }
      frameRef.current?.contentWindow?.postMessage({ type: "helix:result", id: d.id, result }, "*")
    }
    window.addEventListener("message", handler)
    return () => window.removeEventListener("message", handler)
  }, [])

  if (error) return <div className="text-sm text-destructive">{error}</div>
  if (html === null) return <div className="text-sm text-muted-foreground">Loading…</div>

  const dark = document.documentElement.classList.contains("dark")
  const srcDoc = `<style>${panelCss(dark ? DARK : LIGHT)}</style><script>${RUNTIME}</script>${html}`

  return (
    <iframe
      ref={frameRef}
      title={panelId}
      sandbox="allow-scripts allow-forms allow-modals"
      srcDoc={srcDoc}
      className="kl-panel h-[calc(100vh-8rem)] w-full rounded-[var(--radius-panel)] border-[3px] border-border bg-background"
    />
  )
}
