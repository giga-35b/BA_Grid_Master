package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardVisualModelsTest {
    @Test fun openedBackgroundComesFromPixelsSharedOutsideDifferentObjects() {
        val width = 180
        val height = 60
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val localX = x % 60
            val background = intArrayOf(188 + localX / 15, 205 + y / 20, 220 + localX / 20)
            val objectPixel = when (x / 60) {
                0 -> localX in 5..27 && y in 5..48
                1 -> localX in 25..54 && y in 12..35
                else -> false
            }
            val colour = if (!objectPixel) background else if (x < 60) intArrayOf(35, 45, 75) else intArrayOf(220, 90, 55)
            val offset = (y * width + x) * 4
            bytes[offset] = colour[0].toByte(); bytes[offset + 1] = colour[1].toByte()
            bytes[offset + 2] = colour[2].toByte(); bytes[offset + 3] = 255.toByte()
        }
        val frame = RgbaFrame(width, height, width * 4, bytes, 0L)
        val regions = listOf(ScreenRegion(0, 0, 60, 60), ScreenRegion(60, 0, 120, 60))
        val model = checkNotNull(OpenCellBackgroundModel.learn(frame, regions))
        val objectCell = model.measure(frame, regions.first())
        val emptyCell = model.measure(frame, ScreenRegion(120, 0, 180, 60))
        assertTrue(emptyCell.backgroundRatio > 0.85)
        assertTrue(emptyCell.residualConnectedRatio < 0.05)
        assertTrue(objectCell.residualConnectedRatio > 0.15)
    }

    @Test fun diagonalCoverCanBeLearnedFromMinorityAfterOpenedCellsAreExcluded() {
        val covered = (0 until 12).map { index ->
            val row = index / 4
            val column = index % 4
            val phase = (column - row + 4) % 4
            val colours = arrayOf(intArrayOf(230, 210, 70), intArrayOf(170, 205, 225),
                intArrayOf(175, 205, 225), intArrayOf(220, 175, 215))
            val colour = colours[phase]
            CoveredPatternCell(row, column, 0.95, colour[0].toDouble(), colour[1].toDouble(), colour[2].toDouble())
        }
        val opened = (12 until 45).map { index ->
            CoveredPatternCell(index / 9, index % 9, 0.95, 245.0, 246.0, 248.0)
        }
        val excluded = opened.mapTo(mutableSetOf()) { it.row to it.column }
        val model = checkNotNull(CoveredPatternModel.learn(covered + opened, excluded))
        assertTrue(covered.all { model.score(it) >= 0.50 })
        assertTrue(model.score(CoveredPatternCell(0, 0, 0.95, 245.0, 246.0, 248.0)) == 0.0)
    }
}
