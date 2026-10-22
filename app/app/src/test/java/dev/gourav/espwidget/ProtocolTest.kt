package dev.gourav.espwidget

import dev.gourav.espwidget.protocol.FragmentAssembler
import dev.gourav.espwidget.protocol.LayoutPayload
import dev.gourav.espwidget.protocol.Msg
import dev.gourav.espwidget.protocol.PageConfig
import dev.gourav.espwidget.protocol.SettingsPayload
import dev.gourav.espwidget.protocol.SlotConfig
import dev.gourav.espwidget.protocol.StockPayload
import dev.gourav.espwidget.protocol.TimePayload
import dev.gourav.espwidget.protocol.WeatherPayload
import dev.gourav.espwidget.protocol.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val vectors by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("vectors.json")
            ?: error("vectors.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    @Test
    fun crcReferenceMatchesCcitt() {
        val expected = vectors["crc"]!!.jsonObject["value"]!!.jsonPrimitive.content.toInt()
        assertEquals(expected, Wire.crc16("123456789".toByteArray()))
        assertEquals(0x29B1, Wire.crc16("123456789".toByteArray()))
    }

    @Test
    fun decodesEveryKnownFrame() {
        vectors["frames"]!!.jsonArray.forEach { element ->
            val frame = element.jsonObject
            val bytes = frame["hex"]!!.jsonPrimitive.content.hex()
            val decoded = Wire.decode(bytes)
            assertNotNull("failed ${frame["name"]}", decoded)
            assertEquals(frame["type"]!!.jsonPrimitive.content.toInt(), decoded!!.type)
            assertEquals(frame["seq"]!!.jsonPrimitive.content.toInt(), decoded.seq)
            assertEquals(frame["payload"]!!.jsonPrimitive.content, decoded.payload.hexString())
        }
    }

    @Test
    fun timeEncodingMatchesSharedVector() {
        val payload = TimePayload(epoch = 0x66F0A2B0L, tzOffsetMinutes = 330, dst = false).encode()
        val frame = Wire.encode(Msg.TIME, 66, payload)
        assertEquals(
            "455702100042000800b0a2f0664a0100005f3e",
            frame.hexString(),
        )
    }

    @Test
    fun fragmentationMatchesSharedVector() {
        val shared = vectors["fragmentation"]!!.jsonObject
        val frame = shared["frame"]!!.jsonPrimitive.content.hex()
        val chunk = shared["chunk"]!!.jsonPrimitive.content.toInt()
        val id = shared["frame_id"]!!.jsonPrimitive.content.toInt()
        val chunks = Wire.fragment(frame, chunk, id)
        val expected = shared["chunks"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected.size, chunks.size)
        expected.zip(chunks).forEach { (want, got) -> assertEquals(want, got.hexString()) }
    }

    @Test
    fun reassemblesOutOfOrderAndIgnoresDuplicates() {
        val shared = vectors["fragmentation"]!!.jsonObject
        val frame = shared["frame"]!!.jsonPrimitive.content.hex()
        val chunk = shared["chunk"]!!.jsonPrimitive.content.toInt()
        val chunks = Wire.fragment(frame, chunk, 9)
        val assembler = FragmentAssembler()
        assertNull(assembler.feed(chunks[0]))
        assertNull(assembler.feed(chunks[2]))
        assertNull(assembler.feed(chunks[2]))
        assertNull(assembler.feed(chunks[1]))
        val result = assembler.feed(chunks[3])
        assertNotNull(result)
        assertEquals(frame.hexString(), result!!.hexString())
        assertNull(assembler.feed(chunks[1]))
    }

    @Test
    fun rejectsCorruptAndTruncatedFrames() {
        val frame = Wire.encode(Msg.PING, 0x1234)
        assertNotNull(Wire.decode(frame))
        val corrupt = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(Wire.decode(corrupt))
        assertNull(Wire.decode(frame.copyOf(frame.size - 1)))
        assertNull(Wire.decode(ByteArray(4)))
        assertFalse(frame.isEmpty())
    }

    @Test
    fun layoutRoundTripsAtWireSize() {
        val payload = LayoutPayload(
            listOf(
                PageConfig(0, listOf(SlotConfig(0, 0, 0), SlotConfig(1, 0, 2), SlotConfig(2, 0, 1), SlotConfig(255))),
                PageConfig(1, listOf(SlotConfig(3, 5, 4), SlotConfig(4, 0, 5), SlotConfig(8, 2, 3), SlotConfig(SlotConfig.EMPTY))),
            ),
            version = 7,
        ).encode()
        assertEquals(89, payload.size)
        val decoded = LayoutPayload.decode(payload)
        assertNotNull(decoded)
        assertEquals(7, decoded!!.version)
        assertEquals(2, decoded.pages.size)
        assertEquals(3, decoded.pages[1].slots[0].type)
        assertEquals(5, decoded.pages[1].slots[0].option)
        assertEquals(255, decoded.pages[1].slots[3].type)
    }

    @Test
    fun weatherPayloadUsesPackedLayout() {
        val payload = WeatherPayload(
            tempC10 = 287,
            hiC10 = 312,
            loC10 = 265,
            code = 3,
            humidity = 78,
            precip = 40,
            isDay = true,
            updated = 1_700_000_000,
            city = "Mumbai",
            trend = (0 until 12).map { 280 + it },
        ).encode()
        assertEquals(58, payload.size)
        val decoded = WeatherPayload.decode(payload)
        assertEquals(287, decoded!!.tempC10)
        assertEquals("Mumbai", decoded.city)
        assertEquals(40, decoded.precip)
        assertEquals(12, decoded.trend.size)
        assertEquals(291, decoded.trend.last())
        assertTrue(decoded.isDay)
    }

    @Test
    fun stockPayloadCarriesFullChart() {
        val payload = StockPayload(
            symbol = "AAPL",
            priceCents = 23_456,
            changeBp = 125,
            spark = (0 until 24).map { it * 10 - 100 },
            currency = 1,
            market = 1,
            updated = 1_700_000_000,
            index = 2,
        ).encode()
        assertEquals(76, payload.size)
        val decoded = StockPayload.decode(payload)
        assertEquals("AAPL", decoded!!.symbol)
        assertEquals(24, decoded.spark.size)
        assertEquals(130, decoded.spark.last())
        assertEquals(2, decoded.index)
        assertEquals(23_456, decoded.priceCents)
    }

    @Test
    fun settingsFlagsAndSize() {
        val payload = SettingsPayload(
            brightness = 180,
            sleepSec = 60,
            rotation = 3,
            twelveHour = true,
            celsius = true,
            wifiEnabled = true,
            showSeconds = false,
            currentPage = 2,
        ).encode()
        assertEquals(8, payload.size)
        assertEquals(0x07, payload[3].toInt())
        assertEquals(2, payload[4].toInt())
    }

    private fun String.hex(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            val hi = Character.digit(this[index * 2], 16)
            val lo = Character.digit(this[index * 2 + 1], 16)
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun ByteArray.hexString(): String = joinToString("") { "%02x".format(it) }
}
