package com.easytranscriber.dictation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.easytranscriber.EasyTranscriberApp
import com.easytranscriber.R
import com.easytranscriber.ui.MainActivity

/**
 * Foreground service that keeps the process (and so the Bluetooth keyboard registration and any
 * dictation) alive while the app is in the background. Types: connectedDevice while the keyboard
 * is registered, plus microphone while listening or recording.
 */
class DictationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val controller = (application as EasyTranscriberApp).controller
            if (controller.session.value.isActive()) {
                controller.stopDictation()
                controller.stopRecording()
            } else {
                controller.hid.stop()
            }
            return START_NOT_STICKY
        }
        val keyboard = intent?.getBooleanExtra(EXTRA_KEYBOARD, false) ?: false
        val mic = intent?.getBooleanExtra(EXTRA_MIC, false) ?: false
        if (!keyboard && !mic) {
            stopSelf()
            return START_NOT_STICKY
        }
        var types = 0
        if (keyboard) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(keyboard, mic), types)
        } catch (e: Exception) {
            // E.g. permission revoked, or a microphone FGS requested while in the background.
            Log.e("EasyTranscriber", "startForeground failed: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(keyboard: Boolean, mic: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Dictation keyboard", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the Bluetooth keyboard or microphone is active"
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            mic -> "Listening — audio stays on this phone"
            else -> "Bluetooth keyboard is on"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("EasyTranscriber")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, if (mic) "Stop listening" else "Turn off keyboard", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "dictation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.easytranscriber.STOP"
        private const val EXTRA_KEYBOARD = "keyboard"
        private const val EXTRA_MIC = "mic"

        fun update(context: Context, keyboard: Boolean, mic: Boolean) {
            val intent = Intent(context, DictationService::class.java)
            if (!keyboard && !mic) {
                context.stopService(intent)
                return
            }
            intent.putExtra(EXTRA_KEYBOARD, keyboard).putExtra(EXTRA_MIC, mic)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e("EasyTranscriber", "Could not start foreground service: ${e.message}")
            }
        }
    }
}
