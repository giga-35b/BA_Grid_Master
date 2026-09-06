package com.bagridmaster.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.AnalysisTimings
import com.bagridmaster.app.analysis.TemplatePreview
import com.bagridmaster.app.analysis.CellRecommendation
import com.bagridmaster.app.analysis.ResultSource
import com.bagridmaster.app.debug.RecognitionDebugSnapshot
import com.bagridmaster.app.debug.RecognitionDebugStore
import com.bagridmaster.app.ui.RecognitionDebugPanel
import com.bagridmaster.app.ui.TemplatePreprocessingPreview
import com.bagridmaster.app.ui.toPreviewBitmap
import com.bagridmaster.app.analysis.RgbaPatch
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import com.bagridmaster.app.vision.RgbaFrame
import com.bagridmaster.app.vision.GameVisionDetector
import com.bagridmaster.app.vision.FragmentCompletionPlanner
import com.bagridmaster.app.vision.LocalTemplateMatcher
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

class RecognitionDebugPanelTest {
    @get:Rule val compose = createComposeRule()

    @After fun clearSnapshot() { RecognitionDebugStore.clear() }

    @Test fun segmentedPreviewRetainsTransparentBackground() {
        val bitmap = RgbaPatch(2, 1, byteArrayOf(40, 80, 100, 0, 120, 100, 90, -1)).toPreviewBitmap()
        try {
            org.junit.Assert.assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
            org.junit.Assert.assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(1, 0)))
        } finally { bitmap.recycle() }
    }

    @Test
    fun sliceRowsFollowLogicalShapeInsteadOfAvailableWidth() {
        val card = checkNotNull(GameVisionDetector().analyze(loadFixture())).itemCards[2]
        val tile = RgbaPatch(1, 1, byteArrayOf(120, 100, 90, -1))
        val preview = TemplatePreview(2, 4, 36, tile, List(8) { tile }, "4×2测试")
        // Old FlowRow would fit five 48dp slices at this width and put 2-1 on row one.
        compose.setContent { BAGridMasterTheme {
            Column(Modifier.width(300.dp)) { TemplatePreprocessingPreview(card.copy(templatePreview = preview)) }
        } }
        val topLeft = compose.onNodeWithText("1-1").fetchSemanticsNode().boundsInRoot
        val topRight = compose.onNodeWithText("1-4").fetchSemanticsNode().boundsInRoot
        val bottomLeft = compose.onNodeWithText("2-1").fetchSemanticsNode().boundsInRoot
        val bottomRight = compose.onNodeWithText("2-4").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(topLeft.top, topRight.top, .1f)
        org.junit.Assert.assertEquals(bottomLeft.top, bottomRight.top, .1f)
        org.junit.Assert.assertEquals(topLeft.left, bottomLeft.left, .1f)
        org.junit.Assert.assertTrue(bottomLeft.top > topRight.bottom)
    }

    @Test
    fun showsScreenshotAnnotationsDetailsAndClearAction() {
        val frame = loadFixture()
        val detection = checkNotNull(GameVisionDetector().analyze(frame))
        val inventory = detection.itemCards.mapIndexed { index, card -> card.copy(remainingCount = listOf(3, 3, 1)[index], countText = "测试数量",
            templatePreview = LocalTemplateMatcher().preparePreview(card)) }
        val completion = FragmentCompletionPlanner().analyze(frame, detection, inventory)
        val result = AnalysisResult(
            (completion.recommendations + CellRecommendation(3, 4, 0.25, "测试补全")).distinctBy { it.row to it.column },
            123, ResultSource.LIVE, detection.geometry, inventory,
            statusText = "有限前瞻 · 现场计算",
            boardCells = detection.boardCells,
            boardObjects = completion.objects,
            boardContentScore = detection.boardContentScore,
            timings = AnalysisTimings(boardMs = 60, placementMatchingMs = 30, explorationMs = 12),
        )
        RecognitionDebugStore.publish(RecognitionDebugSnapshot(1_788_120_000_000, frame, result, "识别完成", "有限前瞻", 123))
        compose.setContent { BAGridMasterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecognitionDebugPanel() } } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("最近一次识别的截图")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("最近一次识别的截图").assertIsDisplayed()
        compose.onNodeWithText("识别：90 ms · 推荐：12 ms", substring = true).assertExists()
        compose.onNodeWithText("保存标注图").assertExists()
        compose.onNodeWithText("查看原图").performScrollTo().performClick()
        compose.onNodeWithText("保存原图").assertExists()
        compose.onNodeWithText("显示标注").performClick()
        saveScreenshot("debug-panel-preview.png")
        compose.onNodeWithText("展开所有识别信息").performScrollTo().performClick()
        compose.onNodeWithText("贴图预旋转 / 预分割").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("物品a预旋转参考").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("物品a分格1").assertExists()
        compose.onNodeWithText("第一步：格子/物品存在判断", substring = true).assertExists()
        compose.onNodeWithText("未解释连通区", substring = true).assertDoesNotExist()
        compose.onNodeWithText("E4 [已勾选]", substring = true).assertExists()
        compose.onNodeWithContentDescription("最近一次识别的截图").performScrollTo().performClick()
        compose.onNodeWithText("双击放大点击位置", substring = true).assertIsDisplayed()
        saveScreenshot("debug-panel-zoom.png")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("清空调试记录").performScrollTo().performClick()
        compose.onNodeWithText("暂无记录", substring = true).assertExists()
    }

    @Test
    fun rejectedAttemptDoesNotDisplayPreviousSuccess() {
        val frame = RgbaFrame(320, 240, 1280, ByteArray(320 * 240 * 4), 2)
        RecognitionDebugStore.publish(RecognitionDebugSnapshot(2, frame, null, "未识别到内容", "贪婪", 90))
        compose.setContent { BAGridMasterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecognitionDebugPanel() } } }
        compose.onNodeWithText("未识别到内容", substring = true).assertExists()
        compose.onNodeWithText("展开所有识别信息").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), name)
        val roots = compose.onAllNodes(isRoot())
        file.outputStream().use { roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun loadFixture(): RgbaFrame {
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets.open("debug-board.jpg").use { BitmapFactory.decodeStream(it) }
        val colors = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(colors.size * 4)
        colors.forEachIndexed { index, argb ->
            bytes[index * 4] = (argb shr 16).toByte()
            bytes[index * 4 + 1] = (argb shr 8).toByte()
            bytes[index * 4 + 2] = argb.toByte()
            bytes[index * 4 + 3] = 255.toByte()
        }
        return RgbaFrame(bitmap.width, bitmap.height, bitmap.width * 4, bytes, 1).also { bitmap.recycle() }
    }
}
