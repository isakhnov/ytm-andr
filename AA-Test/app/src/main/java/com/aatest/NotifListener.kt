package com.aatest

import android.service.notification.NotificationListenerService

/**
 * Deliberately empty — see ytmprobe's identical file. Declaring a
 * NotificationListenerService is what makes this app eligible for
 * Notification Access, which MediaSessionManager.getActiveSessions()
 * requires. Nothing here ever reads a notification.
 */
class NotifListener : NotificationListenerService()
