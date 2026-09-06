package com.bagridmaster.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.bagridmaster.app.assistant.AssistantStatus
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeSourceControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceUsesAssistantButtonTypographyAndCentersSingleLineText() {
        showHero()
        fun layout(text: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val start = layout("启动悬浮助手").layoutInput.style
        val source = layout("请选择识别来源")
        assertEquals(start.fontSize, source.layoutInput.style.fontSize)
        assertEquals(start.fontWeight, source.layoutInput.style.fontWeight)
        assertEquals(start.fontFamily, source.layoutInput.style.fontFamily)
        assertEquals(start.lineHeight, source.layoutInput.style.lineHeight)
        assertEquals(start.letterSpacing, source.layoutInput.style.letterSpacing)
        assertEquals(1, source.lineCount)
        val labelBounds = compose.onNodeWithText("请选择识别来源", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val buttonBounds = compose.onNodeWithTag("image_source").fetchSemanticsNode().boundsInRoot
        assertEquals(buttonBounds.center.y, labelBounds.center.y, 1f)
    }

    @Test fun sourceStaysRightOfStartWithIdenticalWidthForAllChoices() {
        showHero()
        compose.onNodeWithText("请授权悬浮显示、相册权限（全部始终允许），然后选择识别来源。").assertExists()
        val button = compose.onNodeWithTag("toggle_assistant")
        val source = compose.onNodeWithTag("image_source")
        button.assertIsNotEnabled()
        source.assertTextContains("请选择识别来源")
        val initial = source.fetchSemanticsNode().boundsInRoot
        assertTrue(initial.left >= button.fetchSemanticsNode().boundsInRoot.right)

        for ((tag, label) in listOf(
            "SCREEN_CAPTURE" to "来源：屏幕捕获",
            "LATEST_PHOTO" to "来源：最新截图",
            "SELECTED_PHOTO" to "来源：自行选择",
            "UNSELECTED" to "请选择识别来源",
        )) {
            source.performClick()
            compose.onNodeWithTag("source_UNSELECTED").assertExists()
            compose.onNodeWithTag("source_SCREEN_CAPTURE").assertTextContains("来源：屏幕捕获（推荐）")
            compose.onNodeWithTag("source_LATEST_PHOTO").assertTextContains("来源：最新截图（次选）")
            compose.onNodeWithTag("source_SELECTED_PHOTO").assertTextContains("来源：自行选择（备用）")
            compose.onNodeWithTag("source_$tag").performClick()
            source.assertTextContains(label)
            compose.onNodeWithText("来源：屏幕捕获（推荐）").assertDoesNotExist()
            compose.onNodeWithText("来源：最新截图（次选）").assertDoesNotExist()
            compose.onNodeWithText("来源：自行选择（备用）").assertDoesNotExist()
            val current = source.fetchSemanticsNode().boundsInRoot
            assertEquals(initial.width, current.width, 0.5f)
            assertEquals(initial.left, current.left, 0.5f)
            val startBounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(current.top < startBounds.bottom && current.bottom > startBounds.top)
            if (tag == "UNSELECTED") button.assertIsNotEnabled() else button.assertIsEnabled()
        }
        compose.onNodeWithText("启动时授权，直接采集画面").assertDoesNotExist()
        compose.onNodeWithText("手动截图后读取最新图片").assertDoesNotExist()
    }

    @Test fun selectedPhotoReplacesAssistantActionWithoutOverlayPermission() {
        showHero(ImageInputMode.SELECTED_PHOTO, overlayAllowed = false)
        compose.onNodeWithTag("toggle_assistant").assertIsEnabled().assertTextContains("选择图片")
        compose.onNodeWithText("点击「选择图片」，在系统窗口中确认图片；识别结果会直接显示在首页。").assertExists()
    }

    @Test fun galleryDescriptionUsesRequestedWordingAndSourceChangeCanBeLocked() {
        showHero(ImageInputMode.LATEST_PHOTO, changing = true)
        compose.onNodeWithText("点击「清屏」后，请手动截图，之后点击「识别」").assertExists()
        compose.onNodeWithTag("image_source").assertIsNotEnabled()
        compose.onNodeWithTag("toggle_assistant").assertIsNotEnabled()
    }

    private fun showHero(initial: ImageInputMode? = null, changing: Boolean = false, overlayAllowed: Boolean = true) {
        compose.setContent {
            var selected by remember { mutableStateOf(initial) }
            BAGridMasterTheme {
                Box(Modifier.width(320.dp)) {
                    StatusHero(
                        overlayRunning = false, captureActive = false, overlayAllowed = overlayAllowed,
                        imageInputMode = selected, status = AssistantStatus(), changingInput = changing,
                        onToggleOverlay = {}, onImageInputMode = { selected = it },
                    )
                }
            }
        }
    }
}
