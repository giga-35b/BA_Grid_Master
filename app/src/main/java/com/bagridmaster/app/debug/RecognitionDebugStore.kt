package com.bagridmaster.app.debug

import com.bagridmaster.app.analysis.itemCode
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.timingSummary
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.cellAddress
import com.bagridmaster.app.vision.BoardCellState
import com.bagridmaster.app.vision.RgbaFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecognitionDebugSnapshot(
    val capturedAtMillis: Long,
    val frame: RgbaFrame?,
    val result: AnalysisResult?,
    val message: String,
    val algorithm: String,
    val elapsedMs: Long = 0,
    val inputDescription: String = "屏幕捕获",
)

/** Only the latest attempt lives in process memory. No automatic disk storage or upload. */
object RecognitionDebugStore {
    private val mutableLatest = MutableStateFlow<RecognitionDebugSnapshot?>(null)
    val latest = mutableLatest.asStateFlow()
    fun publish(snapshot: RecognitionDebugSnapshot) { mutableLatest.value = snapshot }
    fun clear() { mutableLatest.value = null }
}

fun BoardCellState.debugLabel(): String = when (this) {
    BoardCellState.CLOSED -> "未翻开"
    BoardCellState.SELECTED -> "已勾选（按未翻开计算）"
    BoardCellState.OPEN_EMPTY -> "已翻开空格"
    BoardCellState.OPEN_FRAGMENT -> "点亮碎片（含浅色）"
    BoardCellState.OPEN_OBJECT -> "完成物品剪影"
    BoardCellState.UNCERTAIN -> "待确认（非空格）"
}

fun BoardCellState.debugSymbol(): String = when (this) {
    BoardCellState.CLOSED -> "·"
    BoardCellState.SELECTED -> "S"
    BoardCellState.OPEN_EMPTY -> "E"
    BoardCellState.OPEN_FRAGMENT -> "L"
    BoardCellState.OPEN_OBJECT -> "C"
    BoardCellState.UNCERTAIN -> "?"
}

fun ScreenRegion.debugLabel(): String = "($left,$top)–($right,$bottom) ${width}×$height"

fun AnalysisResult.debugReport(): String = buildString {
    appendLine("$statusText · $source")
    appendLine(timingSummary())
    timings?.let { appendLine("采集/等候：${it.inputMs} ms · 总耗时：${elapsedMs} ms") }
    appendLine(geometry.detectorLabel)
    appendLine("棋盘 ${geometry.board.columns}×${geometry.board.rows} 置信度 ${percent(geometry.board.confidence)}")
    appendLine("棋盘像素 ${geometry.board.region.debugLabel()}")
    appendLine("画面有效格占比 ${boardContentScore?.let(::percent) ?: "未知"}")
    appendLine("物品托盘 ${geometry.itemTray?.debugLabel() ?: "未识别"}")
    diagnosticNotes.forEach { appendLine("注意：$it") }
    appendLine("\n左下物品（按从左到右编号，不猜测物品名称）")
    inventory.forEach { item ->
        appendLine("物品${itemCode(item.index)}：${item.shape?.compactLabel ?: "形状未知"} ×${item.remainingCount ?: "?"} ${if (item.isFinished) "已完成" else ""} · ${percent(item.confidence)}")
        item.shape?.let { shape ->
            for (row in 0 until shape.rows) appendLine((0 until shape.columns).joinToString("") { column ->
                if (com.bagridmaster.app.analysis.ShapeCell(row, column) in shape.occupiedCells) "■" else "□"
            })
        }
        appendLine("卡片 ${item.cardRegion.debugLabel()}")
        appendLine("贴图 ${item.spriteRegion.debugLabel()} / 采集 ${item.spriteTemplate.width}×${item.spriteTemplate.height}")
        item.templatePreview?.let { appendLine("预旋转/预分割：${it.explanation}") }
        appendLine("形状区 ${item.footprintRegion.debugLabel()}")
        appendLine("数量区 ${item.countRegion.debugLabel()} / OCR「${item.countText.replace('\n', ' ')}」")
        if (item.countEvidence.isNotBlank()) appendLine("数量依据：${item.countEvidence}")
        if (item.finishText.isNotBlank() || item.finishEvidence.isNotBlank()) {
            appendLine("完成状态：${item.finishEvidence} / 中央区 OCR「${item.finishText.replace('\n', ' ')}」")
        }
    }
    appendLine("\n格子：·未开 S勾选 E空 L点亮 C完成剪影 ?待确认")
    appendLine("  " + (0 until geometry.board.columns).joinToString(" ") { ('A' + it).toString() })
    for (row in 0 until geometry.board.rows) {
        appendLine("${row + 1} " + (0 until geometry.board.columns).joinToString(" ") { column ->
            boardCells.firstOrNull { it.row == row && it.column == column }?.state?.debugSymbol() ?: "?"
        })
    }
    BoardCellState.entries.forEach { state ->
        val cells = boardCells.filter { it.state == state }
        appendLine("${state.debugLabel()} ${cells.size}格：" + cells.joinToString(" ") { cellAddress(it.row, it.column) }.ifEmpty { "无" })
    }
    appendLine("\n第一步：格子/物品存在判断见上方状态汇总；逐格判断细节仅记入内部日志。")
    for (phase in BoardObjectPhase.entries) {
        appendLine("\n${phase.label}的物品区域")
        val objects = boardObjects.filter { it.phase == phase }
        if (objects.isEmpty()) appendLine("未检测到")
        objects.forEach { item ->
            appendLine("${item.id} ${item.typeLabel} · ${if (phase == BoardObjectPhase.LIT) "颜色匹配分（非位置概率）" else "轮廓匹配分"} ${percent(item.confidence)}")
            appendLine("已见位置：" + item.observedCells.sortedWith(compareBy({ it.row }, { it.column })).joinToString(" ") { cellAddress(it.row, it.column) })
            appendLine(item.evidence)
            if (phase == BoardObjectPhase.LIT) {
                appendLine("第二步：种类候选（外观分非校准概率；低分不会抹去物品存在）")
                item.typeScores.forEach { score ->
                    appendLine("  物品${itemCode(score.itemIndex)} ${percent(score.appearanceScore)} ${if (score.retained) "保留候选" else "未保留"}")
                }
                if (item.typeScores.isEmpty()) appendLine("  无可用种类证据，保留点亮事实")
                appendLine("第三步：摆放识别（独立严格门槛；不确定时不宣称完整摆放）")
            }
            item.localMatch?.let { match ->
                appendLine("局部贴图匹配：${if (match.accepted) "已采用" else "未确定"} · 候选差距 ${percent(match.margin)}")
                appendLine(match.evidence)
                match.candidates.forEachIndexed { index, candidate ->
                    appendLine("${if (index == 0 && match.accepted) "采用摆法" else "候选${index + 1}"} 物品${itemCode(candidate.itemIndex)} · 空间纹理分 ${percent(candidate.similarity)} · 轮廓 ${percent(candidate.maskSimilarity)} · 明暗纹理 ${percent(candidate.textureSimilarity)}")
                    item.observedCells.sortedWith(compareBy({ it.row }, { it.column })).forEach { cell ->
                        appendLine("  ${cellAddress(cell.row, cell.column)} → ${candidate.localLabel(cell)}")
                    }
                    appendLine("  推定占格：" + candidate.cells.sortedWith(compareBy({ it.row }, { it.column })).joinToString(" ") { cellAddress(it.row, it.column) })
                    appendLine("  对齐旋转 ${candidate.rotationDegrees}°（相对采集贴图顺时针；不是游戏内的旋转角）")
                    candidate.preRotationDegrees?.let { appendLine("  形状长轴预对齐：顺时针 ${it}° → 水平贴图 → 按格切分/横竖摆放匹配") }
                }
            }
            if (item.completionEvidence.isNotBlank()) appendLine(item.completionEvidence)
        }
    }
    appendLine("\n建议（包括已勾选但未翻开的格，不是要求重复点击）")
    if (recommendations.isEmpty()) appendLine("暂无可靠建议")
    recommendations.forEach {
        val selected = boardCells.any { cell -> cell.row == it.row && cell.column == it.column && cell.state == BoardCellState.SELECTED }
        appendLine("${cellAddress(it.row, it.column)}${if (selected) " [已勾选]" else ""}：${it.strategy}；评分/估计落空 ${percent(it.missProbability)}")
    }
    appendLine("颜色分与空间纹理分不是校准概率。全部点亮物品可完整补开时，其摆法和扣除后的数量仅用于本轮探索规划；以上截图、格子状态和清单仍是实际观测，不把计划当成已翻开事实。")
}

private fun percent(value: Double): String = "${(value * 100).toInt()}%"
