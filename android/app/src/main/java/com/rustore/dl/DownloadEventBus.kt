package com.rustore.dl

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class DownloadEvent {
    data class Progress(
        val packageName: String,
        val appName: String,
        val status: String,
        val percent: Int? = null,
    ) : DownloadEvent()
    data class Completed(
        val packageName: String,
        val appName: String,
        val savedPaths: List<String>,
        val recordId: String,
    ) : DownloadEvent()
    data class Failed(val packageName: String, val message: String) : DownloadEvent()
}

object DownloadEventBus {
    private val _events = MutableSharedFlow<DownloadEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun post(event: DownloadEvent) {
        _events.tryEmit(event)
    }
}
