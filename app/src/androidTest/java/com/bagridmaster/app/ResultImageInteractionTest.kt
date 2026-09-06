package com.bagridmaster.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.bagridmaster.app.debug.RecognitionDebugSnapshot
import com.bagridmaster.app.debug.RecognitionDebugStore
import com.bagridmaster.app.ui.RecognitionDebugPanel
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import com.bagridmaster.app.vision.RgbaFrame
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ResultImageInteractionTest {
    @get:Rule val compose = createComposeRule()
    @After fun clear() { RecognitionDebugStore.clear() }

    @Test fun thumbnailOpensImageAndDoubleTapTogglesZoomWithoutResetButton() {
        RecognitionDebugStore.publish(RecognitionDebugSnapshot(1,
            RgbaFrame(400, 200, 1600, ByteArray(400 * 200 * 4) { 127 }, 1), null, "test", "贪婪"))
        compose.setContent { BAGridMasterTheme { RecognitionDebugPanel() } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("result_thumbnail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("放大查看").assertDoesNotExist()
        compose.onNodeWithTag("result_thumbnail").performClick()
        compose.onNodeWithTag("zoom_image").assertIsDisplayed()
        compose.onNodeWithText("复位").assertDoesNotExist()
        compose.onNodeWithTag("zoom_image").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("zoom_image").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 3.0"))
        compose.onNodeWithTag("zoom_image").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("zoom_image").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 1.0"))
        compose.onNodeWithText("旋转").assertIsDisplayed()
        compose.onNodeWithTag("rotate_result_image").performClick()
        compose.onNodeWithTag("zoom_image")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 1.0"))
            .performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("zoom_image")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 3.0"))
            .performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("rotate_result_image").performClick()
        compose.onNodeWithTag("zoom_image")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 1.0"))
            .performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("zoom_image")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩放 3.0"))
        compose.onNodeWithText("原图", substring = false).performClick()
        compose.onAllNodesWithText("保存原图").onLast().assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("zoom_image").assertDoesNotExist()
    }
}
