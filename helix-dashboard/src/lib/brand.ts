/**
 * Visible network branding (DESIGN.md §19, §20): the network is released as "KronlandMC" (realm "Kronland" in the
 * lore), website kronland.de. `VITE_NETWORK_NAME` can still override the wordmark text at build time. The platform's
 * technical name never appears as visible branding.
 */
export const NETWORK_NAME: string = import.meta.env.VITE_NETWORK_NAME?.trim() || "KronlandMC"

/** Public website of the network, shown in the panel footer. */
export const NETWORK_DOMAIN = "kronland.de"
