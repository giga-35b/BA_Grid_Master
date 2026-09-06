package com.bagridmaster.app.vision

import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.analysis.cellAddress
import org.junit.Assert.*
import org.junit.Test

class BoardCalibrationImageTest {
    @Test fun correctionResamplesCellsAndFragmentEdgesRatherThanJustMovingTheOverlay() {
        val frame = LocalTemplateMatcherTest.readFrame("app/src/test/resources/calibration/late-14-closed.jpg")
        val detector = GameVisionDetector()
        val original = checkNotNull(detector.analyze(frame))
        val region = original.geometry.board.region
        val key = CalibrationKey(ImageInputMode.LATEST_PHOTO, frame.width, frame.height)
        val tracker = BoardCalibrationTracker(listOf(BoardCalibrationProfile(key, region)))
        val drift = region.copy(top = region.top + 20, bottom = region.bottom + 20)
        val incorrect = checkNotNull(detector.reinspect(frame, original, drift))
        assertNotEquals("fixture must really change pixel classification", original.boardCells, incorrect.boardCells)
        val result = checkNotNull(calibrateLocatedBoard(frame, key.source, tracker, detector, incorrect).detection)
        assertEquals(region, result.geometry.board.region)
        assertEquals(original.boardCells, result.boardCells)
        assertEquals(original.fragmentEdges, result.fragmentEdges)
        assertEquals(original.itemCards.map { it.cardRegion }, result.itemCards.map { it.cardRegion })
    }

    @Test fun lateBoardAttachmentsUseOpeningCoordinatesForPixelRecognition() {
        val tracker = BoardCalibrationTracker()
        val detector = GameVisionDetector()
        val opening = LocalTemplateMatcherTest.readFrame("dataset/202608292110_1st_round/Screenshot_2026-08-29-21-06-10-167_com.YostarJP..jpg")
        val seed = LocalTemplateMatcherTest.resizedJpeg(opening, 1280)
        repeat(3) {
            val learned = calibratedDetection(seed, ImageInputMode.LATEST_PHOTO, tracker, detector)
            assertNotNull(learned.detection)
            println("SEED ${learned.note}")
        }
        val saved = tracker.profiles.single().region
        for ((name, closed, litCell) in listOf(Triple("late-18-closed.jpg", 18, "F1"), Triple("late-14-closed.jpg", 14, "I1"))) {
            val frame = LocalTemplateMatcherTest.readFrame("app/src/test/resources/calibration/$name")
            val raw = checkNotNull(detector.analyze(frame))
            val result = calibratedDetection(frame, ImageInputMode.LATEST_PHOTO, tracker, detector)
            val detected = checkNotNull(result.detection)
            println("ATTACHMENT $name raw=${raw.geometry.board.region} corrected=${detected.geometry.board.region} ${result.note}")
            assertEquals(saved, detected.geometry.board.region)
            assertEquals(closed, detected.boardCells.count { it.state.isUnopened })
            assertEquals(listOf(litCell), detected.boardCells.filter { it.state == BoardCellState.OPEN_FRAGMENT }.map { cellAddress(it.row, it.column) })
            assertEquals("late boards must not poison learned coordinates", saved, tracker.profiles.single().region)
        }
    }

    @Test fun savedCoordinatesNeverTurnNegativeFramesIntoBoards() {
        val tracker = BoardCalibrationTracker()
        val detector = GameVisionDetector()
        val opening = LocalTemplateMatcherTest.readFrame("dataset/202608292110_1st_round/Screenshot_2026-08-29-21-06-10-167_com.YostarJP..jpg")
        repeat(3) { calibratedDetection(opening, ImageInputMode.LATEST_PHOTO, tracker, detector) }
        val root = sequenceOf(java.io.File("dataset/others"), java.io.File("../dataset/others")).first { it.isDirectory }
        for (file in root.listFiles()!!.filter { it.extension in setOf("jpg", "png", "jpeg") }) {
            val result = calibratedDetection(LocalTemplateMatcherTest.readFrame(file.absolutePath), ImageInputMode.LATEST_PHOTO, tracker, detector)
            assertNull(file.name, result.detection)
        }
    }
}
