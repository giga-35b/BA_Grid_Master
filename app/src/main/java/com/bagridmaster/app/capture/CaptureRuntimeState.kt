package com.bagridmaster.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CaptureRuntimeState {
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    internal fun setActive(active: Boolean) {
        _isActive.value = active
    }
}

