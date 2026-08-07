// Resolve a CodeMirror language extension from a file name (by extension).
import type { Extension } from "@codemirror/state"
import { StreamLanguage } from "@codemirror/language"
import { yaml } from "@codemirror/lang-yaml"
import { json } from "@codemirror/lang-json"
import { xml } from "@codemirror/lang-xml"
import { html } from "@codemirror/lang-html"
import { markdown } from "@codemirror/lang-markdown"
import { java } from "@codemirror/lang-java"
import { kotlin } from "@codemirror/legacy-modes/mode/clike"
import { properties } from "@codemirror/legacy-modes/mode/properties"
import { toml } from "@codemirror/legacy-modes/mode/toml"
import { shell } from "@codemirror/legacy-modes/mode/shell"

/** Language extension for a file name, or null when the type is unknown. */
export function languageForFile(name: string): Extension | null {
  const ext = name.slice(name.lastIndexOf(".") + 1).toLowerCase()
  switch (ext) {
    case "yml":
    case "yaml":
      return yaml()
    case "json":
    case "json5":
      return json()
    case "xml":
      return xml()
    case "html":
    case "htm":
      return html()
    case "md":
    case "markdown":
      return markdown()
    case "java":
      return java()
    case "kt":
    case "kts":
      return StreamLanguage.define(kotlin)
    case "properties":
    case "conf":
    case "cfg":
    case "ini":
    case "env":
      return StreamLanguage.define(properties)
    case "toml":
      return StreamLanguage.define(toml)
    case "sh":
    case "bash":
      return StreamLanguage.define(shell)
    default:
      return null
  }
}

/** Human label for the resolved language, used as an editor badge. */
export function languageLabel(name: string): string {
  const ext = name.slice(name.lastIndexOf(".") + 1).toLowerCase()
  const map: Record<string, string> = {
    yml: "YAML", yaml: "YAML", json: "JSON", json5: "JSON5", xml: "XML",
    html: "HTML", htm: "HTML", md: "Markdown", markdown: "Markdown",
    java: "Java", kt: "Kotlin", kts: "Kotlin", properties: "Properties",
    conf: "Properties", cfg: "Properties", ini: "INI", env: "Env",
    toml: "TOML", sh: "Shell", bash: "Shell",
  }
  return map[ext] ?? "Plain text"
}
