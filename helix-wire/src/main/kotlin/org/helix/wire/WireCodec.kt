package org.helix.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer

/**
 * Encodes and decodes Helix-Wire payloads as CBOR, reusing the project's
 * existing `@Serializable` DTOs unchanged.
 *
 * CBOR keeps the frames compact and binary while every request/response
 * type stays an ordinary Kotlin data class, so the wire carries exactly the
 * same models the HTTP endpoints do — no parallel schema to maintain.
 */
object WireCodec {
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Encodes a value to CBOR bytes.
     *
     * @param T the value type.
     * @param value the value to encode.
     * @return the CBOR-encoded payload.
     */
    inline fun <reified T> encode(value: T): ByteArray = encode(serializer(), value)

    /**
     * Encodes a value to CBOR bytes with an explicit serializer.
     *
     * @param T the value type.
     * @param serializer the serializer to use.
     * @param value the value to encode.
     * @return the CBOR-encoded payload.
     */
    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    /**
     * Decodes CBOR bytes into a value.
     *
     * @param T the value type.
     * @param bytes the CBOR payload.
     * @return the decoded value.
     */
    inline fun <reified T> decode(bytes: ByteArray): T = decode(serializer(), bytes)

    /**
     * Decodes CBOR bytes into a value with an explicit serializer.
     *
     * @param T the value type.
     * @param serializer the serializer to use.
     * @param bytes the CBOR payload.
     * @return the decoded value.
     */
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(serializer, bytes)
}
