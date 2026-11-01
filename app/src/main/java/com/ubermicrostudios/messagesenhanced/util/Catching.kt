package com.ubermicrostudios.messagesenhanced.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but cancellation still cancels the coroutine.
 * [runCatching] catches [CancellationException], which lets a cancelled call
 * continue as a normal failure.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
