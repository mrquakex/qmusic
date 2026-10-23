package com.qmusic.engine.cipher

import kotlinx.coroutines.CoroutineDispatcher

internal expect fun createQuickJsDispatcher(): CoroutineDispatcher
