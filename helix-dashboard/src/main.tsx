import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import "./index.css"
import { App } from "./App"
import { NETWORK_NAME } from "@/lib/brand"
import { applyTheme, storedTheme } from "@/lib/theme"

applyTheme(storedTheme())
document.title = `${NETWORK_NAME} · Panel`

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
