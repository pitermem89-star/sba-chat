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
import android.os.SystemClock
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

class OverlayService : Service(), TgHost {

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

    /** Номер текущего подключения. Сообщения от старых подключений (после смены комнаты) игнорируются. */
    private var gen = 0
    private var nick = ""

    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var badge: TextView? = null

    private var panel: PanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var listLayout: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var inputRow: LinearLayout? = null
    private var edit: EditText? = null
    private var statusView: TextView? = null
    private var titleView: TextView? = null
    private var sendBtn: TextView? = null

    // экран выбора комнат
    private var roomsScroll: ScrollView? = null
    private var roomsList: LinearLayout? = null
    private var newRoomName: EditText? = null
    private var newRoomPass: EditText? = null
    private var privToggle: TextView? = null
    private var newIsPrivate = false

    /** true, пока в поле ввода пишется ник, а не сообщение */
    private var nickMode = false

    /** true, пока показан экран выбора комнат */
    private var roomsMode = false

    // крестики-нолики
    private val ttt = TttSession()
    private var gameScroll: ScrollView? = null
    private var gameBox: LinearLayout? = null

    /** true, пока показан экран игры */
    private var gameMode = false

    // Telegram
    private var tgPanel: TgPanel? = null
    private var tgMode = false

    /** Чёрный экран проигравшего (виден только пока он «ищет») */
    private var blackout: FrameLayout? = null
    private val blackoutExit = Runnable { endBlackout() }

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

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Нет разрешения показывать поверх приложений", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        nick = prefs.cleanNick()
        buildBubble()
        buildPanel()
        showBubble()

        if (prefs.currentRoom() != null) startClient()
        try { tgPanel?.ensureStarted() } catch (_: Throwable) {}

        // Нет ника или комнаты — сразу открываем окно и просим выбрать
        if (nick.isEmpty() || prefs.currentRoom() == null) {
            expand()
            main.postDelayed({
                if (nick.isEmpty()) startNickEntry() else showRooms()
            }, 400)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        gen++
        client?.stop()
        main.removeCallbacksAndMessages(null)
        tgPanel?.destroy()
        safeRemove(bubble)
        safeRemove(panel)
        safeRemove(blackout)
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

    private fun toastImpl(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
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

    // ---------------------------------------------------------------- подключение к комнате

    /** (Пере)подключаемся к выбранной комнате. */
    private fun startClient() {
        client?.stop()
        client = null
        val room = prefs.currentRoom() ?: return
        val myGen = ++gen
        historyLoaded = false
        client = ChatClient(
            Prefs.SERVER,
            room.topic(),
            onMessages = { list -> main.post { if (myGen == gen) onIncoming(list) } },
            onStatus = { s -> main.post { if (myGen == gen) showStatus(s) } },
        ).also { it.start() }
    }

    private fun switchRoom(room: Room) {
        val cur = prefs.currentRoom()
        if (cur != null && cur.sameAs(room) && client != null) {
            hideRooms()
            return
        }
        prefs.setCurrent(room)
        ttt.reset()
        if (gameMode) hideGame()
        listLayout?.removeAllViews()
        unread = 0
        updateBadge()
        showStatus(null)
        updateTitle()
        hideRooms()
        startClient()
    }

    private fun deleteRoom(r: Room) {
        val cur = prefs.currentRoom()
        val wasCurrent = cur != null && cur.sameAs(r)
        prefs.removeRoom(r)
        if (wasCurrent) {
            ttt.reset()
            if (gameMode) hideGame()
            gen++
            client?.stop()
            client = null
            listLayout?.removeAllViews()
            showStatus(null)
            updateTitle()
        }
        buildRoomsContent()
    }

    // ---------------------------------------------------------------- кружок

    private fun buildBubble() {
        val sizeDp = prefs.bubbleSizeDp
        val size = dp(sizeDp)
        val root = FrameLayout(this)

        // 0% = квадрат, 100% = полный круг
        val radiusPx = size / 2f * prefs.bubbleRoundPct / 100f

        val icon = TextView(this).apply {
            text = Style.BUBBLE_ICON
            textSize = sizeDp * 0.43f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Style.accent)
                cornerRadius = radiusPx
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

    private fun makeEdit(hintText: String, maxLen: Int): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Style.hintColor)
            setTextColor(Style.textColor)
            textSize = Style.TEXT_SP
            background = roundBg(Style.inputBg, 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        }

    /**
     * Чтобы клавиатура работала в окне поверх приложений, окно должно уметь получать фокус.
     * Обычно оно «не фокусируемое» (игра продолжает получать нажатия), а при касании поля — включаем фокус.
     */
    private fun attachFocusImpl(et: EditText) {
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
    }

    private fun buildPanel() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val w = minOf(dp(prefs.panelWidthDp), screenW - dp(16))
        val h = minOf(dp(prefs.panelHeightDp), screenH - dp(48))

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

        val bw = dp(36)
        header.addView(headerButton("⇄") { toggleRooms() }, LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(headerButton("✎") { startNickEntry() }, LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(headerButton("—") { collapse() }, LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(headerButton("✕") { stopSelf() }, LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.MATCH_PARENT))
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

        // --- экран выбора комнат (скрыт, пока не нажали ⇄)
        val rl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }
        val rs = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        rs.addView(rl, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(rs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        roomsList = rl
        roomsScroll = rs

        // --- экран игры «крестики-нолики» (скрыт, пока не нажали 🎮)
        val gb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val gs = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        gs.addView(gb, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(gs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        gameBox = gb
        gameScroll = gs

        // --- экран Telegram (скрыт, пока не нажали ✈)
        val tp = TgPanel(this, prefs, this)
        tp.root.visibility = View.GONE
        root.addView(tp.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tgPanel = tp

        // поля формы «создать или войти» (создаются один раз, чтобы не терять набранный текст)
        val nameEt = makeEdit("Название комнаты", 30)
        nameEt.inputType = InputType.TYPE_CLASS_TEXT
        attachFocusImpl(nameEt)
        newRoomName = nameEt

        val passEt = makeEdit("Пароль комнаты", 40)
        passEt.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        attachFocusImpl(passEt)
        newRoomPass = passEt

        val doneListener = TextView.OnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) {
                createRoomFromForm()
                true
            } else {
                false
            }
        }
        nameEt.setOnEditorActionListener(doneListener)
        passEt.setOnEditorActionListener(doneListener)

        val toggle = TextView(this).apply {
            textSize = 14f
            setTextColor(Style.textColor)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                newIsPrivate = !newIsPrivate
                updatePrivateUi()
            }
        }
        privToggle = toggle
        updatePrivateUi()

        // --- строка ввода
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val et = makeEdit(Style.INPUT_HINT, 300)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.imeOptions = EditorInfo.IME_ACTION_SEND

        val gameBtn = TextView(this).apply {
            text = "🎮"
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { toggleGame() }
        }
        val tgBtn = TextView(this).apply {
            text = "✈"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Style.textColor)
            setOnClickListener { toggleTelegram() }
        }
        row.addView(tgBtn, LinearLayout.LayoutParams(dp(34), dp(38)))
        row.addView(gameBtn, LinearLayout.LayoutParams(dp(34), dp(38)))
        row.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        row.addView(send, lp)
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inputRow = row

        attachFocusImpl(et)
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
    override fun releaseFocus() {
        // Передумали менять ник — отменяем. (Если ника ещё нет, режим ввода ника остаётся.)
        if (nickMode && nick.isNotEmpty()) finishNickEntry()
        val et = edit
        if (et != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus()
        }
        newRoomName?.clearFocus()
        newRoomPass?.clearFocus()
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
    }

    private fun collapse() {
        if (!expanded) return
        releaseFocus()
        if (tgMode) closeTelegram()
        expanded = false
        safeRemove(panel)
        focusable = false
        showBubble()
    }

    // ---------------------------------------------------------------- Telegram

    private fun toggleTelegram() {
        if (tgMode) {
            closeTelegram()
        } else {
            if (!expanded) expand()
            releaseFocus()
            if (roomsMode) {
                roomsMode = false
                roomsScroll?.visibility = View.GONE
            }
            if (gameMode) {
                gameMode = false
                gameScroll?.visibility = View.GONE
            }
            tgMode = true
            scroll?.visibility = View.GONE
            inputRow?.visibility = View.GONE
            tgPanel?.show()
        }
    }

    /** Прячет экран Telegram без возврата к чату (его место займёт другой экран). */
    private fun leaveTgUi() {
        tgMode = false
        tgPanel?.hide()
    }

    override fun closeTelegram() {
        if (!tgMode) return
        leaveTgUi()
        releaseFocus()
        scroll?.visibility = View.VISIBLE
        inputRow?.visibility = View.VISIBLE
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    override fun incoming() {
        main.post {
            if (!expanded) {
                unread++
                updateBadge()
            }
        }
    }

    override fun attachFocus(et: EditText) = attachFocusImpl(et)

    override fun toast(s: String) = toastImpl(s)

    // ---------------------------------------------------------------- экран комнат

    private fun updateTitle() {
        val t = titleView ?: return
        val r = prefs.currentRoom()
        val roomPart = if (r == null) "Выбери комнату" else r.label()
        t.text = roomPart + if (nick.isNotEmpty()) "  ·  $nick" else ""
    }

    private fun updatePrivateUi() {
        privToggle?.text =
            if (newIsPrivate) "☑ Закрытая (нужен пароль)" else "☐ Закрытая (нужен пароль)"
        newRoomPass?.visibility = if (newIsPrivate) View.VISIBLE else View.GONE
    }

    private fun toggleRooms() {
        if (nick.isEmpty()) {
            startNickEntry()
            return
        }
        if (roomsMode) {
            if (prefs.currentRoom() == null) {
                toast("Выбери или создай комнату")
                return
            }
            releaseFocus()
            hideRooms()
        } else {
            showRooms()
        }
    }

    private fun showRooms() {
        if (!expanded) expand()
        releaseFocus()
        if (tgMode) leaveTgUi()
        if (gameMode) {
            gameMode = false
            gameScroll?.visibility = View.GONE
        }
        roomsMode = true
        updatePrivateUi()
        buildRoomsContent()
        scroll?.visibility = View.GONE
        inputRow?.visibility = View.GONE
        roomsScroll?.visibility = View.VISIBLE
    }

    private fun hideRooms() {
        roomsMode = false
        roomsScroll?.visibility = View.GONE
        scroll?.visibility = View.VISIBLE
        inputRow?.visibility = View.VISIBLE
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun label(s: String): TextView =
        TextView(this).apply {
            text = s
            textSize = 12f
            setTextColor(Style.hintColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }

    private fun topMargin(dpTop: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(dpTop) }

    /** Заново наполняет экран комнат: список сохранённых комнат и форма «создать или войти». */
    private fun buildRoomsContent() {
        val rl = roomsList ?: return
        val nameEt = newRoomName ?: return
        val passEt = newRoomPass ?: return
        val toggle = privToggle ?: return

        rl.removeAllViews()
        val current = prefs.currentRoom()
        val saved = prefs.rooms()

        rl.addView(label("МОИ КОМНАТЫ"))
        if (saved.isEmpty()) {
            rl.addView(TextView(this).apply {
                text = "Пока пусто — создай комнату ниже."
                textSize = 13f
                setTextColor(Style.hintColor)
            })
        }
        for (r in saved) {
            val isCur = current != null && current.sameAs(r)
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameTv = TextView(this).apply {
                text = (if (isCur) "✓ " else "") + r.label()
                textSize = 14f
                setTextColor(if (isCur) Style.accent else Style.textColor)
                typeface = if (isCur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = roundBg(Style.inputBg, 10)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener { switchRoom(r) }
            }
            rowLayout.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val del = TextView(this).apply {
                text = "🗑"
                textSize = 16f
                gravity = Gravity.CENTER
                setOnClickListener { deleteRoom(r) }
            }
            rowLayout.addView(del, LinearLayout.LayoutParams(dp(40), dp(36)))
            rl.addView(rowLayout, topMargin(4))
        }

        rl.addView(label("СОЗДАТЬ ИЛИ ВОЙТИ"))
        rl.addView(nameEt, topMargin(2))
        rl.addView(toggle, topMargin(2))
        rl.addView(passEt, topMargin(0))

        val go = TextView(this).apply {
            text = "Создать / войти"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = roundBg(Style.accent, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { createRoomFromForm() }
        }
        rl.addView(go, topMargin(8))

        rl.addView(TextView(this).apply {
            text = "Открытая: войдёт любой, кто знает название. " +
                "Закрытая: нужны и название, и пароль."
            textSize = 12f
            setTextColor(Style.hintColor)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun createRoomFromForm() {
        val nameEt = newRoomName ?: return
        val passEt = newRoomPass ?: return
        val name = nameEt.text.toString().trim().take(30)
        val pass = passEt.text.toString().trim().take(40)
        if (name.isEmpty()) {
            toast("Введи название комнаты")
            return
        }
        if (newIsPrivate && pass.isEmpty()) {
            toast("Для закрытой комнаты нужен пароль")
            return
        }
        val room = Room(name, newIsPrivate, if (newIsPrivate) pass else "")
        nameEt.setText("")
        passEt.setText("")
        releaseFocus()
        switchRoom(room)
    }

    // ---------------------------------------------------------------- сообщения и ник

    private fun sendCurrent() {
        val et = edit ?: return
        val text = et.text.toString().trim()

        if (nickMode) {
            if (text.isEmpty()) {
                if (nick.isEmpty()) {
                    toast("Сначала введи ник")
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
            if (prefs.currentRoom() == null) showRooms()
            return
        }

        if (text.isNotEmpty()) {
            val c = client
            if (c == null) {
                toast("Сначала выбери комнату")
                showRooms()
                return
            }
            c.send(nick, text)
            et.setText("")
        }
        releaseFocus()
    }

    /** Поле ввода временно превращается в поле для ника (первый запуск или кнопка ✎). */
    private fun startNickEntry() {
        val et = edit ?: return
        if (!expanded) expand()
        if (tgMode) leaveTgUi()
        if (roomsMode) hideRooms()
        if (gameMode) hideGame()
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

    // ---------------------------------------------------------------- крестики-нолики

    private fun mmss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    private fun newGameId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    private fun toggleGame() {
        if (nick.isEmpty()) {
            startNickEntry()
            return
        }
        if (client == null) {
            toast("Сначала выбери комнату")
            showRooms()
            return
        }
        if (gameMode) {
            releaseFocus()
            hideGame()
        } else {
            showGame()
        }
    }

    private fun showGame() {
        if (!expanded) expand()
        releaseFocus()
        if (tgMode) leaveTgUi()
        if (roomsMode) {
            roomsMode = false
            roomsScroll?.visibility = View.GONE
        }
        gameMode = true
        scroll?.visibility = View.GONE
        inputRow?.visibility = View.GONE
        gameScroll?.visibility = View.VISIBLE
        renderGame()
    }

    private fun hideGame() {
        gameMode = false
        gameScroll?.visibility = View.GONE
        scroll?.visibility = View.VISIBLE
        inputRow?.visibility = View.VISIBLE
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /** Отправить игровое событие всем в комнате. Экран обновится, когда событие вернётся с сервера. */
    private fun sendGame(type: String, gid: String, cell: Int = -1) {
        val c = client
        if (c == null) {
            toast("Сначала выбери комнату")
            return
        }
        c.send(nick, TttProto.encode(type, gid, prefs.playerId, cell))
    }

    private fun gameText(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun gameButton(label: String, primary: Boolean = true, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = roundBg(if (primary) Style.accent else Style.inputBg, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = topMargin(8)
            setOnClickListener { onClick() }
        }

    private fun statusLine(g: TttGame, me: String): String {
        val xn = g.x.nick
        val on = g.o?.nick ?: "?"
        return when (g.result) {
            'X' -> "Победил $xn (✕). $on ищет!"
            'O' -> "Победил $on (○). $xn ищет!"
            'D' -> "Ничья! Сыграйте ещё."
            'A' -> "Игра прервана."
            else -> {
                val cur = g.currentPlayer()
                val sign = if (g.turn == 'X') "✕" else "○"
                if (cur?.id == me) "Твой ход ($sign)" else "Ходит ${cur?.nick ?: "?"} ($sign)"
            }
        }
    }

    private fun boardView(g: TttGame, me: String): LinearLayout {
        val canMove = g.isRunning && g.currentPlayer()?.id == me
        val colLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0..2) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..2) {
                val i = r * 3 + c
                val ch = g.board[i]
                val cell = TextView(this).apply {
                    text = when (ch) {
                        'X' -> "✕"
                        'O' -> "○"
                        else -> ""
                    }
                    textSize = 22f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (ch == 'X') Style.gameX else Style.gameO)
                    background = roundBg(Style.inputBg, 8)
                    if (canMove && ch == ' ') setOnClickListener { sendGame("mv", g.id, i) }
                }
                val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
                lp.setMargins(dp(2), dp(2), dp(2), dp(2))
                rowLayout.addView(cell, lp)
            }
            colLayout.addView(
                rowLayout,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        return colLayout
    }

    private fun renderGame() {
        val box = gameBox ?: return
        box.removeAllViews()
        val me = prefs.playerId
        val g = ttt.game

        box.addView(gameButton("← В чат", primary = false) {
            releaseFocus()
            hideGame()
        })
        box.addView(gameText("Крестики-нолики", 16f, Style.textColor, bold = true))
        box.addView(
            gameText(
                "Проигравший ищет: у него экран темнеет на ${mmss(Style.BLACKOUT_SEC)}.",
                12f, Style.hintColor,
            )
        )

        when {
            g == null -> {
                box.addView(gameText("Сейчас никто не играет.", 14f, Style.textColor))
                box.addView(gameButton("Предложить игру") { sendGame("inv", newGameId()) })
            }
            g.isOpen -> {
                if (g.x.id == me) {
                    box.addView(gameText("Ждём соперника… Ты играешь за ✕.", 14f, Style.textColor))
                    box.addView(gameButton("Отменить") { sendGame("end", g.id) })
                } else {
                    box.addView(gameText("${g.x.nick} предлагает сыграть.", 14f, Style.textColor))
                    box.addView(gameButton("Принять (ты будешь ○)") { sendGame("acc", g.id) })
                }
            }
            else -> {
                box.addView(gameText("${g.x.nick} ✕  против  ${g.o?.nick ?: "?"} ○", 13f, Style.hintColor))
                box.addView(boardView(g, me))
                box.addView(gameText(statusLine(g, me), 14f, Style.textColor, bold = true))
                val iPlay = g.x.id == me || g.o?.id == me
                if (g.isRunning && iPlay) {
                    box.addView(gameButton("Прервать игру", primary = false) { sendGame("end", g.id) })
                }
                if (g.isFinished) {
                    box.addView(gameButton("Сыграть ещё") { sendGame("inv", newGameId()) })
                }
            }
        }
    }

    /** Применяет игровое событие. Возвращает true, если о нём стоит напомнить значком на пузыре. */
    private fun handleGameEvent(ev: TttProto.Event, live: Boolean): Boolean {
        val change = ttt.apply(ev.type, ev.gid, ev.player, ev.cell)
        val g = ttt.game
        val mine = ev.player.id == prefs.playerId
        var worthBadge = false

        // Старые события (история при входе в комнату) только восстанавливают состояние, без сообщений и затемнения
        if (live && g != null) {
            when (change) {
                TttChange.INVITED -> {
                    if (mine) {
                        addSystemLine("🎮 Ты предложил сыграть в крестики-нолики. Ждём соперника")
                    } else {
                        addSystemLine("🎮 ${ev.player.nick} предлагает сыграть в крестики-нолики. Нажми 🎮")
                        worthBadge = true
                    }
                }
                TttChange.ACCEPTED ->
                    addSystemLine("🎮 Играют: ${g.x.nick} ✕ и ${ev.player.nick} ○")
                TttChange.FINISHED -> {
                    val loser = g.loser()
                    val winner = g.winner()
                    if (loser != null && winner != null) {
                        addSystemLine(
                            "🎮 Победил ${winner.nick}! ${loser.nick} ищет: экран потемнеет на ${mmss(Style.BLACKOUT_SEC)}"
                        )
                    } else {
                        addSystemLine("🎮 Ничья!")
                    }
                }
                TttChange.ABORTED -> addSystemLine("🎮 Игра прервана")
                else -> {}
            }
        }

        if (live && change == TttChange.FINISHED) {
            val loser = g?.loser()
            if (loser != null && loser.id == prefs.playerId) startBlackout()
        }
        if (gameMode) renderGame()
        return worthBadge
    }

    private fun addSystemLine(s: String) {
        val list = listLayout ?: return
        val tv = TextView(this).apply {
            text = s
            textSize = Style.TEXT_SP - 1
            setTextColor(Style.hintColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            setPadding(0, dp(3), 0, dp(3))
        }
        list.addView(tv)
        while (list.childCount > 120) list.removeViewAt(0)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Экран проигравшего темнеет на [Style.BLACKOUT_SEC] секунд: на весь экран ложится чёрное окно,
     * которое не пропускает нажатия. Пока он «считает», остальные прячутся.
     * Запасной выход: зажать экран на 5 секунд.
     */
    private fun startBlackout() {
        if (blackout != null) return
        releaseFocus()
        val endAt = SystemClock.elapsedRealtime() + Style.BLACKOUT_SEC * 1000L

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        val tv = TextView(this).apply {
            setTextColor(0xFF5A5A6E.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val hint = TextView(this).apply {
            text = "Выйти раньше: зажми экран на 5 секунд"
            setTextColor(0xFF3A3A48.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        val hintLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        )
        hintLp.bottomMargin = dp(60)
        root.addView(hint, hintLp)

        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START

        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> main.postDelayed(blackoutExit, 5000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(blackoutExit)
            }
            true
        }

        try {
            wm.addView(root, p)
        } catch (_: Exception) {
            return
        }
        blackout = root
        root.animate().alpha(1f).setDuration(1500).start() // экран плавно темнеет

        val tick = object : Runnable {
            override fun run() {
                if (blackout !== root) return
                val left = ((endAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
                if (left <= 0) {
                    endBlackout()
                    return
                }
                tv.text = "Ты проиграл, теперь ищешь!\n\nЭкран включится через ${mmss(left)}"
                main.postDelayed(this, 500)
            }
        }
        tick.run()
    }

    private fun endBlackout() {
        val v = blackout ?: return
        blackout = null
        main.removeCallbacks(blackoutExit)
        v.animate().cancel()
        safeRemove(v)
        toast("Экран включён. Иди искать!")
    }

    private fun onIncoming(list: List<ChatMsg>) {
        val firstBatch = !historyLoaded
        historyLoaded = true
        var newFromOthers = 0
        for (m in list) {
            val ev = TttProto.parse(m.text, m.nick)
            if (ev != null) {
                // игровое событие, в чате как текст не показываем
                if (handleGameEvent(ev, live = !firstBatch)) newFromOthers++
                continue
            }
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
