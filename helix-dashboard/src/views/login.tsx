import { useState } from "react"
import { toast } from "sonner"
import { api } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { CodeInput } from "@/components/code-input"
import { Wordmark } from "@/components/brand/wordmark"
import { NETWORK_DOMAIN } from "@/lib/brand"
import { Knoepfchen } from "@/components/brand/knoepfchen"

/** Two-step Minecraft-account login with an admin-token fallback. */
export function LoginView({ establish }: { establish: (token: string) => Promise<void> }) {
  const [mode, setMode] = useState<"name" | "code" | "token">("name")
  const [name, setName] = useState("")
  const [code, setCode] = useState("")
  const [tokenInput, setTokenInput] = useState("")
  const [error, setError] = useState("")
  const [busy, setBusy] = useState(false)

  const requestCode = async () => {
    if (!name.trim()) return setError("Enter your Minecraft name")
    setBusy(true)
    setError("")
    try {
      const r = await api<{ message: string }>("/auth/request-code", { method: "POST", body: { name: name.trim() } })
      toast.success(r.message)
      setMode("code")
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const verify = async () => {
    setBusy(true)
    setError("")
    try {
      const res = await api<{ token: string }>("/auth/verify", { method: "POST", body: { name: name.trim(), code: code.trim() } })
      await establish(res.token)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const adminLogin = async () => {
    setBusy(true)
    setError("")
    try {
      await establish(tokenInput.trim())
    } catch {
      setError("Sign in failed — check the token")
    } finally {
      setBusy(false)
    }
  }

  const lines: Record<typeof mode, string> = {
    name: "Staff only! Tell me your Minecraft name. I write everything down. Mostly.",
    code: "I sent you a code in-game. Six digits, I counted twice.",
    token: "The secret control token? I am not supposed to look. I am not looking.",
  }

  return (
    // Parchment card on wood (§17): the backdrop keeps the wood token set, the card the parchment one.
    <div className="theme-wood kl-planks flex min-h-screen flex-col items-center justify-center gap-6 p-4 text-foreground">
      <Wordmark size={52} mark />
      <div className="kl-rise relative w-full max-w-[440px]">
        <Card className="theme-parchment">
          <CardHeader className="items-center gap-3 pb-4 text-center">
            <div className="flex w-full items-end gap-3">
              <Knoepfchen className="h-32 w-auto shrink-0 -ml-1" />
              <div className="relative mb-6 flex-1 rounded-[14px] border-2 border-border bg-[var(--parchment-hi)] px-3 py-2 text-left text-sm font-bold leading-snug shadow-[0_3px_0_var(--border)]">
                {lines[mode]}
                <span aria-hidden className="absolute -left-[9px] bottom-3 size-4 rotate-45 border-b-2 border-l-2 border-border bg-[var(--parchment-hi)]" />
              </div>
            </div>
            <CardTitle className="text-[22px]">Staff sign-in</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {mode === "name" && (
              <>
                <p className="text-center text-sm text-muted-foreground">Sign in with your Minecraft account</p>
                <div className="grid gap-1.5">
                  <Label htmlFor="l-name">Minecraft name</Label>
                  <Input id="l-name" value={name} autoFocus onChange={(e) => setName(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && requestCode()} placeholder="Steve" />
                </div>
                <Button disabled={busy} onClick={requestCode}>Send login code</Button>
              </>
            )}
            {mode === "code" && (
              <>
                <p className="text-center text-sm text-muted-foreground">Enter the code sent to <b className="text-foreground">{name}</b> in-game</p>
                <div className="grid gap-2">
                  <Label htmlFor="l-code" className="text-center">Code</Label>
                  <CodeInput idPrefix="l-code" value={code} autoFocus disabled={busy} onChange={setCode} onSubmit={verify} />
                </div>
                <Button disabled={busy} onClick={verify}>Sign in</Button>
                <Button variant="ghost" size="sm" onClick={() => { setCode(""); setMode("name") }}>Use a different name</Button>
              </>
            )}
            {mode === "token" && (
              <>
                <p className="text-center text-sm text-muted-foreground">Sign in with the admin control token</p>
                <div className="grid gap-1.5">
                  <Label htmlFor="l-token">Control token</Label>
                  <Input id="l-token" type="password" value={tokenInput} autoFocus onChange={(e) => setTokenInput(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && adminLogin()} placeholder="Control token" />
                </div>
                <Button disabled={busy} onClick={adminLogin}>Sign in</Button>
              </>
            )}
            {error && (
              <p className="rounded-[12px] border-2 border-border bg-destructive px-3 py-1.5 text-center text-sm font-bold text-destructive-foreground [text-shadow:0_1px_0_var(--border)]">
                {error}
              </p>
            )}
            <button className="mt-1 text-center text-sm font-bold text-muted-foreground underline decoration-[var(--gold-lo)] decoration-2 underline-offset-4 hover:text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring rounded"
              onClick={() => { setError(""); setMode(mode === "token" ? "name" : "token") }}>
              {mode === "token" ? "Use Minecraft account instead" : "Use admin token instead"}
            </button>
          </CardContent>
        </Card>
      </div>
      <a href={`https://${NETWORK_DOMAIN}`} target="_blank" rel="noreferrer"
        className="text-sm font-bold text-[var(--parchment)] opacity-80 underline decoration-[var(--gold-lo)] decoration-2 underline-offset-4 hover:opacity-100">
        {NETWORK_DOMAIN}
      </a>
    </div>
  )
}
