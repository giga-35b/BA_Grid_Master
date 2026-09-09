package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.model.ImageInputMode
import org.junit.Assert.*
import org.junit.Test

class BoardCalibrationTest {
    private val key = CalibrationKey(ImageInputMode.LATEST_PHOTO, 2400, 1080)
    private val board = ScreenRegion(1146, 260, 2186, 840)
    private fun shift(region: ScreenRegion, x: Int = 0, y: Int = 0) = ScreenRegion(region.left+x, region.top+y, region.right+x, region.bottom+y)
    private fun learned() = BoardCalibrationTracker().apply { repeat(3) { observe(key, board, true) } }

    @Test fun learnsAverageFromThreeStableRawCorners() {
        val tracker = BoardCalibrationTracker()
        tracker.observe(key, shift(board, -1, -1), true)
        tracker.observe(key, shift(board, 1, 1), true)
        assertTrue(tracker.profiles.isEmpty())
        val result = tracker.observe(key, board, true)
        assertEquals(board, result.region)
        assertEquals(board, tracker.profiles.single().region)
        assertTrue(result.note.contains("已建立"))
    }

    @Test fun appliesSmallLateBoardDriftWithoutLearningIt() {
        val tracker = learned()
        val raw = board.copy(top = board.top + 12, bottom = board.bottom + 24)
        repeat(4) { assertEquals(board, tracker.observe(key, raw, false).region) }
        assertEquals(board, tracker.profiles.single().region)
    }

    @Test fun anyCornerBeyondLimitPreventsCorrection() {
        val tracker = learned()
        val limit = BoardCalibrationTracker.correctionLimit(key, board)
        assertEquals(board, tracker.observe(key, shift(board, limit), false).region)
        val far = shift(board, limit + 1)
        assertEquals(far, tracker.observe(key, far, false).region)
        assertTrue(tracker.observe(key, far, false).note.contains("不强行校正"))
    }

    @Test fun recoversOneRowVerticalPhaseSlipOnNonLearnableFrame() {
        val tracker = learned()
        val cell = board.height / 5
        val raw = shift(board, x = -2, y = cell).copy(right = board.right - 5, bottom = board.bottom + cell - 2)
        val result = tracker.observe(key, raw, false)
        assertEquals(board, result.region)
        assertTrue(result.note.contains("1格向下相位偏移"))
        assertEquals(board, tracker.profiles.single().region)
    }

    @Test fun inconsistentVerticalJumpIsNotTreatedAsGridPhase() {
        val tracker = learned()
        val cell = board.height / 5
        val raw = shift(board, y = cell).copy(bottom = board.bottom + cell + 30)
        val result = tracker.observe(key, raw, false)
        assertEquals(raw, result.region)
    }

    @Test fun stableLargeDeviationReplacesReferenceOnlyOnLearnableFrames() {
        val tracker = learned()
        val moved = shift(board, -130)
        repeat(6) { assertEquals(moved, tracker.observe(key, moved, false).region) }
        assertEquals(board, tracker.profiles.single().region)
        repeat(2) { tracker.observe(key, moved, true) }
        assertEquals(board, tracker.profiles.single().region)
        assertTrue(tracker.observe(key, moved, true).note.contains("重新建立"))
        assertEquals(moved, tracker.profiles.single().region)
    }

    @Test fun outlierMissAndNonLearnableFramesBreakConsecutiveSamples() {
        for (interruption in 0..2) {
            val tracker = BoardCalibrationTracker()
            repeat(2) { tracker.observe(key, board, true) }
            when (interruption) {
                0 -> tracker.observe(key, shift(board, -80), true)
                1 -> tracker.missed(key)
                else -> tracker.observe(key, board, false)
            }
            tracker.observe(key, board, true)
            assertTrue("interruption $interruption", tracker.profiles.isEmpty())
        }
    }

    @Test fun samplesCannotGraduallyWalkAwayFromFirstCorner() {
        val tracker = BoardCalibrationTracker()
        tracker.observe(key, board, true)
        tracker.observe(key, shift(board, 4), true)
        tracker.observe(key, shift(board, 8), true)
        assertTrue(tracker.profiles.isEmpty())
    }

    @Test fun sourceResolutionAndOrientationDoNotReuseOtherCoordinates() {
        val tracker = learned()
        val raw = shift(board, 10)
        assertEquals(raw, tracker.observe(key.copy(source = ImageInputMode.SCREEN_CAPTURE), raw, false).region)
        assertEquals(raw, tracker.observe(key.copy(width = 2500), raw, false).region)
        assertEquals(board, tracker.observe(key, raw, false).region)
        val portrait = CalibrationKey(key.source, 1080, 2400)
        assertEquals(raw, tracker.observe(portrait, raw, false).region)
    }

    @Test fun clearRemovesTrustedAndPendingSamples() {
        val tracker = learned()
        repeat(2) { tracker.observe(key, shift(board, -100), true) }
        tracker.clear()
        assertTrue(tracker.profiles.isEmpty())
        tracker.observe(key, shift(board, -100), true)
        assertTrue(tracker.profiles.isEmpty())
        assertEquals(shift(board, 10), tracker.observe(key, shift(board, 10), false).region)
    }

    @Test fun persistenceRoundTripRestoresTrustedCornersButNotLearningProgress() {
        val tracker = learned()
        val text = BoardCalibrationCodec.encode(tracker.profiles)
        val restored = BoardCalibrationTracker(BoardCalibrationCodec.decode(text))
        assertEquals(board, restored.observe(key, shift(board, 10), false).region)
        assertEquals(tracker.profiles, restored.profiles)
    }

    @Test fun invalidOrVersionMismatchedPersistedDataIsIgnored() {
        for (value in listOf(null, "", "v9\nLATEST_PHOTO|2400|1080|1146|260|2186|840",
            "v1\nLATEST_PHOTO|2400|1080|2147483647|260|-2147483648|840",
            "v1\nLATEST_PHOTO|2400|1080|-1|260|2186|840", "v1\nwrong|2400|1080|1146|260|2186|840")) {
            assertTrue("value=$value", BoardCalibrationCodec.decode(value).isEmpty())
        }
    }

    @Test fun smallImagesScaleThresholdAndNeverCorrectAnEntireCell() {
        val small = CalibrationKey(key.source, 1280, 576)
        val region = ScreenRegion(610, 139, 1164, 447)
        assertEquals(2, BoardCalibrationTracker.stabilityTolerance(small))
        assertTrue(BoardCalibrationTracker.correctionLimit(small, region) < region.height / 5 / 2)
    }

    @Test fun rejectedPixelValidationRollsBackNewCalibration() {
        val tracker = learned()
        val previous = tracker.profiles
        repeat(3) { tracker.observe(key, shift(board, -130), true) }
        tracker.rejectCorrection(previous)
        assertEquals(previous, tracker.profiles)
    }
}
