package sk.housenail.manager

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

class HouseNailTodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        const val OPEN_TODAY = "sk.housenail.manager.OPEN_TODAY"
        private val salonZone = ZoneId.of("Europe/Bratislava")
        private const val STORAGE = "housenail_today_widget"

        fun installed(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, HouseNailTodayWidget::class.java)
            ).isNotEmpty()

        fun save(context: Context, day: String, rows: List<JSONObject>) {
            if (!installed(context) || day != LocalDate.now(salonZone).toString()) return
            val today = rows.filter { it.optString("date") == day }
            val completed = today.count {
                it.optString("status") == "completed" && it.optString("attendanceOutcome") != "no_show"
            }
            val now = LocalTime.now(salonZone)
            val next = today.asSequence().filter {
                it.optString("status") !in setOf("cancelled", "completed") &&
                    it.optString("attendanceOutcome") != "no_show" &&
                    runCatching { LocalTime.parse(it.optString("endTime")) > now }.getOrDefault(false)
            }.map { it.optString("time") }.filter { it.isNotBlank() }.minOrNull().orEmpty()
            val prefs = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
            if (prefs.getString("day", "") == day &&
                prefs.getInt("total", -1) == today.size &&
                prefs.getInt("completed", -1) == completed &&
                prefs.getString("next", "") == next) return
            prefs.edit().putString("day", day).putInt("total", today.size)
                .putInt("completed", completed).putString("next", next)
                .putLong("updated", System.currentTimeMillis()).apply()
            refresh(context)
        }

        fun clear(context: Context) {
            context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE).edit().clear().apply()
            refresh(context)
        }

        private fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            render(context, manager, manager.getAppWidgetIds(ComponentName(context, HouseNailTodayWidget::class.java)))
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val prefs = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
            val valid = prefs.getString("day", "") == LocalDate.now(salonZone).toString()
            val next = prefs.getString("next", "").orEmpty()
            val updated = prefs.getLong("updated", 0)
            val views = RemoteViews(context.packageName, R.layout.housenail_today_widget)
            views.setTextViewText(R.id.hn_widget_count, if (valid) "${prefs.getInt("total", 0)} lịch hôm nay" else "Lịch hôm nay")
            views.setTextViewText(R.id.hn_widget_next, when {
                !valid -> "Mở app để đồng bộ"
                next.isEmpty() -> "Hết lịch chờ"
                else -> "Lịch tiếp theo · $next"
            })
            val detail = if (valid && updated > 0) {
                val formatter = DateFormat.getTimeFormat(context).apply {
                    timeZone = java.util.TimeZone.getTimeZone(salonZone)
                }
                "${prefs.getInt("completed", 0)} hoàn thành · Số liệu đổi lúc ${formatter.format(Date(updated))}"
            } else ""
            views.setTextViewText(R.id.hn_widget_detail, detail)
            val launch = Intent(context, MainActivity::class.java).setAction(OPEN_TODAY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(R.id.hn_widget_root, PendingIntent.getActivity(
                context, 72, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            manager.updateAppWidget(ids, views)
        }
    }
}
