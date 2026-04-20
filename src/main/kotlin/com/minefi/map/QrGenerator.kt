package com.minefi.map

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapPalette

@Suppress("DEPRECATION")
object QrGenerator {

    fun generateQrPixels(content: String, size: Int): Array<BooleanArray> =
        generateQrPixels(content, size, ErrorCorrectionLevel.M)

    fun generateQrPixels(content: String, size: Int, ecl: ErrorCorrectionLevel): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ecl,
            EncodeHintType.MARGIN to 0,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        return Array(size) { y ->
            BooleanArray(size) { x -> matrix.get(x, y) }
        }
    }

    fun renderBrandedQr(canvas: MapCanvas, content: String) {
        val frame = 4
        val qrSize = 128 - 2 * frame

        val pixels = generateQrPixels(content, qrSize, ErrorCorrectionLevel.H)

        val brown = MapPalette.matchColor(74, 56, 40)
        val trim = MapPalette.matchColor(139, 105, 78)
        val gold = MapPalette.matchColor(212, 175, 55)

        for (y in 0 until 128) {
            for (x in 0 until 128) {
                canvas.setPixel(x, y, MapPalette.WHITE)
            }
        }

        for (i in 0 until 3) {
            for (p in 0 until 128) {
                canvas.setPixel(p, i, brown)
                canvas.setPixel(p, 127 - i, brown)
                canvas.setPixel(i, p, brown)
                canvas.setPixel(127 - i, p, brown)
            }
        }
        for (p in 3 until 125) {
            canvas.setPixel(p, 3, trim)
            canvas.setPixel(p, 124, trim)
            canvas.setPixel(3, p, trim)
            canvas.setPixel(124, p, trim)
        }

        for (y in 0 until qrSize) {
            for (x in 0 until qrSize) {
                val color = if (pixels[y][x]) MapPalette.DARK_GRAY else MapPalette.WHITE
                canvas.setPixel(x + frame, y + frame, color)
            }
        }

        drawLogoBadge(canvas, 64, 64, 11, gold, brown)
    }

    private fun drawLogoBadge(canvas: MapCanvas, cx: Int, cy: Int, radius: Int, frame: Byte, ink: Byte) {
        val r2 = radius * radius
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= r2) {
                    canvas.setPixel(cx + dx, cy + dy, MapPalette.WHITE)
                }
            }
        }
        val rInner2 = (radius - 1) * (radius - 1)
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val d = dx * dx + dy * dy
                if (d in (rInner2 + 1)..r2) {
                    canvas.setPixel(cx + dx, cy + dy, frame)
                }
            }
        }
        drawM(canvas, cx - 5, cy - 5, ink)
    }

    private fun drawM(canvas: MapCanvas, x: Int, y: Int, color: Byte) {
        val glyph = arrayOf(
            "## ## ## ##",
            "## ## ## ##",
            "## ### ####",
            "## ## # ###",
            "## ##   ###",
            "## ##   ###",
            "## ##   ###",
            "## ##   ###",
            "## ##   ###",
            "## ##   ###",
            "## ##   ###",
        )
        for ((row, line) in glyph.withIndex()) {
            for ((col, ch) in line.withIndex()) {
                if (ch == '#') canvas.setPixel(x + col, y + row, color)
            }
        }
    }
}
