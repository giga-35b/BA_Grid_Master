package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ItemCardRecognition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest per-round item sprite crops; the same crops feed spatial matching and debug preview. */
object ItemTemplateStore {
    private val _items = MutableStateFlow<List<ItemCardRecognition>>(emptyList())
    val items = _items.asStateFlow()

    internal fun update(items: List<ItemCardRecognition>) {
        _items.value = items
    }

    /** Reuse artwork only when the dim Finish badge still confirms the same shape. */
    internal fun recoverFinishedArtwork(items: List<ItemCardRecognition>): List<ItemCardRecognition> {
        val previous = _items.value.associateBy { it.index }
        val matchingActivePeers = items.count { current ->
            val old = previous[current.index]
            !current.isFinished && current.shape != null && old != null && !old.isFinished &&
                old.shape == current.shape && old.cardRegion.width == current.cardRegion.width &&
                old.cardRegion.height == current.cardRegion.height
        }
        val sameRound = matchingActivePeers >= 2
        return items.map { current ->
            val old = previous[current.index]
            val confirmedShape = current.shape ?: if (sameRound) old?.shape else null
            if (current.isFinished && confirmedShape != null && old != null && !old.isFinished &&
                old.shape == confirmedShape && old.cardRegion.width == current.cardRegion.width &&
                old.cardRegion.height == current.cardRegion.height) {
                current.copy(shape = confirmedShape, spriteTemplate = old.spriteTemplate,
                    confidence = maxOf(current.confidence, old.confidence),
                    finishEvidence = current.finishEvidence +
                        if (current.shape == null) "；同轮其余两张卡形状一致，沿用该卡 Finish 前的形状与贴图" else "；沿用 Finish 前贴图")
            } else current
        }
    }
}
