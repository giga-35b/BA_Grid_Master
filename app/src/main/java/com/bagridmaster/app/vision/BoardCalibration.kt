package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.model.ImageInputMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Coordinates always refer to the actual input bitmap, never overlay/window coordinates. */
data class CalibrationKey(val source: ImageInputMode, val width: Int, val height: Int)
data class BoardCalibrationProfile(val key: CalibrationKey, val region: ScreenRegion)
data class CalibrationDecision(val region: ScreenRegion, val note: String)
data class CalibratedDetection(val detection: GameVisionDetection?, val note: String)

class BoardCalibrationTracker(saved: List<BoardCalibrationProfile> = emptyList()) {
    private val trusted = linkedMapOf<CalibrationKey, ScreenRegion>().apply {
        saved.filter { validRegion(it.key, it.region) }.takeLast(MAX_PROFILES).forEach { put(it.key, it.region) }
    }
    private val samples = mutableMapOf<CalibrationKey, List<ScreenRegion>>()
    private var previousKey: CalibrationKey? = null
    val profiles: List<BoardCalibrationProfile> get() = trusted.map { BoardCalibrationProfile(it.key, it.value) }

    fun observe(key: CalibrationKey, raw: ScreenRegion, learnable: Boolean): CalibrationDecision {
        switchInput(key)
        val reference = trusted[key]
        if (!validRegion(key, raw)) {
            samples.remove(key)
            return CalibrationDecision(raw, "经验校正：当前坐标不满足学习条件，使用本次检测")
        }
        val distance = reference?.let { cornerError(raw, it) }
        val limit = reference?.let { correctionLimit(key, it) }
        if (reference != null && distance!! <= limit!!) {
            samples.remove(key)
            return CalibrationDecision(reference,
                "经验校正：采用经验坐标；最大角点误差 ${distance}px ≤ ${limit}px；原始 ${raw.label()} → 经验 ${reference.label()}")
        }
        if (!learnable) {
            samples.remove(key)
            return CalibrationDecision(raw, if (reference == null) {
                "经验校正：暂无该来源/尺寸的经验坐标；仅在翻开≤8格且画面质量可靠时学习，当前不累计"
            } else {
                "经验校正：偏差 ${distance}px > ${limit}px，不强行校正；当前不适合重学，等待少量翻开的稳定棋盘；经验 ${reference.label()}，原始 ${raw.label()}"
            })
        }
        val tolerance = stabilityTolerance(key)
        val previous = samples[key].orEmpty()
        val stable = previous.all { cornerError(it, raw) <= tolerance }
        val batch = if (stable) previous + raw else listOf(raw)
        samples[key] = batch
        if (batch.size < REQUIRED_SAMPLES) {
            val progress = "连续稳定 ${batch.size}/$REQUIRED_SAMPLES 次，容差 ${tolerance}px"
            return CalibrationDecision(raw, if (reference == null) "经验校正：学习中（$progress），使用本次检测"
                else "经验校正：偏差 ${distance}px > ${limit}px，不强行校正；重新学习中（$progress）")
        }
        val average = ScreenRegion(
            batch.map { it.left }.average().roundToInt(), batch.map { it.top }.average().roundToInt(),
            batch.map { it.right }.average().roundToInt(), batch.map { it.bottom }.average().roundToInt(),
        )
        trusted.remove(key)
        trusted[key] = average
        while (trusted.size > MAX_PROFILES) trusted.remove(trusted.keys.first())
        samples.remove(key)
        return CalibrationDecision(average,
            "经验校正：${if (reference == null) "已建立" else "已重新建立"}经验坐标 ${average.label()}，取连续$REQUIRED_SAMPLES 次原始检测均值；本次原始 ${raw.label()}")
    }

    fun missed(key: CalibrationKey) {
        switchInput(key)
        samples.remove(key)
    }

    fun clear() { trusted.clear(); samples.clear(); previousKey = null }

    fun rejectCorrection(previous: List<BoardCalibrationProfile>) {
        trusted.clear()
        previous.forEach { trusted[it.key] = it.region }
        samples.clear()
    }

    private fun switchInput(key: CalibrationKey) {
        // A different source/size interrupts a consecutive learning sequence, but keeps trusted profiles.
        if (previousKey != key) samples.clear()
        previousKey = key
    }

    companion object {
        const val REQUIRED_SAMPLES = 3
        const val MAX_PROFILES = 8
        fun stabilityTolerance(key: CalibrationKey): Int = max(2, (4.0 * key.width / 2400).roundToInt())
        fun correctionLimit(key: CalibrationKey, region: ScreenRegion): Int = min(
            50.0 * key.width / 2400,
            min(region.width / 9.0, region.height / 5.0) * 0.4,
        ).roundToInt().coerceAtLeast(1)
        fun cornerError(a: ScreenRegion, b: ScreenRegion): Int = maxOf(
            abs(a.left - b.left), abs(a.top - b.top), abs(a.right - b.right), abs(a.bottom - b.bottom))
        fun validRegion(key: CalibrationKey, region: ScreenRegion): Boolean =
            key.width in 320..16384 && key.height in 240..16384 && region.left in 0 until key.width && region.top in 0 until key.height &&
                region.right in (region.left + 1)..key.width && region.bottom in (region.top + 1)..key.height && region.width >= 72 && region.height >= 40 &&
                region.width.toDouble() / region.height / (9.0 / 5.0) in 0.90..1.10
    }
}

/** Shared production/test path: fresh detection is mandatory; correction re-runs all pixel sampling. */
fun calibratedDetection(
    frame: RgbaFrame,
    source: ImageInputMode,
    tracker: BoardCalibrationTracker,
    detector: GameVisionDetector,
): CalibratedDetection {
    val key = CalibrationKey(source, frame.width, frame.height)
    val raw = detector.analyze(frame)
    if (raw == null) {
        tracker.missed(key)
        return CalibratedDetection(null, "经验校正：本次未检测到有效棋盘，不使用历史坐标强行识别")
    }
    return calibrateLocatedBoard(frame, source, tracker, detector, raw)
}

/** Kept separate for regression injection of detector drift, not as a history-only detector. */
internal fun calibrateLocatedBoard(
    frame: RgbaFrame,
    source: ImageInputMode,
    tracker: BoardCalibrationTracker,
    detector: GameVisionDetector,
    raw: GameVisionDetection,
): CalibratedDetection {
    val key = CalibrationKey(source, frame.width, frame.height)
    val learnable = raw.geometry.board.confidence >= 0.90 && raw.boardContentScore >= 0.90 &&
        raw.strictSquareFitUsed && raw.strictSquareFitConfidence >= 0.72 &&
        raw.boardCells.none { it.state == BoardCellState.UNCERTAIN } &&
        raw.boardCells.size == 45 && raw.boardCells.count { !it.state.isUnopened } <= 8 &&
        // Historical coordinates are only learned from a complete three-card layout. Detection
        // may still proceed with two shapes, but a partial tray must never teach screen geometry.
        raw.itemCards.count { it.shape != null && it.confidence >= 0.70 } == 3
    val previousProfiles = tracker.profiles
    val decision = tracker.observe(key, raw.geometry.board.region, learnable)
    if (decision.region == raw.geometry.board.region) return CalibratedDetection(raw, decision.note)
    val corrected = detector.reinspect(frame, raw, decision.region)
    if (corrected == null || corrected.boardContentScore < max(0.6, raw.boardContentScore - 0.10)) {
        tracker.rejectCorrection(previousProfiles)
        return CalibratedDetection(raw, decision.note + "；校正后场景校验未通过，已撤回校正，使用原始检测")
    }
    return CalibratedDetection(corrected.copy(geometry = corrected.geometry.copy(
        detectorLabel = corrected.geometry.detectorLabel + " · 经验坐标校正")), decision.note)
}

private fun ScreenRegion.label() = "($left,$top)–($right,$bottom)"

/** Small versioned text format: only trusted corners survive process restarts, not pending samples. */
object BoardCalibrationCodec {
    fun encode(profiles: List<BoardCalibrationProfile>): String = "v1\n" + profiles.joinToString("\n") {
        "${it.key.source.name}|${it.key.width}|${it.key.height}|${it.region.left}|${it.region.top}|${it.region.right}|${it.region.bottom}"
    }

    fun decode(text: String?): List<BoardCalibrationProfile> {
        if (text == null || text.length > 4096) return emptyList()
        val lines = text.lines()
        if (lines.firstOrNull() != "v1") return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 7) return@mapNotNull null
            val source = ImageInputMode.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val n = parts.drop(1).map { it.toIntOrNull() ?: return@mapNotNull null }
            val key = CalibrationKey(source, n[0], n[1])
            val region = ScreenRegion(n[2], n[3], n[4], n[5])
            BoardCalibrationProfile(key, region).takeIf { BoardCalibrationTracker.validRegion(key, region) }
        }.distinctBy { it.key }.takeLast(BoardCalibrationTracker.MAX_PROFILES)
    }
}
