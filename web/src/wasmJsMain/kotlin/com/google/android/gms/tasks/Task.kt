package com.google.android.gms.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A piece of work under way (Google's Task, as the phone's code uses it): awaited, or told when it is done. */
class Task<T> internal constructor(internal val deferred: CompletableDeferred<T>) {
    val isComplete: Boolean get() = deferred.isCompleted
    val isSuccessful: Boolean get() = deferred.isCompleted && runCatching { deferred.getCompleted() }.isSuccess

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val result: T? get() = if (deferred.isCompleted) runCatching { deferred.getCompleted() }.getOrNull() else null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val exception: Throwable? get() = if (deferred.isCompleted) deferred.getCompletionExceptionOrNull() else null

    fun addOnCompleteListener(listener: (Task<T>) -> Unit): Task<T> {
        deferred.invokeOnCompletion { listener(this) }
        return this
    }

    fun addOnSuccessListener(listener: (T) -> Unit): Task<T> {
        deferred.invokeOnCompletion { if (it == null) result?.let(listener) }
        return this
    }

    fun addOnFailureListener(listener: (Throwable) -> Unit): Task<T> {
        deferred.invokeOnCompletion { e -> if (e != null) listener(e) }
        return this
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> com.nuvelo.web.logJs("task: " + e) })

        /** Starts [work] now (as Firebase does: a write goes whether or not anyone waits for it). */
        fun <T> of(work: suspend () -> T): Task<T> {
            val d = CompletableDeferred<T>()
            scope.launch { runCatching { work() }.fold({ d.complete(it) }, { d.completeExceptionally(it) }) }
            return Task(d)
        }
    }
}
