package com.elderptod.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

private const val REMINDER_ALARM_REQUEST_CODE = 4107
private const val REMINDER_NOTIFICATION_ID = 4108
private const val REMINDER_NOTIFICATION_POOL_SIZE = 8
private const val REMINDER_CHANNEL_ID = "elderptod_reminders"

object ReminderAlarmContract {
    const val ACTION_ALARM = "com.elderptod.android.action.REMINDER_ALARM"
    const val ACTION_SHOW = "com.elderptod.android.action.SHOW_REMINDER"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_REMINDER_TITLE = "reminder_title"
    const val EXTRA_REMINDER_MESSAGE = "reminder_message"
    const val EXTRA_REMINDER_TIME_TEXT = "reminder_time_text"
    const val EXTRA_REMINDER_AUDIO_TYPE = "reminder_audio_type"
    const val EXTRA_REMINDER_AUDIO_ASSET_ID = "reminder_audio_asset_id"
    const val EXTRA_REMINDER_AUDIO_URL = "reminder_audio_url"
    const val EXTRA_REMINDER_AUDIO_CONTENT_TYPE = "reminder_audio_content_type"
    const val EXTRA_REMINDER_AUDIO_FILENAME = "reminder_audio_filename"
    const val EXTRA_REMINDER_AUDIO_SIZE = "reminder_audio_size"
    const val EXTRA_REMINDER_AUDIO_CHECKSUM = "reminder_audio_checksum"
    const val EXTRA_REMINDER_AUDIO_UPDATED_AT = "reminder_audio_updated_at"
    const val EXTRA_REMINDER_AUDIO_LOCAL_PATH = "reminder_audio_local_path"
    const val EXTRA_REMINDER_AUDIO_CACHE_STATUS = "reminder_audio_cache_status"
}

internal object ReminderForegroundHost {
    private var activityRef: WeakReference<MainActivity>? = null

    val isForeground: Boolean
        get() = activityRef?.get() != null

    fun attach(activity: MainActivity) {
        activityRef = WeakReference(activity)
        Log.i(LOG_TAG, "reminder foreground host attached")
    }

    fun detach(activity: MainActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            Log.i(LOG_TAG, "reminder foreground host detached")
        }
    }

    fun showReminder(reminder: ReminderState): Boolean {
        val activity = activityRef?.get() ?: run {
            Log.i(LOG_TAG, "local reminder foreground host unavailable")
            return false
        }
        Log.i(LOG_TAG, "local reminder foreground UI id=${reminder.reminderId}")
        activity.runOnUiThread {
            activity.showLocalAlarmReminder(reminder)
        }
        return true
    }
}

object ReminderAlarmScheduler {
    private var reminderNotificationSequence = 0

    fun scheduleNext(context: Context) {
        val appContext = context.applicationContext
        ReminderLocalStore(appContext).use { store ->
            scheduleNext(appContext, store)
        }
    }

    fun scheduleNext(context: Context, store: ReminderLocalStore) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val pendingIntent = alarmPendingIntent(appContext)
        alarmManager.cancel(pendingIntent)

        val next = store.nextAlarmReminder() ?: return
        val triggerAtMs = next.scheduledAt.toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()
        if (triggerAtMs <= nowMs) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            store.markExecutionState(next.reminderId, "scheduled_locally")
            Log.i(LOG_TAG, "scheduled local reminder id=${next.reminderId} at=${next.scheduledAt}")
        } catch (error: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            store.markExecutionState(next.reminderId, "scheduled_locally")
            Log.w(LOG_TAG, "scheduled inexact local reminder id=${next.reminderId}", error)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(alarmPendingIntent(context.applicationContext))
    }

    fun dismissReminderNotification(context: Context) {
        val manager = NotificationManagerCompat.from(context.applicationContext)
        for (offset in 0 until REMINDER_NOTIFICATION_POOL_SIZE) {
            manager.cancel(REMINDER_NOTIFICATION_ID + offset)
        }
    }

    fun showReminderNotification(context: Context, reminder: ReminderState) {
        val appContext = context.applicationContext
        if (ReminderForegroundHost.isForeground) {
            Log.i(LOG_TAG, "skip reminder notification while foreground")
            return
        }
        createReminderChannel(appContext)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        reminderNotificationSequence =
            (reminderNotificationSequence + 1) % REMINDER_NOTIFICATION_POOL_SIZE
        val notificationId = REMINDER_NOTIFICATION_ID + reminderNotificationSequence
        val requestCode = REMINDER_NOTIFICATION_ID + reminderNotificationSequence
        val contentIntent = PendingIntent.getActivity(
            appContext,
            requestCode,
            showReminderIntent(appContext, reminder),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentText = reminder.message.ifBlank { "播放錄音提醒" }
        val notification = NotificationCompat.Builder(appContext, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setTimeoutAfter(30_000L)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        val notificationManager = NotificationManagerCompat.from(appContext)
        notificationManager.cancel(notificationId)
        Log.i(LOG_TAG, "show reminder notification id=${reminder.reminderId}")
        notificationManager.notify(notificationId, notification)
    }

    fun showReminderIntent(context: Context, reminder: ReminderState): Intent =
        Intent(context.applicationContext, MainActivity::class.java).apply {
            action = ReminderAlarmContract.ACTION_SHOW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_ID, reminder.reminderId)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_TITLE, reminder.title)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_MESSAGE, reminder.message)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_TIME_TEXT, reminder.timeText)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_TYPE, reminder.audioType)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_ASSET_ID, reminder.audioAssetId)
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_URL, reminder.audioUrl)
            putExtra(
                ReminderAlarmContract.EXTRA_REMINDER_AUDIO_CONTENT_TYPE,
                reminder.audioContentType,
            )
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_FILENAME, reminder.audioFilename)
            reminder.audioSize?.let {
                putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_SIZE, it)
            }
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_CHECKSUM, reminder.audioChecksum)
            putExtra(
                ReminderAlarmContract.EXTRA_REMINDER_AUDIO_UPDATED_AT,
                reminder.audioUpdatedAt,
            )
            putExtra(ReminderAlarmContract.EXTRA_REMINDER_AUDIO_LOCAL_PATH, reminder.audioLocalPath)
            putExtra(
                ReminderAlarmContract.EXTRA_REMINDER_AUDIO_CACHE_STATUS,
                reminder.audioCacheStatus,
            )
        }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmContract.ACTION_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REMINDER_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderAlarmContract.ACTION_ALARM) return

        val appContext = context.applicationContext
        ReminderLocalStore(appContext).use { store ->
            val reminder = store.dueAlarmReminder() ?: return
            Log.i(LOG_TAG, "local reminder alarm id=${reminder.reminderId}")
            store.markExecutionState(reminder.reminderId, "triggered")
            ReminderAlarmScheduler.scheduleNext(appContext, store)
            if (!ReminderForegroundHost.showReminder(reminder)) {
                ReminderAlarmScheduler.showReminderNotification(appContext, reminder)
            }
        }
    }
}

private fun createReminderChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        REMINDER_CHANNEL_ID,
        "提醒",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "用戶提醒播放"
        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
}
