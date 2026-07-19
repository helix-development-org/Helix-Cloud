package org.helix.api.addon

/**
 * Receives notifications published by other addons.
 *
 * Notifications are lightweight, categorized text events — for example
 * every ban, warn or kick is published under the `moderation` category so
 * a team addon can forward them to online staff without the publishing
 * addon knowing about it.
 */
fun interface NotificationListener {
    /**
     * Called for every published notification.
     *
     * @param category notification category, for example `moderation`.
     * @param message human readable text, `&` color codes allowed.
     */
    fun onNotification(category: String, message: String)
}
