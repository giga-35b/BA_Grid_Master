package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.debug.debugReport
import com.bagridmaster.app.model.StrategyAlgorithm
import org.junit.Assert.*
import org.junit.Test

class PaleFragmentRegressionTest {
    @Test fun shellCornerRemainsAnObjectThroughAllThreeStages() {
        val (frame, detection) = LocalTemplateMatcherTest.load("dataset/regressions/shell-corner-20260831-112136.jpg")
        val h2 = detection.boardCells.single { it.row == 1 && it.column == 7 }
        assertEquals("H2 ${h2.presence}", BoardCellState.OPEN_FRAGMENT, h2.state)
        assertEquals(BoardCellState.OPEN_EMPTY, detection.boardCells.single { it.row == 1 && it.column == 1 }.state)
        assertEquals(43, detection.boardCells.count { it.state.isUnopened })
        assertTrue(checkNotNull(h2.presence).paleRatio > 0.1)
        val cards = detection.itemCards.mapIndexed { i, c -> c.copy(remainingCount = listOf(3, 4, 1)[i]) }
        val completion = FragmentCompletionPlanner().analyze(frame, detection, cards)
        val item = completion.objects.single { it.phase == BoardObjectPhase.LIT }
        println("SHELL presence=${h2.presence} type=${item.typeScores} match=${item.localMatch} completion=${item.completionEvidence}")
        assertEquals(setOf(GridCell(1, 7)), item.observedCells)
        assertTrue("shell c must survive type filtering: $item", 2 in item.possibleItemIndices)
        assertNotNull(item.localMatch)
        assertTrue("should retain useful contact evidence", detection.fragmentEdges.single().bottom > 0.12)
        assertTrue(completion.recommendations.isNotEmpty())
        assertTrue(completion.recommendations.none { it.isExploration })
        val prepared = LitObjectExplorationGate().prepare(detection.geometry.board, detection.boardCells, cards, completion)
        if (completion.fullCompletions.isEmpty()) assertFalse(prepared.allowed)
    }

    @Test fun paleShellSurvivesResizingAndJpeg() {
        val original = LocalTemplateMatcherTest.readFrame("dataset/regressions/shell-corner-20260831-112136.jpg")
        for (width in listOf(1280, 1800, 2400)) {
            val frame = LocalTemplateMatcherTest.resizedJpeg(original, width)
            val detection = checkNotNull(GameVisionDetector().analyze(frame))
            assertEquals("width=$width", BoardCellState.OPEN_FRAGMENT, detection.boardCells.single { it.row == 1 && it.column == 7 }.state)
            assertEquals("width=$width", BoardCellState.OPEN_EMPTY, detection.boardCells.single { it.row == 1 && it.column == 1 }.state)
            val item = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards)
                .single { it.phase == BoardObjectPhase.LIT }
            assertTrue("width=$width ${item.typeScores}", 2 in item.possibleItemIndices)
        }
    }

    @Test fun noAppearanceMatchDoesNotErasePresence() {
        val (frame, detection) = LocalTemplateMatcherTest.load("dataset/regressions/shell-corner-20260831-112136.jpg")
        val cards = detection.itemCards.map { it.copy(spriteTemplate = RgbaPatch(2, 2, ByteArray(16))) }
        val objects = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, cards)
        val lit = objects.single { it.phase == BoardObjectPhase.LIT }
        assertEquals(0.0, lit.confidence, 0.0)
        assertEquals(setOf(0, 1, 2), lit.possibleItemIndices.toSet())
        assertEquals(setOf(GridCell(1, 7)), lit.observedCells)
        val result = AnalysisResult(emptyList(), 1, ResultSource.GALLERY, detection.geometry, cards,
            boardCells = detection.boardCells, boardObjects = objects)
        val report = result.debugReport()
        assertTrue(report.contains("第一步")); assertTrue(report.contains("第二步")); assertTrue(report.contains("第三步"))
        assertTrue(report.contains("低分不会抹去物品存在"))
    }

    @Test fun cyanCoralAndIsolatedNoiseDoNotBecomePaleObjects() {
        assertFalse(FragmentPixels.foreground(190, 222, 243))
        assertFalse(FragmentPixels.foreground(65, 90, 92)) // completed silhouette
        val background = cellFrame { x, y -> if (x == 24 && y == 24) intArrayOf(220, 203, 190) else intArrayOf(160, 210, 230) }
        assertEquals(BoardCellState.OPEN_EMPTY, inspect(background).state)
        val pale = cellFrame { x, y -> if (x >= 25 && y >= 25) intArrayOf(220, 203, 190) else intArrayOf(160, 210, 230) }
        assertEquals(BoardCellState.OPEN_FRAGMENT, inspect(pale).state)
    }

    @Test fun unexplainedAreaIsNotAnEmptyConstraintOrExplorationInput() {
        val observation = inspect(cellFrame { _, _ -> intArrayOf(170, 170, 170) })
        assertEquals(BoardCellState.UNCERTAIN, observation.state)
        assertFalse(observation.state.isUnopened)
        val (_, detection) = LocalTemplateMatcherTest.load("dataset/regressions/shell-corner-20260831-112136.jpg")
        val cells = detection.boardCells.map { if (it.row == 1 && it.column == 7) it.copy(state = BoardCellState.UNCERTAIN) else it }
        val cards = detection.itemCards.mapIndexed { i, c -> c.copy(remainingCount = listOf(3, 4, 1)[i]) }
        val prepared = LitObjectExplorationGate().prepare(detection.geometry.board, cells, cards, FragmentCompletion(emptyList(), emptyList()))
        assertFalse(prepared.allowed)
        assertTrue(prepared.explanation.contains("待确认"))
        for (algorithm in StrategyAlgorithm.entries) assertNull(BoardStrategySolver().recommend(cells, cards, algorithm))
        assertEquals(BoardCellState.UNCERTAIN, cells.single { it.row == 1 && it.column == 7 }.state)
    }

    @Test fun preprocessingExistsWithoutAnyLitCellsAndTilesReconstructReference() {
        val (_, detection) = LocalTemplateMatcherTest.load("dataset/202608292110_1st_round/Screenshot_2026-08-29-21-11-32-464_com.YostarJP..jpg")
        assertTrue(detection.boardCells.all { it.state == BoardCellState.CLOSED })
        for (card in detection.itemCards) {
            val preview = LocalTemplateMatcher().preparePreview(card)
            if (card.shape!!.rows == card.shape.columns) {
                assertNull(preview.aligned); assertTrue(preview.tiles.isEmpty()); assertTrue(preview.explanation.contains("正方形"))
            } else {
                assertNotNull(preview.rotationDegrees)
                val aligned = checkNotNull(preview.aligned) { "card=${card.index} ${preview.explanation}" }
                assertEquals(card.shape.cellCount, preview.tiles.size)
                assertTrue(aligned.rgba8888.indices.any { it % 4 == 3 && aligned.rgba8888[it] != 0.toByte() })
                for ((index, tile) in preview.tiles.withIndex()) for (y in 0 until tile.height) for (x in 0 until tile.width) {
                    val source = ((index / preview.columns * tile.height + y) * aligned.width + index % preview.columns * tile.width + x) * 4
                    assertArrayEquals(aligned.rgba8888.copyOfRange(source, source + 4), tile.rgba8888.copyOfRange((y * tile.width + x) * 4, (y * tile.width + x) * 4 + 4))
                }
            }
        }
    }

    @Test fun exportRealPreprocessingReferencesForVisualReview() {
        val (_, detection) = LocalTemplateMatcherTest.load("dataset/regressions/shell-corner-20260831-112136.jpg")
        val image = java.awt.image.BufferedImage(800, 640, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        fun draw(patch: RgbaPatch, x: Int, y: Int, width: Int, height: Int) {
            val bitmap = java.awt.image.BufferedImage(patch.width, patch.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (py in 0 until patch.height) for (px in 0 until patch.width) {
                val i = (py * patch.width + px) * 4
                bitmap.setRGB(px, py, ((patch.rgba8888[i + 3].toInt() and 255) shl 24) or
                    ((patch.rgba8888[i].toInt() and 255) shl 16) or ((patch.rgba8888[i + 1].toInt() and 255) shl 8) or
                    (patch.rgba8888[i + 2].toInt() and 255))
            }
            graphics.drawImage(bitmap, x, y, width, height, null); bitmap.flush()
        }
        try {
            graphics.color = java.awt.Color(38, 55, 70); graphics.fillRect(0, 0, image.width, image.height)
            for (card in detection.itemCards) {
                val y = card.index * 210 + 20
                val preview = LocalTemplateMatcher().preparePreview(card)
                draw(card.spriteTemplate, 10, y + 20, 160, 112)
                graphics.color = java.awt.Color.WHITE
                graphics.drawString("${itemCode(card.index)}  ${preview.rotationDegrees ?: "square"}", 10, y)
                preview.aligned?.let {
                    draw(it, 190, y + 20, preview.columns * 70, preview.rows * 70)
                    graphics.color = java.awt.Color.CYAN
                    for (r in 0 until preview.rows) for (c in 0 until preview.columns)
                        graphics.drawRect(190 + c * 70, y + 20 + r * 70, 70, 70)
                    preview.tiles.forEachIndexed { i, tile -> draw(tile, 500 + i % 4 * 60, y + 20 + i / 4 * 60, 54, 54) }
                }
            }
        } finally { graphics.dispose() }
        val output = java.io.File("build/reports/vision/preprocessing-shell.png")
        checkNotNull(output.parentFile).mkdirs()
        javax.imageio.ImageIO.write(image, "png", output); image.flush()
        assertTrue(output.length() > 0)
    }

    private fun inspect(frame: RgbaFrame) = GameVisionDetector().inspectCellObservation(frame, ScreenRegion(0, 0, frame.width, frame.height))
    private fun cellFrame(pixel: (Int, Int) -> IntArray): RgbaFrame {
        val bytes = ByteArray(64 * 64 * 4)
        for (y in 0 until 64) for (x in 0 until 64) {
            val color = pixel(x, y); val offset = (y * 64 + x) * 4
            for (c in 0..2) bytes[offset + c] = color[c].toByte()
            bytes[offset + 3] = 255.toByte()
        }
        return RgbaFrame(64, 64, 64 * 4, bytes, 0)
    }
}
