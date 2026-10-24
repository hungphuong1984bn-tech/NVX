package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.protocol.ByteViews
import vn.nvxconnect.protocol.ByteViews.Endian

/** Phép biến đổi số học trên byte TỔNG HỢP; không có ý nghĩa giao thức. */
class ByteViewsTest {
    private val b = byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x80.toByte(), 0x01)

    @Test fun parseHexAcceptsCommonFormsAndRejectsGarbage() {
        val want = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x01)
        listOf("AA BB 01", "aa-bb-01", "0xAA,0xBB,0x01", "AABB01", " aa:bb:01 ").forEach { assertArrayEquals(it, want, ByteViews.parseHex(it)) }
        listOf("", "A", "AAB", "GG", "AA BX").forEach { assertNull(it, ByteViews.parseHex(it)) }
    }

    @Test fun textViews() {
        assertEquals("12 34 FF 80 01", ByteViews.hex(b)); assertEquals("18 52 255 128 1", ByteViews.decimal(b))
        assertEquals("00010010 00110100", ByteViews.binary(b.copyOf(2)))
        assertEquals(listOf(0, 7), ByteViews.setBits(0x81)); assertTrue(ByteViews.setBits(0).isEmpty())
    }

    @Test fun endianDiffersAndWidthsAreBounded() {
        assertEquals(0x1234L, ByteViews.readUnsigned(b, 0, 2, Endian.BIG)); assertEquals(0x3412L, ByteViews.readUnsigned(b, 0, 2, Endian.LITTLE))
        assertEquals(0x1234FF80L, ByteViews.readUnsigned(b, 0, 4, Endian.BIG)); assertEquals(0x80FF3412L, ByteViews.readUnsigned(b, 0, 4, Endian.LITTLE))
        assertNull(ByteViews.readUnsigned(b, 4, 2, Endian.BIG)); assertNull(ByteViews.readUnsigned(b, -1, 1, Endian.BIG))
        assertNull(ByteViews.readUnsigned(b, 0, 5, Endian.BIG)); assertNull(ByteViews.readUnsigned(b, 0, 0, Endian.BIG))
    }

    @Test fun signedUsesTwosComplement() {
        assertEquals(-1L, ByteViews.readSigned(b, 2, 1, Endian.BIG)); assertEquals(-128L, ByteViews.readSigned(b, 3, 1, Endian.BIG))
        assertEquals(-129L, ByteViews.readSigned(byteArrayOf(0xFF.toByte(), 0x7F), 0, 2, Endian.BIG))
        assertEquals(0x7FFFFFFFL, ByteViews.readSigned(byteArrayOf(0x7F, -1, -1, -1), 0, 4, Endian.BIG))
        assertEquals(-2147483648L, ByteViews.readSigned(byteArrayOf(0x80.toByte(), 0, 0, 0), 0, 4, Endian.BIG))
    }

    @Test fun interpretationsListsOnlyWhatFits() {
        assertEquals(5, ByteViews.interpretations(b, 0).size)          // u8 + 2×16 + 2×32
        assertEquals(3, ByteViews.interpretations(b, 3).size)          // u8 + 2×16 (còn 2 byte)
        assertEquals(1, ByteViews.interpretations(b, 4).size)
        assertTrue(ByteViews.interpretations(b, 5).isEmpty())
        assertEquals("u8/i8", ByteViews.interpretations(b, 0)[0].label)
    }
}
