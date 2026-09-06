package com.bagridmaster.app.model

data class AppSettings(
    val bubbleSizeDp: Float = 64f,
    val bubbleOpacity: Float = 0.92f,
    val overlayContentMode: OverlayContentMode = OverlayContentMode.COMPACT,
    val temporaryHideSeconds: Int = 5,
    val snapToEdge: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val captureDelayMs: Int = 150,
    val markerColor: MarkerColor = MarkerColor.TEAL,
    val markerStrokeDp: Float = 2f,
    val strategyAlgorithm: StrategyAlgorithm = StrategyAlgorithm.LIMITED_LOOKAHEAD,
    val showBoardHeaders: Boolean = true,
    val showKnownObjects: Boolean = true,
    val showInventoryInfo: Boolean = false,
    val experienceCalibrationEnabled: Boolean = true,
    val blackBorderDetectionEnabled: Boolean = true,
    val imageInputMode: ImageInputMode = ImageInputMode.SCREEN_CAPTURE,
    val hasSelectedImageSource: Boolean = false,
    val showTopCandidates: Boolean = false,
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
)

fun normalizedTemporaryHideSeconds(value: Int): Int = value.coerceIn(1, 10)

enum class ImageInputMode(val label: String) {
    SCREEN_CAPTURE("屏幕捕获"),
    LATEST_PHOTO("最新截图"),
    SELECTED_PHOTO("自行选择"),
}

enum class StrategyAlgorithm(
    val label: String,
    val description: String,
) {
    LIMITED_LOOKAHEAD("有限前瞻", "默认；比较当前命中率与一次落空后的下一步收益"),
    GREEDY("贪婪", "始终选择当前后验命中率最高的探索格，计算更快"),
}

enum class OverlayContentMode(
    val label: String,
    val description: String,
) {
    BUTTON_ONLY("仅按钮", "只显示触发按钮，最少遮挡游戏画面"),
    COMPACT("有限信息", "建议坐标（* 为探索格）、探索置信度、识别和推荐耗时"),
    FULL("完整信息", "另显示棋盘顶点、物品形状及数量、已识别清单；超长内容可滚动"),
    ;

    companion object {
        fun fromSaved(value: String?): OverlayContentMode =
            if (value == "DEBUG") FULL else entries.firstOrNull { it.name == value } ?: COMPACT
    }
}

fun normalizedBubbleSize(value: Float): Float = if (value.isFinite()) value.coerceIn(30f, 75f) else 64f

enum class MarkerColor(
    val label: String,
    val argb: Int,
) {
    TEAL("青色", 0xFF38E8C6.toInt()),
    AMBER("琥珀色", 0xFFFFC247.toInt()),
    MAGENTA("洋红色", 0xFFFF5DAD.toInt()),
}
