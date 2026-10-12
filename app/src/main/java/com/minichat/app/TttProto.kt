package com.minichat.app

import org.json.JSONObject

/**
 * Игровые события едут через обычные сообщения комнаты.
 * Приложение узнаёт их по полю "ttt" и не показывает в чате как текст.
 */
object TttProto {
    class Event(val type: String, val gid: String, val player: Player, val cell: Int)

    fun encode(type: String, gid: String, pid: String, cell: Int = -1): String =
        JSONObject().put("ttt", type).put("g", gid).put("p", pid).put("c", cell).toString()

    /** Если [text] — игровое событие, вернёт его (ник берётся из заголовка сообщения), иначе null. */
    fun parse(text: String, nick: String): Event? {
        if (!text.startsWith("{") || !text.contains("\"ttt\"")) return null
        return try {
            val o = JSONObject(text)
            val type = o.optString("ttt", "")
            val gid = o.optString("g", "")
            val pid = o.optString("p", "")
            if (type.isEmpty() || gid.isEmpty() || pid.isEmpty()) {
                null
            } else {
                Event(type, gid, Player(pid, nick), o.optInt("c", -1))
            }
        } catch (e: Exception) {
            null
        }
    }
}
