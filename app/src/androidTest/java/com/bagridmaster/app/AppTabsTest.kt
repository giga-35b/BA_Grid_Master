package com.bagridmaster.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import com.bagridmaster.app.model.AppSettings
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import org.junit.Rule
import org.junit.Test

class AppTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsSeparateHomeResultsAndPermissionsAndPreserveHomeExpansion() {
        compose.setContent {
            BAGridMasterTheme {
                AppRoot(
                    settings = AppSettings(), overlayRunning = false, captureActive = false,
                    changingInput = false,
                    onBubbleSize = {}, onBubbleOpacity = {}, onOverlayContentMode = {},
                    onSnapToEdge = {}, onHaptics = {}, onCaptureDelay = {},
                    onMarkerColor = {}, onMarkerStroke = {}, onStrategyAlgorithm = {},
                    onShowBoardHeaders = {}, onShowKnownObjects = {}, onImageInputMode = {},
                )
            }
        }
        compose.onNodeWithTag("page_HOME").assertIsDisplayed()
        compose.onNodeWithText("挖格子小游戏悬浮助手").assertExists()
        compose.onNodeWithText("游戏识别悬浮助手").assertDoesNotExist()
        compose.onNodeWithTag("image_source").assertExists()
        compose.onNodeWithText("显示在其他应用上层").assertDoesNotExist()
        compose.onNodeWithText("1. 到权限管理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("使用说明 ▾").performScrollTo().performClick()
        compose.onNodeWithText("1. 到权限管理", substring = true).assertExists()
        compose.onNodeWithText("每轮先点「清屏」，然后手动截图当前画面，再点击「识别」。", substring = true).assertExists()
        compose.onNodeWithText("点「清屏」后面板不透明度降至10%持续3秒，以便截图，但「识别」按钮在清屏后0.5秒就可点击，无需等待不透明度恢复。").assertExists()
        compose.onNodeWithText("4. 拖动面板可", substring = true).assertDoesNotExist()
        compose.onNodeWithText("4. 自行选择", substring = true).assertExists()
        compose.onNodeWithText("5. 到「结果信息」", substring = true).assertExists()
        compose.onNodeWithTag("tab_RESULTS").performClick()
        compose.onNodeWithTag("page_RESULTS").assertIsDisplayed()
        compose.onNodeWithText("识别调试").assertExists()
        compose.onNodeWithTag("image_source").assertDoesNotExist()
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithText("显示在其他应用上层").assertDoesNotExist()
        compose.onNodeWithText("权限管理").performClick()
        compose.onNodeWithText("显示在其他应用上层").assertExists()
        compose.onNodeWithText("通知权限").assertDoesNotExist()
        compose.onNodeWithText("调整授权范围").assertDoesNotExist()
        compose.onNodeWithText("屏幕捕获会话").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithText("悬浮窗").performClick()
        compose.onNodeWithText("临时隐藏时间").assertExists()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithText("截图与识别").performClick()
        compose.onNodeWithText("经验坐标校正").assertExists()
        compose.onNodeWithText("画面黑边检测").assertExists()
        compose.onNodeWithText("移除经验坐标").performScrollTo().performClick()
        compose.onNodeWithText("移除全部经验坐标？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("tab_HOME").performClick()
        compose.onNodeWithText("使用说明 ▴").assertExists()
        compose.onNodeWithText("权限管理 →").performScrollTo().performClick()
        compose.onNodeWithText("显示在其他应用上层").assertIsDisplayed()
    }
}
