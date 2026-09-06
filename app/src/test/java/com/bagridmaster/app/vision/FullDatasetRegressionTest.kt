package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Every image under dataset, including negatives and the next-round opening.
 * Active quantities are replayed from the user's labelled actions, NOT passed off as OCR.
 * The actual bitmap Finish detector is asserted independently before injecting active counts.
 */
class FullDatasetRegressionTest {
    private val root = sequenceOf(File("dataset"), File("../dataset")).first { it.isDirectory }
    private val truth = listOf(
        0 to addresses("B1 B2 B3"), 1 to addresses("B4 C4 D4 E4"),
        1 to addresses("A5 B5 C5 D5"), 0 to addresses("E5 F5 G5"),
        2 to addresses("D1 E1 D2 E2 D3 E3"), 0 to addresses("G2 G3 G4"),
        1 to addresses("F1 F2 F3 F4"), 0 to addresses("I1 I2 I3"))

    @Test fun allDatasetImagesRespectLabelsAndNeverInventCompletedItemsOrFootprints() = runDataset(false)

    @Test fun calibratedPipelineRespectsAllDatasetLabels() = runDataset(true)

    private fun runDataset(calibrated: Boolean) {
        val tracker = BoardCalibrationTracker()
        val detector = GameVisionDetector()
        val annotations = root.resolve("file_tree.txt").readLines().mapNotNull { line ->
            Regex("(Screenshot_.*?\\.jpg)\\s+(.*)").find(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }.toMap()
        val images = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sortedBy { it.path }.toList()
        assertEquals("Update coverage when adding dataset images", 55, images.size)
        val opened = mutableSetOf<GridCell>()
        var valid = 0; var rejected = 0; var matched = 0; var fallback = 0; var finishCards = 0; var selectionDifferences = 0
        for (file in images) {
            val frame = LocalTemplateMatcherTest.readFrame(file.absolutePath)
            val detected = if (calibrated) calibratedDetection(frame, com.bagridmaster.app.model.ImageInputMode.LATEST_PHOTO, tracker, detector).let {
                println("CALIBRATION ${file.name}: ${it.note}")
                it.detection
            } else detector.analyze(frame)
            val negative = file.parentFile?.name == "others" || file.name.contains("21-11-21-376")
            if (negative) {
                assertNull("negative ${file.name}", detected)
                println("DATASET ${file.name}: rejected (expected)"); rejected++; continue
            }
            val detection = checkNotNull(detected) { "Missing board: ${file.name}" }
            val boardRegion = detection.geometry.board.region
            val subpixel = checkNotNull(detection.geometry.board.subpixelGrid)
            assertTrue("${file.name} balanced horizontal bounds", kotlin.math.abs(boardRegion.width - 9 * subpixel.cellSizePx) <= 1.0)
            assertTrue("${file.name} balanced vertical bounds", kotlin.math.abs(boardRegion.height - 5 * subpixel.cellSizePx) <= 1.0)
            valid++
            if (file.name == "shell-corner-20260831-112136.jpg") {
                assertEquals(BoardCellState.OPEN_FRAGMENT, detection.boardCells.single { it.row == 1 && it.column == 7 }.state)
                assertEquals(BoardCellState.OPEN_EMPTY, detection.boardCells.single { it.row == 1 && it.column == 1 }.state)
                assertEquals(43, detection.boardCells.count { it.state.isUnopened })
            }
            var expectedCounts: List<Int>? = null
            val firstRound = file.parentFile?.name == "202608292110_1st_round" && !file.name.contains("21-11-32-464")
            if (firstRound) {
                val annotation = checkNotNull(annotations[file.name])
                Regex("已翻开([A-I][1-5](?: [A-I][1-5])*)").find(annotation)?.let { opened += addresses(it.groupValues[1]) }
                val selected = Regex("已选择([A-I][1-5](?: [A-I][1-5])*)").find(annotation)?.let { addresses(it.groupValues[1]) }.orEmpty()
                expectedCounts = listOf(4, 3, 1).mapIndexed { index, count -> count - truth.count { it.first == index && opened.containsAll(it.second) } }
                for (cell in detection.boardCells) {
                    val position = GridCell(cell.row, cell.column)
                    val objectTruth = truth.firstOrNull { position in it.second }
                    val expected = when {
                        position in selected -> BoardCellState.SELECTED
                        position !in opened -> BoardCellState.CLOSED
                        objectTruth == null -> BoardCellState.OPEN_EMPTY
                        opened.containsAll(objectTruth.second) -> BoardCellState.OPEN_OBJECT
                        else -> BoardCellState.OPEN_FRAGMENT
                    }
                    if (expected.isUnopened && cell.state.isUnopened) {
                        // CLOSED and SELECTED are intentionally identical solver inputs.
                        // Still report display-level differences instead of hiding them.
                        if (expected != cell.state) {
                            selectionDifferences++
                            println("SELECTION ${file.name} ${cellAddress(cell.row, cell.column)} expected=$expected actual=${cell.state}")
                        }
                    } else assertEquals("${file.name} ${cellAddress(cell.row, cell.column)}", expected, cell.state)
                }
            }
            for (card in detection.itemCards) {
                val expectedFinished = expectedCounts?.get(card.index)?.let { it == 0 }
                if (expectedFinished != null) assertEquals("${file.name} card${card.index} Finish", expectedFinished, card.isFinished)
                if (card.isFinished) { assertEquals(0, card.remainingCount); finishCards++ }
                else assertNotNull("active shape ${file.name} card${card.index}", card.shape)
                if (firstRound && expectedFinished == false) assertEquals("${file.name} active shape", listOf(3, 4, 6)[card.index], card.shape?.cellCount)
            }
            if (file.name.contains("21-11-32-464")) {
                assertTrue(detection.boardCells.all { it.state == BoardCellState.CLOSED })
                assertEquals(listOf(2, 4, 8), detection.itemCards.map { it.shape?.cellCount })
            }
            if (file.parentFile?.name == "selection_states") {
                // These legacy screenshots already contain this app's old recommendation
                // overlays. In the 3-cell image E3/F3 checkmarks are completely occluded:
                // only C2 is observable. Do not invent invisible ticks from the filename.
                val visibleTicks = if (file.name.contains("3_cells")) 1 else 4
                assertEquals(file.name, visibleTicks, detection.boardCells.count { it.state == BoardCellState.SELECTED })
                assertTrue(detection.boardCells.all { it.state.isUnopened })
            }
            val previewMatcher = LocalTemplateMatcher()
            val cards = detection.itemCards.map { card ->
                if (card.isFinished) card else card.copy(remainingCount = expectedCounts?.get(card.index))
            }.map { card -> card.copy(templatePreview = previewMatcher.preparePreview(card)) }
            val completion = FragmentCompletionPlanner().analyze(frame, detection, cards)
            completion.recommendations.forEach { recommendation ->
                assertTrue(completion.boardCells.single { it.row == recommendation.row && it.column == recommendation.column }.state.isUnopened)
            }
            if (firstRound && completion.objects.none { it.phase == BoardObjectPhase.LIT }) {
                val prepared = LitObjectExplorationGate().prepare(detection.geometry.board, completion.boardCells, cards, completion)
                assertTrue("${file.name} finished cards must not block exploration: ${prepared.explanation}", prepared.allowed)
            }
            val lit = completion.objects.filter { it.phase == BoardObjectPhase.LIT }
            for (item in lit) {
                if (item.localMatch?.accepted == true) matched++ else fallback++
                if (item.localMatch?.accepted != true) println("FALLBACK ${file.name} ${item.localMatch}")
                if (firstRound) {
                    val expected = truth.single { it.second.containsAll(item.observedCells) }
                    if (item.localMatch?.accepted == true) assertEquals("${file.name} matched footprint", expected.second, item.localMatch.candidates.first().cells)
                    completion.fullCompletions.filter { it.objectId == item.id }.forEach { plan ->
                        assertTrue("${file.name} full plan must cover true footprint", expected.second in plan.footprints)
                        assertEquals(expected.first, plan.itemIndex)
                    }
                }
            }
            if (firstRound) {
                val expectedMissing = truth.filter { (_, footprint) -> footprint.any { it in opened } && !opened.containsAll(footprint) }
                    .flatMap { it.second - opened }.toSet()
                val actual = completion.recommendations.map { GridCell(it.row, it.column) }.toSet()
                assertTrue("${file.name} invented completion $actual", expectedMissing.containsAll(actual))
            }
            println("DATASET ${file.name}: board=OK Finish=${cards.filter { it.isFinished }.map { it.index + 1 }} lit=${lit.size} matched=${lit.count { it.localMatch?.accepted == true }} suggestions=${completion.recommendations.map { cellAddress(it.row, it.column) }}")
        }
        println("DATASET TOTAL images=${images.size} valid=$valid rejected=$rejected FinishCards=$finishCards matched=$matched fallback=$fallback selectionDifferences=$selectionDifferences")
        assertEquals(50, valid); assertEquals(5, rejected)
        assertEquals("No neighboring-border selection errors", 0, selectionDifferences)
        if (calibrated) assertTrue("dataset must establish at least one trusted profile", tracker.profiles.isNotEmpty())
    }

    private fun addresses(text: String) = text.split(" ").filter { it.isNotBlank() }.map { GridCell(it[1] - '1', it[0] - 'A') }.toSet()
}
