package sk.housenail.manager

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * HouseNail new-booking alerts. Foreground and WorkManager scans use the existing
 * owner-authenticated website API. First successful scan is a silent baseline.
 * Optional FCM receives server-originated near-real-time alerts once configured.
 * Tokens are never logged, embedded in the APK or sent to non-HouseNail endpoints.
 */
object BookingAlerts {
    // WorkManager auto-initialization can be unavailable on some Android ROMs.
    // Keep notification scheduling optional; never crash login or startup.
    private fun safeWorkManager(context: Context): WorkManager? {
        val ctx = context.applicationContext
        return try {
            WorkManager.getInstance(ctx)
        } catch (initialError: IllegalStateException) {
            try {
                WorkManager.initialize(ctx, Configuration.Builder().build())
                WorkManager.getInstance(ctx)
            } catch (e: Exception) {
                android.util.Log.e("HouseNailAlerts", "WorkManager unavailable", e)
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("HouseNailAlerts", "WorkManager unavailable", e)
            null
        }
    }

    const val PERMISSION_CODE = 4104
    private const val PREF = "housenail_booking_alerts_v1"
    private const val CHANNEL_ID = "housenail_bookings_new_v1"
    private const val WORK_NAME = "housenail_booking_new_scan_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACTIVE = "owner_session_active"
    private const val KEY_STAFF = "staff_session"
    private const val KEY_PRIMED = "primed"
    private const val KEY_SEEN = "seen_ids"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_SERVER_TOKEN = "server_fcm_token"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun loggedIn(context: Context) = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun isFcmConfigured(): Boolean =
        BuildConfig.HN_FCM_APP_ID.isNotBlank() &&
        BuildConfig.HN_FCM_API_KEY.isNotBlank() &&
        BuildConfig.HN_FCM_SENDER_ID.isNotBlank() &&
        BuildConfig.HN_FCM_PROJECT_ID.isNotBlank()

    fun fcmServerRegistered(context: Context): Boolean {
        val p = prefs(context)
        val token = p.getString(KEY_FCM_TOKEN, null)
        return !token.isNullOrBlank() && p.getString(KEY_SERVER_TOKEN, null) == token
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HouseNail · Lịch hẹn mới",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo khi khách đặt lịch mới trên housenail.sk"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_CODE)
        }
    }

    fun start(context: Context, staff: Boolean = prefs(context).getBoolean(KEY_STAFF, false)) {
        val ctx = context.applicationContext
        prefs(ctx).edit().putBoolean(KEY_ACTIVE, true).putBoolean(KEY_STAFF, staff).apply()
        createChannel(ctx)
        if (!enabled(ctx)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<BookingAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        safeWorkManager(ctx)?.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
        if (!staff) initializeFcmAndSync(ctx)
    }

    fun setEnabled(context: Context, value: Boolean) {
        val ctx = context.applicationContext
        val wasEnabled = enabled(ctx)
        val edit = prefs(ctx).edit().putBoolean(KEY_ENABLED, value)
        if (!wasEnabled && value) edit.remove(KEY_SEEN).remove(KEY_PRIMED)
        edit.apply()
        if (value && loggedIn(ctx)) {
            start(ctx)
        } else {
            safeWorkManager(ctx)?.cancelUniqueWork(WORK_NAME)
        }
    }

    /** Must be called before revoking the owner cookie. */
    fun unregisterBeforeLogout(context: Context) {
        val ctx = context.applicationContext
        val token = prefs(ctx).getString(KEY_FCM_TOKEN, null)
        if (!token.isNullOrBlank() && prefs(ctx).getString(KEY_SERVER_TOKEN, null) == token) {
            try {
                ApiClient().call("adminFcmUnregister", JSONArray().put(JSONObject().put("token", token)))
            } catch (_: Exception) {
                // Never block logout on push unregistration.
            }
        }
        prefs(ctx).edit().putBoolean(KEY_ACTIVE, false).remove(KEY_SERVER_TOKEN)
            .remove(KEY_SEEN).remove(KEY_PRIMED).apply()
        safeWorkManager(ctx)?.cancelUniqueWork(WORK_NAME)
    }

    fun markLoggedOut(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit().putBoolean(KEY_ACTIVE, false).remove(KEY_SERVER_TOKEN)
            .remove(KEY_SEEN).remove(KEY_PRIMED).apply()
        safeWorkManager(ctx)?.cancelUniqueWork(WORK_NAME)
    }

    /** First run: snapshot silently; subsequent scans alert only on unseen booking IDs. */
    fun checkNow(context: Context) {
        val ctx = context.applicationContext
        if (!enabled(ctx) || !loggedIn(ctx)) return
        val today = LocalDate.now(ZoneId.of("Europe/Bratislava"))
        val staff = prefs(ctx).getBoolean(KEY_STAFF, false)
        val from = if (staff) today else today.minusDays(7)
        val to = today.plusDays(if (staff) 30 else 180)
        val rows = ApiClient().callArray("adminList", JSONArray().put(from.toString()).put(to.toString()))
        reconcile(ctx, rows)
    }

    @Synchronized
    private fun reconcile(context: Context, rows: JSONArray) {
        if (!enabled(context) || !loggedIn(context)) return
        val p = prefs(context)
        val wasPrimed = p.getBoolean(KEY_PRIMED, false)
        val seen = p.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val fresh = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue
            if (!seen.contains(id) && wasPrimed && item.optString("status") != "cancelled") {
                fresh.add(item)
            }
            seen.add(id)
        }
        p.edit().putStringSet(KEY_SEEN, seen).putBoolean(KEY_PRIMED, true).apply()
        // Limit bursts after a long absence and avoid repeat alerts from future scans.
        for (item in fresh.takeLast(5)) {
            post(
                context,
                item.optString("id"),
                item.optString("date"),
                item.optString("time"),
                item.optString("name"),
                item.optString("service"),
                false
            )
        }
        if (fresh.size > 5) {
            post(context, "summary_" + System.currentTimeMillis(), "", "",
                (fresh.size - 5).toString() + " lịch mới khác", "", true)
        }
    }

    /** Called by FCM service after an authenticated owner has opted in. */
    @Synchronized
    fun receiveFcmBooking(context: Context, id: String, date: String, time: String, name: String, service: String) {
        if (!enabled(context) || !loggedIn(context) || id.isBlank()) return
        val p = prefs(context)
        val seen = p.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!seen.add(id)) return
        p.edit().putStringSet(KEY_SEEN, seen).apply()
        post(context, id, date, time, name, service, false)
    }

    private fun post(
        context: Context, id: String, date: String, time: String,
        name: String, service: String, summary: Boolean
    ) {
        if (!canNotify(context)) return
        createChannel(context)
        val title = if (summary) "HouseNail · Nhiều lịch hẹn mới" else "HouseNail · Lịch hẹn mới"
        val detail = if (summary) name else
            listOf(name.take(48).ifBlank { "Khách hàng" }, service.take(54), date, time)
                .filter { it.isNotBlank() }.joinToString(" · ")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("hn_from_booking_alert", true)
            putExtra("hn_booking_date", date)
            putExtra("hn_booking_id", id)
        }
        val notificationId = ("new:" + id).hashCode() and Int.MAX_VALUE
        val pi = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setOnlyAlertOnce(true)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun testNotification(context: Context) {
        post(context, "test_" + System.currentTimeMillis(), LocalDate.now().toString(),
            "14:30", "Thông báo thử", "Đặt lịch HouseNail", false)
    }

    private fun initializeFcmAndSync(context: Context) {
        if (!isFcmConfigured() || !enabled(context)) return
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.HN_FCM_APP_ID)
                    .setApiKey(BuildConfig.HN_FCM_API_KEY)
                    .setProjectId(BuildConfig.HN_FCM_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.HN_FCM_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                registerToken(context, token)
            }
        } catch (_: Exception) {
            // Polling remains available until Firebase configuration is installed.
        }
    }

    fun registerToken(context: Context, token: String) {
        val ctx = context.applicationContext
        if (token.isBlank()) return
        val p = prefs(ctx)
        p.edit().putString(KEY_FCM_TOKEN, token).apply()
        if (!loggedIn(ctx) || !enabled(ctx) || !isFcmConfigured() || p.getBoolean(KEY_STAFF, false)) return
        if (p.getString(KEY_SERVER_TOKEN, null) == token) return
        Thread {
            try {
                val result = ApiClient().call(
                    "adminFcmRegister", JSONArray().put(
                        JSONObject().put("token", token).put("platform", "android")
                    )
                )
                if (result.optBoolean("registered")) {
                    prefs(ctx).edit().putString(KEY_SERVER_TOKEN, token).apply()
                }
            } catch (_: Exception) {
                // The live Worker may not yet support FCM: keep polling active.
            }
        }.start()
    }
}

class BookingAlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!BookingAlerts.enabled(applicationContext) || !BookingAlerts.loggedIn(applicationContext)) {
            return Result.success()
        }
        return try {
            BookingAlerts.checkNow(applicationContext)
            Result.success()
        } catch (_: ApiClient.AuthException) {
            // The user must explicitly reauthenticate; no prompt from background.
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
