package com.bagridmaster.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PermissionCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoPermissionOnlyOffersTheOriginalGrantAction() {
        val granted = mutableStateOf(false)
        var requests = 0
        compose.setContent {
            BAGridMasterTheme {
                Box(Modifier.width(320.dp)) {
                    PermissionCard(
                        title = "相册权限", description = "用于自动读取相册最新截图",
                        granted = granted.value, actionLabel = if (granted.value) "已授权" else "去授权",
                        onAction = { requests++ },
                    )
                }
            }
        }
        compose.onNodeWithText("去授权").assertIsEnabled().performClick()
        compose.onNodeWithText("调整授权范围").assertDoesNotExist()
        compose.runOnIdle { granted.value = true }
        compose.onNodeWithText("已授权").assertIsNotEnabled()
        compose.onNodeWithText("调整授权范围").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, requests)
        }
    }
}
