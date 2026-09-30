package com.remindme.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.graphics.*
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var lv: ListView
    private var items = listOf<Reminder>()
    private var onRing: ((String) -> Unit)? = null
    private val fmt = SimpleDateFormat("EEE, dd MMM yyyy  hh:mm a", Locale.getDefault())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val add = Button(this).apply { text = "+ Add reminder"; setOnClickListener { edit(null) } }
        lv = ListView(this)
        lv.setOnItemClickListener { _, _, p, _ -> edit(items[p]) }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            AlertDialog.Builder(this).setMessage("Delete \"${items[p].title}\"?")
                .setPositiveButton("Delete") { _, _ -> Alarms.cancel(this, items[p].id); Store.delete(this, items[p].id); refresh() }
                .setNegativeButton("No", null).show()
            true
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            addView(add); addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        items = Store.load(this).sortedWith(compareBy({ it.done }, { it.time }))
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = items[p].id.toLong()
            override fun getView(p: Int, v: View?, g: ViewGroup?): View {
                val r = items[p]
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16, 0, 16) }
                val cb = CheckBox(this@MainActivity).apply {
                    isChecked = r.done; isFocusable = false
                    setOnClickListener {
                        r.done = isChecked; Store.upsert(context, r)
                        if (r.done) Alarms.cancel(context, r.id) else if (r.time > System.currentTimeMillis()) Alarms.set(context, r)
                        refresh()
                    }
                }
                val tv = TextView(this@MainActivity).apply {
                    val rep = arrayOf("", "  (daily)", "  (weekly)")[r.repeat]
                    text = "${r.title}\n${fmt.format(Date(r.time))}$rep" + if (r.note.isBlank()) "" else "\n${r.note}"
                    textSize = 16f
                    if (r.done) { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG; alpha = .5f }
                    else if (r.time < System.currentTimeMillis()) setTextColor(Color.RED)
                }
                row.addView(cb); row.addView(tv); return row
            }
        }
    }

    private fun edit(old: Reminder?) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = old?.time ?: (System.currentTimeMillis() + 3_600_000); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var ring = old?.ringtone ?: ""
        val title = EditText(this).apply { hint = "What to remember?"; setText(old?.title ?: "") }
        val note = EditText(this).apply { hint = "Note (optional)"; setText(old?.note ?: "") }
        val date = Button(this); val time = Button(this); val tone = Button(this)
        val rep = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("No repeat", "Repeat daily", "Repeat weekly"))
            setSelection(old?.repeat ?: 0)
        }
        fun upd() {
            date.text = "Date: " + SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(cal.time)
            time.text = "Time: " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
            val name = if (ring.isEmpty()) "Default alarm" else RingtoneManager.getRingtone(this, Uri.parse(ring))?.getTitle(this) ?: "Custom"
            tone.text = "Ringtone: $name"
        }
        date.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); upd() },
                cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).show()
        }
        time.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); upd() },
                cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE], false).show()
        }
        tone.setOnClickListener {
            onRing = { ring = it; upd() }
            startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (ring.isEmpty()) null else Uri.parse(ring)), 5)
        }
        upd()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0)
            listOf(title, note, date, time, tone, rep).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle(if (old == null) "New reminder" else "Edit reminder").setView(v)
            .setPositiveButton("Save") { _, _ ->
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Pick a future time", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val r = Reminder(old?.id ?: (System.currentTimeMillis() % 100_000_000).toInt(),
                    title.text.toString().ifBlank { "Reminder" }, note.text.toString(), cal.timeInMillis,
                    ring, rep.selectedItemPosition, false)
                Store.upsert(this, r); Alarms.set(this, r); refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        if (rq == 5 && rs == RESULT_OK)
            onRing?.invoke(d?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString() ?: "")
    }
}

class PopupActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val id = intent.getIntExtra("id", 0)
        val r = Store.find(this, id)
        fun send(a: String) { startService(Intent(this, AlarmService::class.java).setAction(a).putExtra("id", id)); finish() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(60, 60, 60, 60) }
        col.addView(TextView(this).apply { text = r?.title ?: "Reminder"; textSize = 26f; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = r?.note ?: ""; textSize = 18f; gravity = Gravity.CENTER })
        listOf("Done" to "done", "Snooze 10 min" to "snooze", "Dismiss" to "stop").forEach { (t, a) ->
            col.addView(Button(this).apply { text = t; setOnClickListener { send(a) } })
        }
        setContentView(col)
    }
}
