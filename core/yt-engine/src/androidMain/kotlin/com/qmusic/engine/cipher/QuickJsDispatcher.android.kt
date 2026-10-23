package com.qmusic.engine.cipher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

private const val JS_THREAD_STACK_BYTES = 32L * 1024L * 1024L

internal actual fun createQuickJsDispatcher(): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "QuickJs", JS_THREAD_STACK_BYTES)
    }.asCoroutineDispatcher()
