package vn.nvxconnect

import org.junit.Assert.*
import org.junit.Test
import vn.nvxconnect.pro.HexDiff

class HexDiffTest {
    @Test fun parsesSpacedAndPackedAndPrefixed() {
        assertEquals(listOf(0x6B, 0x0A, 0xFF), HexDiff.parse("6B 0A FF"))
        assertEquals(listOf(0x6B, 0x0A, 0xFF), HexDiff.parse("6b0aff"))
        assertEquals(listOf(0x6B, 0x0A), HexDiff.parse("0x6B,0x0A"))
        assertEquals(listOf(0xAB, 0xCD), HexDiff.parse("ab:cd"))
    }
    @Test fun rejectsGarbageAndOddPackedLength() {
        assertNull(HexDiff.parse("6B ZZ")); assertNull(HexDiff.parse("6B0")); assertNull(HexDiff.parse("123 45"))
    }
    @Test fun emptyParsesToEmpty() { assertEquals(emptyList<Int>(), HexDiff.parse("  ")) }
    @Test fun identicalFrames() {
        val d = HexDiff.diff(listOf(1, 2, 3), listOf(1, 2, 3)); assertTrue(d.identical); assertTrue(d.diffIndices.isEmpty())
    }
    @Test fun highlightsDifferingBytes() {
        val d = HexDiff.diff(listOf(1, 2, 3, 4), listOf(1, 9, 3, 8))
        assertEquals(listOf(1, 3), d.diffIndices); assertFalse(d.identical); assertFalse(d.lengthDiffers); assertEquals(2, d.sameLengthDiffCount)
    }
    @Test fun differentLengthsMarkTrailingBytesAsDifferent() {
        val d = HexDiff.diff(listOf(1, 2), listOf(1, 2, 3, 4))
        assertTrue(d.lengthDiffers); assertEquals(listOf(2, 3), d.diffIndices); assertEquals(0, d.sameLengthDiffCount); assertFalse(d.identical)
    }
    @Test fun formatUppercaseTwoDigits() { assertEquals("0A FF 00", HexDiff.format(listOf(10, 255, 0))) }
}
