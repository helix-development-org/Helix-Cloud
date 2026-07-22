package org.helix.wrapper

import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Forwards console commands appended to a file into the server's standard
 * input. The node (process or docker executor) appends command lines to the
 * shared `console.in` file in the service workspace; the wrapper — running with
 * that workspace as its working directory — streams new lines to the server.
 *
 * This makes the web-panel console work identically for process and docker
 * services, since the workspace is bind-mounted into the container.
 *
 * @property file the console input file to watch.
 * @property target the server process standard input.
 * @param startAtEnd whether to ignore content already present on start
 *  (commands from a previous run of a static service).
 */
class ConsoleForwarder(
    private val file: Path,
    private val target: OutputStream,
    startAtEnd: Boolean = true,
) {
    private var offset: Long = if (startAtEnd && Files.exists(file)) Files.size(file) else 0L

    /**
     * Reads bytes appended since the last call and writes them to the server
     * input.
     *
     * @return the number of bytes forwarded.
     */
    fun pump(): Int {
        if (Files.notExists(file)) {
            return 0
        }
        val length = Files.size(file)
        if (length < offset) {
            offset = 0 // file was truncated or replaced
        }
        if (length <= offset) {
            return 0
        }
        RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray((length - offset).toInt())
            raf.readFully(buffer)
            offset = length
            target.write(buffer)
            target.flush()
            return buffer.size
        }
    }

    /**
     * Starts a daemon thread that pumps the file into the server input until
     * [alive] returns `false`.
     *
     * @param alive whether the server is still running.
     * @param pollMillis poll interval.
     * @return the started thread.
     */
    fun startPumping(alive: () -> Boolean, pollMillis: Long = 150): Thread {
        val thread = Thread {
            runCatching { if (Files.notExists(file)) Files.createFile(file) }
            while (alive() && !Thread.currentThread().isInterrupted) {
                runCatching { pump() }
                try {
                    Thread.sleep(pollMillis)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        thread.name = "helix-console"
        thread.isDaemon = true
        thread.start()
        return thread
    }
}
