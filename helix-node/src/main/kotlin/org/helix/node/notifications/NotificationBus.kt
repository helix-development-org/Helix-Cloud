package org.helix.node.notifications

import org.helix.api.addon.NotificationListener
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fans addon-published notifications out to all registered listeners.
 *
 * Publishers and subscribers stay decoupled: the bans addon publishes
 * `moderation` events without knowing who listens, the team addon
 * forwards them without knowing who publishes. A listener that throws is
 * skipped.
 */
class NotificationBus {
    private val logger = LoggerFactory.getLogger(NotificationBus::class.java)
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<NotificationListener>>()

    /**
     * Registers a listener under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param listener receives all published notifications.
     */
    fun register(owner: String, listener: NotificationListener) {
        listeners.computeIfAbsent(owner) { CopyOnWriteArrayList() }.add(listener)
    }

    /**
     * Removes all listeners of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        listeners.remove(owner)
    }

    /**
     * Publishes a notification to every listener.
     *
     * @param category notification category, for example `moderation`.
     * @param message human readable text.
     */
    fun publish(category: String, message: String) {
        logger.info("[{}] {}", category, message)
        listeners.values.flatten().forEach { listener ->
            runCatching { listener.onNotification(category, message) }
                .onFailure { logger.error("notification listener failed", it) }
        }
    }
}
