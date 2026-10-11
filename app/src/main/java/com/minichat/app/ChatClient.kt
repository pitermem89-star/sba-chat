package com.minichat.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class ChatMsg(val key: String, val nick: String, val text: String, val ts: Long)

/**
 * Простой клиент чата поверх Firebase Realtime Database (через обычные HTTP-запросы).
 * Никаких библиотек и google-services.json не нужно — только ссылка на базу.
 *
 * Колбэки вызываются из фонового потока.
 */
class ChatClient(
    baseUrl: String,
    room: String,
    private val onMessages: (List<ChatMsg>) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    private val msgUrl = "$baseUrl/rooms/${URLEncoder.encode(room, "UTF-8")}/messages.json"

    @Volatile private var running = false
    @Volatile var pollMs = 2000L
    private val wakeQueue = LinkedBlockingQueue<Int>()
    private var thread: Thread? = null
    private var lastStatus: String? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        wake()
        thread?.interrupt()
    }

    /** Проверить новые сообщения прямо сейчас, не дожидаясь таймера. */
    fun wake() {
        wakeQueue.offer(1)
    }

    fun send(nick: String, text: String) {
        Thread {
            try {
                val c = URL(msgUrl).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = JSONObject()
                        .put("n", nick)
                        .put("t", text)
                        .put("ts", JSONObject().put(".sv", "timestamp"))
                        .toString()
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    if (code in 200..299) {
                        setStatus(null)
                        wake()
                    } else {
                        setStatus(errorFor(code))
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                setStatus("Не удалось отправить — нет связи")
            }
        }.start()
    }

    private fun loop() {
        var lastKey = ""
        while (running) {
            try {
                val url = if (lastKey.isEmpty()) {
                    "$msgUrl?orderBy=%22%24key%22&limitToLast=50"
                } else {
                    "$msgUrl?orderBy=%22%24key%22&startAt=%22" + URLEncoder.encode(lastKey, "UTF-8") + "%22"
                }
                val list = parse(httpGet(url))
                val fresh = if (lastKey.isEmpty()) list else list.filter { it.key > lastKey }
                setStatus(null)
                if (fresh.isNotEmpty()) {
                    lastKey = fresh.last().key
                    onMessages(fresh)
                } else if (lastKey.isEmpty()) {
                    // комната пока пустая — всё равно сообщаем, что история загружена
                    onMessages(emptyList())
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                setStatus(e.message ?: "Нет связи с сервером")
            }
            try {
                wakeQueue.poll(pollMs, TimeUnit.MILLISECONDS)
                wakeQueue.clear()
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun setStatus(s: String?) {
        if (s == lastStatus) return
        lastStatus = s
        onStatus(s)
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val code = c.responseCode
            if (code !in 200..299) throw IOException(errorFor(code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun errorFor(code: Int): String = when (code) {
        401, 403 -> "Нет доступа — проверь правила базы (Rules)"
        404 -> "Неверная ссылка на базу данных"
        else -> "Ошибка сервера ($code)"
    }

    private fun parse(body: String): List<ChatMsg> {
        val t = body.trim()
        if (t.isEmpty() || t == "null") return emptyList()
        val obj = JSONObject(t)
        val out = ArrayList<ChatMsg>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val m = obj.optJSONObject(key) ?: continue
            out.add(ChatMsg(key, m.optString("n", "?"), m.optString("t", ""), m.optLong("ts", 0L)))
        }
        out.sortBy { it.key }
        return out
    }
}
