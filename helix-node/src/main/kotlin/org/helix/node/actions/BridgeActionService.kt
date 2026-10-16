package org.helix.node.actions

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Executes actions invoked by a Paper/Velocity component holding a
 * per-service token, through `POST /internal/action` or the wire link.
 *
 * Only actions whose descriptor has `bridgeInvocable` set may be invoked
 * this way — everything else answers a failure, so a per-service token
 * (present in every managed game server's environment) can not reach
 * admin-only actions the way an admin-token or `helix.admin` session could
 * via `POST /api/v1/actions`. Actions declaring
 * [org.helix.api.action.ActionDescriptor.bridgeTasks] are additionally limited
 * to services of those tasks.
 *
 * Invocations carrying an [ActionInvocation.requestId] are executed at most
 * once per calling service: repeats (a client retrying after a timeout, or the
 * HTTP fallback after a sent wire frame) wait for and receive the original
 * result instead of running the action again.
 *
 * @property registry action registry.
 * @property taskOf resolves the task name of a calling service id, `null` when unknown.
 * @property dedup the idempotency cache.
 */
class BridgeActionService(
    private val registry: ActionRegistry,
    private val taskOf: (String) -> String? = { null },
    private val dedup: ActionDedup = ActionDedup(),
) {
    /**
     * Executes one bridge-invocable action.
     *
     * @param invocation action name and arguments from the bridge.
     * @param serviceId id of the calling service; `null` for the static admin token,
     *   which is never task-restricted.
     * @return the action result, or a failure for unknown, non-bridge or
     *   task-restricted actions.
     */
    fun invoke(invocation: ActionInvocation, serviceId: String? = null): ActionResult {
        val descriptor = registry.descriptors().firstOrNull { it.name == invocation.action }
        if (descriptor == null || !descriptor.bridgeInvocable) {
            return ActionResult.error("unknown or non-bridge action: ${invocation.action}")
        }
        if (serviceId != null && descriptor.bridgeTasks.isNotEmpty()) {
            val task = taskOf(serviceId)
            if (task == null || descriptor.bridgeTasks.none { it.equals(task, ignoreCase = true) }) {
                return ActionResult.error("action ${invocation.action} is not available for service $serviceId")
            }
        }
        val run = { registry.invoke(invocation.copy(source = ActionSource.BRIDGE)) }
        val requestId = invocation.requestId?.takeIf { it.isNotBlank() } ?: return run()
        return dedup.once("${serviceId ?: "-"}|$requestId", run)
    }
}

/**
 * At-most-once execution cache for bridge invocations, keyed by caller and request id.
 *
 * The first invocation of a key runs; concurrent and later duplicates within [ttlMs] wait for
 * (at most [waitMs]) and return its result. Entries expire after [ttlMs]; at most [maxEntries]
 * are kept (oldest completed entries are evicted first). A run that throws is forgotten so a
 * retry can execute again — the exception is rethrown to the first caller and duplicates get an
 * error result.
 *
 * @property ttlMs how long a result is remembered.
 * @property maxEntries upper bound of remembered keys.
 * @property waitMs how long a duplicate waits for a still running original.
 * @property clock current time in epoch milliseconds (injectable for tests).
 */
class ActionDedup(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val waitMs: Long = DEFAULT_WAIT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val createdAt: Long, val result: CompletableFuture<ActionResult>)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Number of remembered keys (for tests and metrics). */
    val size: Int get() = entries.size

    /**
     * Runs [action] once for [key] and returns its (possibly remembered) result.
     *
     * @param key caller-scoped request key.
     * @param action the work to execute at most once.
     * @return the result of the first execution.
     */
    fun once(key: String, action: () -> ActionResult): ActionResult {
        val now = clock()
        prune(now)
        val mine = Entry(now, CompletableFuture())
        val existing = entries.compute(key) { _, current ->
            if (current != null && now - current.createdAt < ttlMs) current else mine
        }!!
        if (existing !== mine) {
            return runCatching { existing.result.get(waitMs, TimeUnit.MILLISECONDS) }
                .getOrElse { ActionResult.error("duplicate request still running or failed: ${it.message}") }
        }
        return try {
            action().also { mine.result.complete(it) }
        } catch (failure: Throwable) {
            entries.remove(key, mine)
            mine.result.completeExceptionally(failure)
            throw failure
        }
    }

    private fun prune(now: Long) {
        entries.entries.removeIf { now - it.value.createdAt >= ttlMs && it.value.result.isDone }
        if (entries.size <= maxEntries) return
        entries.entries.filter { it.value.result.isDone }
            .sortedBy { it.value.createdAt }
            .take(entries.size - maxEntries)
            .forEach { entries.remove(it.key, it.value) }
    }

    /** Defaults. */
    companion object {
        /** Ten minutes. */
        const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000L

        /** Remembered keys before eviction. */
        const val DEFAULT_MAX_ENTRIES: Int = 50_000

        /** Wait of a duplicate for the running original. */
        const val DEFAULT_WAIT_MS: Long = 60_000L
    }
}
