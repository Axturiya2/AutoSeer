package com.autoseer.scripts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** 精靈編號隱藏標籤（PNG tEXt）讀寫＋檔名退路——純位元組，不需裝置。 */
class PetIdTagTest {

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** 最小 PNG：簽章 + IHDR(1×1) + [extra] 區塊 + IDAT + IEND，CRC 皆正確。 */
    private fun png(vararg extra: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(PetIdTag.chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0)))
        extra.forEach { out.write(it) }
        out.write(PetIdTag.chunk("IDAT", byteArrayOf(1, 2, 3, 4)))
        out.write(PetIdTag.chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun text(key: String, value: String) =
        PetIdTag.chunk("tEXt", (key + "\u0000" + value).toByteArray(Charsets.ISO_8859_1))

    /** 逐區塊驗 CRC，回傳區塊型別序列。 */
    private fun chunkTypes(b: ByteArray): List<String> {
        val types = ArrayList<String>()
        var off = 8
        while (off < b.size) {
            val len = ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
            val type = String(b, off + 4, 4, Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(b, off + 4, 4 + len) }.value
            val stored = ((b[off + 8 + len].toLong() and 0xFF) shl 24) or ((b[off + 9 + len].toLong() and 0xFF) shl 16) or
                ((b[off + 10 + len].toLong() and 0xFF) shl 8) or (b[off + 11 + len].toLong() and 0xFF)
            assertEquals("CRC of $type", crc, stored)
            types += type
            off += 12 + len
        }
        return types
    }

    @Test fun embedThenReadRoundTrips() {
        val tagged = PetIdTag.embed(png(), "1234")
        assertEquals("1234", PetIdTag.read(tagged))
    }

    @Test fun tagSitsRightAfterIhdrWithValidCrcs() {
        val tagged = PetIdTag.embed(png(), "1234")
        assertEquals(listOf("IHDR", "tEXt", "IDAT", "IEND"), chunkTypes(tagged))
    }

    @Test fun embedTwiceReplacesInsteadOfDuplicating() {
        val twice = PetIdTag.embed(PetIdTag.embed(png(), "1111"), "2222")
        assertEquals("2222", PetIdTag.read(twice))
        assertEquals(1, chunkTypes(twice).count { it == "tEXt" })
    }

    @Test fun otherTextChunksAreKeptAndIgnored() {
        val src = png(text("Software", "Skia"))
        val tagged = PetIdTag.embed(src, "42")
        assertEquals("42", PetIdTag.read(tagged))
        assertEquals(listOf("IHDR", "tEXt", "tEXt", "IDAT", "IEND"), chunkTypes(tagged))
        assertNull(PetIdTag.read(src))
    }

    @Test fun readWorksOnTruncatedHead() {
        val tagged = PetIdTag.embed(png(), "98765")
        assertEquals("98765", PetIdTag.read(tagged.copyOf(70)))
    }

    @Test fun readStopsAtIdat() {
        // 標籤若出現在 IDAT 之後（非本工具寫的），不讀——保證只讀檔頭就夠。
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(PetIdTag.chunk("IHDR", ByteArray(13)))
        out.write(PetIdTag.chunk("IDAT", byteArrayOf(1)))
        out.write(text(PetIdTag.KEY, "1234"))
        out.write(PetIdTag.chunk("IEND", ByteArray(0)))
        assertNull(PetIdTag.read(out.toByteArray()))
    }

    @Test fun readWithoutTagOrNonPngIsNull() {
        assertNull(PetIdTag.read(png()))
        assertNull(PetIdTag.read("not a png at all".toByteArray()))
        assertNull(PetIdTag.read(ByteArray(0)))
        assertNull(PetIdTag.read(signature))
    }

    @Test fun readRejectsNonNumericTagValue() {
        assertNull(PetIdTag.read(png(text(PetIdTag.KEY, "12a"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun embedRejectsInvalidId() { PetIdTag.embed(png(), "12a") }

    @Test(expected = IllegalArgumentException::class)
    fun embedRejectsNonPng() { PetIdTag.embed("nope".toByteArray(), "1") }

    @Test fun embedKeepsOriginalBytesOtherwise() {
        val src = png()
        val tagged = PetIdTag.embed(src, "7")
        val tag = text(PetIdTag.KEY, "7")
        val ihdrEnd = 8 + 25
        assertArrayEquals(src.copyOfRange(0, ihdrEnd), tagged.copyOfRange(0, ihdrEnd))
        assertArrayEquals(src.copyOfRange(ihdrEnd, src.size), tagged.copyOfRange(ihdrEnd + tag.size, tagged.size))
    }

    @Test fun isValidIdAcceptsAsciiDigitsOnly() {
        assertTrue(PetIdTag.isValidId("0"))
        assertTrue(PetIdTag.isValidId("1234"))
        assertFalse(PetIdTag.isValidId(""))
        assertFalse(PetIdTag.isValidId(null))
        assertFalse(PetIdTag.isValidId("12 3"))
        assertFalse(PetIdTag.isValidId("１２３"))   // 全形數字不收
    }

    @Test fun idFromFileNameLegacyCases() {
        assertEquals("1234", PetIdTag.idFromFileName("01_厄孽提亞_1234.png"))
        assertEquals("1234", PetIdTag.idFromFileName("01_厄孽提亞_1234"))
        assertEquals("1234", PetIdTag.idFromFileName("厄孽提亞_1234.PNG"))
        assertEquals("1234", PetIdTag.idFromFileName("厄孽提亞_1234 (1).png"))
        assertEquals("1234", PetIdTag.idFromFileName("1234.png"))
        assertNull(PetIdTag.idFromFileName("厄孽提亞.png"))
        assertNull(PetIdTag.idFromFileName("01_厄孽提亞.png"))
        assertNull(PetIdTag.idFromFileName("01_厄孽提亞_12a.png"))
        assertNull(PetIdTag.idFromFileName(""))
        // 新的預設檔名不含底線，不會被誤判成編號
        assertNull(PetIdTag.idFromFileName("pet-20261006-120000-1.png"))
    }

    @Test fun resolvePrefersTagOverFileName() {
        assertEquals("555", PetIdTag.resolve("555", "01_x_1234.png"))
        assertEquals("1234", PetIdTag.resolve(null, "01_x_1234.png"))
        assertEquals("1234", PetIdTag.resolve("bad", "01_x_1234.png"))
        assertNull(PetIdTag.resolve(null, "厄孽提亞.png"))
    }
}
