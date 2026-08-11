package com.ytmlauncher

import android.service.notification.NotificationListenerService

/**
 * Deliberately empty.
 *
 * MediaSessionManager.getActiveSessions() requires the caller to hold
 * Notification Access, and eligibility is granted by declaring a
 * NotificationListenerService. We never read a single notification —
 * the declaration alone is what unlocks session access.
 */
class NotifListener : NotificationListenerService()
