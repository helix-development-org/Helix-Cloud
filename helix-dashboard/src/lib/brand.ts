/**
 * Visible network branding (DESIGN.md §19, §20). The network name is still open (§21), so the wordmark text is
 * configurable at build time via `VITE_NETWORK_NAME`; "Kronland" is the placeholder. The platform's technical
 * name never appears as visible branding.
 */
export const NETWORK_NAME: string = import.meta.env.VITE_NETWORK_NAME?.trim() || "Kronland"
