package com.minichat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.Executors

/** То, что TgPanel берёт у окна чата. */
interface TgHost {
    fun attachFocus(et: EditText)
    fun releaseFocus()
    fun toast(s: String)
    /** Закрыть Telegram и вернуться к обычному чату. */
    fun closeTelegram()
    /** Пришло новое сообщение из Telegram (для красного значка на пузыре). */
    fun incoming()
}

/**
 * Экран Telegram внутри окна чата: настройка → вход → список чатов → переписка с ответами,
 * просмотр фото, стикеров, видео.
 */
class TgPanel(private val ctx: Context, private val prefs: Prefs, private val host: TgHost) : TgListener {

    private enum class Screen { SETUP, LOGIN, LIST, CHAT }

    val root = FrameLayout(ctx)
    val input: EditText

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 12).toInt().coerceAtMost(40 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var client: TgClient? = null
    private var screen = Screen.SETUP
    private var visible = false

    private val topTitle: TextView
    private val topBack: TextView
    private val topAction: TextView
    private val statusTv: TextView
    private val content: FrameLayout
    private val loginBox: LinearLayout
    private val loginInfo: TextView
    private val listScroll: ScrollView
    private val listBox: LinearLayout
    private val chatScroll: ScrollView
    private val chatBox: LinearLayout
    private val replyBar: LinearLayout
    private val replyText: TextView
    private val inputRow: LinearLayout
    private val sendBtn: TextView
    private val viewer: FrameLayout
    private val viewerBody: FrameLayout

    private var curChat = 0L
    private val msgs = ArrayList<TdApi.Message>()
    private var replyTo: TdApi.Message? = null

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

    private fun tv(s: String, size: Float, color: Int = Style.textColor, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit): TextView =
        tv(label, 14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = round(if (primary) Style.accent else Style.inputBg, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    init {
        root.visibility = View.GONE
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // --- верхняя строка
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Style.inputBg)
        }
        topBack = tv("←", 20f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onBack() }
        }
        topTitle = tv("Telegram", 14f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topAction = tv("", 12f, Style.hintColor).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(10), 0)
        }
        top.addView(topBack, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(topTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        top.addView(topAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))

        statusTv = tv("", 11f, Style.errorColor).apply {
            setPadding(dp(10), dp(2), dp(10), dp(2))
            visibility = View.GONE
        }
        col.addView(statusTv)

        // --- середина: вход / список / переписка
        content = FrameLayout(ctx)

        loginBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        loginInfo = tv("", 13f)
        val loginScroll = ScrollView(ctx)
        loginScroll.addView(loginBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        loginScroll.tag = "login"
        content.addView(loginScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        listScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        listScroll.addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(listScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        chatBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        chatScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        chatScroll.addView(chatBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(chatScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- полоска «ответ на…»
        replyBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Style.headerBg)
            setPadding(dp(10), dp(2), dp(4), dp(2))
            visibility = View.GONE
        }
        replyText = tv("", 12f, Style.accent).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        replyBar.addView(replyText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        replyBar.addView(
            tv("✕", 16f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { setReply(null) }
            },
            LinearLayout.LayoutParams(dp(36), dp(30)),
        )
        col.addView(replyBar)

        // --- строка ввода (в чате — сообщение, при входе — номер, код, пароль)
        inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            visibility = View.GONE
        }
        input = EditText(ctx).apply {
            setHintTextColor(Style.hintColor)
            setTextColor(Style.textColor)
            textSize = Style.TEXT_SP
            background = round(Style.inputBg, 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2000))
        }
        host.attachFocus(input)
        input.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) {
                submit()
                true
            } else {
                false
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = tv("➤", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Style.accent)
            }
            setOnClickListener { submit() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(6) })
        col.addView(inputRow)

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- просмотр фото и видео поверх всего
        viewer = FrameLayout(ctx).apply {
            setBackgroundColor(0xF0000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        viewerBody = FrameLayout(ctx)
        viewer.addView(viewerBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        viewer.addView(
            tv("✕", 22f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { closeViewer() }
            },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END),
        )
        root.addView(viewer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------------------------------------------------------------- жизненный цикл

    private fun hasCredentials(): Boolean = prefs.tgApiId > 0 && prefs.tgApiHash.isNotBlank()

    /** Запускает связь с Telegram (если заданы api_id и api_hash), чтобы приходили уведомления на пузырь. */
    fun ensureStarted() {
        if (client != null || !hasCredentials()) return
        try {
            val db = File(ctx.filesDir, "td").apply { mkdirs() }
            val fl = File(ctx.filesDir, "tdfiles").apply { mkdirs() }
            val c = TgHub.get(
                db.absolutePath, fl.absolutePath, prefs.tgApiId, prefs.tgApiHash.trim(),
                prefs.tgProxy, android.os.Build.MODEL ?: "Android", "1.0",
            )
            c.listener = this
            client = c
        } catch (e: Throwable) {
            host.toast("Telegram не запустился: ${e.message}")
        }
    }

    fun show() {
        visible = true
        root.visibility = View.VISIBLE
        ensureStarted()
        client?.listener = this
        screen = when {
            !hasCredentials() || client == null -> Screen.SETUP
            client?.auth != TgAuth.READY -> Screen.LOGIN
            else -> Screen.LIST
        }
        render()
    }

    fun hide() {
        visible = false
        closeViewer()
        leaveChat()
        root.visibility = View.GONE
    }

    fun destroy() {
        closeViewer()
        client?.listener = null
        pool.shutdownNow()
    }

    private fun onBack() {
        when (screen) {
            Screen.CHAT -> {
                leaveChat()
                screen = Screen.LIST
                render()
            }
            else -> host.closeTelegram()
        }
    }

    private fun leaveChat() {
        if (curChat != 0L) client?.close(curChat)
        curChat = 0L
        msgs.clear()
        replyTo = null
    }

    // ---------------------------------------------------------------- события Telegram (из другого потока)

    override fun onAuth() {
        main.post {
            if (!visible) return@post
            val c = client ?: return@post
            if (c.auth == TgAuth.READY) {
                if (screen == Screen.LOGIN || screen == Screen.SETUP) screen = Screen.LIST
            } else if (screen != Screen.SETUP) {
                leaveChat()
                screen = Screen.LOGIN
            }
            render()
        }
    }

    override fun onConnection() {
        main.post { if (visible) updateStatus() }
    }

    override fun onChats() {
        main.post { if (visible && screen == Screen.LIST) renderList() }
    }

    override fun onNewMessage(m: TdApi.Message) {
        main.post {
            val c = client ?: return@post
            if (visible && screen == Screen.CHAT && m.chatId == curChat) {
                if (msgs.none { it.id == m.id }) {
                    msgs.add(m)
                    chatBox.addView(messageView(m))
                    scrollDown()
                    if (!m.isOutgoing) c.markRead(curChat, longArrayOf(m.id))
                }
                return@post
            }
            if (!m.isOutgoing) {
                val chat = c.chat(m.chatId)
                if (chat == null || !c.isMuted(chat)) host.incoming()
            }
        }
    }

    override fun onMessageReplaced(oldId: Long, m: TdApi.Message) {
        main.post {
            if (!visible || screen != Screen.CHAT || m.chatId != curChat) return@post
            val i = msgs.indexOfFirst { it.id == oldId }
            if (i >= 0) msgs[i] = m else if (msgs.none { it.id == m.id }) msgs.add(m)
            renderMessages()
        }
    }

    override fun onMessageDeleted(chatId: Long, ids: LongArray) {
        main.post {
            if (!visible || screen != Screen.CHAT || chatId != curChat) return@post
            if (msgs.removeAll { it.id in ids }) renderMessages()
        }
    }

    override fun onError(msg: String) {
        main.post { host.toast(msg) }
    }

    // ---------------------------------------------------------------- отрисовка

    private fun updateStatus() {
        val c = client
        val s = c?.connection ?: ""
        if (s.isEmpty()) {
            statusTv.visibility = View.GONE
        } else {
            statusTv.text = s + if (prefs.tgProxy.isBlank()) "  (если долго: в России Telegram может требовать VPN или прокси)" else ""
            statusTv.visibility = View.VISIBLE
        }
    }

    private fun render() {
        updateStatus()
        val loginScroll = content.findViewWithTag<View>("login")
        loginScroll?.visibility = if (screen == Screen.SETUP || screen == Screen.LOGIN) View.VISIBLE else View.GONE
        listScroll.visibility = if (screen == Screen.LIST) View.VISIBLE else View.GONE
        chatScroll.visibility = if (screen == Screen.CHAT) View.VISIBLE else View.GONE
        replyBar.visibility = if (screen == Screen.CHAT && replyTo != null) View.VISIBLE else View.GONE
        when (screen) {
            Screen.SETUP -> renderSetup()
            Screen.LOGIN -> renderLogin()
            Screen.LIST -> renderList()
            Screen.CHAT -> renderChat()
        }
    }

    private fun renderSetup() {
        topTitle.text = "Telegram"
        topAction.text = ""
        topAction.setOnClickListener(null)
        inputRow.visibility = View.GONE
        loginBox.removeAllViews()
        loginBox.addView(tv("Нужно один раз получить ключи Telegram (бесплатно):", 13f, bold = true))
        loginBox.addView(
            tv(
                "1. Открой my.telegram.org и войди по своему номеру.\n" +
                    "2. «API development tools» → создай приложение (название любое).\n" +
                    "3. Впиши api_id и api_hash в настройках этого приложения, раздел «Telegram», " +
                    "и нажми «Запустить чат» заново.",
                13f, Style.hintColor,
            ).apply { setPadding(0, dp(6), 0, dp(6)) }
        )
        if (client == null && hasCredentials()) {
            loginBox.addView(tv("Telegram не удалось запустить. Закрой приложение полностью и открой снова.", 13f, Style.errorColor))
        }
        loginBox.addView(button("← В чат", primary = false) { host.closeTelegram() })
    }

    private fun renderLogin() {
        val c = client ?: return
        topTitle.text = "Вход в Telegram"
        topAction.text = ""
        topAction.setOnClickListener(null)
        loginBox.removeAllViews()

        val (title, hint, type) = when (c.auth) {
            TgAuth.PHONE -> Triple("Номер телефона", "+7 900 000 00 00", InputType.TYPE_CLASS_PHONE)
            TgAuth.CODE -> Triple("Код из Telegram", "Код", InputType.TYPE_CLASS_NUMBER)
            TgAuth.PASSWORD -> Triple("Пароль", "Пароль двухэтапной проверки", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            else -> Triple("Подождите…", "", InputType.TYPE_CLASS_TEXT)
        }
        loginBox.addView(tv(title, 15f, bold = true))
        if (c.authHint.isNotEmpty()) {
            loginBox.addView(tv(c.authHint, 13f, Style.hintColor).apply { setPadding(0, dp(4), 0, 0) })
        }
        if (c.authError.isNotEmpty()) {
            loginBox.addView(tv(c.authError, 13f, Style.errorColor).apply { setPadding(0, dp(4), 0, 0) })
        }
        val interactive = c.auth == TgAuth.PHONE || c.auth == TgAuth.CODE || c.auth == TgAuth.PASSWORD
        if (!interactive) {
            loginBox.addView(tv(if (c.auth == TgAuth.STARTING) "Запускаю Telegram…" else "", 13f, Style.hintColor))
        }
        loginBox.addView(button("← В чат", primary = false) { host.closeTelegram() })

        inputRow.visibility = if (interactive) View.VISIBLE else View.GONE
        input.hint = hint
        input.inputType = type
        input.setSingleLine(true)
        input.setText("")
    }

    private fun renderList() {
        val c = client ?: return
        topTitle.text = "Telegram"
        topAction.text = "выйти"
        topAction.setOnClickListener {
            c.logOut()
            host.toast("Вышел из Telegram")
        }
        inputRow.visibility = View.GONE
        listBox.removeAllViews()
        val chats = c.chatList().take(80)
        if (chats.isEmpty()) {
            listBox.addView(tv("Загружаю чаты…", 13f, Style.hintColor).apply { setPadding(dp(6), dp(10), 0, 0) })
        }
        for (chat in chats) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = round(Style.inputBg, 10)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener { openChat(chat.id) }
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                tv((if (c.isMuted(chat)) "🔕 " else "") + chat.title, 14f, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (chat.unreadCount > 0) {
                head.addView(
                    tv(chat.unreadCount.toString(), 11f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = round(if (c.isMuted(chat)) Style.hintColor else Style.accent, 10)
                        setPadding(dp(7), dp(1), dp(7), dp(1))
                    }
                )
            }
            row.addView(head)
            val last = chat.lastMessage
            val prev = (if (last != null && last.isOutgoing) "Ты: " else "") + TgClient.describe(last?.content).replace('\n', ' ')
            row.addView(tv(prev, 12f, Style.hintColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            listBox.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) },
            )
        }
    }

    private fun openChat(id: Long) {
        val c = client ?: return
        curChat = id
        msgs.clear()
        replyTo = null
        screen = Screen.CHAT
        c.open(id)
        render()
        loadHistory(id, 0)
    }

    private fun loadHistory(id: Long, attempt: Int) {
        val c = client ?: return
        c.history(id, 0, 40) { list ->
            main.post {
                if (curChat != id) return@post
                // сразу после открытия чата TDLib иногда отдаёт только последнее сообщение: пробуем ещё раз
                if (list.size < 5 && attempt < 2) {
                    main.postDelayed({ if (curChat == id) loadHistory(id, attempt + 1) }, 700)
                }
                if (list.isNotEmpty() || attempt >= 2) {
                    msgs.clear()
                    msgs.addAll(list)
                    renderMessages()
                    val inc = list.filter { !it.isOutgoing }.map { it.id }.toLongArray()
                    c.markRead(id, inc)
                }
            }
        }
    }

    private fun renderChat() {
        val c = client ?: return
        topTitle.text = c.chat(curChat)?.title ?: "Чат"
        topAction.text = ""
        topAction.setOnClickListener(null)
        inputRow.visibility = View.VISIBLE
        input.hint = "Сообщение…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setSingleLine(true)
        input.setText("")
        updateReplyBar()
        renderMessages()
    }

    private fun renderMessages() {
        chatBox.removeAllViews()
        if (msgs.isEmpty()) {
            chatBox.addView(tv("Загружаю сообщения…", 13f, Style.hintColor).apply { setPadding(dp(6), dp(10), 0, 0) })
            return
        }
        for (m in msgs.takeLast(60)) chatBox.addView(messageView(m))
        scrollDown()
    }

    private fun scrollDown() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- сообщение

    private fun messageView(m: TdApi.Message): View {
        val c = client
        val out = m.isOutgoing
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (out) 0xFF2B5278.toInt() else Style.inputBg, 10)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setOnClickListener { setReply(m) }
        }
        if (!out && c != null) {
            val name = c.senderName(m)
            if (name.isNotEmpty()) box.addView(tv(name, 12f, Style.nickColor(name), bold = true))
        }

        // «ответ на …»
        val rt = m.replyTo
        if (rt is TdApi.MessageReplyToMessage) {
            val orig = msgs.firstOrNull { it.id == rt.messageId }
            val s = if (orig != null) TgClient.describe(orig.content).replace('\n', ' ') else "сообщение"
            box.addView(tv("↩ $s", 11f, Style.accent).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        val media = TgClient.media(m.content)
        if (media.kind != MediaKind.NONE) {
            box.addView(mediaView(media), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) })
            val cap = TgClient.caption(m.content)
            if (cap.isNotEmpty()) box.addView(tv(cap, Style.TEXT_SP))
        } else {
            val txt = TgClient.describe(m.content)
            val extra = if (m.content is TdApi.MessageText) "" else ""
            box.addView(tv(txt + extra, Style.TEXT_SP).apply {
                if (m.content !is TdApi.MessageText) setTextColor(Style.hintColor)
            })
        }

        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.date * 1000L))
        box.addView(tv(time, 10f, Style.hintColor).apply { gravity = Gravity.END })

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(3)
        if (out) {
            lp.gravity = Gravity.END
            lp.leftMargin = dp(36)
        } else {
            lp.gravity = Gravity.START
            lp.rightMargin = dp(36)
        }
        box.layoutParams = lp
        return box
    }

    private fun mediaView(info: MediaInfo): View {
        val maxW = dp(if (info.kind == MediaKind.STICKER) 130 else 190)
        val maxH = dp(if (info.kind == MediaKind.STICKER) 130 else 220)
        var w = if (info.w > 0) info.w else maxW
        var h = if (info.h > 0) info.h else maxW
        val k = minOf(maxW / w.toFloat(), maxH / h.toFloat())
        w = (w * k).toInt().coerceAtLeast(dp(60))
        h = (h * k).toInt().coerceAtLeast(dp(60))

        val frame = FrameLayout(ctx)
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = if (info.kind == MediaKind.STICKER) null else round(Style.headerBg, 8)
        }
        frame.addView(iv, FrameLayout.LayoutParams(w, h))

        val mini = info.mini
        if (mini != null && info.kind != MediaKind.STICKER) {
            val b = BitmapFactory.decodeByteArray(mini, 0, mini.size)
            if (b != null) iv.setImageBitmap(b)
        }
        val pv = info.preview
        if (pv != null) loadInto(pv, iv, maxW * 2)

        if (info.kind == MediaKind.VIDEO || info.kind == MediaKind.ANIM) {
            frame.addView(
                tv(if (info.kind == MediaKind.ANIM) "GIF ▶" else "▶", 26f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(w, h),
            )
        }
        frame.setOnClickListener { openViewer(info) }
        return frame
    }

    // ---------------------------------------------------------------- картинки

    private fun loadInto(file: TdApi.File, iv: ImageView, maxPx: Int) {
        val c = client ?: return
        c.fetch(file) { path ->
            val key = "$path#$maxPx"
            val hit = cache.get(key)
            if (hit != null) {
                main.post { iv.setImageBitmap(hit) }
                return@fetch
            }
            try {
                pool.execute {
                    val b = decode(path, maxPx)
                    if (b != null) {
                        cache.put(key, b)
                        main.post { iv.setImageBitmap(b) }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun decode(path: String, maxPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= maxPx && o.outHeight / (sample * 2) >= maxPx) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, o2)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- просмотр

    private fun openViewer(info: MediaInfo) {
        val full = info.full ?: info.preview ?: return
        viewerBody.removeAllViews()
        viewer.visibility = View.VISIBLE
        val wait = tv("Загружаю…", 14f, Style.hintColor).apply { gravity = Gravity.CENTER }
        viewerBody.addView(wait, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val c = client ?: return
        c.fetch(full) { path ->
            main.post {
                if (viewer.visibility != View.VISIBLE) return@post
                viewerBody.removeAllViews()
                if (info.kind == MediaKind.VIDEO || info.kind == MediaKind.ANIM) {
                    val vv = VideoView(ctx)
                    viewerBody.addView(vv, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
                    ))
                    vv.setOnPreparedListener { mp ->
                        mp.isLooping = info.kind == MediaKind.ANIM
                        vv.start()
                    }
                    vv.setOnErrorListener { _, _, _ ->
                        host.toast("Не удалось воспроизвести видео")
                        true
                    }
                    vv.setOnClickListener { if (vv.isPlaying) vv.pause() else vv.start() }
                    vv.setVideoPath(path)
                } else {
                    val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    viewerBody.addView(iv, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    try {
                        pool.execute {
                            val b = decode(path, 1400)
                            main.post { if (b != null) iv.setImageBitmap(b) else host.toast("Не удалось показать") }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun closeViewer() {
        if (viewer.visibility != View.VISIBLE) return
        for (i in 0 until viewerBody.childCount) {
            val v = viewerBody.getChildAt(i)
            if (v is VideoView) {
                try { v.stopPlayback() } catch (_: Exception) {}
            }
        }
        viewerBody.removeAllViews()
        viewer.visibility = View.GONE
    }

    // ---------------------------------------------------------------- ответ и отправка

    private fun setReply(m: TdApi.Message?) {
        replyTo = m
        updateReplyBar()
        if (m != null) {
            input.requestFocus()
        }
    }

    private fun updateReplyBar() {
        val r = replyTo
        if (r == null || screen != Screen.CHAT) {
            replyBar.visibility = View.GONE
        } else {
            val who = if (r.isOutgoing) "себе" else (client?.senderName(r) ?: "")
            replyText.text = "↩ Ответ $who: " + TgClient.describe(r.content).replace('\n', ' ')
            replyBar.visibility = View.VISIBLE
        }
    }

    private fun submit() {
        val c = client ?: return
        val text = input.text.toString().trim()
        if (screen == Screen.LOGIN) {
            if (text.isEmpty()) return
            when (c.auth) {
                TgAuth.PHONE -> c.sendPhone(text)
                TgAuth.CODE -> c.sendCode(text)
                TgAuth.PASSWORD -> c.sendPassword(text)
                else -> {}
            }
            input.setText("")
            host.releaseFocus()
            return
        }
        if (screen == Screen.CHAT && curChat != 0L) {
            if (text.isEmpty()) {
                host.releaseFocus()
                return
            }
            c.sendText(curChat, text, replyTo?.id ?: 0L)
            input.setText("")
            replyTo = null
            updateReplyBar()
            host.releaseFocus()
        }
    }
}
