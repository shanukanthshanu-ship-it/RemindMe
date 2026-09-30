package com.remindme.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import org.json.*

data class Reminder(val id: Int, var title: String, var note: String, var time: Long,
                    var ringtone: String, var repeat: Int, var done: Boolean)

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("r", 0)
    fun load(c: Context): MutableList<Reminder> {
        val a = JSONArray(prefs(c).getString("d", "[]"))
        return MutableList(a.length()) { i ->
            a.getJSONObject(i).let {
                Reminder(it.getInt("id"), it.getString("t"), it.getString("n"), it.getLong("m"),
                    it.getString("r"), it.getInt("p"), it.getBoolean("d"))
            }
        }
    }
    fun save(c: Context, l: List<Reminder>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("n", it.note).put("m", it.time)
            .put("r", it.ringtone).put("p", it.repeat).put("d", it.done)) }
        prefs(c).edit().putString("d", a.toString()).apply()
    }
    fun find(c: Context, id: Int) = load(c).firstOrNull { it.id == id }
    fun upsert(c: Context, r: Reminder) {
        val l = load(c); val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r); save(c, l)
    }
    fun delete(c: Context, id: Int) = save(c, load(c).filter { it.id != id })
}

object Alarms {
    private fun pi(c: Context, id: Int) = PendingIntent.getBroadcast(c, id,
        Intent(c, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun set(c: Context, r: Reminder) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(r.time, show), pi(c, r.id))
    }
    fun cancel(c: Context, id: Int) = c.getSystemService(AlarmManager::class.java).cancel(pi(c, id))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startForegroundService(Intent(c, AlarmService::class.java).putExtra("id", i.getIntExtra("id", 0)))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.load(c).filter { !it.done && it.time > System.currentTimeMillis() }.forEach { Alarms.set(c, it) }
    }
}

class AlarmService : Service() {
    private var ring: Ringtone? = null
    private val h = Handler(Looper.getMainLooper())
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val id = i?.getIntExtra("id", 0) ?: 0
        val r = Store.find(this, id)
        when (i?.action) {
            "done" -> { r?.let { it.done = true; Store.upsert(this, it); Alarms.cancel(this, id) }; stopAll() }
            "snooze" -> { r?.let { it.time = System.currentTimeMillis() + 600_000; Store.upsert(this, it); Alarms.set(this, it) }; stopAll() }
            "stop" -> stopAll()
            else -> start(id, r)
        }
        return START_NOT_STICKY
    }

    private fun start(id: Int, r: Reminder?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rem", "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val popIntent = Intent(this, PopupActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pop = PendingIntent.getActivity(this, id, popIntent, fl)
        fun act(a: String, k: Int) = PendingIntent.getService(this, id * 3 + k,
            Intent(this, AlarmService::class.java).setAction(a).putExtra("id", id), fl)
        val n = Notification.Builder(this, "rem").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(r?.title ?: "Reminder")
            .setContentText(if (r == null || r.note.isBlank()) "Time's up!" else r.note)
            .setCategory(Notification.CATEGORY_ALARM).setContentIntent(pop).setFullScreenIntent(pop, true)
            .addAction(0, "Done", act("done", 1)).addAction(0, "Snooze 10m", act("snooze", 2))
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(id, n)
        try { startActivity(popIntent) } catch (_: Exception) {}
        ring?.stop()
        val u = if (r == null || r.ringtone.isEmpty()) Settings.System.DEFAULT_ALARM_ALERT_URI else Uri.parse(r.ringtone)
        ring = RingtoneManager.getRingtone(this, u)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true; play()
        }
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        if (r != null && r.repeat > 0) {
            val step = if (r.repeat == 1) 86_400_000L else 604_800_000L
            while (r.time <= System.currentTimeMillis()) r.time += step
            Store.upsert(this, r); Alarms.set(this, r)
        }
        h.postDelayed({ stopAll() }, 60_000)
    }

    private fun stopAll() {
        ring?.stop(); getSystemService(Vibrator::class.java).cancel()
        h.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { ring?.stop(); super.onDestroy() }
}
