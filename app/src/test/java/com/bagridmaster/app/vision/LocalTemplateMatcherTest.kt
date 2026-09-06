package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage
import org.junit.Assert.*
import org.junit.Test

class LocalTemplateMatcherTest {
    @Test fun matchesSeahorseMiddleInReportedGalleryFrame() {
        val (frame, detection) = load("dataset/regressions/gallery-20260830-104931.jpg")
        val item = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards)
            .single { it.phase == BoardObjectPhase.LIT }
        val result = LocalTemplateMatcher().match(frame, detection.geometry.board, detection.boardCells, item, detection.itemCards)
        println("reported gallery match=$result")
        assertTrue("$result", result.accepted)
        assertEquals(setOf(GridCell(0, 1), GridCell(1, 1), GridCell(2, 1)), result.candidates.first().cells)
        assertTrue(result.candidates.first().localLabel(GridCell(1, 1)).contains("中段"))
        val completion = FragmentCompletionPlanner().analyze(frame, detection, detection.itemCards)
        assertEquals(setOf("B1", "B3"), completion.recommendations.map { cellAddress(it.row, it.column) }.toSet())
        assertTrue(completion.recommendations.all { it.strategy.contains("局部贴图") })
        val fallback = FragmentCompletionPlanner().complete(detection.geometry.board, detection.boardCells, detection.itemCards,
            listOf(item), detection.fragmentEdges)
        assertEquals("fallback must also fix the C2/C1/C3 bug", setOf("B1", "B3"), fallback.recommendations.map { cellAddress(it.row, it.column) }.toSet())
        assertEquals(0.0, detection.fragmentEdges.single().right, 0.001)
    }

    @Test fun prealignmentMatchesAllKnownEndAndMiddleSections() {
        val cases = mapOf(
            "21-06-37-762" to setOf("B1", "B2", "B3"),
            "21-07-58-619" to setOf("B4", "C4", "D4", "E4"),
            "21-08-44-066" to setOf("A5", "B5", "C5", "D5"),
            "21-09-05-966" to setOf("E5", "F5", "G5"),
            "21-09-43-626" to setOf("D1", "E1", "D2", "E2", "D3", "E3"),
            "21-10-18-410" to setOf("G2", "G3", "G4"),
            "21-10-58-958" to setOf("F1", "F2", "F3", "F4"),
            "21-11-11-314" to setOf("I1", "I2", "I3"))
        for ((time, expected) in cases) {
            val (frame, detection) = load("dataset/202608292110_1st_round/Screenshot_2026-08-29-${time}_com.YostarJP..jpg")
            for (item in BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards).filter { it.phase == BoardObjectPhase.LIT }) {
                val result = LocalTemplateMatcher().match(frame, detection.geometry.board, detection.boardCells, item, detection.itemCards)
                println("$time ${item.observedCells} match=$result")
                assertEquals("$time best footprint", expected, result.candidates.first().cells.map { cellAddress(it.row, it.column) }.toSet())
                assertTrue("$time $result", result.accepted)
                assertNotNull(result.candidates.first().preRotationDegrees)
                assertTrue(result.candidates.all { it.cells.containsAll(item.observedCells) })
            }
            val completion = FragmentCompletionPlanner().analyze(frame, detection, detection.itemCards)
            val addresses = completion.recommendations.map { cellAddress(it.row, it.column) }.toSet()
            println("$time final completion=$addresses objects=${completion.objects.filter { it.phase == BoardObjectPhase.LIT }}")
            assertTrue("$time invented cells: $addresses", addresses.all { it in expected })
            assertTrue("$time must retain a useful completion", addresses.isNotEmpty())
        }
    }

    @Test fun identicalCandidateTypesDoNotInventCertainty() {
        val (frame, detection) = load("dataset/regressions/gallery-20260830-104931.jpg")
        val item = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards).single()
        val original = detection.itemCards.first()
        val result = LocalTemplateMatcher().match(frame, detection.geometry.board, detection.boardCells,
            item.copy(possibleItemIndices = listOf(0, 1)), listOf(original, original.copy(index = 1)))
        assertFalse(result.accepted)
        assertEquals(0.0, result.margin, 0.000001)
    }

    @Test fun shuffledPixelsWithSameColorsCannotPassSpatialMatch() {
        val (frame, detection) = load("dataset/regressions/gallery-20260830-104931.jpg")
        val item = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards).single()
        val card = detection.itemCards.first()
        val bytes = card.spriteTemplate.rgba8888
        val shuffled = ByteArray(bytes.size)
        val order = (0 until bytes.size / 4).shuffled(kotlin.random.Random(741))
        order.forEachIndexed { destination, source -> bytes.copyInto(shuffled, destination * 4, source * 4, source * 4 + 4) }
        val result = LocalTemplateMatcher().match(frame, detection.geometry.board, detection.boardCells, item,
            listOf(card.copy(spriteTemplate = card.spriteTemplate.copy(rgba8888 = shuffled))))
        assertFalse("same colour histogram is not the same local texture: $result", result.accepted)
    }

    @Test fun partialMatchingWorksAtDifferentScreenshotSizes() {
        val (original, _) = load("dataset/regressions/gallery-20260830-104931.jpg")
        for (width in listOf(1280, 1800)) {
            val height = original.height * width / original.width
            val bytes = ByteArray(width * height * 4)
            for (y in 0 until height) for (x in 0 until width) {
                val source = ((y * original.height / height) * original.width + x * original.width / width) * 4
                original.rgba8888.copyInto(bytes, (y * width + x) * 4, source, source + 4)
            }
            val frame = RgbaFrame(width, height, width * 4, bytes, 0)
            val detection = checkNotNull(GameVisionDetector().analyze(frame))
            val completion = FragmentCompletionPlanner().analyze(frame, detection, detection.itemCards)
            assertEquals("width=$width", setOf("B1", "B3"), completion.recommendations.map { cellAddress(it.row, it.column) }.toSet())
            assertTrue("width=$width", completion.objects.single().localMatch!!.accepted)
        }
    }

    @Test fun endSectionsAndFinishSurviveResizedJpegScreenshots() {
        val cases = mapOf("21-10-18-410" to setOf("G3", "G4"), "21-11-11-314" to setOf("I2", "I3"),
            "21-10-58-958" to setOf("F2", "F3", "F4"), "21-09-05-966" to setOf("E5", "F5"))
        for ((time, expected) in cases) for (width in listOf(1280, 1800, 1920)) {
            val (original, _) = load("dataset/202608292110_1st_round/Screenshot_2026-08-29-${time}_com.YostarJP..jpg")
            val frame = resizedJpeg(original, width)
            val detection = checkNotNull(GameVisionDetector().analyze(frame))
            val completion = FragmentCompletionPlanner().analyze(frame, detection, detection.itemCards)
            val lit = completion.objects.single { it.phase == BoardObjectPhase.LIT }
            println("RESIZED $time width=$width match=${lit.localMatch}")
            assertEquals("$time width=$width", expected, completion.recommendations.map { cellAddress(it.row, it.column) }.toSet())
            assertTrue("$time width=$width ${lit.localMatch}", lit.localMatch?.accepted == true)
            if (time != "21-09-05-966") {
                FinishLabelDetector.detect(detection.itemCards[2].spriteTemplate) { println("FINISH $time width=$width $it") }
                assertTrue("$time width=$width Finish", detection.itemCards[2].isFinished)
            }
        }
    }

    @Test fun mixedSquareAndNonSquareTypesMustNotPretendSquareWasCompared() {
        val (frame, detection) = load("dataset/regressions/gallery-20260830-104931.jpg")
        val item = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards).single()
        val square = detection.itemCards.first().copy(index = 3, shape = ItemShape(2, 2,
            setOf(ShapeCell(0, 0), ShapeCell(0, 1), ShapeCell(1, 0), ShapeCell(1, 1))))
        val result = LocalTemplateMatcher().match(frame, detection.geometry.board, detection.boardCells,
            item.copy(possibleItemIndices = listOf(0, 3)), detection.itemCards + square)
        assertFalse(result.accepted)
        assertTrue(result.evidence.contains("仍可能为正方形"))
    }

    @Test fun confidentMatchesStayWithinGroundTruthAcrossWholeFirstRound() {
        val truth = listOf(setOf("B1", "B2", "B3"), setOf("B4", "C4", "D4", "E4"),
            setOf("A5", "B5", "C5", "D5"), setOf("E5", "F5", "G5"),
            setOf("D1", "E1", "D2", "E2", "D3", "E3"), setOf("G2", "G3", "G4"),
            setOf("F1", "F2", "F3", "F4"), setOf("I1", "I2", "I3"))
        val folder = sequenceOf(File("dataset/202608292110_1st_round"), File("../dataset/202608292110_1st_round")).first { it.exists() }
        val samples = folder.listFiles()!!.filter { it.extension == "jpg" && !it.name.contains("21-11-32") }
        assertTrue(samples.size >= 30)
        var acceptedCount = 0
        for (sample in samples) {
            val frame = readFrame(sample.absolutePath)
            val detected = GameVisionDetector().analyze(frame)
            if (sample.name.contains("21-11-21")) {
                // This is the dimmed end-of-round prompt, not an active treasure board.
                assertNull("end-of-round overlay must remain rejected", detected)
                continue
            }
            val detection = checkNotNull(detected) { "No board: ${sample.name}" }
            val completion = FragmentCompletionPlanner().analyze(frame, detection, detection.itemCards)
            for (item in completion.objects.filter { it.localMatch?.accepted == true }) {
                val actual = item.localMatch!!.candidates.first().cells.map { cellAddress(it.row, it.column) }.toSet()
                assertTrue("${sample.name} invented footprint $actual", actual in truth)
                acceptedCount++
            }
        }
        println("full round accepted spatial matches=$acceptedCount over ${samples.size} frames")
        assertTrue(acceptedCount >= 10)
    }

    companion object {
        fun resizedJpeg(frame: RgbaFrame, width: Int): RgbaFrame {
            val source = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val offset = y * frame.rowStrideBytes + x * 4
                source.setRGB(x, y, ((frame.rgba8888[offset].toInt() and 255) shl 16) or
                    ((frame.rgba8888[offset + 1].toInt() and 255) shl 8) or (frame.rgba8888[offset + 2].toInt() and 255))
            }
            val scaled = BufferedImage(width, frame.height * width / frame.width, BufferedImage.TYPE_INT_RGB)
            val graphics = scaled.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(source, 0, 0, scaled.width, scaled.height, null)
            } finally { graphics.dispose(); source.flush() }
            val bytes = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
                try { writer.write(null, IIOImage(scaled, null, null), parameters) } finally { writer.dispose(); scaled.flush() }
            }
            val image = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
            return frameFromImage(image)
        }
        fun load(path: String): Pair<RgbaFrame, GameVisionDetection> {
            val frame = readFrame(path)
            return frame to checkNotNull(GameVisionDetector().analyze(frame)) { "No board: $path" }
        }

        fun readFrame(path: String): RgbaFrame {
            val file = sequenceOf(File(path), File("../$path")).first { it.exists() }
            val image = checkNotNull(ImageIO.read(file))
            return frameFromImage(image)
        }

        private fun frameFromImage(image: BufferedImage): RgbaFrame {
            val bytes = ByteArray(image.width * image.height * 4)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y); val offset = (y * image.width + x) * 4
                bytes[offset] = (rgb shr 16).toByte(); bytes[offset + 1] = (rgb shr 8).toByte()
                bytes[offset + 2] = rgb.toByte(); bytes[offset + 3] = 255.toByte()
            }
            val frame = RgbaFrame(image.width, image.height, image.width * 4, bytes, 0)
            image.flush()
            return frame
        }
    }
}
