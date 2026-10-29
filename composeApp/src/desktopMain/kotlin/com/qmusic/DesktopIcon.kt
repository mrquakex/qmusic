package com.qmusic

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO

object DesktopIcon {

    private val ICON_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)

    /** Returns multi-resolution AWT images for the Windows title bar, taskbar, and Alt+Tab switcher. */
    fun createWindowIcons(): List<BufferedImage> = ICON_SIZES.map { renderIcon(it) }

    /** Renders the official QMusic squircle icon at [size] x [size] pixels. */
    fun renderIcon(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pad = size * 0.04
        val boxSize = size - pad * 2
        val corner = boxSize * 0.24
        val squircle = RoundRectangle2D.Double(pad, pad, boxSize, boxSize, corner, corner)

        // Subtle dark luxury surface gradient (#15151C -> #08080A) matching QMusic's brand theme
        g.paint = GradientPaint(
            0f,
            pad.toFloat(),
            Color(0x16, 0x16, 0x1E),
            0f,
            (pad + boxSize).toFloat(),
            Color(0x07, 0x07, 0x09),
        )
        g.fill(squircle)

        // Subtle inner rim highlight so the dark icon pops on dark Windows 11 taskbars and wallpapers
        val strokeW = (size * 0.022f).coerceAtLeast(1f)
        g.stroke = BasicStroke(strokeW)
        g.color = Color(255, 255, 255, 36)
        g.draw(
            RoundRectangle2D.Double(
                pad + strokeW * 0.5,
                pad + strokeW * 0.5,
                boxSize - strokeW,
                boxSize - strokeW,
                corner,
                corner,
            ),
        )

        // Transform 512x512 QMusic glyph into the icon safe zone
        val glyphScale = (size / 512.0) * 0.76
        val tx = AffineTransform().apply {
            translate(size / 2.0, size / 2.0)
            scale(glyphScale, glyphScale)
            translate(-256.0, -256.0)
        }

        g.color = Color.WHITE
        g.fill(tx.createTransformedShape(buildQGlyphPath()))
        g.fill(tx.createTransformedShape(RoundRectangle2D.Double(206.0, 224.0, 16.0, 48.0, 16.0, 16.0)))
        g.fill(tx.createTransformedShape(RoundRectangle2D.Double(238.0, 208.0, 16.0, 80.0, 16.0, 16.0)))

        g.dispose()
        return img
    }

    /** Writes a valid multi-resolution Windows `.ico` file containing 16..256px PNG frames. */
    fun writeIcoFile(target: File) {
        target.parentFile?.mkdirs()
        val pngFrames = ICON_SIZES.map { sz ->
            val img = renderIcon(sz)
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "png", baos)
            sz to baos.toByteArray()
        }

        FileOutputStream(target).use { fos ->
            val out = DataOutputStream(fos)
            // ICONDIR header (6 bytes, little-endian)
            out.writeShortLE(0) // Reserved
            out.writeShortLE(1) // Type = 1 (ICO)
            out.writeShortLE(pngFrames.size) // Count

            var offset = 6 + pngFrames.size * 16
            for ((sz, bytes) in pngFrames) {
                out.writeByte(if (sz >= 256) 0 else sz) // Width (0 means 256)
                out.writeByte(if (sz >= 256) 0 else sz) // Height (0 means 256)
                out.writeByte(0) // Color palette count
                out.writeByte(0) // Reserved
                out.writeShortLE(1) // Color planes
                out.writeShortLE(32) // Bits per pixel
                out.writeIntLE(bytes.size) // Image data size
                out.writeIntLE(offset) // Offset in file
                offset += bytes.size
            }

            for ((_, bytes) in pngFrames) {
                out.write(bytes)
            }
            out.flush()
        }
    }

    private fun buildQGlyphPath(): Path2D.Double = Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
        moveTo(232.0, 128.0)
        curveTo(165.7, 128.0, 112.0, 181.7, 112.0, 248.0)
        curveTo(112.0, 314.3, 165.7, 368.0, 232.0, 368.0)
        curveTo(261.2, 368.0, 288.0, 357.5, 308.8, 340.1)
        lineTo(308.8, 404.0)
        curveTo(308.8, 417.3, 319.5, 428.0, 332.8, 428.0)
        curveTo(346.1, 428.0, 356.8, 417.3, 356.8, 404.0)
        lineTo(356.8, 160.0)
        curveTo(356.8, 160.0, 398.0, 168.0, 408.0, 212.0)
        curveTo(410.5, 223.0, 422.0, 228.0, 432.0, 224.0)
        curveTo(442.0, 220.0, 446.0, 208.0, 443.0, 196.0)
        curveTo(428.0, 136.0, 364.0, 116.0, 332.8, 116.0)
        curveTo(319.5, 116.0, 308.8, 126.7, 308.8, 140.0)
        lineTo(308.8, 155.9)
        curveTo(288.0, 138.5, 261.2, 128.0, 232.0, 128.0)
        closePath()

        moveTo(232.0, 320.0)
        curveTo(192.2, 320.0, 160.0, 287.8, 160.0, 248.0)
        curveTo(160.0, 208.2, 192.2, 176.0, 232.0, 176.0)
        curveTo(271.8, 176.0, 304.0, 208.2, 304.0, 248.0)
        curveTo(304.0, 287.8, 271.8, 320.0, 232.0, 320.0)
        closePath()
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
        writeByte((v ushr 16) and 0xFF)
        writeByte((v ushr 24) and 0xFF)
    }
}
