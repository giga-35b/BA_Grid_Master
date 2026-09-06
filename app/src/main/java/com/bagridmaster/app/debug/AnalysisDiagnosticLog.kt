package com.bagridmaster.app.debug

import android.util.Log
import com.bagridmaster.app.analysis.AnalysisResult

/** Local logcat only: no screenshots, gallery filenames or uploads. Available in release too. */
object AnalysisDiagnosticLog {
    fun record(attemptId: Long, result: AnalysisResult) {
        Log.i("BAGridAnalysis", "attempt=$attemptId ${result.timingLogLine()}")
        result.cellDiagnosticLines().forEach { Log.d("BAGridCells", "attempt=$attemptId $it") }
    }
}
