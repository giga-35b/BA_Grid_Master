package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class InventoryCutoutTest {
    @Test fun extractsTheSameDarkDetailsAcrossBackgroundThemes() {
        val backgrounds = listOf(0x4773bb, 0xb89f45, 0x489a65, 0x995f98, 0xaeb8bd, 0x252a31)
        var reference: BooleanArray? = null
        for (background in backgrounds) {
            val patch = syntheticPatch(background)
            val mask = FragmentPixels.templateMask(patch)
            if (reference == null) reference = mask else assertArrayEquals("theme=${background.toString(16)}", reference, mask)
            assertTrue("dark marking", mask[30 * patch.width + 35])
            assertTrue("thin connected tip", mask[21 * patch.width + 61])
            assertFalse("actual background hole", mask[27 * patch.width + 48])
            assertFalse("card border", mask[patch.width / 2])
            assertFalse("detached noise", mask[12 * patch.width + 14])
            assertFalse("background grid", mask[10 * patch.width + 20])
        }
    }

    @Test fun unreliablePerimeterDoesNotInventForeground() {
        val patch = syntheticPatch(0x4773bb)
        val bytes = patch.rgba8888.copyOf()
        for (i in 0 until patch.width * patch.height) {
            val color = if (i % patch.width < patch.width / 2) 0x205020 else 0xe8b0e8
            for (c in 0..2) bytes[i * 4 + c] = (color shr (16 - c * 8)).toByte()
        }
        assertFalse(FragmentPixels.templateMask(patch.copy(rgba8888 = bytes)).any { it })
    }

    @Test fun previewPreservesDarkPixelsAndInterpolatedAlpha() {
        val (_, detection) = LocalTemplateMatcherTest.load("app/src/test/resources/shell-performance-2400.jpg")
        val card = detection.itemCards.first().copy(spriteTemplate = syntheticPatch(0x4773bb))
        val preview = LocalTemplateMatcher().preparePreview(card)
        val patch = checkNotNull(preview.aligned)
        assertEquals(192, patch.width)
        assertEquals(96, patch.height)
        var dark = 0; var translucent = 0
        for (i in 0 until patch.width * patch.height) {
            val p = i * 4
            val r = patch.rgba8888[p].toInt() and 255
            val g = patch.rgba8888[p + 1].toInt() and 255
            val b = patch.rgba8888[p + 2].toInt() and 255
            val alpha = patch.rgba8888[p + 3].toInt() and 255
            if (alpha == 255 && r < 125 && !FragmentPixels.foreground(r, g, b)) dark++
            if (alpha in 1..254) translucent++
        }
        assertTrue("dark artwork must remain opaque", dark > 100)
        assertTrue("rotation should retain edge coverage", translucent > 10)
    }

    @Test fun realSlugDarkMarkingIsRetainedWithoutChangingBoardPresenceRules() {
        val (_, detection) = LocalTemplateMatcherTest.load("app/src/test/resources/shell-performance-2400.jpg")
        val card = detection.itemCards.first()
        val x = 400 - card.spriteRegion.left
        val y = 938 - card.spriteRegion.top
        val i = y * card.spriteTemplate.width + x
        val bytes = card.spriteTemplate.rgba8888
        assertFalse(FragmentPixels.foreground(bytes[i * 4].toInt() and 255,
            bytes[i * 4 + 1].toInt() and 255, bytes[i * 4 + 2].toInt() and 255))
        assertTrue("dark detail belongs to the inventory sprite", FragmentPixels.templateMask(card.spriteTemplate)[i])
    }

    private fun syntheticPatch(background: Int): RgbaPatch {
        val width = 80; val height = 56
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val grid = (0..2).fold(0) { rgb, c -> rgb or ((((background shr (16 - c * 8)) and 255) - 16).coerceAtLeast(0) shl (16 - c * 8)) }
            val color = when {
                y == 0 -> 0xffffff
                x in 18..60 && y in 20..39 -> if (x in 30..39 && y in 27..34) 0x71606a else 0xeee7dc
                x in 61..66 && y == 21 -> 0x554756
                x == 14 && y == 12 -> 0xeeeeee
                x % 20 == 0 || y % 18 == 0 -> grid
                else -> background
            }
            val actual = if (x in 46..50 && y in 25..29) background else color
            for (c in 0..2) bytes[(y * width + x) * 4 + c] = (actual shr (16 - c * 8)).toByte()
            bytes[(y * width + x) * 4 + 3] = 255.toByte()
        }
        return RgbaPatch(width, height, bytes)
    }

    @Test fun exportInventoryCutoutsForVisualReview() {
        val (_, detection) = LocalTemplateMatcherTest.load("app/src/test/resources/shell-performance-2400.jpg")
        export(detection.itemCards, System.getenv("CUTOUT_REPORT_LABEL") ?: "current")
    }

    @Test fun exportResizedCutoutsAndOtherEventForVisualReview() {
        val cases = listOf("dataset/regressions/gallery-20260830-104931.jpg" to 1280,
            "dataset/202608292110_1st_round/Screenshot_2026-08-29-21-10-18-410_com.YostarJP..jpg" to 1920)
        val cards = cases.map { (path, width) ->
            val original = LocalTemplateMatcherTest.readFrame(path)
            val frame = if (width == 1920) LocalTemplateMatcherTest.resizedJpeg(original, width) else {
                val height = original.height * width / original.width
                val bytes = ByteArray(width * height * 4)
                for (y in 0 until height) for (x in 0 until width) {
                    val source = ((y * original.height / height) * original.width + x * original.width / width) * 4
                    original.rgba8888.copyInto(bytes, (y * width + x) * 4, source, source + 4)
                }
                RgbaFrame(width, height, width * 4, bytes, 0)
            }
            checkNotNull(GameVisionDetector().analyze(frame)).itemCards.first()
        }
        export(cards, "resized")
        val frame = LocalTemplateMatcherTest.readFrame("app/src/test/resources/inventory-multicolor-event.png")
        val detected = GameVisionDetector().analyze(frame)
        println("OTHER_EVENT board=${detected?.geometry?.board} states=${detected?.boardCells?.groupingBy { it.state }?.eachCount()}")
        // Labelled inventory crops, independent of the unrelated board-locator limitations.
        val regions = listOf(ScreenRegion(201, 534, 313, 611), ScreenRegion(340, 534, 457, 611), ScreenRegion(485, 534, 594, 611))
        val others = regions.mapIndexed { index, region ->
            val bytes = ByteArray(region.width * region.height * 4)
            for (y in 0 until region.height) {
                val start = (region.top + y) * frame.rowStrideBytes + region.left * 4
                frame.rgba8888.copyInto(bytes, y * region.width * 4, start, start + region.width * 4)
            }
            val (rows, columns) = listOf(2 to 3, 1 to 3, 1 to 2)[index]
            cards.first().copy(index = index, shape = ItemShape(rows, columns,
                (0 until rows).flatMap { r -> (0 until columns).map { c -> ShapeCell(r, c) } }.toSet()),
                spriteTemplate = RgbaPatch(region.width, region.height, bytes))
        }
        for (card in others) {
            val count = FragmentPixels.templateMask(card.spriteTemplate).count { it }
            val area = card.spriteTemplate.width * card.spriteTemplate.height
            assertTrue("other event card${card.index}: $count / $area", count in area / 20..area * 3 / 4)
        }
        export(others, "other-event")
    }

    private fun export(cards: List<ItemCardRecognition>, label: String) {
        val image = BufferedImage(1200, 70 + cards.size * 240, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        fun draw(patch: RgbaPatch, x: Int, y: Int, width: Int, height: Int) {
            val bitmap = BufferedImage(patch.width, patch.height, BufferedImage.TYPE_INT_ARGB)
            for (i in 0 until patch.width * patch.height) {
                val p = i * 4
                bitmap.setRGB(i % patch.width, i / patch.width,
                    ((patch.rgba8888[p + 3].toInt() and 255) shl 24) or
                        ((patch.rgba8888[p].toInt() and 255) shl 16) or
                        ((patch.rgba8888[p + 1].toInt() and 255) shl 8) or (patch.rgba8888[p + 2].toInt() and 255))
            }
            g.drawImage(bitmap, x, y, width, height, null)
            bitmap.flush()
        }
        try {
            g.color = Color(38, 55, 70); g.fillRect(0, 0, image.width, image.height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color.WHITE
            g.drawString("Original inventory crop", 10, 20)
            g.drawString("Extracted sprite (same source resolution)", 360, 20)
            g.drawString("Rotated preview (matching stays 32px / cell)", 760, 20)
            for ((row, card) in cards.withIndex()) {
                val patch = card.spriteTemplate
                val mask = FragmentPixels.templateMask(patch)
                val bytes = patch.rgba8888.copyOf()
                for (i in mask.indices) bytes[i * 4 + 3] = if (mask[i]) 255.toByte() else 0
                val preview = LocalTemplateMatcher().preparePreview(card)
                val y = 55 + row * 240
                g.color = Color.WHITE
                g.drawString("${itemCode(card.index)}: mask=${mask.count { it }}, angle=${preview.rotationDegrees}", 10, y - 8)
                draw(patch, 10, y, 320, 222)
                draw(patch.copy(rgba8888 = bytes), 365, y, 320, 222)
                preview.aligned?.let { draw(it, 765, y, 420, 420 * it.height / it.width) }
                println("INVENTORY_MASK label=$label card=${card.index} size=${patch.width}x${patch.height} pixels=${mask.count { it }} preview=${preview.aligned?.let { "${it.width}x${it.height}" }} angle=${preview.rotationDegrees}")
            }
        } finally { g.dispose() }
        val output = File("build/reports/vision/inventory-cutout-$label.png")
        checkNotNull(output.parentFile).mkdirs()
        ImageIO.write(image, "png", output); image.flush()
        assertTrue(output.length() > 0)
    }
}
