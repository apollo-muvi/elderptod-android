package com.elderptod.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val FCM_PREFS = "elderptod"
private const val FCM_DEVICE_ID_KEY = "device_id"
private const val FCM_DEVICE_TOKEN_KEY = "device_token"
private const val FCM_BASE_URL_KEY = "base_url"
private const val FCM_TOKEN_KEY = "fcm_token"
private const val FCM_NOTIFICATIONS_ENABLED_KEY = "fcm_notifications_enabled"
private const val FCM_DEFAULT_BASE_URL = "https://elderweb.classtutorbot.com"
private const val FCM_NOTIFICATION_CHANNEL_ID = "elderptod_workflow"
private const val FCM_NOTIFICATION_ID = 4209
private val FCM_JSON = "application/json; charset=utf-8".toMediaType()

/** Registers the current Firebase token through the existing paired-device API. */
@Suppress("DEPRECATION")
object FcmRegistration {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun syncCurrentToken(context: Context) {
        if (!notificationsEnabled(context)) return
        val messaging = try {
            FirebaseMessaging.getInstance()
        } catch (error: IllegalStateException) {
            Log.w(LOG_TAG, "Firebase is not configured", error)
            return
        }
        messaging.token
            .addOnSuccessListener { token ->
                rememberToken(context.applicationContext, token)
                register(context.applicationContext, token)
            }
            .addOnFailureListener { error ->
                Log.w(LOG_TAG, "Unable to obtain FCM token", error)
            }
    }

    fun register(context: Context, fcmToken: String) {
        if (fcmToken.isBlank()) return
        rememberToken(context.applicationContext, fcmToken)
        if (!notificationsEnabled(context)) return
        val prefs = context.getSharedPreferences(FCM_PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(FCM_DEVICE_ID_KEY, null) ?: return
        val deviceToken = prefs.getString(FCM_DEVICE_TOKEN_KEY, null) ?: return
        val baseUrl = (prefs.getString(FCM_BASE_URL_KEY, FCM_DEFAULT_BASE_URL)
            ?: FCM_DEFAULT_BASE_URL).trimEnd('/')
        if (baseUrl.isBlank()) return

        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/fcm-registrations")
            .header("x-elder-device-token", deviceToken)
            .post(JSONObject().put("token", fcmToken).put("platform", "android")
                .toString().toRequestBody(FCM_JSON))
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                Log.w(LOG_TAG, "Unable to register FCM token", error)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(LOG_TAG, "FCM token registration failed: ${it.code}")
                    }
                }
            }
        })
    }

    fun notificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(FCM_NOTIFICATIONS_ENABLED_KEY, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        prefs(appContext).edit().putBoolean(FCM_NOTIFICATIONS_ENABLED_KEY, enabled).apply()
        if (enabled) {
            syncCurrentToken(appContext)
            return
        }
        val savedToken = prefs(appContext).getString(FCM_TOKEN_KEY, null)
        if (!savedToken.isNullOrBlank()) {
            deactivate(appContext, savedToken)
            return
        }
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                rememberToken(appContext, token)
                deactivate(appContext, token)
            }
        } catch (error: IllegalStateException) {
            Log.w(LOG_TAG, "Firebase is not configured", error)
        }
    }

    private fun deactivate(context: Context, fcmToken: String) {
        val prefs = prefs(context)
        val deviceId = prefs.getString(FCM_DEVICE_ID_KEY, null) ?: return
        val deviceToken = prefs.getString(FCM_DEVICE_TOKEN_KEY, null) ?: return
        val baseUrl = (prefs.getString(FCM_BASE_URL_KEY, FCM_DEFAULT_BASE_URL)
            ?: FCM_DEFAULT_BASE_URL).trimEnd('/')
        if (baseUrl.isBlank()) return

        val request = Request.Builder()
            .url("$baseUrl/api/devices/$deviceId/fcm-registrations")
            .header("x-elder-device-token", deviceToken)
            .delete(JSONObject().put("token", fcmToken).put("platform", "android")
                .toString().toRequestBody(FCM_JSON))
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                Log.w(LOG_TAG, "Unable to deactivate FCM token", error)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(LOG_TAG, "FCM token deactivation failed: ${it.code}")
                    }
                }
            }
        })
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FCM_PREFS, Context.MODE_PRIVATE)

    private fun rememberToken(context: Context, token: String) {
        if (token.isNotBlank()) {
            prefs(context).edit().putString(FCM_TOKEN_KEY, token).apply()
        }
    }
}

class ElderFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        FcmRegistration.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!FcmRegistration.notificationsEnabled(applicationContext)) return
        showWorkflowNotification(message)
    }

    private fun showWorkflowNotification(message: RemoteMessage) {
        val context = applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createWorkflowNotificationChannel(context)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "ElderPTOD 通知"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "您有一則新的工作流程通知。"
        val contentIntent = PendingIntent.getActivity(
            context,
            FCM_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, FCM_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(FCM_NOTIFICATION_ID, notification)
        Log.i(LOG_TAG, "show workflow FCM notification")
    }

    private fun createWorkflowNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(FCM_NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                FCM_NOTIFICATION_CHANNEL_ID,
                "工作流程通知",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
