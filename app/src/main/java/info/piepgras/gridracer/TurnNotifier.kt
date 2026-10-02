package info.piepgras.gridracer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts a one-shot "it's your turn" notification when the app is backgrounded during the local
 * player's move, and cancels it once the app is foregrounded again.
 *
 * The server has always force-forfeited a turn nobody answers within its timeout - see
 * `Room.forfeitTimedOutPlayer` - but a backgrounded player had no way to know their clock was
 * running until they happened to reopen the app on their own. This doesn't stop the forfeit
 * (the server still has to protect the other real players from an unreachable one), it just
 * gives the player a way back in before it happens.
 */
class TurnNotifier(private val context: Context) {

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.turn_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [secondsRemaining] is how long is actually left, not the budget the turn started with. */
    fun notifyTurn(secondsRemaining: Int) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Reuses the launcher icon rather than a dedicated monochrome notification icon - the
        // project has no notification-specific asset, and adding one is out of scope here.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.turn_notification_title))
            .setContentText(context.getString(R.string.turn_notification_text, secondsRemaining))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Removes the turn notification; called when the app comes back to the foreground. */
    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "turn_reminders"
        const val NOTIFICATION_ID = 1001
    }
}
