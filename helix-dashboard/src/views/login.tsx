import { useState } from "react"
import { toast } from "sonner"
import { api } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

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

  return (
    <div className="grid min-h-screen place-items-center bg-gradient-to-b from-primary/10 to-background p-4">
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          <div className="mb-2 grid size-11 place-items-center rounded-xl bg-primary font-bold text-primary-foreground">H</div>
          <CardTitle className="text-lg">Welcome to Helix</CardTitle>
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
              <p className="text-center text-sm text-muted-foreground">Enter the code sent to <b>{name}</b> in-game</p>
              <div className="grid gap-1.5">
                <Label htmlFor="l-code">Code</Label>
                <Input id="l-code" value={code} autoFocus inputMode="numeric" onChange={(e) => setCode(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && verify()} placeholder="6-digit code" />
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
          {error && <p className="text-center text-sm text-destructive">{error}</p>}
          <button className="mt-1 text-center text-xs text-muted-foreground hover:text-foreground"
            onClick={() => { setError(""); setMode(mode === "token" ? "name" : "token") }}>
            {mode === "token" ? "Use Minecraft account instead" : "Use admin token instead"}
          </button>
        </CardContent>
      </Card>
    </div>
  )
}
