package com.bagridmaster.app.debug

import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.cellAddress

/** Keep per-cell evidence out of the UI while retaining every measured judgment for ADB. */
fun AnalysisResult.cellDiagnosticLines(): List<String> = boardCells.mapNotNull { cell ->
    cell.presence?.let { p ->
        "${cellAddress(cell.row, cell.column)} state=${cell.state} " +
            "chromatic=${p.chromaticRatio} pale=${p.paleRatio} foreground=${p.connectedForegroundRatio} " +
            "openedBackground=${p.openedBackgroundRatio} unexplained=${p.unexplainedRatio}; ${p.explanation}"
    }
}

fun AnalysisResult.timingLogLine(): String = buildString {
    append("source=$source frame=${frameWidthPx}x$frameHeightPx totalMs=$elapsedMs")
    val t = timings
    if (t == null) append(" timings=unavailable") else {
        append(" inputMs=${t.inputMs} recognitionMs=${t.recognitionMs} recommendationMs=${t.recommendationMs}")
        append(" boardMs=${t.boardMs} ocrMs=${t.inventoryOcrMs} previewMs=${t.templatePreparationMs}")
        append(" objectMs=${t.objectRecognitionMs} placementMs=${t.placementMatchingMs}")
        append(" completionMs=${t.completionMs} explorationMs=${t.explorationMs}")
    }
}
