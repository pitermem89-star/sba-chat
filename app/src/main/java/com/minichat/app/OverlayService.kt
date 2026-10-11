package com.minichat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Окно чата, которое висит поверх других приложений. Принимает кнопку «назад» для выхода из ввода. */
class PanelView(ctx: Context, private val onBack: () -> Unit) : LinearLayout(ctx) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.minichat.app.STOP"
        private const val CHANNEL_ID = "minichat_overlay"
        private const val NOTIF_ID = 1

        @Volatile
        var isRunning = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var client: ChatClient? = null
    private var nick = ""

    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var badge: TextView? = null

    private var panel: PanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var listLayout: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var edit: EditText? = null
    private var statusView: TextView? = null
    private var titleView: TextView? = null
    private var sendBtn: TextView? = null

    /** true, пока в поле ввода пишется ник, а не сообщение */
    private var nickMode = false

    private var expanded = false
    private var focusable = false
    private var historyLoaded = false
    private var unread = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)

        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        val base = prefs.cleanDbUrl()
        if (!Settings.canDrawOverlays(this) || base.isEmpty()) {
            Toast.makeText(this, "Не хватает разрешения или ссылки на базу", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        nick = prefs.cleanNick()
        buildBubble()
        buildPanel()
        showBubble()

        client = ChatClient(
            base,
            prefs.cleanRoom(),
            onMessages = { list -> main.post { onIncoming(list) } },
            onStatus = { s -> main.post { showStatus(s) } },
        ).also {
            it.pollMs = 4000
            it.start()
        }

        // Ника ещё нет — сразу открываем окно и просим его ввести
        if (nick.isEmpty()) {
            expand()
            main.postDelayed({ startNickEntry() }, 400)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Если чат уже работает, а ник сменили в настройках — подхватываем новый
        if (::prefs.isInitialized && !nickMode) {
            val n = prefs.cleanNick()
            if (n.isNotEmpty() && n != nick) {
                nick = n
                updateTitle()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        client?.stop()
        main.removeCallbacksAndMessages(null)
        safeRemove(bubble)
        safeRemove(panel)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- уведомление

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Мини-чат", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Мини-чат работает")
            .setContentText("Нажми, чтобы открыть настройки")
            .setContentIntent(open)
            .addAction(0, "Выключить", stop)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------- вспомогательное

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun safeRemove(v: View?) {
        if (v == null) return
        try {
            if (v.parent != null) wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** Делает так, чтобы окно можно было таскать пальцем за [handle]. Короткое касание — [onTap]. */
    private fun makeDraggable(
        handle: View,
        target: View,
        p: WindowManager.LayoutParams,
        onTap: (() -> Unit)?,
        onSave: () -> Unit,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try { wm.updateViewLayout(target, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) onSave() else onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------- кружок

    private fun buildBubble() {
        val size = dp(Style.BUBBLE_DP)
        val root = FrameLayout(this)

        val icon = TextView(this).apply {
            text = Style.BUBBLE_ICON
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Style.accent)
            }
        }
        root.addView(icon, FrameLayout.LayoutParams(size, size))

        val bd = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Style.badgeColor)
            }
        }
        root.addView(bd, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        badge = bd

        root.alpha = prefs.opacity / 100f

        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = prefs.bubbleY
        }

        makeDraggable(root, root, p, onTap = { expand() }, onSave = {
            prefs.bubbleX = p.x
            prefs.bubbleY = p.y
        })

        bubble = root
        bubbleParams = p
    }

    private fun updateBadge() {
        val b = badge ?: return
        if (unread > 0) {
            b.text = if (unread > 99) "99+" else unread.toString()
            b.visibility = View.VISIBLE
        } else {
            b.visibility = View.GONE
        }
    }

    private fun showBubble() {
        val v = bubble ?: return
        if (v.parent == null) {
            try { wm.addView(v, bubbleParams) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- окно чата

    private fun buildPanel() {
        val screenW = resources.displayMetrics.widthPixels
        val w = minOf(dp(Style.PANEL_W_DP), screenW - dp(16))
        val h = dp(Style.PANEL_H_DP)

        val root = PanelView(this) { releaseFocus() }
        root.orientation = LinearLayout.VERTICAL
        root.background = roundBg(Style.panelBg, Style.CORNER_DP)
        root.outlineProvider = ViewOutlineProvider.BACKGROUND
        root.clipToOutline = true
        root.alpha = prefs.opacity / 100f

        // --- шапка
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Style.headerBg)
            setPadding(dp(12), 0, dp(4), 0)
        }
        val title = TextView(this).apply {
            text = "# " + prefs.cleanRoom()
            setTextColor(Style.textColor)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        titleView = title
        updateTitle()

        fun headerButton(label: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Style.textColor)
                setOnClickListener { onClick() }
            }

        header.addView(headerButton("✎") { startNickEntry() }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(headerButton("—") { collapse() }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(headerButton("✕") { stopSelf() }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        // --- строка статуса (видна только при ошибках)
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Style.errorColor)
            setPadding(dp(12), dp(3), dp(12), dp(3))
            visibility = View.GONE
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        statusView = status

        // --- список сообщений
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        sv.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        listLayout = list
        scroll = sv

        // --- строка ввода
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val et = EditText(this).apply {
            hint = Style.INPUT_HINT
            setHintTextColor(Style.hintColor)
            setTextColor(Style.textColor)
            textSize = Style.TEXT_SP
            background = roundBg(Style.inputBg, 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(300))
        }
        inputRow.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        edit = et

        val send = TextView(this).apply {
            text = "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Style.accent)
            }
            setOnClickListener { sendCurrent() }
        }
        val lp = LinearLayout.LayoutParams(dp(38), dp(38))
        lp.leftMargin = dp(6)
        sendBtn = send
        inputRow.addView(send, lp)
        root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Чтобы клавиатура работала в окне поверх приложений, окно должно уметь получать фокус.
        // Обычно оно «не фокусируемое» (игра продолжает получать нажатия), а при касании поля — включаем фокус.
        et.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) setPanelFocusable(true)
            if (e.action == MotionEvent.ACTION_UP) {
                et.postDelayed({
                    et.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, 0)
                }, 120)
            }
            false
        }
        et.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) {
                sendCurrent()
                true
            } else {
                false
            }
        }

        val p = WindowManager.LayoutParams(
            w, h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.panelX
            y = prefs.panelY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        makeDraggable(title, root, p, onTap = null, onSave = {
            prefs.panelX = p.x
            prefs.panelY = p.y
        })

        panel = root
        panelParams = p
    }

    private fun setPanelFocusable(on: Boolean) {
        val p = panelParams ?: return
        val v = panel ?: return
        if (on == focusable) return
        focusable = on
        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /** Закрыть клавиатуру и вернуть управление игре. */
    private fun releaseFocus() {
        // Передумали менять ник — отменяем. (Если ника ещё нет, режим ввода ника остаётся.)
        if (nickMode && nick.isNotEmpty()) finishNickEntry()
        val et = edit
        if (et != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus()
        }
        setPanelFocusable(false)
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        unread = 0
        updateBadge()
        safeRemove(bubble)
        val v = panel ?: return
        if (v.parent == null) {
            try { wm.addView(v, panelParams) } catch (_: Exception) {}
        }
        client?.pollMs = 2000
        client?.wake()
    }

    private fun collapse() {
        if (!expanded) return
        releaseFocus()
        expanded = false
        safeRemove(panel)
        focusable = false
        showBubble()
        client?.pollMs = 4000
    }

    // ---------------------------------------------------------------- сообщения

    private fun sendCurrent() {
        val et = edit ?: return
        val text = et.text.toString().trim()

        if (nickMode) {
            if (text.isEmpty()) {
                if (nick.isEmpty()) {
                    Toast.makeText(this, "Сначала введи ник", Toast.LENGTH_SHORT).show()
                    return
                }
                releaseFocus() // ник уже есть, пустое поле = отмена смены
                return
            }
            nick = text.take(20)
            prefs.nick = nick
            updateTitle()
            finishNickEntry()
            releaseFocus()
            return
        }

        if (text.isNotEmpty()) {
            client?.send(nick, text)
            et.setText("")
        }
        releaseFocus()
    }

    private fun updateTitle() {
        val t = titleView ?: return
        t.text = "# " + prefs.cleanRoom() + if (nick.isNotEmpty()) "  ·  $nick" else ""
    }

    /** Поле ввода временно превращается в поле для ника (первый запуск или кнопка ✎). */
    private fun startNickEntry() {
        val et = edit ?: return
        if (!expanded) expand()
        nickMode = true
        et.hint = if (nick.isEmpty()) "Сначала введи свой ник…" else "Новый ник…"
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(20))
        et.setText(nick)
        et.setSelection(et.text.length)
        sendBtn?.text = "✓"
        setPanelFocusable(true)
        et.postDelayed({
            et.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, 0)
        }, 150)
    }

    /** Возвращаем поле ввода в обычный режим (сообщения). */
    private fun finishNickEntry() {
        val et = edit ?: return
        nickMode = false
        et.hint = Style.INPUT_HINT
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(300))
        et.setText("")
        sendBtn?.text = "➤"
    }

    private fun onIncoming(list: List<ChatMsg>) {
        val firstBatch = !historyLoaded
        historyLoaded = true
        var newFromOthers = 0
        for (m in list) {
            addMessage(m)
            if (!firstBatch && m.nick != nick) newFromOthers++
        }
        if (!expanded && newFromOthers > 0) {
            unread += newFromOthers
            updateBadge()
        }
    }

    private fun addMessage(m: ChatMsg) {
        val list = listLayout ?: return
        val nickPart = m.nick + ": "
        val s = SpannableStringBuilder()
        s.append(nickPart)
        s.setSpan(ForegroundColorSpan(Style.nickColor(m.nick)), 0, nickPart.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, nickPart.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append(m.text)

        val tv = TextView(this).apply {
            text = s
            textSize = Style.TEXT_SP
            setTextColor(Style.textColor)
            setPadding(0, dp(2), 0, dp(2))
        }
        list.addView(tv)
        while (list.childCount > 120) list.removeViewAt(0)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showStatus(s: String?) {
        val v = statusView ?: return
        if (s == null) {
            v.visibility = View.GONE
        } else {
            v.text = s
            v.visibility = View.VISIBLE
        }
    }
}
