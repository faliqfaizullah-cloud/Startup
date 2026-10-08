package com.startup.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ───────────────────────── Model + storage ─────────────────────────

data class Meeting(
    val id: Int,
    val title: String,
    val team: String,
    val host: String,
    val time: Long,
    val remind: Int = 10,
    val alarm: Boolean = true,
    val people: Int = 5,
    val kind: String = "Weekly Sync",
    val link: String = "",
)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("startup", Context.MODE_PRIVATE)

    fun meetings(c: Context): List<Meeting> {
        val raw = p(c).getString("meetings", null)
        if (raw == null) {
            val s = seed()
            save(c, s)
            return s
        }
        val a = JSONArray(raw)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Meeting(
                o.getInt("id"), o.getString("title"), o.getString("team"), o.getString("host"),
                o.getLong("time"), o.getInt("remind"), o.getBoolean("alarm"), o.getInt("people"),
                o.optString("kind", "Weekly Sync"), o.optString("link", ""),
            )
        }.sortedBy { it.time }
    }

    fun save(c: Context, list: List<Meeting>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("title", it.title).put("team", it.team)
                    .put("kind", it.kind).put("host", it.host).put("time", it.time)
                    .put("remind", it.remind).put("alarm", it.alarm).put("people", it.people).put("link", it.link)
            )
        }
        p(c).edit().putString("meetings", a.toString()).apply()
    }

    fun members(c: Context): List<String> =
        p(c).getString("members", "Alex|Sophia|Eric|Eva")!!.split("|").filter { it.isNotBlank() }

    fun saveMembers(c: Context, m: List<String>) =
        p(c).edit().putString("members", m.joinToString("|")).apply()

    fun onboarded(c: Context) = p(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) = p(c).edit().putBoolean("onboarded", true).apply()

    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun seed() = listOf(
        Meeting(1, "Daily Engineering Sync", "Engineering", "Rahul Verma", at(9, 30), kind = "Daily Sync"),
        Meeting(2, "Sprint 24 Planning", "Product Team", "Caleb May", at(11, 0), kind = "Planning"),
        Meeting(3, "Quarterly Stakeholder", "Leadership", "Alex Espy", at(14, 0), kind = "Quarterly"),
        Meeting(4, "Growth Strategy Review", "Growth", "Sophia", at(15, 0), people = 5, kind = "Review"),
        Meeting(5, "Product Discovery Session", "Product Team", "Eric", at(15, 0), people = 6, kind = "Discovery"),
    )
}

// ───────────────────────── Reminders / alarms ─────────────────────────

const val CH_REMIND = "startup_reminders"
const val CH_ALARM = "startup_alarms"

object Reminders {
    const val ACTION_SNOOZE = "com.startup.app.SNOOZE"

    private fun pending(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, id, Intent(c, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleAt(c: Context, m: Meeting, at: Long) {
        if (at <= System.currentTimeMillis()) return
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c, m.id)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun schedule(c: Context, m: Meeting) = scheduleAt(c, m, m.time - m.remind * 60_000L)

    fun cancel(c: Context, m: Meeting) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, m.id))
    }

    fun scheduleAll(c: Context) = Store.meetings(c).forEach { schedule(c, it) }
}

class StartupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Meeting reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Meeting alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
            }
        )
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        val m = Store.meetings(c).firstOrNull { it.id == id } ?: return

        if (i.action == Reminders.ACTION_SNOOZE) {
            NotificationManagerCompat.from(c).cancel(id)
            Reminders.scheduleAt(c, m, System.currentTimeMillis() + 5 * 60_000L)
            return
        }

        val open = PendingIntent.getActivity(
            c, id,
            Intent(c, MainActivity::class.java).putExtra("meeting", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            c, id + 100_000,
            Intent(c, AlarmReceiver::class.java).setAction(Reminders.ACTION_SNOOZE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.time))
        val b = NotificationCompat.Builder(c, if (m.alarm) CH_ALARM else CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(m.title)
            .setContentText("Starts at $hm · ${m.team}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Start", open)
            .addAction(0, "Snooze 5 min", snooze)
        if (m.link.isNotBlank()) {
            val joinIntent = PendingIntent.getActivity(
                c, id + 200_000,
                Intent(Intent.ACTION_VIEW, Uri.parse(m.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "Join", joinIntent)
        }
        if (m.alarm) b.setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(open, true)
        else b.setCategory(NotificationCompat.CATEGORY_REMINDER)

        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try { NotificationManagerCompat.from(c).notify(id, b.build()) } catch (_: SecurityException) {}
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Reminders.scheduleAll(c)
    }
}

// ───────────────────────── Haptics + pin result ─────────────────────────

// ───────────────────────── Google Meet / Teams / Zoom ─────────────────────────

enum class Platform(val label: String, val short: String, val pkg: String) {
    MEET("Google Meet", "Meet", "com.google.android.apps.tachyon"),
    TEAMS("Microsoft Teams", "Teams", "com.microsoft.teams"),
    ZOOM("Zoom", "Zoom", "us.zoom.videomeetings");

    companion object {
        fun detect(link: String): Platform? {
            val l = link.lowercase()
            return when {
                "meet.google." in l -> MEET
                "teams.microsoft." in l || "teams.live." in l -> TEAMS
                "zoom.us" in l || "zoom.com" in l -> ZOOM
                else -> null
            }
        }

        /** Turns a pasted link, Meet code or Zoom ID into a join URL. Teams needs the full link. */
        fun toLink(p: Platform, raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("http", true)) return s
            if ("/" in s && "." in s) return "https://$s"
            return when (p) {
                MEET -> "https://meet.google.com/" + s.replace(" ", "")
                ZOOM -> s.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { "https://zoom.us/j/$it" }
                TEAMS -> null
            }
        }
    }
}

object Join {
    /** Opens the meeting in its app if installed, otherwise in the browser. */
    fun open(c: Context, link: String) {
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Haptics.pop(c)
        Platform.detect(link)?.let { p ->
            try {
                c.startActivity(Intent(base).setPackage(p.pkg))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        try {
            c.startActivity(base)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(c, "No app or browser can open this link", Toast.LENGTH_LONG).show()
        }
    }
}

object Haptics {
    fun pop(c: Context) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            c.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)
        val fx = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        else VibrationEffect.createOneShot(30, 200)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(fx, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else v.vibrate(fx)
    }
}

object PinEvents {
    val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** Called by the launcher when the user confirms pinning the widget. */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Haptics.pop(c)
        PinEvents.flow.tryEmit(Unit)
        StartupWidget.refreshAll(c)
    }
}

// ───────────────────────── 2×2 widget (28dp corners) ─────────────────────────

class StartupWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, build(c)) }
    }

    override fun onEnabled(c: Context) {
        Haptics.pop(c)
    }

    companion object {
        fun refreshAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, StartupWidget::class.java))
                .forEach { m.updateAppWidget(it, build(c)) }
        }

        fun build(c: Context): RemoteViews {
            val list = Store.meetings(c)
            val now = System.currentTimeMillis()
            val next = list.firstOrNull { it.time >= now } ?: list.firstOrNull()
            val rv = RemoteViews(c.packageName, R.layout.widget_startup)

            if (next != null) {
                rv.setTextViewText(R.id.w_chip, next.title)
                rv.setTextViewText(R.id.w_time, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next.time)))
                rv.setTextViewText(R.id.w_team, "| ${next.team}")
                val left = next.time - now
                rv.setChronometerCountDown(R.id.w_timer, true)
                rv.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime() + maxOf(left, 0L), null, left > 0)
            }

            val open = PendingIntent.getActivity(
                c, 0,
                Intent(c, MainActivity::class.java).putExtra("meeting", next?.id ?: -1)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv.setOnClickPendingIntent(R.id.w_stop, open)
            return rv
        }
    }
}
