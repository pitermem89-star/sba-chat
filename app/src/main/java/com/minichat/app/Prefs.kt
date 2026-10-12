package com.minichat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Комната чата.
 *  - открытая: войти может любой, кто знает название;
 *  - закрытая: нужны название И пароль (без пароля комнату не найти).
 */
data class Room(val name: String, val isPrivate: Boolean, val password: String) {

    /**
     * Адрес комнаты на сервере. Это «отпечаток» названия (и пароля для закрытой комнаты),
     * поэтому у закрытой комнаты другой пароль = другая комната.
     */
    fun topic(): String {
        val raw = if (isPrivate) {
            "private:" + name.lowercase() + "\n" + password
        } else {
            "open:" + name.lowercase()
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("minichat:$raw").toByteArray(Charsets.UTF_8))
        return "mc" + digest.joinToString("") { "%02x".format(it) }.take(30)
    }

    fun sameAs(o: Room): Boolean =
        isPrivate == o.isPrivate && name.equals(o.name, ignoreCase = true) && password == o.password

    /** Как комната подписана в списках и в шапке чата. */
    fun label(): String = (if (isPrivate) "🔒 " else "# ") + name
}

/** Сохранённые настройки приложения. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("minichat", Context.MODE_PRIVATE)

    companion object {
        /** Единый сервер для всех пользователей (бесплатный, без регистрации). */
        const val SERVER = "https://ntfy.sh"
    }

    var nick: String
        get() = sp.getString("nick", "") ?: ""
        set(v) { sp.edit().putString("nick", v).apply() }

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

    /** Ширина окна чата в dp. */
    var panelWidthDp: Int
        get() = sp.getInt("panelWidthDp", Style.PANEL_W_DP)
        set(v) { sp.edit().putInt("panelWidthDp", v).apply() }

    /** Высота окна чата в dp. */
    var panelHeightDp: Int
        get() = sp.getInt("panelHeightDp", Style.PANEL_H_DP)
        set(v) { sp.edit().putInt("panelHeightDp", v).apply() }

    /** Размер свёрнутого пузыря в dp. */
    var bubbleSizeDp: Int
        get() = sp.getInt("bubbleSizeDp", Style.BUBBLE_DP)
        set(v) { sp.edit().putInt("bubbleSizeDp", v).apply() }

    /** Скругление пузыря: 0 = квадрат, 100 = полный круг. */
    var bubbleRoundPct: Int
        get() = sp.getInt("bubbleRoundPct", 100)
        set(v) { sp.edit().putInt("bubbleRoundPct", v).apply() }

    /** Ник игрока (до 20 символов). Пусто, если ещё не задан. */
    fun cleanNick(): String = nick.trim().take(20)

    /** Уникальный код этой установки приложения. Нужен, чтобы в игре отличать игроков с одинаковыми никами. */
    val playerId: String
        get() {
            var v = sp.getString("pid", null)
            if (v == null) {
                v = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                sp.edit().putString("pid", v).apply()
            }
            return v
        }

    // ------------------------------------------------------------ комнаты

    private fun toJson(r: Room): JSONObject =
        JSONObject().put("n", r.name).put("p", r.isPrivate).put("w", r.password)

    private fun fromJson(o: JSONObject): Room =
        Room(o.optString("n", ""), o.optBoolean("p", false), o.optString("w", ""))

    /** Все сохранённые комнаты (новые сверху). */
    fun rooms(): MutableList<Room> {
        val out = ArrayList<Room>()
        try {
            val arr = JSONArray(sp.getString("rooms", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val r = fromJson(arr.getJSONObject(i))
                if (r.name.isNotEmpty()) out.add(r)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveRooms(list: List<Room>) {
        val arr = JSONArray()
        for (r in list) arr.put(toJson(r))
        sp.edit().putString("rooms", arr.toString()).apply()
    }

    /** Комната, в которой сейчас находится игрок. Пусто, если ещё не выбрана. */
    fun currentRoom(): Room? {
        val s = sp.getString("current", null) ?: return null
        return try {
            val r = fromJson(JSONObject(s))
            if (r.name.isEmpty()) null else r
        } catch (_: Exception) {
            null
        }
    }

    /** Выбрать комнату (и запомнить её в списке, если её там не было). */
    fun setCurrent(r: Room) {
        val list = rooms()
        if (list.none { it.sameAs(r) }) list.add(0, r)
        saveRooms(list.take(30))
        sp.edit().putString("current", toJson(r).toString()).apply()
    }

    fun removeRoom(r: Room) {
        saveRooms(rooms().filterNot { it.sameAs(r) })
        val cur = currentRoom()
        if (cur != null && cur.sameAs(r)) sp.edit().remove("current").apply()
    }
}
