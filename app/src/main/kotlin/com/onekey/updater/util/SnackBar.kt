package com.onekey.updater.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 应用内提示消息总线。
 *
 * 上游基于 material3 的 `SnackbarVisuals`；改用 Miuix 后 UI 层只需要一个字符串，
 * 因此这里退化为最简单的字符串事件流，由 MainScreen 负责用 Miuix 的 Snackbar 呈现。
 */
class SnackBar {

    private val messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun flow(): SharedFlow<String> = messages

    fun snackBar(
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        message: String
    ) = scope.launch { messages.emit(message) }
}
