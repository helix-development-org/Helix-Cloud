package org.helix.addons.feedback.paper

import java.security.MessageDigest

/**
 * Stack trace normalisation for automatic error reports (pure, unit-tested): the same bug must always produce the
 * same hash, no matter which player, entity id, coordinates or line numbers are involved.
 */
object StackTraces {
    /** Frames taken into the signature. */
    const val SIGNATURE_FRAMES = 8

    /** Frames shown in the description. */
    const val DESCRIPTION_FRAMES = 25

    private val numbers = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val hex = Regex("\\b(0x)?[0-9a-f]{8,}\\b", RegexOption.IGNORE_CASE)
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    private val synthetic = Regex("\\$(lambda|\\d+)[^.]*")
    private val ignoredPrefixes = listOf("java.", "jdk.", "sun.", "kotlin.", "kotlinx.coroutines.")

    /**
     * Strips volatile parts from an exception message.
     *
     * @param message the raw message.
     * @return the message with UUIDs, hex ids and numbers replaced.
     */
    fun normalizeMessage(message: String?): String = (message ?: "")
        .replace(uuid, "<uuid>")
        .replace(hex, "<hex>")
        .replace(numbers, "#")
        .take(200)

    /**
     * Normalises one frame to `class.method` without line numbers and synthetic lambda suffixes.
     *
     * @param className declaring class.
     * @param method method name.
     * @return the normalised frame.
     */
    fun normalizeFrame(className: String, method: String): String =
        "${className.replace(synthetic, "")}.${method.replace(synthetic, "")}"

    /**
     * The signature of a throwable: its class, normalised message and the first [SIGNATURE_FRAMES] frames that
     * are not JDK/Kotlin runtime frames (falling back to all frames), including causes' classes.
     *
     * @param error the throwable.
     * @return the signature text the hash is computed from.
     */
    fun signature(error: Throwable): String {
        val frames = error.stackTrace.map { normalizeFrame(it.className, it.methodName) }
        val own = frames.filter { frame -> ignoredPrefixes.none { frame.startsWith(it) } }
        val picked = (own.ifEmpty { frames }).take(SIGNATURE_FRAMES)
        val causes = generateSequence(error.cause) { it.cause }.take(5).map { it.javaClass.name }.toList()
        return buildString {
            append(error.javaClass.name).append(':').append(normalizeMessage(error.message))
            picked.forEach { append('\n').append(it) }
            causes.forEach { append("\ncaused by ").append(it) }
        }
    }

    /**
     * @param error the throwable.
     * @return the 16-hex-digit SHA-256 prefix of [signature].
     */
    fun hash(error: Throwable): String = hashOf(signature(error))

    /**
     * @param text any text.
     * @return the 16-hex-digit SHA-256 prefix.
     */
    fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /**
     * A short title for the report, e.g. `NullPointerException in CityBuild.onClick`.
     *
     * @param error the throwable.
     * @return the title (max. 60 characters).
     */
    fun title(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { f -> ignoredPrefixes.none { f.className.startsWith(it) } }
            ?: error.stackTrace.firstOrNull()
        val where = frame?.let { "${it.className.substringAfterLast('.').replace(synthetic, "")}.${it.methodName.replace(synthetic, "")}" }
        return (error.javaClass.simpleName + (where?.let { " in $it" } ?: "")).take(60)
    }

    /**
     * The readable description: message plus the first frames of the trace and of each cause.
     *
     * @param error the throwable.
     * @param origin where it was caught (event name, plugin, thread).
     * @return the description.
     */
    fun describe(error: Throwable, origin: String): String = buildString {
        append(origin).append('\n')
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) append("\nCaused by: ")
            append(current.javaClass.name).append(": ").append(current.message ?: "")
            current.stackTrace.take(DESCRIPTION_FRAMES).forEach { append("\n  at ").append(it) }
            current = current.cause
            depth++
        }
    }.take(FormValidation.DESCRIPTION_MAX)
}

/**
 * Remembers which hashes were sent recently so a loop that throws every tick sends at most one report per hash and
 * window (pure apart from the injected clock).
 *
 * @property windowMillis dedupe window.
 * @property clock current time in milliseconds.
 */
class RecentHashes(private val windowMillis: Long = 60_000, private val clock: () -> Long = System::currentTimeMillis) {
    private val sent = LinkedHashMap<String, Long>()

    /**
     * Records a hash if it was not sent within the window.
     *
     * @param hash the hash.
     * @return true when the caller should send it now.
     */
    @Synchronized
    fun admit(hash: String): Boolean {
        val now = clock()
        sent.entries.removeIf { now - it.value >= windowMillis }
        if (sent.containsKey(hash)) return false
        sent[hash] = now
        return true
    }
}
