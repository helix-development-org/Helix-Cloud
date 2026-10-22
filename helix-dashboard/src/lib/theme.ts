/**
 * Colour mode (DESIGN.md §17): dark = wood is the default, light = parchment; the choice is persisted.
 * index.html applies the stored mode before first paint, this module keeps it in sync afterwards.
 */
export type ThemeMode = "dark" | "light"

const STORAGE_KEY = "panel.theme"

export function storedTheme(): ThemeMode {
  try {
    return localStorage.getItem(STORAGE_KEY) === "light" ? "light" : "dark"
  } catch {
    return "dark"
  }
}

export function applyTheme(mode: ThemeMode) {
  document.documentElement.classList.toggle("dark", mode === "dark")
  try {
    localStorage.setItem(STORAGE_KEY, mode)
  } catch {
    /* storage unavailable (private mode, sandbox) – the mode still applies for this page */
  }
}
