package com.focuslock

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var p: Palette
    private lateinit var ui: Ui

    private var draft = Config()
    private var lockPick: Set<String> = emptySet()
    private var lockGames = true
    private var lockDur = 2

    // Live views updated every second without rebuilding the screen.
    private var heroSub: TextView? = null
    private val countdowns = ArrayList<Pair<TextView, Long>>()
    private var lastState = ""

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (screenState() != lastState) render() else refreshLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        p = Palette.of(this)
        ui = Ui(this, p)
        root = ui.column().apply { setPadding(ui.dp(18), ui.dp(28), ui.dp(18), ui.dp(40)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        setContentView(scroll)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
    }

    override fun onResume() {
        super.onResume()
        Enforcer.enforce(this)
        draft = store.draft()
        render()
        handler.postDelayed(tick, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    // ---------- helpers ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun fmt(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    private fun hm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dialog(): AlertDialog.Builder = AlertDialog.Builder(
        this,
        if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    )

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** "3d 4h", "2h 14m", "4m 09s". */
    private fun left(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m %02ds".format(s % 60)
        }
    }

    private fun names(pkgs: Collection<String>, max: Int = 3): String {
        val l = pkgs.map { label(it) }.sorted()
        return l.take(max).joinToString(", ") + if (l.size > max) " +${l.size - max}" else ""
    }

    /** Anything that changes which sections are shown. When it changes, the screen is rebuilt. */
    private fun screenState(): String {
        val now = System.currentTimeMillis()
        return "${Enforcer.isOwner(this)}|${store.armed}|${store.anyLockActive(now)}|${store.config.isActive(now)}|" +
            store.locks.count { it.value > now } + "|${store.shortsOn}|${store.shortsOffAt}|${ShortsBlocker.isEnabled(this)}"
    }

    private fun nextUnlock(now: Long): Long? {
        val all = store.locks.values.filter { it > now } + listOf(store.gamesLockUntil).filter { it > now }
        return all.minOrNull()
    }

    private fun heroText(): String {
        val now = System.currentTimeMillis()
        val cfg = store.config
        val parts = ArrayList<String>()
        nextUnlock(now)?.let { parts.add("Next unlock in ${left(it - now)}") }
        if (cfg.isActive(now)) {
            parts.add(if (cfg.limitMin > 0) "Focus hours until ${hm(cfg.endMin)} · ${cfg.limitMin} min/app" else "Focus hours until ${hm(cfg.endMin)}")
        }
        if (parts.isEmpty()) {
            val edge = cfg.nextEdge(now)
            parts.add(if (cfg.scheduleOn && edge != null) "Focus hours start ${fmt(edge)}" else "Nothing is blocked right now")
        }
        if (store.pending != null) parts.add("Weaker settings apply ${fmt(store.pendingAt)}")
        return parts.joinToString("\n")
    }

    private fun refreshLive() {
        heroSub?.text = heroText()
        val now = System.currentTimeMillis()
        for ((tv, until) in countdowns) tv.text = left(until - now)
    }

    // ---------- screen ----------
    private fun render() {
        lastState = screenState()
        countdowns.clear()
        root.removeAllViews()
        val now = System.currentTimeMillis()
        val owner = Enforcer.isOwner(this)
        val locked = store.anyLockActive(now)
        val focus = store.config.isActive(now)

        // Header
        val header = ui.row().apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(18)) }
        header.addView(ui.text("FocusLock", 28f, bold = true), ui.weighted())
        header.addView(
            ui.pill(
                when {
                    !owner -> "Not set up"
                    store.armed -> "Armed"
                    else -> "Test mode"
                },
                owner && store.armed
            )
        )
        root.addView(header)

        heroCard(locked || focus)

        if (!owner) {
            val c = ui.card(root)
            c.addView(ui.title("Setup"))
            c.addView(ui.text(
                "On a phone with no accounts signed in, enable USB debugging and run this from a computer:"
            ))
            c.addView(ui.text("adb shell dpm set-device-owner com.focuslock/.AdminReceiver", 13f, p.accent).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = ui.shape(p.raised, 12)
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            }, ui.lp(top = 12))
            return
        }
        if (locked) activeLocksCard()
        lockCard()
        shortsCard()
        hoursCard()
        if (!store.armed) setupCard()
        root.addView(
            ui.text(
                if (store.armed) "Lockdown is armed. Locks can only be extended." else "Test mode: everything can still be removed.",
                12f, p.muted
            ).apply { gravity = Gravity.CENTER },
            ui.lp(top = 6)
        )
    }

    private fun heroCard(active: Boolean) {
        val c = ui.card(root, if (active) p.accent else p.card)
        val fg = if (active) p.onAccent else p.text
        val sub = if (active) p.onAccent else p.muted
        val r = ui.row()
        r.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_lock)
            setColorFilter(fg)
        }, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { marginEnd = ui.dp(10) })
        r.addView(ui.text(if (active) "Focus is on" else "Free time", 22f, fg, bold = true))
        c.addView(r)
        val s = ui.text(heroText(), 15f, sub).apply { alpha = if (active) 0.85f else 1f }
        heroSub = s
        c.addView(s, ui.lp(top = 8))
    }

    private fun activeLocksCard() {
        val now = System.currentTimeMillis()
        val c = ui.card(root)
        c.addView(ui.title("Locked right now"))
        if (store.gamesLockUntil > now) lockRow(c, null, "All games", store.gamesLockUntil)
        val rows = store.locks.entries.filter { it.value > now }.sortedBy { it.value }
        for (e in rows.take(12)) lockRow(c, e.key, label(e.key), e.value)
        if (rows.size > 12) c.addView(ui.text("and ${rows.size - 12} more", 13f, p.muted), ui.lp(top = 6))
        c.addView(ui.text("Locks can't be shortened, only extended. Reinstalling an app doesn't help.", 13f, p.muted), ui.lp(top = 10))

        if (Secrets.PARTNER_SHA256.isNotEmpty()) {
            ui.divider(c)
            val code = EditText(this).apply {
                hint = "Partner code"
                setTextColor(p.text)
                setHintTextColor(p.muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                background = ui.shape(p.raised, 14)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            }
            c.addView(code)
            c.addView(ui.button("Partner unlock (ends all locks)", Ui.Style.SECONDARY) {
                if (sha256(code.text.toString()) == Secrets.PARTNER_SHA256) {
                    store.clearLocks()
                    Enforcer.enforce(this)
                    render()
                } else {
                    it.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    toast("Wrong code")
                }
            }, ui.lp(top = 10))
        }
    }

    private fun lockRow(parent: LinearLayout, pkg: String?, name: String, until: Long) {
        val r = ui.row().apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        r.addView(icon(pkg), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(12) })
        val t = ui.column()
        t.addView(ui.text(name, 15f, bold = true))
        t.addView(ui.text("until ${fmt(until)}", 12f, p.muted))
        r.addView(t, ui.weighted(end = 8))
        val cd = ui.text(left(until - System.currentTimeMillis()), 14f, p.accent, bold = true)
        countdowns.add(cd to until)
        r.addView(cd)
        parent.addView(r)
    }

    private val iconCache = HashMap<String, Drawable?>()
    private fun icon(pkg: String?): ImageView = ImageView(this).apply {
        val d = if (pkg == null) null else iconCache.getOrPut(pkg) {
            try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
        }
        if (d != null) {
            setImageDrawable(d)
        } else {
            setImageResource(R.drawable.ic_stat_lock)
            setColorFilter(p.accent)
            background = ui.shape(p.accentSoft, 12)
            val pad = ui.dp(7)
            setPadding(pad, pad, pad, pad)
        }
    }

    private fun lockCard() {
        val c = ui.card(root)
        c.addView(ui.title("Lock apps now"))

        val pick = ui.row().apply {
            background = ui.pressable(ui.shape(p.raised, 16), 16)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            isClickable = true
            setOnClickListener { pickApps("Apps to lock", lockPick) { lockPick = it; render() } }
        }
        val t = ui.column()
        t.addView(ui.text(if (lockPick.isEmpty()) "Choose apps" else "${lockPick.size} app${if (lockPick.size == 1) "" else "s"} selected", 15f, bold = true))
        if (lockPick.isNotEmpty()) t.addView(ui.text(names(lockPick), 13f, p.muted))
        pick.addView(t, ui.weighted())
        pick.addView(ui.text("›", 22f, p.muted))
        c.addView(pick)

        c.addView(ui.switchRow("Also lock all games", "Including games installed later", lockGames) { lockGames = it }, ui.lp(top = 6))

        c.addView(ui.text("For how long", 13f, p.muted), ui.lp(top = 8, bottom = 8))
        val chips = ui.row()
        DUR_LABELS.forEachIndexed { i, s ->
            chips.addView(ui.chip(s, i == lockDur) { lockDur = i; render() }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        c.addView(hs)
        // Keep the selected chip in view after a re-render.
        hs.post { chips.getChildAt(lockDur)?.let { hs.scrollTo((it.left - ui.dp(40)).coerceAtLeast(0), 0) } }

        val until = System.currentTimeMillis() + DUR_MIN[lockDur] * 60_000L
        c.addView(ui.text("Until ${fmt(until)}", 13f, p.muted), ui.lp(top = 10))
        c.addView(ui.button("Start lock") {
            if (lockPick.isEmpty() && !lockGames) {
                it.performHapticFeedback(HapticFeedbackConstants.REJECT)
                toast("Choose at least one app, or turn on \"Also lock all games\".")
                return@button
            }
            val mins = DUR_MIN[lockDur]
            confirmLock(System.currentTimeMillis() + mins * 60_000L, mins >= 10080)
        }, ui.lp(top = 12))
    }

    private fun confirmLock(until: Long, needTyping: Boolean) {
        val what = listOfNotNull(
            if (lockPick.isNotEmpty()) names(lockPick, 5) else null,
            if (lockGames) "all games" else null
        ).joinToString(" + ")
        val input = EditText(this).apply { hint = "Type LOCK to confirm" }
        val b = dialog()
            .setTitle("Start lock?")
            .setMessage("$what will be blocked until ${fmt(until)}.\n\nThis cannot be shortened or undone.")
        if (needTyping) {
            val box = padded(input)
            b.setView(box)
        }
        b.setPositiveButton("Lock") { _, _ ->
            if (needTyping && input.text.toString().trim() != "LOCK") {
                toast("Not locked. You have to type LOCK.")
                return@setPositiveButton
            }
            val games = if (lockGames) Enforcer.installedGames(this) else emptySet()
            store.addLock(lockPick + games, lockGames, until)
            lockPick = emptySet()
            Enforcer.enforce(this)
            root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            render()
        }.setNegativeButton("Cancel", null).show()
    }

    /** Pads a view inside a dialog. */
    private fun padded(v: View): LinearLayout = ui.column().apply {
        setPadding(ui.dp(22), ui.dp(4), ui.dp(22), 0)
        addView(v)
    }

    private fun shortsCard() {
        val now = System.currentTimeMillis()
        val c = ui.card(root)
        c.addView(ui.title("YouTube Shorts"))
        val on = store.shortsOn
        val offAt = store.shortsOffAt
        val sub = when {
            on && offAt > now -> "Turns off ${fmt(offAt)}"
            on -> "Shorts close as soon as they open. Normal videos still work."
            else -> "Block Shorts in the YouTube app and on youtube.com"
        }
        c.addView(ui.switchRow("Block Shorts", sub, on && offAt == 0L) { want ->
            if (want) {
                store.shortsTurnOn()
                Enforcer.enforce(this)
                render()
            } else {
                dialog()
                    .setTitle("Turn off Shorts blocking?")
                    .setMessage("Shorts blocking stays on for 24 more hours, then turns off. You can switch it back on any time to cancel.")
                    .setPositiveButton("Turn off in 24 h") { _, _ ->
                        store.shortsRequestOff(System.currentTimeMillis())
                        Enforcer.enforce(this)
                        render()
                    }
                    .setNegativeButton("Keep it on") { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            }
        })
        if (on && !ShortsBlocker.isEnabled(this)) {
            c.addView(ui.text(
                "The whole YouTube app is blocked until the Shorts blocker is switched on in Accessibility settings.",
                13f, p.danger
            ), ui.lp(top = 6))
            c.addView(ui.button("Open accessibility settings", Ui.Style.SECONDARY) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, ui.lp(top = 10))
            c.addView(ui.text("If the switch is greyed out, run this from your computer instead:", 12f, p.muted), ui.lp(top = 10))
            c.addView(ui.text(
                "adb shell settings put secure enabled_accessibility_services com.focuslock/.ShortsBlocker\n" +
                    "adb shell settings put secure accessibility_enabled 1",
                12f, p.accent
            ).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = ui.shape(p.raised, 12)
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            }, ui.lp(top = 6))
        }
    }

    private fun hoursCard() {
        val c = ui.card(root)
        c.addView(ui.title("Focus hours (automatic)"))

        c.addView(ui.switchRow("Block automatically", "On the days and hours below", draft.scheduleOn) {
            draft = draft.copy(scheduleOn = it); render()
        })

        val pick = ui.row().apply {
            background = ui.pressable(ui.shape(p.raised, 16), 16)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            isClickable = true
            setOnClickListener { pickApps("Apps to block in focus hours", draft.blocked) { draft = draft.copy(blocked = it); render() } }
        }
        val t = ui.column()
        t.addView(ui.text(if (draft.blocked.isEmpty()) "Choose apps" else "${draft.blocked.size} app${if (draft.blocked.size == 1) "" else "s"}", 15f, bold = true))
        if (draft.blocked.isNotEmpty()) t.addView(ui.text(names(draft.blocked), 13f, p.muted))
        pick.addView(t, ui.weighted())
        pick.addView(ui.text("›", 22f, p.muted))
        c.addView(pick, ui.lp(top = 6))

        c.addView(ui.switchRow("Block all games", null, draft.blockGames) { draft = draft.copy(blockGames = it) }, ui.lp(top = 4))

        // Days
        c.addView(ui.text("Days", 13f, p.muted), ui.lp(top = 8, bottom = 8))
        val days = ui.row()
        val dl = arrayOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0..6) {
            val on = (draft.days and (1 shl i)) != 0
            days.addView(ui.chip(dl[i], on, round = true) {
                draft = draft.copy(days = if (on) draft.days and (1 shl i).inv() else draft.days or (1 shl i))
                render()
            }, ui.weighted(start = 3, end = 3))
        }
        c.addView(days)

        // Hours
        val times = ui.row()
        times.addView(timeBox("From", draft.startMin) { pickTime(true) }, ui.weighted(end = 6))
        times.addView(timeBox("To", draft.endMin) { pickTime(false) }, ui.weighted(start = 6))
        c.addView(times, ui.lp(top = 14))

        // Daily limit stepper
        c.addView(ui.text("Daily limit per app", 13f, p.muted), ui.lp(top = 14, bottom = 8))
        val step = ui.row().apply {
            background = ui.shape(p.raised, 16)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        step.addView(stepBtn("−") { changeLimit(-1) })
        step.addView(
            ui.text(if (draft.limitMin <= 0) "Fully blocked" else "${draft.limitMin} min / day", 16f, bold = true).apply { gravity = Gravity.CENTER },
            ui.weighted()
        )
        step.addView(stepBtn("+") { changeLimit(+1) })
        c.addView(step)

        c.addView(ui.switchRow("No installing new apps", "While anything is blocked", draft.blockInstalls) {
            draft = draft.copy(blockInstalls = it)
        }, ui.lp(top = 8))

        val dirty = draft != store.draft()
        c.addView(ui.button(if (dirty) "Save changes" else "Saved", if (dirty) Ui.Style.PRIMARY else Ui.Style.SECONDARY) { saveDraft() }, ui.lp(top = 12))
        c.addView(ui.text("Stricter changes apply at once. Weaker ones (fewer apps, shorter hours, bigger limit) wait 24 hours.", 12f, p.muted), ui.lp(top = 10))
    }

    private fun timeBox(caption: String, min: Int, onClick: () -> Unit): LinearLayout = ui.column().apply {
        background = ui.pressable(ui.shape(p.raised, 16), 16)
        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        isClickable = true
        setOnClickListener { onClick() }
        addView(ui.text(caption, 12f, p.muted))
        addView(ui.text(hm(min), 24f, bold = true))
    }

    private fun stepBtn(s: String, onClick: () -> Unit): TextView = ui.text(s, 22f, p.text, bold = true).apply {
        gravity = Gravity.CENTER
        background = ui.pressable(ui.shape(p.card, 12), 12)
        isClickable = true
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(44))
    }

    private fun changeLimit(dir: Int) {
        val cur = draft.limitMin
        val next = if (dir > 0) LIMITS.firstOrNull { it > cur } ?: LIMITS.last() else LIMITS.lastOrNull { it < cur } ?: 0
        draft = draft.copy(limitMin = next)
        render()
    }

    private fun setupCard() {
        val c = ui.card(root)
        c.addView(ui.title("Setup · test mode"))
        val ok = Enforcer.hasUsageAccess(this)
        c.addView(ui.row().apply {
            addView(ui.text("Usage access", 15f), ui.weighted())
            addView(ui.pill(if (ok) "Granted" else "Missing", ok))
        })
        if (!ok) c.addView(ui.button("Grant usage access", Ui.Style.SECONDARY) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }, ui.lp(top = 10))

        c.addView(ui.button("Arm permanent lockdown", Ui.Style.PRIMARY) {
            if (!Enforcer.hasUsageAccess(this)) {
                toast("Grant usage access first")
                return@button
            }
            dialog()
                .setTitle("Arm lockdown?")
                .setMessage(
                    "After this the app cannot be uninstalled, force-stopped or removed, USB debugging is switched off, " +
                        "and the date and time are locked. Only a factory reset from recovery undoes it.\n\n" +
                        "Only arm a build signed with your own key."
                )
                .setPositiveButton("Arm") { _, _ ->
                    store.armed = true
                    Enforcer.enforce(this)
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, ui.lp(top = 12))

        c.addView(ui.button("Remove device owner (test mode only)", Ui.Style.DANGER) {
            dialog()
                .setTitle("Remove device owner?")
                .setMessage("Undoes all blocks and locks and removes FocusLock's control. This only works before lockdown is armed.")
                .setPositiveButton("Remove") { _, _ ->
                    toast(if (Enforcer.removeTestOwner(this)) "Removed. You can uninstall the app now." else "Could not remove")
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, ui.lp(top = 10))
    }

    // ---------- actions ----------

    /** Searchable app list with icons. */
    private fun pickApps(title: String, initial: Set<String>, onDone: (Set<String>) -> Unit) {
        val apps = Enforcer.launchableApps(this)
        val selected = HashSet(initial)
        var shown = apps

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val (pkg, name) = shown[i]
                val r = ui.row().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(16), ui.dp(10)) }
                r.addView(icon(pkg), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(14) })
                r.addView(ui.text(name, 15f), ui.weighted())
                r.addView(CheckBox(this@MainActivity).apply {
                    isChecked = pkg in selected
                    isClickable = false
                    isFocusable = false
                    buttonTintList = android.content.res.ColorStateList.valueOf(p.accent)
                })
                return r
            }
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = null
            setOnItemClickListener { _, v, i, _ ->
                val pkg = shown[i].first
                if (!selected.add(pkg)) selected.remove(pkg)
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                adapter.notifyDataSetChanged()
            }
        }
        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine()
            setTextColor(p.text)
            setHintTextColor(p.muted)
            background = ui.shape(p.raised, 14)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    shown = if (q.isEmpty()) apps else apps.filter { it.second.lowercase().contains(q) }
                    adapter.notifyDataSetChanged()
                }
            })
        }
        val box = ui.column().apply {
            addView(search, ui.lp().apply { marginStart = ui.dp(20); marginEnd = ui.dp(20); topMargin = ui.dp(4); bottomMargin = ui.dp(8) })
            addView(list, ui.lp(h = (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        }
        dialog()
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Done") { _, _ ->
                // Keep apps that are selected but not shown (e.g. uninstalled) so nothing is dropped by accident.
                onDone(selected.toSet())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTime(start: Boolean) {
        val m = if (start) draft.startMin else draft.endMin
        TimePickerDialog(
            this,
            if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
            { _, h, mi ->
                draft = if (start) draft.copy(startMin = h * 60 + mi) else draft.copy(endMin = h * 60 + mi)
                render()
            }, m / 60, m % 60, true
        ).show()
    }

    private fun saveDraft() {
        draft = draft.copy(limitMin = draft.limitMin.coerceIn(0, 1440))
        if (draft.scheduleOn && draft.endMin <= draft.startMin) {
            root.performHapticFeedback(HapticFeedbackConstants.REJECT)
            toast("The end time must be after the start time.")
            return
        }
        val applied = store.submit(draft, System.currentTimeMillis())
        Enforcer.enforce(this)
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toast(if (applied) "Saved" else "Saved. Weaker settings apply after 24 hours.")
        draft = store.draft()
        render()
    }

    private companion object {
        val DUR_LABELS = arrayOf(
            "15 min", "30 min", "1 hour", "2 hours", "4 hours", "8 hours", "12 hours", "1 day",
            "3 days", "1 week", "2 weeks", "1 month", "3 months", "6 months", "1 year"
        )
        val DUR_MIN = intArrayOf(
            15, 30, 60, 120, 240, 480, 720, 1440,
            4320, 10080, 20160, 43200, 129600, 259200, 525600
        )
        val LIMITS = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180)
    }
}
