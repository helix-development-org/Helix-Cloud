import { createContext, useContext, type ReactNode } from "react"
import type { Identity } from "@/lib/api"

const IdentityContext = createContext<Identity | null>(null)

/** Makes the signed-in identity available to {@link useCan} below the app shell. */
export function AccessProvider({ identity, children }: { identity: Identity; children: ReactNode }) {
  return <IdentityContext.Provider value={identity}>{children}</IdentityContext.Provider>
}

/**
 * Returns a check for dashboard capabilities (`services.console`, `players.manage` …, the permission node
 * without `helix.panel.`): admins may do everything, everyone else exactly what `/auth/me` lists. The server
 * enforces the same nodes – this only hides controls that would answer 403.
 */
export function useCan(): (capability: string) => boolean {
  const identity = useContext(IdentityContext)
  return (capability) => !!identity && (identity.admin || (identity.capabilities ?? []).includes(capability))
}

/** Whether the signed-in user is a full admin (only admins may change the access matrix). */
export function useIsAdmin(): boolean {
  const identity = useContext(IdentityContext)
  return !!identity?.admin
}
