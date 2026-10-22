/**
 * Addon panels the dashboard renders natively in its own navigation instead of as an extension iframe.
 * The addon still registers them – that registration carries the permission node
 * (`helix.panel.addon.<id>`) and tells the dashboard the feature is installed – but the view itself is
 * part of the dashboard. The view id equals the panel id.
 */
export const INTEGRATED_PANELS = ["testguide", "bugreport"] as const

/** Whether a panel id is shown natively. */
export function isIntegratedPanel(id: string): boolean {
  return (INTEGRATED_PANELS as readonly string[]).includes(id)
}
