package com.minichat.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class ChatMsg(val key: String, val nick: String, val text: String, val ts: Long)

/**
 * Клиент чата поверх сервера ntfy (бесплатный публичный ntfy.sh).
 * Регистрация и ключи не нужны: комната — это просто «тема» (topic), в которую все пишут и из которой все читают.
 *
 * Ник передаётся в поле title, текст — в поле message.
 * Колбэки вызываются из фонового потока.
 */
class ChatClient(
    private val baseUrl: String,
    private val topic: String,
    private val onMessages: (List<ChatMsg>) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    private val subUrl = "$baseUrl/$topic/json"

    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
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
        wakeQueue.offer(1)
        val c = conn
        // Закрывать соединение нужно не из главного потока
        Thread {
            try { c?.disconnect() } catch (_: Exception) {}
        }.start()
        thread?.interrupt()
    }

    fun send(nick: String, text: String) {
        Thread {
            try {
                val c = URL(baseUrl).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 10000
                    c.readTimeout = 10000
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = JSONObject()
                        .put("topic", topic)
                        .put("title", nick)
                        .put("message", text)
                        .toString()
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    if (code in 200..299) setStatus(null) else setStatus(errorFor(code))
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                setStatus("Не удалось отправить — нет связи")
            }
        }.start()
    }

    private fun loop() {
        var lastId = ""
        var historyDone = false
        while (running) {
            try {
                if (!historyDone) {
                    // 1) история за последние часы (сервер хранит её недолго)
                    val body = httpGetText("$subUrl?poll=1&since=12h")
                    val list = parseLines(body).takeLast(50)
                    if (list.isNotEmpty()) lastId = list.last().key
                    historyDone = true
                    setStatus(null)
                    onMessages(list) // даже пустой список — чтобы окно знало, что история загружена
                }
                // 2) живое соединение: новые сообщения приходят сразу
                stream(lastId) { msg ->
                    lastId = msg.key
                    onMessages(listOf(msg))
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!running) return
                setStatus(e.message ?: "Нет связи с сервером")
            }
            if (!running) return
            try {
                wakeQueue.poll(3, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun stream(lastId: String, onMsg: (ChatMsg) -> Unit) {
        val since = if (lastId.isEmpty()) "latest" else lastId
        val c = URL("$subUrl?since=$since").openConnection() as HttpURLConnection
        conn = c
        try {
            c.connectTimeout = 10000
            c.readTimeout = 120000 // сервер шлёт «пульс» чаще, чем раз в 2 минуты
            val code = c.responseCode
            if (code !in 200..299) throw IOException(errorFor(code))
            setStatus(null)
            c.inputStream.bufferedReader().use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    parseLine(line)?.let(onMsg)
                }
            }
        } finally {
            conn = null
            c.disconnect()
        }
    }

    private fun setStatus(s: String?) {
        if (s == lastStatus) return
        lastStatus = s
        onStatus(s)
    }

    private fun httpGetText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            val code = c.responseCode
            if (code !in 200..299) throw IOException(errorFor(code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun errorFor(code: Int): String = when (code) {
        401, 403 -> "Сервер не пускает (нужен вход)"
        404 -> "Неверный адрес сервера"
        429 -> "Слишком часто — подожди немного"
        else -> "Ошибка сервера ($code)"
    }

    private fun parseLines(body: String): List<ChatMsg> =
        body.lineSequence().mapNotNull { parseLine(it) }.toList()

    private fun parseLine(line: String): ChatMsg? {
        val t = line.trim()
        if (t.isEmpty()) return null
        return try {
            val o = JSONObject(t)
            if (o.optString("event") != "message") return null
            val id = o.optString("id")
            if (id.isEmpty()) return null
            val nick = o.optString("title", "").ifEmpty { "?" }
            ChatMsg(id, nick, o.optString("message", ""), o.optLong("time", 0L))
        } catch (e: Exception) {
            null
        }
    }
}
