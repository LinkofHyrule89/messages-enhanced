package com.ubermicrostudios.messagesenhanced.data.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionMonitor @Inject constructor() {
    private val _authLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authLost: SharedFlow<Unit> = _authLost.asSharedFlow()

    fun notifyAuthLost() {
        _authLost.tryEmit(Unit)
    }
}
