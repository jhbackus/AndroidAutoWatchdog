package nl.chatgptauto.app

import android.service.notification.NotificationListenerService

/**
 * Grants ChatGPT Auto access to active MediaSession controllers after the user
 * enables Notification Access for the app. No notification contents are used.
 */
class MediaAccessService : NotificationListenerService()
