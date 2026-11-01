package com.ubermicrostudios.messagesenhanced.update

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Worker access to the process-wide [UpdateChecker]. Workers stay plain [androidx.work.CoroutineWorker]s. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateWorkerEntryPoint {
    fun updateChecker(): UpdateChecker
}
