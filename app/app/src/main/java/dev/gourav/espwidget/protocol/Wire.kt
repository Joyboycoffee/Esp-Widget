package dev.gourav.espwidget.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wire {
    const val MAGIC_0 = 0x45
    const val MAGIC_1 = 0x57
    const val VERSION = 2
    const val HEADER_SIZE = 9
    const val CRC_SIZE = 2
    const val MAX_PAYLOAD = 1024
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD + CRC_SIZE
    const val FRAG_MARKER = 0xF1
    const val FRAG_HEADER = 5
    const val MAX_FRAG_PARTS = 32

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun encode(type: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val out = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        out[0] = MAGIC_0.toByte()
        out[1] = MAGIC_1.toByte()
        out[2] = VERSION.toByte()
        out[3] = type.toByte()
        out[4] = 0
        putU16(out, 5, seq)
        putU16(out, 7, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        val crc = crc16(out, 2, HEADER_SIZE - 2 + payload.size)
        out[out.size - 2] = (crc and 0xFF).toByte()
        out[out.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) return null
        if (bytes[0].toInt() and 0xFF != MAGIC_0 || bytes[1].toInt() and 0xFF != MAGIC_1) return null
        if (bytes[2].toInt() and 0xFF != VERSION) return null
        val length = getU16(bytes, 7)
        if (length > MAX_PAYLOAD || bytes.size != HEADER_SIZE + length + CRC_SIZE) return null
        val expected = getU16(bytes, bytes.size - 2)
        if (crc16(bytes, 2, bytes.size - 4) != expected) return null
        val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
        return Frame(bytes[3].toInt() and 0xFF, getU16(bytes, 5), payload)
    }

    fun fragment(frame: ByteArray, chunkPayload: Int, frameId: Int): List<ByteArray> {
        if (chunkPayload <= 0) return emptyList()
        val count = (frame.size + chunkPayload - 1) / chunkPayload
        if (count > MAX_FRAG_PARTS) return emptyList()
        return (0 until count).map { part ->
            val start = part * chunkPayload
            val end = minOf(frame.size, start + chunkPayload)
            val chunk = ByteArray(FRAG_HEADER + (end - start))
            chunk[0] = FRAG_MARKER.toByte()
            chunk[1] = (frameId and 0xFF).toByte()
            chunk[2] = part.toByte()
            chunk[3] = count.toByte()
            chunk[4] = 0
            frame.copyInto(chunk, FRAG_HEADER, start, end)
            chunk
        }
    }

    fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun getU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

data class Frame(val type: Int, val seq: Int, val payload: ByteArray)

class FragmentAssembler {
    private var id = -1
    private var count = 0
    private var buffer = ByteArray(Wire.MAX_FRAME)
    private var size = 0
    private var mask = 0
    private var received = 0
    private var chunkSize = 0

    fun reset() {
        id = -1; count = 0; size = 0; mask = 0; received = 0; chunkSize = 0
    }

    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.size < Wire.FRAG_HEADER) return null
        if (chunk[0].toInt() and 0xFF != Wire.FRAG_MARKER) return null
        val incomingId = chunk[1].toInt() and 0xFF
        val part = chunk[2].toInt() and 0xFF
        val parts = chunk[3].toInt() and 0xFF
        if (parts == 0 || parts > Wire.MAX_FRAG_PARTS || part >= parts) return null
        if (id != incomingId || count != parts) {
            reset()
            id = incomingId
            count = parts
        }
        val dataLen = chunk.size - Wire.FRAG_HEADER
        if (part == 0) {
            if (dataLen == 0) { reset(); return null }
            chunkSize = dataLen
        } else if (chunkSize == 0) {
            reset()
            return null
        }
        if (mask and (1 shl part) != 0) return null
        val offset = part * chunkSize
        if (offset + dataLen > buffer.size) { reset(); return null }
        chunk.copyInto(buffer, offset, Wire.FRAG_HEADER)
        mask = mask or (1 shl part)
        received++
        if (received == count) {
            size = offset + dataLen
            val frame = buffer.copyOfRange(0, size)
            reset()
            return frame
        }
        return null
    }
}

object Msg {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val TIME = 0x10
    const val WEATHER = 0x11
    const val BATTERY = 0x12
    const val STOCK = 0x13
    const val NOTIFICATION = 0x14
    const val MEDIA = 0x15
    const val CALENDAR = 0x16
    const val STEPS = 0x17
    const val TEXT = 0x18
    const val LOCATION = 0x19
    const val PHONE_BATTERY = 0x1A
    const val LAYOUT = 0x20
    const val SETTINGS = 0x21
    const val WIFI_SCAN = 0x22
    const val WIFI_SCAN_RESULT = 0x23
    const val WIFI_SET = 0x24
    const val WIFI_FORGET = 0x25
    const val PHOTO_BEGIN = 0x26
    const val PHOTO_DATA = 0x27
    const val PHOTO_END = 0x28
    const val PING = 0x30
    const val PONG = 0x31
    const val ACK = 0x32
    const val NACK = 0x33
    const val STATUS_REQ = 0x34
    const val STATUS = 0x35
    const val FACTORY_RESET = 0x36
    const val COMMAND = 0x38
    const val TIME_REQ = 0x37
}
