package com.minichat.app

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/** Этапы входа в Telegram. */
enum class TgAuth { STARTING, PHONE, CODE, PASSWORD, READY, CLOSED, OTHER }

/** Что хочет знать экран. Все методы вызываются НЕ из главного потока. */
interface TgListener {
    fun onAuth()
    fun onConnection()
    fun onChats()
    fun onNewMessage(m: TdApi.Message)
    fun onMessageReplaced(oldId: Long, m: TdApi.Message)
    fun onMessageDeleted(chatId: Long, ids: LongArray)
    fun onError(msg: String)
}

/**
 * Тонкая обёртка над TDLib (официальная библиотека Telegram): вход, список чатов, история,
 * отправка ответов, скачивание файлов. Здесь нет ничего про окна Android.
 */
class TgClient(
    private val dbDir: String,
    private val filesDir: String,
    private val apiId: Int,
    private val apiHash: String,
    private val deviceModel: String,
    private val appVersion: String,
) {
    @Volatile var listener: TgListener? = null
    @Volatile var auth = TgAuth.STARTING
    @Volatile var authHint = ""        // подсказка: куда пришёл код, какое имя у пароля и т.п.
    @Volatile var authError = ""       // последняя ошибка входа
    @Volatile var connection = "Подключаюсь…"
    @Volatile var myId = 0L

    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val users = ConcurrentHashMap<Long, String>()
    private val waiters = ConcurrentHashMap<Int, MutableList<(TdApi.File) -> Unit>>()
    private var client: Client? = null
    private var proxyApplied = false
    @Volatile private var pendingProxy: String = ""
    @Volatile private var chatsRequested = false

    // ---------------------------------------------------------------- запуск

    fun start(proxyLink: String) {
        pendingProxy = proxyLink
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
        } catch (_: Throwable) {
        }
        client = Client.create(
            { obj -> handleUpdate(obj) },
            { e -> authError = "Ошибка Telegram: ${e.message}" },
            { e -> authError = "Ошибка Telegram: ${e.message}" },
        )
    }

    fun stop() {
        listener = null
        try {
            client?.send(TdApi.Close()) {}
        } catch (_: Throwable) {
        }
    }

    private fun send(f: TdApi.Function<*>, cb: ((TdApi.Object) -> Unit)? = null) {
        val c = client ?: return
        c.send(f) { r ->
            if (r is TdApi.Error) {
                onError(f, r)
            } else {
                cb?.invoke(r)
            }
        }
    }

    private fun onError(f: TdApi.Function<*>, e: TdApi.Error) {
        val during = f is TdApi.SetAuthenticationPhoneNumber || f is TdApi.CheckAuthenticationCode ||
            f is TdApi.CheckAuthenticationPassword || f is TdApi.SetTdlibParameters
        if (during) {
            authError = translateError(e.message)
            listener?.onAuth()
        } else if (f is TdApi.SendMessage) {
            val m = e.message ?: ""
            listener?.onError(
                if (m.contains("WRITE_FORBIDDEN") || m.contains("not enough rights")) "В этот чат писать нельзя"
                else "Не отправилось: $m"
            )
        }
    }

    private fun translateError(m: String?): String {
        val s = m ?: ""
        return when {
            s.contains("PHONE_NUMBER_INVALID") -> "Неверный номер телефона"
            s.contains("PHONE_CODE_INVALID") -> "Неверный код"
            s.contains("PHONE_CODE_EXPIRED") -> "Код устарел, начни заново"
            s.contains("PASSWORD_HASH_INVALID") -> "Неверный пароль"
            s.contains("API_ID_INVALID") -> "api_id или api_hash неверные"
            s.contains("FLOOD") -> "Слишком много попыток, подожди немного"
            else -> s
        }
    }

    // ---------------------------------------------------------------- обновления

    private fun handleUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuthState(o.authorizationState)
            is TdApi.UpdateConnectionState -> {
                connection = when (o.state) {
                    is TdApi.ConnectionStateReady -> ""
                    is TdApi.ConnectionStateWaitingForNetwork -> "Нет сети"
                    is TdApi.ConnectionStateConnectingToProxy -> "Подключаюсь через прокси…"
                    is TdApi.ConnectionStateConnecting -> "Подключаюсь к Telegram…"
                    is TdApi.ConnectionStateUpdating -> "Обновляю…"
                    else -> ""
                }
                listener?.onConnection()
            }
            is TdApi.UpdateNewChat -> {
                chats[o.chat.id] = o.chat
                listener?.onChats()
            }
            is TdApi.UpdateChatLastMessage -> {
                val c = chats[o.chatId]
                if (c != null) {
                    c.lastMessage = o.lastMessage
                    if (o.positions != null) c.positions = o.positions
                    listener?.onChats()
                }
            }
            is TdApi.UpdateChatPosition -> {
                val c = chats[o.chatId]
                if (c != null) {
                    updatePosition(c, o.position)
                    listener?.onChats()
                }
            }
            is TdApi.UpdateChatTitle -> {
                chats[o.chatId]?.title = o.title
                listener?.onChats()
            }
            is TdApi.UpdateChatReadInbox -> {
                chats[o.chatId]?.unreadCount = o.unreadCount
                listener?.onChats()
            }
            is TdApi.UpdateUser -> users[o.user.id] = fullName(o.user)
            is TdApi.UpdateFile -> onFile(o.file)
            is TdApi.UpdateNewMessage -> {
                val c = chats[o.message.chatId]
                if (c != null) c.lastMessage = o.message
                listener?.onNewMessage(o.message)
            }
            is TdApi.UpdateMessageSendSucceeded -> listener?.onMessageReplaced(o.oldMessageId, o.message)
            is TdApi.UpdateDeleteMessages -> if (o.isPermanent) listener?.onMessageDeleted(o.chatId, o.messageIds)
            else -> {}
        }
    }

    private fun updatePosition(c: TdApi.Chat, p: TdApi.ChatPosition) {
        val old = c.positions ?: emptyArray()
        val list = ArrayList<TdApi.ChatPosition>()
        for (x in old) if (!sameList(x.list, p.list)) list.add(x)
        if (p.order != 0L) list.add(p)
        c.positions = list.toTypedArray()
    }

    private fun sameList(a: TdApi.ChatList?, b: TdApi.ChatList?): Boolean =
        a is TdApi.ChatListMain && b is TdApi.ChatListMain

    private fun onAuthState(s: TdApi.AuthorizationState) {
        authHint = ""
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                auth = TgAuth.STARTING
                val p = TdApi.SetTdlibParameters()
                p.useTestDc = false
                p.databaseDirectory = dbDir
                p.filesDirectory = filesDir
                p.databaseEncryptionKey = ByteArray(0)
                p.useFileDatabase = true
                p.useChatInfoDatabase = true
                p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = apiId
                p.apiHash = apiHash
                p.systemLanguageCode = "ru"
                p.deviceModel = deviceModel
                p.systemVersion = "Android"
                p.applicationVersion = appVersion
                send(p)
                applyProxy()
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> auth = TgAuth.PHONE
            is TdApi.AuthorizationStateWaitCode -> {
                auth = TgAuth.CODE
                val t = s.codeInfo?.type
                authHint = when (t) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> "Код пришёл в приложение Telegram (чат «Telegram»)"
                    is TdApi.AuthenticationCodeTypeSms -> "Код пришёл по SMS"
                    else -> "Код отправлен на твой номер"
                }
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                auth = TgAuth.PASSWORD
                val h = s.passwordHint
                authHint = if (h.isNullOrEmpty()) "Введи пароль двухэтапной проверки" else "Пароль (подсказка: $h)"
            }
            is TdApi.AuthorizationStateReady -> {
                auth = TgAuth.READY
                authError = ""
                if (!chatsRequested) {
                    chatsRequested = true
                    send(TdApi.GetMe()) { r -> if (r is TdApi.User) myId = r.id }
                    send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
                }
            }
            is TdApi.AuthorizationStateClosed -> auth = TgAuth.CLOSED
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                auth = TgAuth.OTHER
                authHint = "Нужно подтвердить вход с другого устройства. Этот способ здесь не поддерживается."
            }
            else -> {
                auth = TgAuth.OTHER
                authHint = "Этот способ входа здесь не поддерживается (нужна регистрация или e-mail). Войди в Telegram на телефоне обычным способом и попробуй снова."
            }
        }
        listener?.onAuth()
    }

    // ---------------------------------------------------------------- вход

    fun sendPhone(phone: String) {
        authError = ""
        send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    }

    fun sendCode(code: String) {
        authError = ""
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    fun sendPassword(pw: String) {
        authError = ""
        send(TdApi.CheckAuthenticationPassword(pw))
    }

    fun logOut() {
        chatsRequested = false
        chats.clear()
        send(TdApi.LogOut())
    }

    // ---------------------------------------------------------------- прокси

    /**
     * Принимает ссылку Telegram-прокси: tg://proxy?server=..&port=..&secret=.. (или https://t.me/proxy?...),
     * либо tg://socks?server=..&port=..&user=..&pass=.. Пустая строка = без прокси.
     */
    private fun applyProxy() {
        if (proxyApplied) return
        proxyApplied = true
        val link = pendingProxy.trim()
        if (link.isEmpty()) {
            send(TdApi.DisableProxy())
            return
        }
        val q = link.substringAfter('?', "")
        val map = HashMap<String, String>()
        for (part in q.split('&')) {
            val i = part.indexOf('=')
            if (i > 0) map[part.substring(0, i)] = java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }
        val server = map["server"] ?: return
        val port = map["port"]?.toIntOrNull() ?: return
        val type: TdApi.ProxyType = if (link.contains("socks")) {
            TdApi.ProxyTypeSocks5(map["user"] ?: "", map["pass"] ?: "")
        } else {
            TdApi.ProxyTypeMtproto(map["secret"] ?: return)
        }
        send(TdApi.AddProxy(TdApi.Proxy(server, port, type), true, "minichat"))
    }

    // ---------------------------------------------------------------- чаты

    /** Чаты основного списка, свежие сверху. */
    fun chatList(): List<TdApi.Chat> {
        val out = ArrayList<Pair<Long, TdApi.Chat>>()
        for (c in chats.values) {
            val pos = c.positions ?: continue
            for (p in pos) {
                if (p.list is TdApi.ChatListMain && p.order != 0L) {
                    out.add(p.order to c)
                    break
                }
            }
        }
        out.sortByDescending { it.first }
        return out.map { it.second }
    }

    fun chat(id: Long): TdApi.Chat? = chats[id]

    fun isMuted(c: TdApi.Chat): Boolean = (c.notificationSettings?.muteFor ?: 0) > 0

    fun open(chatId: Long) {
        send(TdApi.OpenChat(chatId))
    }

    fun close(chatId: Long) {
        send(TdApi.CloseChat(chatId))
    }

    /** История чата, от старых к новым. [fromId]=0 — самые свежие. */
    fun history(chatId: Long, fromId: Long, limit: Int, cb: (List<TdApi.Message>) -> Unit) {
        send(TdApi.GetChatHistory(chatId, fromId, 0, limit, false)) { r ->
            val ms = (r as? TdApi.Messages)?.messages ?: emptyArray()
            cb(ms.filterNotNull().sortedBy { it.id })
        }
    }

    fun markRead(chatId: Long, ids: LongArray) {
        if (ids.isEmpty()) return
        send(TdApi.ViewMessages(chatId, ids, TdApi.MessageSourceChatHistory(), true))
    }

    fun sendText(chatId: Long, text: String, replyTo: Long) {
        val ft = TdApi.FormattedText()
        ft.text = text
        ft.entities = arrayOf()
        val content = TdApi.InputMessageText()
        content.text = ft
        content.clearDraft = true
        val f = TdApi.SendMessage()
        f.chatId = chatId
        f.inputMessageContent = content
        if (replyTo != 0L) {
            val r = TdApi.InputMessageReplyToMessage()
            r.messageId = replyTo
            f.replyTo = r
        }
        send(f)
    }

    // ---------------------------------------------------------------- имена

    private fun fullName(u: TdApi.User): String =
        (u.firstName + " " + u.lastName).trim().ifEmpty { "Пользователь" }

    fun senderName(m: TdApi.Message): String {
        val s = m.senderId
        if (s is TdApi.MessageSenderUser) {
            val n = users[s.userId]
            if (n == null) {
                send(TdApi.GetUser(s.userId)) { r -> if (r is TdApi.User) users[r.id] = fullName(r) }
            }
            return n ?: "…"
        }
        if (s is TdApi.MessageSenderChat) return chats[s.chatId]?.title ?: "Канал"
        return ""
    }

    // ---------------------------------------------------------------- файлы

    /** Скачивает файл (если надо) и вызывает [cb] с путём. Вызов может прийти из любого потока. */
    fun fetch(file: TdApi.File, cb: (String) -> Unit) {
        val local = file.local
        if (local != null && local.isDownloadingCompleted && local.path.isNotEmpty()) {
            cb(local.path)
            return
        }
        val list = waiters.getOrPut(file.id) { java.util.Collections.synchronizedList(ArrayList()) }
        list.add { f -> cb(f.local.path) }
        send(TdApi.DownloadFile(file.id, 16, 0, 0, false)) { r ->
            if (r is TdApi.File) onFile(r)
        }
    }

    private fun onFile(f: TdApi.File) {
        val l = f.local ?: return
        if (!l.isDownloadingCompleted || l.path.isEmpty()) return
        val list = waiters.remove(f.id) ?: return
        val copy = synchronized(list) { ArrayList(list) }
        for (w in copy) w(f)
    }

    // ---------------------------------------------------------------- описание сообщений

    companion object {
        /** Короткое описание содержимого для списков и «ответа на…». */
        fun describe(c: TdApi.MessageContent?): String = when (c) {
            null -> ""
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> "📷 Фото" + cap(c.caption)
            is TdApi.MessageVideo -> "🎬 Видео" + cap(c.caption)
            is TdApi.MessageAnimation -> "GIF" + cap(c.caption)
            is TdApi.MessageSticker -> c.sticker.emoji + " Стикер"
            is TdApi.MessageVoiceNote -> "🎤 Голосовое (${c.voiceNote.duration} с)"
            is TdApi.MessageVideoNote -> "⏺ Видеосообщение"
            is TdApi.MessageDocument -> "📎 " + c.document.fileName + cap(c.caption)
            is TdApi.MessageAudio -> "🎵 " + c.audio.title.ifEmpty { c.audio.fileName }
            is TdApi.MessageAnimatedEmoji -> c.emoji
            is TdApi.MessagePoll -> "📊 Опрос"
            else -> "[вложение]"
        }

        /** Подпись к фото, видео и т.п. */
        fun caption(c: TdApi.MessageContent?): String = when (c) {
            is TdApi.MessagePhoto -> c.caption.text
            is TdApi.MessageVideo -> c.caption.text
            is TdApi.MessageAnimation -> c.caption.text
            is TdApi.MessageDocument -> c.caption.text
            else -> ""
        }

        /** Что показать картинкой в сообщении и что открыть при нажатии. */
        fun media(c: TdApi.MessageContent?): MediaInfo {
            when (c) {
                is TdApi.MessagePhoto -> {
                    val sizes = c.photo.sizes
                    val pick = sizes.firstOrNull { it.width >= 320 } ?: sizes.lastOrNull()
                    val big = sizes.maxByOrNull { it.width }
                    return MediaInfo(
                        MediaKind.IMAGE, pick?.photo, big?.photo,
                        pick?.width ?: 0, pick?.height ?: 0, c.photo.minithumbnail?.data,
                    )
                }
                is TdApi.MessageSticker -> {
                    val s = c.sticker
                    val file = if (s.format is TdApi.StickerFormatWebp) s.sticker else s.thumbnail?.file
                    return MediaInfo(MediaKind.STICKER, file, file, s.width, s.height, null)
                }
                is TdApi.MessageVideo -> {
                    val v = c.video
                    return MediaInfo(MediaKind.VIDEO, v.thumbnail?.file, v.video, v.width, v.height, v.minithumbnail?.data)
                }
                is TdApi.MessageAnimation -> {
                    val a = c.animation
                    return MediaInfo(MediaKind.ANIM, a.thumbnail?.file, a.animation, a.width, a.height, a.minithumbnail?.data)
                }
                is TdApi.MessageVideoNote -> {
                    val v = c.videoNote
                    return MediaInfo(MediaKind.VIDEO, v.thumbnail?.file, v.video, v.length, v.length, v.minithumbnail?.data)
                }
                else -> return MediaInfo(MediaKind.NONE, null, null, 0, 0, null)
            }
        }

        private fun cap(t: TdApi.FormattedText?): String =
            if (t == null || t.text.isEmpty()) "" else ": " + t.text
    }
}

enum class MediaKind { NONE, IMAGE, STICKER, VIDEO, ANIM }

/** [preview] — маленькая картинка в чате, [full] — что открыть по нажатию (фото покрупнее или видеофайл). */
class MediaInfo(
    val kind: MediaKind,
    val preview: TdApi.File?,
    val full: TdApi.File?,
    val w: Int,
    val h: Int,
    val mini: ByteArray?,
)

/** Один клиент Telegram на весь процесс: TDLib нельзя запускать дважды на одной базе. */
object TgHub {
    @Volatile var client: TgClient? = null

    @Synchronized
    fun get(dbDir: String, filesDir: String, apiId: Int, apiHash: String, proxy: String, device: String, version: String): TgClient {
        val c = client
        if (c != null) return c
        val n = TgClient(dbDir, filesDir, apiId, apiHash, device, version)
        client = n
        n.start(proxy)
        return n
    }
}
