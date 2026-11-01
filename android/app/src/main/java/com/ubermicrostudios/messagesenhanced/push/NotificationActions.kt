package com.ubermicrostudios.messagesenhanced.push

/** Intent contract shared by the notification actions and the receiver that handles them. */
object NotificationActions {
    const val ACTION_REPLY = "com.ubermicrostudios.messagesenhanced.NOTIF_REPLY"
    const val ACTION_READ = "com.ubermicrostudios.messagesenhanced.NOTIF_READ"
    const val ACTION_DISMISSED = "com.ubermicrostudios.messagesenhanced.NOTIF_DISMISSED"

    const val ACTION_RETRY = "com.ubermicrostudios.messagesenhanced.NOTIF_RETRY"
    const val EXTRA_CONV = "conv"
    const val EXTRA_TEXT = "text"
    const val EXTRA_TITLE = "title"
    const val EXTRA_GROUP = "group"

    const val KEY_REPLY = "reply_text"
}
