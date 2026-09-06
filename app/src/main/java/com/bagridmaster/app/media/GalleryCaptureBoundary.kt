package com.bagridmaster.app.media

/** Created only after removing our windows, before the user takes a new screenshot. */
data class GalleryCaptureBoundary(val previousMaxId: Long, val clearedAtMs: Long) {
    fun validate(id: Long, addedAtMs: Long, takenAtMs: Long) {
        check(id > previousMaxId) {
            "尚未发现清屏后的新截图，请先截图并等待保存完成，再点识别；不会重复读取旧图片"
        }
        // DATE_ADDED has second precision. Be conservative for providers without DATE_TAKEN:
        // screenshots in the ambiguous boundary second are rejected, not guessed to be clean.
        val capturedAt = takenAtMs.takeIf { it > 0 } ?: addedAtMs
        check(capturedAt >= clearedAtMs) {
            "最新图片拍摄于清屏之前，可能含旧标注。请在游戏中重新截图后再点识别"
        }
    }
}
