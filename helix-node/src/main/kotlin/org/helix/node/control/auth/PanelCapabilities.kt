package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * One permission-guarded capability of the built-in dashboard: viewing an area or changing something in it.
 *
 * @property id stable id (`services`, `services.console` …), the node without its `helix.panel.` prefix.
 * @property node permission node that grants it.
 * @property label German label for the access matrix.
 * @property description German one-line explanation of what it allows.
 * @property group `Dashboard-Bereiche` (seeing a view) or `Steuerung` (changing things).
 * @property view the dashboard view it belongs to.
 */
@Serializable
data class PanelCapability(
    val id: String,
    val node: String,
    val label: String,
    val description: String,
    val group: String,
    val view: String,
)

/**
 * An addon-contributed dashboard tab in the access catalog.
 *
 * @property id panel id.
 * @property node permission node (`helix.panel.addon.<id>`).
 * @property label the tab's sidebar title.
 */
@Serializable
data class PanelTabCapability(val id: String, val node: String, val label: String)

/**
 * Response of `GET /access/catalog` (permission [PanelCapabilities.ACCESS]): everything the access matrix can
 * grant to a group. Groups and their grants are read and changed through the permissions addon actions
 * (`perm.group.list`, `perm.group.info`, `perm.group.grant`, `perm.group.revoke`).
 *
 * ```json
 * {"capabilities":[{"id":"services.console","node":"helix.panel.services.console","label":"Konsole schreiben",
 *   "description":"…","group":"Steuerung","view":"services"}],
 *  "panels":[{"id":"feedback","node":"helix.panel.addon.feedback","label":"Bugs"}],
 *  "login":"helix.panel.login","admin":"helix.admin"}
 * ```
 *
 * @property capabilities built-in capabilities, view first, then its controls.
 * @property panels addon tabs currently registered.
 * @property login node needed to sign in at all.
 * @property admin node granting everything (full admin session).
 */
@Serializable
data class AccessCatalog(
    val capabilities: List<PanelCapability>,
    val panels: List<PanelTabCapability>,
    val login: String,
    val admin: String,
)

/**
 * Permission nodes of the built-in dashboard, split into seeing an area and changing things in it: a team member
 * may watch the services without being able to stop them or type into a server console.
 */
object PanelCapabilities {
    private const val PREFIX = "helix.panel."
    private const val AREAS = "Dashboard-Bereiche"
    private const val CONTROL = "Steuerung"

    /** Overview, metrics and health. */
    const val OVERVIEW = "helix.panel.overview"

    /** Task list and details. */
    const val TASKS = "helix.panel.tasks"

    /** Edit and delete tasks. */
    const val TASKS_EDIT = "helix.panel.tasks.edit"

    /** Service list and details. */
    const val SERVICES = "helix.panel.services"

    /** Read a service's console output (logs and live stream). */
    const val SERVICES_LOGS = "helix.panel.services.logs"

    /** Type commands into a service console. */
    const val SERVICES_CONSOLE = "helix.panel.services.console"

    /** Start new services, stop and kill running ones. */
    const val SERVICES_CONTROL = "helix.panel.services.control"

    /** Create backups of a service. */
    const val SERVICES_BACKUPS = "helix.panel.services.backups"

    /** Online players and lookups. */
    const val PLAYERS = "helix.panel.players"

    /** Message, kick and ban players from the dashboard. */
    const val PLAYERS_MANAGE = "helix.panel.players.manage"

    /** Proxy status, maintenance state and whitelist. */
    const val PROXY = "helix.panel.proxy"

    /** Toggle maintenance and edit the whitelist. */
    const val PROXY_CONTROL = "helix.panel.proxy.control"

    /** Network events. */
    const val EVENTS = "helix.panel.events"

    /** Node log (contains technical details of the whole network). */
    const val LOGS = "helix.panel.logs"

    /** Audit log of dashboard actions. */
    const val AUDIT = "helix.panel.audit"

    /** Installed addons. */
    const val ADDONS = "helix.panel.addons"

    /** Enable and disable addons. */
    const val ADDONS_MANAGE = "helix.panel.addons.manage"

    /** Scheduled jobs. */
    const val SCHEDULES = "helix.panel.schedules"

    /** Create, delete and run scheduled jobs. */
    const val SCHEDULES_EDIT = "helix.panel.schedules.edit"

    /** Backup list. */
    const val BACKUPS = "helix.panel.backups"

    /** Restore and delete backups. */
    const val BACKUPS_RESTORE = "helix.panel.backups.restore"

    /** File browser (read). */
    const val FILES = "helix.panel.files"

    /** Write and delete files. */
    const val FILES_EDIT = "helix.panel.files.edit"

    /** Translations (read). */
    const val TRANSLATIONS = "helix.panel.translations"

    /** Edit translations and languages. */
    const val TRANSLATIONS_EDIT = "helix.panel.translations.edit"

    /** Node settings. */
    const val SETTINGS = "helix.panel.settings"

    /** The access matrix: who may see and change what. */
    const val ACCESS = "helix.panel.access"

    /** Every built-in capability, grouped by view (view first, then its controls). */
    val ALL: List<PanelCapability> = listOf(
        area(OVERVIEW, "Übersicht", "Startseite mit Auslastung, Metriken und Zustand.", "overview"),
        area(TASKS, "Tasks ansehen", "Liste und Details der Server-Vorlagen.", "tasks"),
        control(TASKS_EDIT, "Tasks bearbeiten", "Tasks ändern und löschen.", "tasks"),
        area(SERVICES, "Server ansehen", "Laufende Server mit Status und Spielerzahl.", "services"),
        control(SERVICES_LOGS, "Konsole lesen", "Ausgabe und Live-Konsole eines Servers.", "services"),
        control(SERVICES_CONSOLE, "Konsole schreiben", "Befehle in die Konsole eines Servers schicken.", "services"),
        control(SERVICES_CONTROL, "Server steuern", "Server starten, stoppen und hart beenden.", "services"),
        control(SERVICES_BACKUPS, "Server sichern", "Backups eines Servers anlegen.", "services"),
        area(PLAYERS, "Spieler ansehen", "Online-Spieler und Spielersuche.", "players"),
        control(PLAYERS_MANAGE, "Spieler verwalten", "Spielern schreiben, sie kicken und bannen.", "players"),
        area(PROXY, "Proxy ansehen", "Proxy-Status, Wartung und Whitelist.", "proxy"),
        control(PROXY_CONTROL, "Proxy steuern", "Wartungsmodus schalten und Whitelist bearbeiten.", "proxy"),
        area(EVENTS, "Ereignisse", "Ereignisse des Netzwerks.", "events"),
        area(LOGS, "Node-Log", "Technisches Log des gesamten Netzwerks.", "logs"),
        area(AUDIT, "Audit-Log", "Wer hat im Dashboard was getan.", "audit"),
        area(ADDONS, "Addons ansehen", "Installierte Addons.", "addons"),
        control(ADDONS_MANAGE, "Addons verwalten", "Addons ein- und ausschalten.", "addons"),
        area(SCHEDULES, "Zeitpläne ansehen", "Geplante Aufgaben.", "schedules"),
        control(SCHEDULES_EDIT, "Zeitpläne bearbeiten", "Geplante Aufgaben anlegen, löschen und ausführen.", "schedules"),
        area(BACKUPS, "Backups ansehen", "Liste der Backups.", "backups"),
        control(BACKUPS_RESTORE, "Backups einspielen", "Backups wiederherstellen und löschen.", "backups"),
        area(FILES, "Dateien ansehen", "Dateien der Server lesen.", "files"),
        control(FILES_EDIT, "Dateien bearbeiten", "Dateien schreiben und löschen.", "files"),
        area(TRANSLATIONS, "Übersetzungen ansehen", "Texte und Sprachen.", "translations"),
        control(TRANSLATIONS_EDIT, "Übersetzungen bearbeiten", "Texte und Sprachen ändern.", "translations"),
        area(SETTINGS, "Einstellungen", "Einstellungen des Nodes.", "settings"),
        area(ACCESS, "Zugriff", "Festlegen, welche Gruppe im Dashboard was sehen und tun darf.", "access"),
    )

    private fun area(node: String, label: String, description: String, view: String) =
        PanelCapability(node.removePrefix(PREFIX), node, label, description, AREAS, view)

    private fun control(node: String, label: String, description: String, view: String) =
        PanelCapability(node.removePrefix(PREFIX), node, label, description, CONTROL, view)
}
