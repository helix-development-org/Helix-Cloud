package de.tytoss.iguard.trace

import de.tytoss.iguard.api.IGuardTraceListener
import de.tytoss.iguard.api.IGuardTraceSession
import java.nio.file.Path
import java.util.UUID

/** Trace recording and frame-listener control behind the trace part of [de.tytoss.iguard.api.IGuardApi]. */
interface TraceControl {
    /** Starts (or restarts) the player's trace recording. */
    fun start(playerId: UUID, label: String): IGuardTraceSession

    /** Stops the player's recording; returns its file or null when none was running. */
    fun stop(playerId: UUID): Path?

    /** Writes a marker into the player's running recording; false when none is running. */
    fun marker(playerId: UUID, text: String): Boolean

    /** The player's running recording, or null. */
    fun active(playerId: UUID): IGuardTraceSession?

    /** Registers a frame listener; closing the handle unregisters it. */
    fun addListener(listener: IGuardTraceListener): AutoCloseable
}
