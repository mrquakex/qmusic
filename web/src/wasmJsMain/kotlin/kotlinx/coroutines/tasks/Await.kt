package kotlinx.coroutines.tasks

import com.google.android.gms.tasks.Task

/** Waits for a [Task] (as kotlinx-coroutines-play-services does on the phone). */
suspend fun <T> Task<T>.await(): T = deferred.await()
