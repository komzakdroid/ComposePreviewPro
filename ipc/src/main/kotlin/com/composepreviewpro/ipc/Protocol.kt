package com.composepreviewpro.ipc

import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Single JSON instance shared by both sides of the wire. Tuned for:
 *   • ignoreUnknownKeys — forward compatibility: an older peer can talk to
 *     a newer one that added fields, without throwing.
 *   • encodeDefaults    — keep messages small; defaults are reconstructed.
 *   • classDiscriminator = "type" — sealed-class polymorphism marker.
 */
val IpcJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
    prettyPrint = false
}

/**
 * Writer side of the protocol. Writes one JSON object per line, flushes
 * after every message — IPC peers are blocked waiting on us, so buffering
 * silently is a deadlock waiting to happen.
 */
class MessageWriter<T : Any>(
    out: OutputStream,
    private val serializer: kotlinx.serialization.KSerializer<T>,
) {
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))

    @Synchronized
    fun send(message: T) {
        val line = IpcJson.encodeToString(serializer, message)
        // JSON itself can never contain a raw newline outside of strings,
        // and kotlinx.serialization escapes any newline within strings,
        // so a single '\n' here unambiguously frames one message.
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    fun close() {
        writer.close()
    }
}

/**
 * Reader side of the protocol. Pull-based: caller invokes [readNext] in a
 * loop until it returns null (EOF). Each line is parsed as one message.
 */
class MessageReader<T : Any>(
    input: InputStream,
    private val deserializer: kotlinx.serialization.KSerializer<T>,
) {
    private val reader: BufferedReader =
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))

    /** @return next message, or null if the peer closed the stream. */
    fun readNext(): T? {
        val line = reader.readLine() ?: return null
        if (line.isBlank()) return readNext()
        return IpcJson.decodeFromString(deserializer, line)
    }

    fun close() {
        reader.close()
    }
}
