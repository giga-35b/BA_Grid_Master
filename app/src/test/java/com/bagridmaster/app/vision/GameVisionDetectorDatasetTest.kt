package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardStrategySolver
import com.bagridmaster.app.analysis.BoardObjectObservation
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.cellAddress
import com.bagridmaster.app.model.StrategyAlgorithm

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameVisionDetectorDatasetTest {
    @Test
    fun lateBoardRecoversOneGridRowPhaseSlipWithCandidateAndExperience() {
        val frame = loadFrame(resolveFile("dataset/regressions/board-one-row-phase-slip-20260909.png"))
        val detector = GameVisionDetector()
        val result = checkNotNull(detector.analyze(frame))
        val rawBoard = result.geometry.board.region
        assertTrue("expanded phase candidates kept the wrong top: " + rawBoard, abs(rawBoard.top - 261) <= 24)
        assertTrue("expanded phase candidates kept the wrong bottom: " + rawBoard, abs(rawBoard.bottom - 839) <= 24)

        val key = CalibrationKey(com.bagridmaster.app.model.ImageInputMode.SCREEN_CAPTURE, frame.width, frame.height)
        val reference = ScreenRegion(1148, 261, 2189, 839)
        val tracker = BoardCalibrationTracker(listOf(BoardCalibrationProfile(key, reference)))
        val shifted = checkNotNull(detector.reinspect(frame, result, ScreenRegion(1146, 379, 2184, 955)))
        val recovered = calibrateLocatedBoard(frame, key.source, tracker, detector, shifted)
        assertEquals(reference, checkNotNull(recovered.detection).geometry.board.region)
        assertTrue(recovered.note.contains("1格向下相位偏移"))
    }

    @Test
    fun threeSidedFragmentsKeepEveryVisibleDirectionAndSquareInventoryShape() {
        val frame = loadFrame(resolveFile("dataset/regressions/three-sided-fragments-square-item-20260902.jpg"))
        val result = checkNotNull(GameVisionDetector().analyze(frame))
        assertEquals(listOf(6, 8, 9), result.itemCards.map { it.shape?.cellCount })

        val states = result.boardCells.associate { "${'A' + it.column}${it.row + 1}" to it.state }
        assertEquals(39, result.boardCells.count { it.state.isUnopened })
        assertEquals(BoardCellState.OPEN_EMPTY, states["E2"])
        assertEquals(BoardCellState.OPEN_EMPTY, states["B4"])
        for (address in listOf("B2", "H2", "E4", "H4")) {
            assertEquals(address, BoardCellState.OPEN_FRAGMENT, states[address])
        }

        val edges = result.fragmentEdges.associateBy { "${'A' + it.cell.column}${it.cell.row + 1}" }
        for (address in listOf("B2", "E4")) {
            val edge = checkNotNull(edges[address]) { "$address was not retained as an open fragment: $edges" }
            assertTrue("$address top=${edge.top}", edge.top >= 0.12)
            assertTrue("$address right=${edge.right}", edge.right < 0.12)
            assertTrue("$address bottom=${edge.bottom}", edge.bottom >= 0.12)
            assertTrue("$address left=${edge.left}", edge.left >= 0.12)
        }
    }

    @Test
    fun twoByTwoFragmentRejectsBackgroundOnlyTopAndRightEdges() {
        val frame = loadFrame(resolveFile("dataset/regressions/two-by-two-background-edge-20260908.png"))
        val result = checkNotNull(GameVisionDetector().analyze(frame))
        val edge = checkNotNull(result.fragmentEdges.singleOrNull {
            it.cell.row == 3 && it.cell.column == 1
        }) { "B4 was not retained as the sole lit fragment: ${result.fragmentEdges}" }
        assertTrue("B4 backgroundTop=${edge.backgroundTop}", edge.backgroundTop < 0.12)
        assertTrue("B4 backgroundRight=${edge.backgroundRight}", edge.backgroundRight < 0.12)
        assertTrue("B4 backgroundBottom=${edge.backgroundBottom}", edge.backgroundBottom >= 0.12)
        assertTrue("B4 backgroundLeft=${edge.backgroundLeft}", edge.backgroundLeft >= 0.12)
    }

    @Test
    fun twoByTwoWeakMainEdgeAndCleanOppositesDetermineTheCorner() {
        val frame = loadFrame(resolveFile("dataset/regressions/two-by-two-clean-opposites-20260908.png"))
        val detector = GameVisionDetector()
        val raw = checkNotNull(detector.analyze(frame))
        val result = checkNotNull(detector.reinspect(
            frame,
            raw,
            ScreenRegion(1148, 261, 2189, 839),
        ))
        val edge = checkNotNull(result.fragmentEdges.singleOrNull {
            it.cell.row == 1 && it.cell.column == 7
        }) { "H2 was not retained as the sole lit fragment: ${result.fragmentEdges}" }
        assertTrue("strict top should remain below the generic gate: ${edge.backgroundTop}",
            edge.backgroundTop < 0.12)
        assertTrue("assisted top=${edge.assistedTop}", edge.assistedTop >= 0.30)
        assertTrue("assisted right=${edge.assistedRight}", edge.assistedRight >= 0.45)
        assertTrue("assisted bottom=${edge.assistedBottom}", edge.assistedBottom <= 0.11)
        assertTrue("assisted left=${edge.assistedLeft}", edge.assistedLeft <= 0.06)

        val lit = BoardObjectObservation(
            id = "L1",
            phase = BoardObjectPhase.LIT,
            observedCells = setOf(GridCell(1, 7)),
            possibleItemIndices = listOf(1),
            confidence = 0.93,
            evidence = "dataset regression",
        )
        val completion = FragmentCompletionPlanner().complete(
            result.geometry.board,
            result.boardCells,
            result.itemCards,
            listOf(lit),
            result.fragmentEdges,
        )
        assertEquals(setOf("H1", "I1", "I2"), completion.recommendations
            .map { cellAddress(it.row, it.column) }.toSet())
        assertEquals(1, completion.fullCompletions.size)
        assertTrue(completion.objects.single().completionEvidence.contains("反方向接近背景"))
    }

    @Test
    fun continuousEdgeOccupancyBeatsFourHighTextureScores() {
        val frame = loadFrame(resolveFile("dataset/regressions/two-by-two-edge-occupancy-20260909.png"))
        val detector = GameVisionDetector()
        val raw = checkNotNull(detector.analyze(frame))
        val result = checkNotNull(detector.reinspect(
            frame,
            raw,
            ScreenRegion(1148, 261, 2189, 839),
        ))
        val edge = checkNotNull(result.fragmentEdges.singleOrNull {
            it.cell.row == 1 && it.cell.column == 3
        }) { "D2 was not retained as the sole lit fragment: ${result.fragmentEdges}" }
        assertTrue("traditional scores should reproduce the noisy case", listOf(
            edge.backgroundTop, edge.backgroundRight, edge.backgroundBottom, edge.backgroundLeft,
        ).all { it >= 0.50 })
        assertTrue("top longest=${edge.occupancy.top.longestRun}",
            edge.occupancy.top.longestRun >= 0.55)
        assertTrue("right longest=${edge.occupancy.right.longestRun}",
            edge.occupancy.right.longestRun >= 0.35)
        assertTrue("bottom longest=${edge.occupancy.bottom.longestRun}",
            edge.occupancy.bottom.longestRun <= 0.12)
        assertTrue("left longest=${edge.occupancy.left.longestRun}",
            edge.occupancy.left.longestRun <= 0.12)

        val lit = BoardObjectObservation(
            id = "L1",
            phase = BoardObjectPhase.LIT,
            observedCells = setOf(GridCell(1, 3)),
            possibleItemIndices = listOf(1),
            confidence = 0.92,
            evidence = "dataset regression",
        )
        val completion = FragmentCompletionPlanner().complete(
            result.geometry.board,
            result.boardCells,
            result.itemCards,
            listOf(lit),
            result.fragmentEdges,
        )
        assertEquals(setOf("D1", "E1", "E2"), completion.recommendations
            .map { cellAddress(it.row, it.column) }.toSet())
        assertEquals(1, completion.fullCompletions.size)
        assertTrue(completion.objects.single().completionEvidence.contains("边缘覆盖长度"))
    }

    @Test
    fun multicolorCoveredBoardDoesNotBecomeStaircaseObjectsAndInventoryStaysRectangular() {
        val frame = loadFrame(resolveFile("dataset/regressions/multicolor-rectangular-items-20260902.jpg"))
        val result = checkNotNull(GameVisionDetector().analyze(frame))
        assertEquals(45, result.boardCells.count { it.state == BoardCellState.CLOSED })
        assertEquals(0, result.boardCells.count { it.state == BoardCellState.OPEN_FRAGMENT })
        assertEquals(listOf(6, 8, 9), result.itemCards.map { it.shape?.cellCount })
        result.itemCards.forEach { card ->
            val shape = checkNotNull(card.shape)
            assertEquals("inventory shape must be a solid rectangle", shape.rows * shape.columns, shape.cellCount)
            assertEquals(shape.cellCount, shape.occupiedCells.size)
        }
    }

    @Test
    fun finishedThirdCardKeepsTrayAlignedToAllThreeInventorySlots() {
        val frame = loadFrame(resolveFile("dataset/regressions/finish-tray-20260902.jpg"))
        val result = checkNotNull(GameVisionDetector().analyze(frame))
        val tray = checkNotNull(result.geometry.itemTray)
        // The old winner started around x=65 in this resized source, exactly one card too far
        // left. The real tray begins around x=250 and ends after the completed third card.
        assertTrue("tray shifted into scenery: $tray", tray.left in 210..290)
        assertTrue("completed third card was cropped out: $tray", tray.right in 760..820)
        assertEquals(3, result.itemCards[0].shape?.cellCount)
        assertEquals(4, result.itemCards[1].shape?.cellCount)
        assertTrue("third card must be Finish", result.itemCards[2].isFinished)
        assertEquals(0, result.itemCards[2].remainingCount)

        // Remove the central word/artwork while preserving the two dimmed bottom badges. The
        // badge pair alone must still keep the correct three-slot lattice ahead of scenery.
        val withoutFinishBytes = frame.rgba8888.copyOf()
        val sprite = result.itemCards[2].spriteRegion
        for (y in sprite.top until sprite.bottom) for (x in sprite.left until sprite.right) {
            val offset = y * frame.rowStrideBytes + x * 4
            withoutFinishBytes[offset] = 30
            withoutFinishBytes[offset + 1] = 40
            withoutFinishBytes[offset + 2] = 62
        }
        val badgeOnly = checkNotNull(GameVisionDetector().analyze(frame.copy(rgba8888 = withoutFinishBytes)))
        val badgeTray = checkNotNull(badgeOnly.geometry.itemTray)
        assertTrue("dimmed shape/count badges did not preserve tray alignment: $badgeTray", badgeTray.left in 210..290)
        assertEquals(3, badgeOnly.itemCards[0].shape?.cellCount)
        assertEquals(4, badgeOnly.itemCards[1].shape?.cellCount)
    }

    @Test
    fun locatesBoardAndInitialInventory() {
        val frame = loadFrame("Screenshot_2026-08-29-21-06-10-167_com.YostarJP..jpg")
        val result = GameVisionDetector().analyze(frame)
        assertNotNull(result)
        result as GameVisionDetection
        val board = result.geometry.board
        println("board=${board.region} tray=${result.geometry.itemTray} shapes=${result.itemCards.map { it.shape }}")

        assertEquals(9, board.columns)
        assertEquals(5, board.rows)
        val subpixel = checkNotNull(board.subpixelGrid)
        assertTrue("subpixel square size=${subpixel.cellSizePx}", subpixel.cellSizePx >= 8.0)
        assertTrue("balanced left rounding", abs(board.region.left - subpixel.originX) <= 0.5)
        assertTrue("balanced right rounding", abs(board.region.right -
            (subpixel.originX + board.columns * subpixel.cellSizePx)) <= 0.5)
        assertTrue("board left=${board.region.left}", abs(board.region.left - 1147) <= 24)
        assertTrue("board top=${board.region.top}", abs(board.region.top - 261) <= 24)
        assertTrue("board right=${board.region.right}", abs(board.region.right - 2186) <= 40)
        assertTrue("board bottom=${board.region.bottom}", abs(board.region.bottom - 838) <= 28)
        assertEquals(3, result.itemCards.size)

        val shapes = result.itemCards.map { it.shape }
        assertNotNull(shapes[0])
        assertNotNull(shapes[1])
        assertNotNull(shapes[2])
        assertEquals(3, shapes[0]?.cellCount)
        assertEquals(4, shapes[1]?.cellCount)
        assertEquals(6, shapes[2]?.cellCount)
        assertEquals(45, result.boardCells.count { it.state == BoardCellState.CLOSED })
        assertTrue("initial board must not invent fragment suggestions", FragmentCompletionPlanner().analyze(frame, result, result.itemCards).recommendations.isEmpty())
    }

    @Test
    fun remainsStableAcrossBoardStatesAndNextRound() {
        val samples = listOf(
            "Screenshot_2026-08-29-21-06-13-120_com.YostarJP..jpg", // selected
            "Screenshot_2026-08-29-21-07-58-619_com.YostarJP..jpg", // partial item
            "Screenshot_2026-08-29-21-09-48-685_com.YostarJP..jpg", // five selections
            "Screenshot_2026-08-29-21-09-53-968_com.YostarJP..jpg", // many opened cells
            "Screenshot_2026-08-29-21-11-11-314_com.YostarJP..jpg", // two finished cards
            "Screenshot_2026-08-29-21-11-32-464_com.YostarJP..jpg", // next round
        )
        for (sample in samples) {
            val result = GameVisionDetector().analyze(loadFrame(sample))
            assertNotNull(sample, result)
            val region = checkNotNull(result).geometry.board.region
            val grid = checkNotNull(result.geometry.board.subpixelGrid)
            assertTrue("$sample balanced horizontal bounds", abs(region.width - 9 * grid.cellSizePx) <= 1.0)
            assertTrue("$sample balanced vertical bounds", abs(region.height - 5 * grid.cellSizePx) <= 1.0)
            assertTrue("$sample left=${region.left}", abs(region.left - 1147) <= 34)
            assertTrue("$sample top=${region.top}", abs(region.top - 261) <= 34)
            assertTrue("$sample right=${region.right}", abs(region.right - 2186) <= 40)
            assertTrue("$sample bottom=${region.bottom}", abs(region.bottom - 838) <= 40)
            assertEquals("$sample cards", 3, result.itemCards.size)
        }
    }

    @Test
    fun rejectsAFrameWithoutGrid() {
        val width = 640
        val height = 360
        val frame = RgbaFrame(
            width = width,
            height = height,
            rowStrideBytes = width * 4,
            rgba8888 = ByteArray(width * height * 4) { 0x80.toByte() },
            timestampNanos = 0,
        )
        assertEquals(null, GameVisionDetector().analyze(frame))
    }

    @Test
    fun rejectsDialogsAndRewardOverlaysEvenWhenGridRemainsBehindThem() {
        val directory = resolveFile("dataset/others")
        val samples = checkNotNull(directory.listFiles())
            .filter { it.extension.equals("png", ignoreCase = true) }
        assertTrue("negative samples missing", samples.isNotEmpty())
        for (sample in samples) {
            assertEquals(sample.name, null, GameVisionDetector().analyze(loadFrame(sample)))
        }
    }

    @Test
    fun followsFragmentAcrossCellBoundariesWithoutAssumingSpriteRotation() {
        val addresses = recommendations("Screenshot_2026-08-29-21-06-37-762_com.YostarJP..jpg").map { "${'A' + it.column}${it.row + 1}" }.toSet()
        assertTrue("expected B1 from vertical fragment, actual=$addresses", "B1" in addresses)
        assertTrue("expected B3 from vertical fragment, actual=$addresses", "B3" in addresses)

        val horizontal = checkNotNull(
            GameVisionDetector().analyze(
                loadFrame("Screenshot_2026-08-29-21-07-58-619_com.YostarJP..jpg"),
            ),
        )
        val horizontalAddresses = recommendations("Screenshot_2026-08-29-21-07-58-619_com.YostarJP..jpg")
            .map { "${'A' + it.column}${it.row + 1}" }
            .toSet()
        assertTrue(
            "expected a horizontal neighbour, actual=$horizontalAddresses states=" +
                horizontal.boardCells.joinToString { "${'A' + it.column}${it.row + 1}:${it.state}" },
            "B4" in horizontalAddresses || "D4" in horizontalAddresses,
        )

        val cases = listOf(
            "Screenshot_2026-08-29-21-08-44-066_com.YostarJP..jpg" to setOf("B5", "D5"),
            "Screenshot_2026-08-29-21-09-05-966_com.YostarJP..jpg" to setOf("F5"),
            "Screenshot_2026-08-29-21-09-43-626_com.YostarJP..jpg" to setOf("D1", "D3", "E2"),
            "Screenshot_2026-08-29-21-10-18-410_com.YostarJP..jpg" to setOf("G3"),
            "Screenshot_2026-08-29-21-10-58-958_com.YostarJP..jpg" to setOf("F2"),
            "Screenshot_2026-08-29-21-11-11-314_com.YostarJP..jpg" to setOf("I2"),
        )
        for ((sample, expected) in cases) {
            val actual = recommendations(sample).map { "${'A' + it.column}${it.row + 1}" }.toSet()
            assertTrue("$sample expected one of $expected, actual=$actual", actual.any { it in expected })
        }

        val multiDirection = recommendations("Screenshot_2026-08-29-21-09-43-626_com.YostarJP..jpg").map { "${'A' + it.column}${it.row + 1}" }.toSet()
        assertTrue("up+right must include upper-right diagonal, actual=$multiDirection", "E1" in multiDirection)
        assertTrue("down+right must include lower-right diagonal, actual=$multiDirection", "E3" in multiDirection)
    }

    @Test
    fun selectedCellsAreNotObjectFragmentsOrRecommendationSources() {
        val samples = listOf(
            "Screenshot_2026-08-29-21-06-13-120_com.YostarJP..jpg" to "A3",
            "Screenshot_2026-08-29-21-06-30-995_com.YostarJP..jpg" to "B2",
        )
        for ((sample, selectedAddress) in samples) {
            val result = checkNotNull(GameVisionDetector().analyze(loadFrame(sample)))
            val selected = result.boardCells.filter { it.state == BoardCellState.SELECTED }
                .map { "${'A' + it.column}${it.row + 1}" }
            assertTrue("$sample selected=$selected", selectedAddress in selected)
            val suggestions = recommendations(sample)
            assertTrue("$sample must not produce fragment suggestions", suggestions.isEmpty())
            assertFalse(
                "$sample selected cell became recommendation source: $suggestions",
                suggestions.any { "${'A' + it.column}${it.row + 1}" == selectedAddress },
            )
        }
    }

    @Test
    fun firstEmptyScreenshotCanTriggerSecondOpeningPreset() {
        val detection = checkNotNull(
            GameVisionDetector().analyze(
                loadFrame("Screenshot_2026-08-29-21-06-20-607_com.YostarJP..jpg"),
            ),
        )
        val emptyCells = detection.boardCells.filter { it.state == BoardCellState.OPEN_EMPTY }
        assertEquals(listOf("A3"), emptyCells.map { "${'A' + it.column}${it.row + 1}" })
        assertEquals(44, detection.boardCells.count { it.state == BoardCellState.CLOSED })
        val counts = listOf(4, 3, 1)
        val recommendation = checkNotNull(
            BoardStrategySolver().recommend(
                detection.boardCells,
                detection.itemCards.mapIndexed { index, card -> card.copy(remainingCount = counts[index]) },
                StrategyAlgorithm.LIMITED_LOOKAHEAD,
            ),
        )
        assertEquals("I3", "${'A' + recommendation.column}${recommendation.row + 1}")
        assertTrue(recommendation.strategy.contains("预计算"))
    }

    @Test
    fun selectedNeighboursStillReceiveFragmentRecommendations() {
        val result = checkNotNull(GameVisionDetector().analyze(loadFrame("Screenshot_2026-08-29-21-06-44-467_com.YostarJP..jpg")))
        val suggested = recommendations("Screenshot_2026-08-29-21-06-44-467_com.YostarJP..jpg").map { "${'A' + it.column}${it.row + 1}" }
        assertTrue("selected B1/B3 must still be recommended: $suggested", suggested.containsAll(listOf("B1", "B3")))
        assertTrue(result.boardCells.any { it.state == BoardCellState.SELECTED })
    }

    @Test
    fun reportsCompletedAndLitObjectsWithoutInventingHiddenCells() {
        val frame = loadFrame("Screenshot_2026-08-29-21-07-58-619_com.YostarJP..jpg")
        val result = checkNotNull(GameVisionDetector().analyze(frame))
        val objects = BoardObjectRecognizer().recognize(frame, result.geometry.board, result.boardCells, result.itemCards)
        println("object diagnostics=$objects")
        assertTrue(objects.any { it.phase == com.bagridmaster.app.analysis.BoardObjectPhase.COMPLETED &&
            it.observedCells.containsAll(listOf(com.bagridmaster.app.analysis.GridCell(0, 1), com.bagridmaster.app.analysis.GridCell(1, 1), com.bagridmaster.app.analysis.GridCell(2, 1))) &&
            0 in it.possibleItemIndices })
        assertTrue(objects.any { it.phase == com.bagridmaster.app.analysis.BoardObjectPhase.LIT &&
            com.bagridmaster.app.analysis.GridCell(3, 2) in it.observedCells && 1 in it.possibleItemIndices })
        objects.forEach { item -> item.observedCells.forEach { cell ->
            assertTrue(result.boardCells.first { it.row == cell.row && it.column == cell.column }.state in
                setOf(BoardCellState.OPEN_OBJECT, BoardCellState.OPEN_FRAGMENT))
        } }
    }

    @Test
    fun separatesCompletedSpritesEvenWhenTheirFootprintsTouch() {
        val frame = loadFrame("Screenshot_2026-08-29-21-09-53-968_com.YostarJP..jpg")
        val detection = checkNotNull(GameVisionDetector().analyze(frame))
        val objects = BoardObjectRecognizer().recognize(frame, detection.geometry.board, detection.boardCells, detection.itemCards)
            .filter { it.phase == com.bagridmaster.app.analysis.BoardObjectPhase.COMPLETED }
        println("adjacent completed objects=$objects")
        val actual = objects.map { item -> item.observedCells.map { "${'A' + it.column}${it.row + 1}" }.toSet() }.toSet()
        val expected = setOf(setOf("B1", "B2", "B3"), setOf("B4", "C4", "D4", "E4"), setOf("A5", "B5", "C5", "D5"),
            setOf("E5", "F5", "G5"), setOf("D1", "E1", "D2", "E2", "D3", "E3"))
        assertEquals(expected, actual)
    }

    private fun recommendations(name: String): List<com.bagridmaster.app.analysis.CellRecommendation> {
        val frame = loadFrame(name)
        val detection = checkNotNull(GameVisionDetector().analyze(frame))
        return FragmentCompletionPlanner().analyze(frame, detection, detection.itemCards).recommendations
    }

    private fun loadFrame(name: String): RgbaFrame {
        val relative = "dataset/202608292110_1st_round/$name"
        val file = resolveFile(relative)
        return loadFrame(file)
    }

    private fun resolveFile(relative: String): File =
        sequenceOf(File(relative), File("../$relative"))
            .firstOrNull { it.exists() }
            ?: error("Dataset file not found: $relative")

    private fun loadFrame(file: File): RgbaFrame {
        val image = checkNotNull(ImageIO.read(file))
        val bytes = ByteArray(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            val offset = (y * image.width + x) * 4
            bytes[offset] = ((argb shr 16) and 0xFF).toByte()
            bytes[offset + 1] = ((argb shr 8) and 0xFF).toByte()
            bytes[offset + 2] = (argb and 0xFF).toByte()
            bytes[offset + 3] = ((argb ushr 24) and 0xFF).toByte()
        }
        image.flush()
        return RgbaFrame(
            width = image.width,
            height = image.height,
            rowStrideBytes = image.width * 4,
            rgba8888 = bytes,
            timestampNanos = 0,
        )
    }
}
