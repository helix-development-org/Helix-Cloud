import { useEffect, useRef, useState } from "react"
import { api, type ActionResult } from "@/lib/api"

const RUNTIME = `
  window.Helix = {
    _seq: 0, _pending: {},
    action: function(name) {
      var args = Array.prototype.slice.call(arguments, 1).map(String);
      var id = ++this._seq; var self = this;
      return new Promise(function(res, rej) {
        self._pending[id] = { res: res, rej: rej };
        parent.postMessage({ type: "helix:action", id: id, name: name, args: args }, "*");
      });
    }
  };
  window.addEventListener("message", function(e) {
    var d = e.data || {};
    if (d.type === "helix:result" && window.Helix._pending[d.id]) {
      window.Helix._pending[d.id].res(d.result); delete window.Helix._pending[d.id];
    }
  });
`

/** Renders an addon-contributed dashboard panel in a sandboxed iframe with a
 *  postMessage action bridge; the session token never enters the iframe. */
export function AddonPanelView({ panelId }: { panelId: string }) {
  const [html, setHtml] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const frameRef = useRef<HTMLIFrameElement>(null)

  useEffect(() => {
    api<{ html: string }>(`/panels/${panelId}`).then((p) => setHtml(p.html)).catch((e) => setError(e.message))
  }, [panelId])

  useEffect(() => {
    const handler = async (e: MessageEvent) => {
      const d = e.data || {}
      if (d.type !== "helix:action") return
      let result: ActionResult
      try { result = await api<ActionResult>("/actions", { method: "POST", body: { action: d.name, arguments: d.args } }) }
      catch (err) { result = { success: false, lines: [(err as Error).message] } }
      frameRef.current?.contentWindow?.postMessage({ type: "helix:result", id: d.id, result }, "*")
    }
    window.addEventListener("message", handler)
    return () => window.removeEventListener("message", handler)
  }, [])

  if (error) return <div className="text-sm text-destructive">{error}</div>
  if (html === null) return <div className="text-sm text-muted-foreground">Loading…</div>

  const theme = `<style>:root{color-scheme:dark;font-family:ui-sans-serif,system-ui,sans-serif;background:#141416;color:#fafafa}</style>`
  const srcDoc = `${theme}${html}<script>${RUNTIME}</script>`

  return (
    <iframe
      ref={frameRef}
      title={panelId}
      sandbox="allow-scripts allow-forms"
      srcDoc={srcDoc}
      className="h-[calc(100vh-8rem)] w-full rounded-xl border bg-card"
    />
  )
}
