package com.squadsync.app.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimal RFC 6455 WebSocket server helpers.
 *
 * - `computeAccept(secKey)` returns the value to send in `Sec-WebSocket-Accept`.
 * - `writeTextFrame(out, text)` writes a single text frame to an output stream.
 * - `readTextFrame(input)` reads one frame and decodes its text payload (UTF-8).
 *
 * Only text frames and close frames are implemented; that covers our JSON
 * protocol. Pings are answered with pongs inside `readFrame`.
 */
object WsFrames {

    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun computeAccept(secKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((secKey + MAGIC).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun writeTextFrame(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val out2 = DataOutputStream(out)
        // FIN=1, opcode=1 (text)
        out2.writeByte(0x81)
        writeLength(out2, payload.size)
        out2.write(payload)
        out2.flush()
    }

    fun writeCloseFrame(out: OutputStream, code: Int = 1000, reason: String = "bye") {
        val payload = ByteArrayOutputStream().apply {
            write((code shr 8) and 0xFF)
            write(code and 0xFF)
            write(reason.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val out2 = DataOutputStream(out)
        out2.writeByte(0x88)                 // FIN + close
        writeLength(out2, payload.size)
        out2.write(payload)
        out2.flush()
    }

    fun writePong(out: OutputStream, payload: ByteArray) {
        val out2 = DataOutputStream(out)
        out2.writeByte(0x8A)                 // FIN + pong
        writeLength(out2, payload.size)
        out2.write(payload)
        out2.flush()
    }

    /** Reads one frame from the stream. Returns null on graceful close. */
    fun readTextFrame(input: InputStream): Frame? {
        val fin = input.read()
        if (fin < 0) return null
        val opcode = fin and 0x0F
        if (opcode == 0x8) return null // close

        val lenByte = input.read()
        var len = lenByte and 0x7F
        var masked = (lenByte and 0x80) != 0

        if (len == 126) {
            // 2 bytes extended length
            val b1 = input.read(); val b2 = input.read()
            len = ((b1 and 0xFF) shl 8) or (b2 and 0xFF)
        } else if (len == 127) {
            // 8 bytes — read into a long but cap at Int
            val dis = DataInputStream(input)
            dis.skipBytes(4) // high 32
            len = dis.readInt()
        }
        val mask = if (masked) ByteArray(4) else null
        if (masked) {
            for (i in 0 until 4) mask!![i] = input.read().toByte()
        }
        val payload = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(payload, read, len - read)
            if (n < 0) return null
            read += n
        }
        if (masked) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask!![i % 4].toInt()).toByte()
        }
        if (opcode == 0x9) return Frame.Ping(payload)   // ping; caller may pong
        if (opcode != 0x1 && opcode != 0x0) return Frame.Other
        return Frame.Text(String(payload, Charsets.UTF_8))
    }

    private fun writeLength(out: DataOutputStream, len: Int) {
        if (len < 126) {
            out.writeByte(len)
        } else if (len <= 0xFFFF) {
            out.writeByte(126)
            out.writeByte((len shr 8) and 0xFF)
            out.writeByte(len and 0xFF)
        } else {
            out.writeByte(127)
            out.writeLong(len.toLong())
        }
    }

    sealed class Frame {
        data class Text(val text: String) : Frame()
        data class Ping(val payload: ByteArray) : Frame()
        data object Other : Frame()
    }
}