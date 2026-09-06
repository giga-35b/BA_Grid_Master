package com.bagridmaster.app.vision

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.ScreenRegion
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitInventoryCountRecognizer : AutoCloseable {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(
        frame: RgbaFrame,
        cards: List<ItemCardRecognition>,
    ): List<ItemCardRecognition> = cards.map { card ->
        val bitmap = frame.cropBitmap(card.countRegion, upscale = 3, enhanceText = false)
        var text = try {
            recognizeText(bitmap)
        } finally {
            bitmap.recycle()
        }
        var countEvidence = ""
        if (text.isBlank()) {
            val retryRegion = expandedCountRegion(card.countRegion, card.cardRegion)
            val retryBitmap = frame.cropBitmap(retryRegion, upscale = 4, enhanceText = true)
            text = try {
                recognizeText(retryBitmap)
            } finally {
                retryBitmap.recycle()
            }
            countEvidence = if (text.isBlank()) {
                "首次 OCR 为空；扩区并增强对比度后重试仍为空"
            } else {
                "首次 OCR 为空；扩区并增强对比度后重试识别为「${text.replace('\n', ' ')}」"
            }
        }
        // The central Finish banner is NOT inside the bottom-right count ROI.
        val centerText = if (parseInventoryCount(text).remainingCount == null || card.isFinished) {
            val center = frame.cropBitmap(card.spriteRegion, upscale = 3, enhanceText = false)
            try { recognizeText(center) } finally { center.recycle() }
        } else ""
        resolveInventoryTexts(card, text, centerText).copy(countEvidence = countEvidence)
    }

    /** A single tight OCR pass above the located board. The result is only a soft state prior. */
    suspend fun recognizeBoardRemainingCount(
        frame: RgbaFrame,
        board: BoardGeometry,
    ): BoardRemainingCountEvidence? {
        val cellHeight = board.subpixelGrid?.cellHeightPx ?: board.region.height.toDouble() / board.rows
        val region = ScreenRegion(
            left = (board.region.left + board.region.width * 0.28).toInt(),
            top = (board.region.top - cellHeight * 0.95).toInt().coerceAtLeast(0),
            right = (board.region.left + board.region.width * 0.74).toInt().coerceAtMost(frame.width),
            bottom = (board.region.top - cellHeight * 0.03).toInt().coerceAtLeast(1),
        )
        if (region.width < 12 || region.height < 8) return null
        val bitmap = frame.cropBitmap(region, upscale = 3, enhanceText = false)
        val text = try { recognizeText(bitmap) } finally { bitmap.recycle() }
        return parseBoardRemainingCount(text)
    }

    /** One spatial OCR pass covers the suspicious board and all three actual sprite artworks.
     * Count badges are deliberately excluded from the sprite evidence. */
    suspend fun recognizeSpatialDigits(
        frame: RgbaFrame,
        board: ScreenRegion,
        cards: List<ItemCardRecognition>,
    ): SpatialDigitEvidence {
        val spriteRegions = cards.map { it.spriteRegion }
        val scanRegion = (spriteRegions + board).reduce { left, right -> ScreenRegion(
            minOf(left.left, right.left), minOf(left.top, right.top),
            maxOf(left.right, right.right), maxOf(left.bottom, right.bottom),
        ) }
        val bitmap = frame.cropBitmap(scanRegion, upscale = 1, enhanceText = false)
        val result = try {
            recognizeResult(bitmap)
        } finally {
            bitmap.recycle()
        }
        val tokens = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
            if (!DIGIT_REGEX.containsMatchIn(element.text)) return@mapNotNull null
            val bounds = element.boundingBox ?: return@mapNotNull null
            LocatedDigitToken(
                text = element.text,
                centerX = scanRegion.left + bounds.centerX(),
                centerY = scanRegion.top + bounds.centerY(),
            )
        }
        return classifySpatialDigits(tokens, board, spriteRegions)
    }

    private suspend fun recognizeText(bitmap: Bitmap): String = recognizeResult(bitmap).text

    private suspend fun recognizeResult(bitmap: Bitmap): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    override fun close() {
        recognizer.close()
    }

}

data class LocatedDigitToken(val text: String, val centerX: Int, val centerY: Int)

data class SpatialDigitEvidence(
    val allTokens: List<LocatedDigitToken>,
    val boardTokens: List<LocatedDigitToken>,
    val spriteTokens: List<LocatedDigitToken>,
) {
    val boardDebugLabel: String get() = boardTokens.joinToString("/") { it.text }.take(80)
    val spriteDebugLabel: String get() = spriteTokens.joinToString("/") { it.text }.take(80)
    fun tokensInside(region: ScreenRegion): List<LocatedDigitToken> =
        allTokens.filter { region.contains(it.centerX, it.centerY) }
}

internal fun classifySpatialDigits(
    tokens: List<LocatedDigitToken>,
    board: ScreenRegion,
    sprites: List<ScreenRegion>,
): SpatialDigitEvidence = SpatialDigitEvidence(
    allTokens = tokens,
    boardTokens = tokens.filter { board.contains(it.centerX, it.centerY) },
    spriteTokens = tokens.filter { token -> sprites.any { it.contains(token.centerX, token.centerY) } },
)

private fun ScreenRegion.contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

private val DIGIT_REGEX = Regex("[0-9０-９]")

internal data class ParsedInventoryCount(
    val remainingCount: Int?,
    val isFinished: Boolean,
)

internal fun parseInventoryCount(text: String): ParsedInventoryCount {
    val normalized = text.replace('O', '0').replace('o', '0')
    val count = Regex("\\d{1,2}").findAll(normalized)
        .mapNotNull { it.value.toIntOrNull() }
        .lastOrNull()
    val finished = count == 0 || normalized.contains("finish", ignoreCase = true)
    return ParsedInventoryCount(
        remainingCount = if (finished) 0 else count,
        isFinished = finished,
    )
}

internal fun resolveInventoryTexts(card: ItemCardRecognition, countText: String, centerText: String): ItemCardRecognition {
    val parsed = parseInventoryCount(countText)
    // Never parse arbitrary digits from the central artwork as inventory counts.
    val centerFinish = Regex("\\bfinish\\b", RegexOption.IGNORE_CASE).containsMatchIn(centerText)
    val labelFinish = card.isFinished || centerFinish
    val conflict = labelFinish && parsed.remainingCount != null && parsed.remainingCount > 0
    val finished = !conflict && (labelFinish || parsed.isFinished)
    val count = if (conflict) null else if (finished) 0 else parsed.remainingCount
    return card.copy(
        countText = countText, finishText = centerText,
        remainingCount = count, isFinished = finished,
        finishEvidence = when {
            conflict -> "完成标记与正数库存冲突，保留未知，暂停探索"
            centerFinish -> "中央贴图区 OCR 识别到 Finish"
            parsed.isFinished -> "数量区识别为 0 / Finish"
            card.isFinished -> card.finishEvidence
            else -> ""
        },
        confidence = if (count != null) (card.confidence + 0.08).coerceAtMost(0.99) else card.confidence * 0.82,
    )
}

internal fun expandedCountRegion(region: ScreenRegion, card: ScreenRegion): ScreenRegion = ScreenRegion(
    left = (region.left - region.width / 10).coerceAtLeast(card.left),
    top = (region.top - region.height / 8).coerceAtLeast(card.top),
    right = (region.right + region.width / 20).coerceAtMost(card.right),
    bottom = (region.bottom + region.height / 12).coerceAtMost(card.bottom),
)

private fun RgbaFrame.cropBitmap(region: ScreenRegion, upscale: Int, enhanceText: Boolean): Bitmap {
    val left = region.left.coerceIn(0, width - 1)
    val top = region.top.coerceIn(0, height - 1)
    val right = region.right.coerceIn(left + 1, width)
    val bottom = region.bottom.coerceIn(top + 1, height)
    val cropWidth = right - left
    val cropHeight = bottom - top
    val colors = IntArray(cropWidth * cropHeight)
    val luminance = if (enhanceText) IntArray(colors.size) else null
    val histogram = if (enhanceText) IntArray(256) else null
    for (row in 0 until cropHeight) for (column in 0 until cropWidth) {
        val offset = (top + row) * rowStrideBytes + (left + column) * 4
        val red = rgba8888[offset].toInt() and 0xFF
        val green = rgba8888[offset + 1].toInt() and 0xFF
        val blue = rgba8888[offset + 2].toInt() and 0xFF
        val alpha = rgba8888[offset + 3].toInt() and 0xFF
        val index = row * cropWidth + column
        colors[index] =
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        if (luminance != null && histogram != null) {
            val gray = (red * 77 + green * 150 + blue * 29) shr 8
            luminance[index] = gray
            histogram[gray]++
        }
    }
    if (luminance != null && histogram != null) {
        val tail = (luminance.size * 0.04).toInt()
        var accumulated = 0
        var low = 0
        while (low < 255 && accumulated + histogram[low] <= tail) accumulated += histogram[low++]
        accumulated = 0
        var high = 255
        while (high > 0 && accumulated + histogram[high] <= tail) accumulated += histogram[high--]
        if (high - low < 32) {
            low = 0
            high = 255
        }
        val range = (high - low).coerceAtLeast(1)
        for (index in colors.indices) {
            val gray = ((luminance[index] - low) * 255 / range).coerceIn(0, 255)
            colors[index] = (colors[index] and -0x1000000) or (gray shl 16) or (gray shl 8) or gray
        }
    }
    val source = Bitmap.createBitmap(colors, cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
    if (upscale <= 1) return source
    val scaled = source.scale(cropWidth * upscale, cropHeight * upscale, filter = enhanceText)
    source.recycle()
    return scaled
}
