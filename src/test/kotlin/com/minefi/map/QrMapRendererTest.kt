package com.minefi.map

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QrMapRendererTest {

    @Test
    fun `generateQrPixels produces 128x128 boolean grid`() {
        val uri = "wc:abc123@2?relay-protocol=irn&symKey=deadbeef"
        val pixels = QrGenerator.generateQrPixels(uri, 128)
        assertEquals(128, pixels.size)
        assertEquals(128, pixels[0].size)
    }

    @Test
    fun `generateQrPixels has some true and some false values`() {
        val uri = "wc:test@2?relay-protocol=irn&symKey=1234"
        val pixels = QrGenerator.generateQrPixels(uri, 128)
        val trueCount = pixels.sumOf { row -> row.count { it } }
        val falseCount = 128 * 128 - trueCount
        assertTrue(trueCount > 100, "QR code should have dark modules")
        assertTrue(falseCount > 100, "QR code should have light modules")
    }

    @Test
    fun `different URIs produce different QR codes`() {
        val pixels1 = QrGenerator.generateQrPixels("wc:aaa@2?symKey=111", 128)
        val pixels2 = QrGenerator.generateQrPixels("wc:bbb@2?symKey=222", 128)
        var diffCount = 0
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                if (pixels1[y][x] != pixels2[y][x]) diffCount++
            }
        }
        assertTrue(diffCount > 50, "Different URIs should produce different patterns")
    }
}
