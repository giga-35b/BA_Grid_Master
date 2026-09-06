package com.bagridmaster.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class AppTab(val label: String) { HOME("首页"), RESULTS("结果信息"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    permissionJump: Int = 0,
    detailTitle: String? = null,
    onBack: () -> Unit = {},
    content: @Composable ColumnScope.(AppTab) -> Unit,
) {
    val pageState = rememberSaveableStateHolder()
    BackHandler(enabled = detailTitle != null, onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(detailTitle ?: if (selected == AppTab.HOME) "BA Grid Master" else selected.label,
                        fontWeight = FontWeight.SemiBold)
                    Text(if (selected == AppTab.HOME) "挖格子小游戏悬浮助手" else "BA Grid Master",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = {
                if (detailTitle != null) TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Text("‹ 返回") }
            })
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("tab_${tab.name}"),
                        selected = selected == tab,
                        onClick = { onSelect(tab) },
                        icon = { TabIcon(tab) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        pageState.SaveableStateProvider(selected.name + (detailTitle ?: "")) {
            val scrollState = rememberScrollState()
            var previousJump by rememberSaveable { mutableIntStateOf(0) }
            LaunchedEffect(permissionJump) {
                if (selected == AppTab.SETTINGS && previousJump != permissionJump) {
                    scrollState.scrollTo(0)
                    previousJump = permissionJump
                }
            }
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("page_${selected.name}"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content(selected) }
        }
    }
}

/** Small code-native icons, without adding an icon dependency to the APK. */
@Composable
private fun TabIcon(tab: AppTab) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.8f * u)
        when (tab) {
            AppTab.HOME -> {
                drawPath(Path().apply {
                    moveTo(3*u, 11*u); lineTo(12*u, 3*u); lineTo(21*u, 11*u)
                    moveTo(5*u, 10*u); lineTo(5*u, 21*u); lineTo(10*u, 21*u)
                    lineTo(10*u, 15*u); lineTo(14*u, 15*u); lineTo(14*u, 21*u)
                    lineTo(19*u, 21*u); lineTo(19*u, 10*u)
                }, color, style = stroke)
            }
            AppTab.RESULTS -> {
                drawRect(color, Offset(5*u, 3*u), Size(14*u, 18*u), style = stroke)
                for (y in listOf(8f, 12f, 16f)) drawLine(color, Offset(8*u, y*u), Offset(16*u, y*u), 1.8f*u)
            }
            AppTab.SETTINGS -> {
                for ((y, x) in listOf(6f to 9f, 12f to 16f, 18f to 7f)) {
                    drawLine(color, Offset(3*u, y*u), Offset(21*u, y*u), 1.8f*u)
                    drawCircle(color, 2.3f*u, Offset(x*u, y*u), style = stroke)
                }
            }
        }
    }
}
