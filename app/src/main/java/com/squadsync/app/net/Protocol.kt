package com.squadsync.app.net

import com.squadsync.app.model.Wire
import kotlinx.serialization.json.Json

/**
 * Encode/decode Wire messages.
 *
 * Wire is a sealed class whose subtypes each carry a literal `type` discriminator.
 * kotlinx.serialization requires a real polymorphic serializer for sealed types,
 * so we generate it with `Wire.serializer()` and let the JSON config use `type`.
 */
object Protocol {
    val lenientJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val wireSerializer = Wire.serializer()

    fun encode(msg: Wire): String = lenientJson.encodeToString(wireSerializer, msg)

    fun decode(text: String): Wire? = try {
        lenientJson.decodeFromString(wireSerializer, text)
    } catch (t: Throwable) {
        null
    }
}