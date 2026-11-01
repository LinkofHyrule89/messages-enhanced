package com.ubermicrostudios.messagesenhanced.util

import coil.decode.DataSource
import coil.drawable.CrossfadeDrawable
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.transition.CrossfadeTransition
import coil.transition.Transition
import coil.transition.TransitionTarget

/** Crossfade only when the bytes came from the network. Memory and disk hits paint immediately. */
object CacheTransitions {
    fun shouldCrossfade(dataSource: DataSource): Boolean = dataSource == DataSource.NETWORK
}

class NetworkCrossfadeFactory(
    private val durationMillis: Int = CrossfadeDrawable.DEFAULT_DURATION,
) : Transition.Factory {
    override fun create(target: TransitionTarget, result: ImageResult): Transition {
        if (result !is SuccessResult || !CacheTransitions.shouldCrossfade(result.dataSource)) {
            return Transition.Factory.NONE.create(target, result)
        }
        return CrossfadeTransition(target, result, durationMillis)
    }
}
