package com.bagridmaster.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bagridmaster.app.BuildConfig
import com.bagridmaster.app.model.ImageInputMode

enum class SettingsPage(val title: String, val description: String) {
    PERMISSIONS("权限管理", "悬浮窗、相册及应用权限"),
    OVERLAY("悬浮窗", "信息显示、按钮大小、透明度与吸附"),
    ANNOTATIONS("游戏覆盖标注", "行列刻度、物品信息与推荐框样式"),
    RECOGNITION("截图与识别", "探索算法、截图等待与棋盘经验坐标"),
    ABOUT("关于", "版本、开源信息与作者"),
}

@Composable
fun SettingsEntry(title: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(description) },
        trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun ImageSourceDropdown(mode: ImageInputMode?, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (ImageInputMode?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(
        null to "请选择识别来源",
        ImageInputMode.SCREEN_CAPTURE to "来源：屏幕捕获（推荐）",
        ImageInputMode.LATEST_PHOTO to "来源：最新截图（次选）",
        ImageInputMode.SELECTED_PHOTO to "来源：自行选择（备用）",
    )
    Box(modifier) {
        Button(onClick = { expanded = true }, enabled = enabled,
            colors = ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth().testTag("image_source")) {
            Text(mode?.let { "来源：${it.label}" } ?: "请选择识别来源", modifier = Modifier.weight(1f),
                // Inherit Button's typography, exactly like the adjacent assistant button.
                // Do not reserve an empty second line: it pushes single-line labels upwards.
                maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            Text("▾", modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (source, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { expanded = false; if (source != mode) onSelect(source) },
                    modifier = Modifier.testTag("source_${source?.name ?: "UNSELECTED"}"),
                )
            }
        }
    }
}

object ProjectLinks {
    const val BILIBILI = "https://space.bilibili.com/15097920"
    // User-provided destination; the repository has not been published yet.
    const val GITHUB = "https://github.com/giga-35b/BA_Grid_Master"
}

@Composable
fun AboutPage() {
    val context = LocalContext.current
    fun copyLink(label: String, url: String, message: String) {
        if (url.isBlank()) {
            Toast.makeText(context, "项目尚未发布，GitHub 地址待补充", Toast.LENGTH_SHORT).show()
            return
        }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, url))
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    ListItem(headlineContent = { Text("BA Grid Master", fontWeight = FontWeight.Bold) },
        supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") })
    ListItem(headlineContent = { Text("开源信息") }, supportingContent = {
        Text("MIT License\n应用源代码采用 MIT 许可。第三方依赖及游戏图片的权利归各自权利人所有。")
    })
    ListItem(headlineContent = { Text("作者") }, supportingContent = { Text("机管giga-35b") })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { copyLink("作者 B 站主页", ProjectLinks.BILIBILI, "已复制作者b站主页链接，请私信联系") },
            modifier = Modifier.weight(1f)) { Text("联系作者") }
        OutlinedButton(onClick = { copyLink("GitHub", ProjectLinks.GITHUB, "已复制github地址") },
            modifier = Modifier.weight(1f)) { Text("项目地址") }
    }
}
