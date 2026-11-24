package com.tunex.app

import android.service.notification.NotificationListenerService

/**
 * Does nothing by itself. Once the user grants "Notification access", Android lets TuneX
 * see and control the media sessions of Spotify / JioSaavn (play / pause) - even on a locked,
 * screen-off phone - which is what makes hands-free playback possible.
 */
class TuneXListener : NotificationListenerService()

