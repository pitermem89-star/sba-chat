package com.minichat.app

import android.content.Context

/** Сохранённые настройки приложения. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("minichat", Context.MODE_PRIVATE)

    var dbUrl: String
        get() = sp.getString("dbUrl", "") ?: ""
        set(v) { sp.edit().putString("dbUrl", v).apply() }

    var nick: String
        get() = sp.getString("nick", "") ?: ""
        set(v) { sp.edit().putString("nick", v).apply() }

    var room: String
        get() = sp.getString("room", "general") ?: "general"
        set(v) { sp.edit().putString("room", v).apply() }

    /** Прозрачность окна чата, 30..100 (%) */
    var opacity: Int
        get() = sp.getInt("opacity", 90)
        set(v) { sp.edit().putInt("opacity", v).apply() }

    var bubbleX: Int
        get() = sp.getInt("bubbleX", 24)
        set(v) { sp.edit().putInt("bubbleX", v).apply() }

    var bubbleY: Int
        get() = sp.getInt("bubbleY", 400)
        set(v) { sp.edit().putInt("bubbleY", v).apply() }

    var panelX: Int
        get() = sp.getInt("panelX", 24)
        set(v) { sp.edit().putInt("panelX", v).apply() }

    var panelY: Int
        get() = sp.getInt("panelY", 160)
        set(v) { sp.edit().putInt("panelY", v).apply() }

    /** Ссылка на базу без пробелов и лишнего слэша в конце. Пусто, если не задана. */
    fun cleanDbUrl(): String {
        var u = dbUrl.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u.trimEnd('/')
    }

    /** Название комнаты без символов, которые Firebase не принимает. */
    fun cleanRoom(): String {
        val r = room.trim().lowercase().replace(Regex("[.\$#\\[\\]/\\s]+"), "_")
        return if (r.isEmpty()) "general" else r.take(30)
    }

    /** Ник игрока (до 20 символов). Пусто, если ещё не задан. */
    fun cleanNick(): String = nick.trim().take(20)
}
