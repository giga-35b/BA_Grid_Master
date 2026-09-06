package com.bagridmaster.app.overlay

/** Gallery input must be explicitly armed before every successful recognition cycle. */
internal class GalleryCaptureWorkflow {
    enum class Phase { NEEDS_CLEAR, CLEARING, READY }

    var phase = Phase.NEEDS_CLEAR
        private set
    fun beginClearing() {
        phase = Phase.CLEARING
    }

    fun ready() {
        check(phase == Phase.CLEARING)
        phase = Phase.READY
    }

    fun requireReady() {
        check(phase == Phase.READY) { "请先点击清屏，再截取新的游戏画面" }
    }

    fun reset() {
        phase = Phase.NEEDS_CLEAR
    }
}
