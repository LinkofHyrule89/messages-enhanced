package com.ubermicrostudios.messagesenhanced.push

import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Worker access to send, mark-read, and the conversation notifications. Workers stay plain CoroutineWorkers. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationWorkerEntryPoint {
    fun messagesRepository(): MessagesRepository
    fun conversationNotifications(): ConversationNotifications
}
