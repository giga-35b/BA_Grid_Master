package com.bagridmaster.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.bagridmaster.app.ui.AboutPage
import com.bagridmaster.app.ui.ProjectLinks
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contactAndProjectAreSideBySideButtonsWithoutVisibleUrls() {
        compose.setContent {
            BAGridMasterTheme {
                Column(Modifier.width(320.dp)) { AboutPage() }
            }
        }
        val contact = compose.onNodeWithText("联系作者").assertHasClickAction().assertIsDisplayed()
        val project = compose.onNodeWithText("项目地址").assertHasClickAction().assertIsDisplayed()
        val left = contact.fetchSemanticsNode().boundsInRoot
        val right = project.fetchSemanticsNode().boundsInRoot
        assertTrue(right.left >= left.right)
        assertEquals(left.center.y, right.center.y, 1f)
        assertEquals(left.width, right.width, 1f)
        compose.onNodeWithText(ProjectLinks.BILIBILI, substring = true).assertDoesNotExist()
        compose.onNodeWithText(ProjectLinks.GITHUB, substring = true).assertDoesNotExist()
        compose.onNodeWithText("GitHub 地址待补充", substring = true).assertDoesNotExist()
        assertEquals("https://github.com/giga-35b/BA_Grid_Master", ProjectLinks.GITHUB)
    }
}
