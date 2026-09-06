package com.bagridmaster.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagridmaster.app.data.BoardCalibrationRepository
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
fun BoardCalibrationCard() {
    val context = LocalContext.current
    val repository = remember { BoardCalibrationRepository(context) }
    val profiles by repository.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("棋盘经验坐标", style = MaterialTheme.typography.titleMedium)
            Text("已保存 ${profiles.size} 组。翻开不超过8格时，连续3次稳定检测取对角坐标均值；来源和图片尺寸分别记录。",
                style = MaterialTheme.typography.bodySmall)
            profiles.forEach { profile ->
                val key = profile.key
                val region = profile.region
                Text("${key.source.label} · ${key.width}×${key.height}\n(${region.left}, ${region.top}) – (${region.right}, ${region.bottom})",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("每次仍先检测棋盘，小偏差才校正；偏差过大则保留现场检测，待新的少量翻开画面稳定后重学。移除后会清空已保存坐标和正在累计的样本，不影响其他设置。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirming = true }, enabled = !clearing) {
                Text(if (clearing) "正在移除…" else "移除经验坐标")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("移除全部经验坐标？") },
        text = { Text("下次识别将使用现场检测；在少量翻开的稳定棋盘上重新学习。之前的识别记录与其他设置不会删除。") },
        confirmButton = { TextButton(onClick = {
            confirming = false
            clearing = true
            scope.launch {
                try { repository.clear(); message = "经验坐标已移除，将重新学习" }
                catch (error: IOException) { message = "移除失败，请重试" }
                finally { clearing = false }
            }
        }) { Text("确认移除") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
    )
}
