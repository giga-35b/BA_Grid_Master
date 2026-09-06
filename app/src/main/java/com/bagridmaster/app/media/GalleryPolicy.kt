package com.bagridmaster.app.media

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.ScreenRegion
import kotlin.math.abs
import kotlin.math.roundToInt

const val DEBUG_IMAGE_PREFIX = "BAGridMasterDebug-"
const val GALLERY_REMINDER = "请确认最近截图的是最新待识别画面"

fun validateGalleryAspect(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int) {
    require(imageWidth > 0 && imageHeight > 0 && screenWidth > 0 && screenHeight > 0) { "图片或屏幕尺寸无效" }
    val relativeRatio = (imageWidth.toDouble() / imageHeight) / (screenWidth.toDouble() / screenHeight)
    require(abs(relativeRatio - 1.0) <= 0.015) {
        "图片比例与当前屏幕不一致，请在游戏内重新截取完整屏幕，不要使用裁剪图或长截图"
    }
}

fun BoardGeometry.mapGalleryToScreen(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): BoardGeometry {
    validateGalleryAspect(imageWidth, imageHeight, screenWidth, screenHeight)
    val xScale = screenWidth.toDouble() / imageWidth
    val yScale = screenHeight.toDouble() / imageHeight
    return copy(
        region = ScreenRegion(
            (region.left * xScale).roundToInt(), (region.top * yScale).roundToInt(),
            (region.right * xScale).roundToInt(), (region.bottom * yScale).roundToInt(),
        ),
        subpixelGrid = subpixelGrid?.let { grid ->
            grid.copy(
                originX = grid.originX * xScale,
                originY = grid.originY * yScale,
                cellSizePx = grid.cellSizePx * xScale,
                cellHeightPx = grid.cellHeightPx * yScale,
            )
        },
    )
}

fun isDebugExport(name: String?): Boolean = name?.startsWith(DEBUG_IMAGE_PREFIX) == true
