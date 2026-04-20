package com.minefi.map

import org.bukkit.entity.Player
import org.bukkit.map.*

enum class MapState { QR, CONNECTING, CONNECTED }

@Suppress("DEPRECATION")
class QrMapRenderer(
    private var uri: String,
) : MapRenderer() {

    var state: MapState = MapState.QR
    var walletAddress: String = ""
    private var rendered = false

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        if (rendered && state == MapState.QR) return

        when (state) {
            MapState.QR -> {
                val pixels = QrGenerator.generateQrPixels(uri, 128)
                for (y in 0 until 128) {
                    for (x in 0 until 128) {
                        canvas.setPixel(x, y, if (pixels[y][x]) MapPalette.DARK_GRAY else MapPalette.WHITE)
                    }
                }
                rendered = true
            }
            MapState.CONNECTING -> {
                clearCanvas(canvas)
                val font = MinecraftFont.Font
                canvas.drawText(20, 56, font, "Connecting...")
            }
            MapState.CONNECTED -> {
                clearCanvas(canvas)
                val font = MinecraftFont.Font
                canvas.drawText(10, 50, font, "Connected!")
                val short = walletAddress.take(6) + "..." + walletAddress.takeLast(4)
                canvas.drawText(10, 66, font, short)
            }
        }
    }

    fun updateState(newState: MapState, address: String = "") {
        state = newState
        walletAddress = address
        rendered = false
    }

    private fun clearCanvas(canvas: MapCanvas) {
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                canvas.setPixel(x, y, MapPalette.WHITE)
            }
        }
    }

}
