package com.minichat.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Экран настроек: ник, комнаты, прозрачность и кнопки запуска. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var nickEdit: EditText
    private lateinit var roomsBox: LinearLayout
    private lateinit var roomNameEdit: EditText
    private lateinit var roomPassEdit: EditText
    private lateinit var privToggle: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var statusText: TextView
    private lateinit var tgIdEdit: EditText
    private lateinit var tgHashEdit: EditText
    private lateinit var tgProxyEdit: EditText

    private var newPrivate = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        col.addView(text("Мини-чат", 26f, bold = true))
        col.addView(text("Чат поверх других приложений и игр. Без фильтров слов. Ничего настраивать не нужно: сервер уже встроен.", 14f, color = 0xFFB0B0C4.toInt()))

        // ---- 1. ник
        col.addView(section("1. Ник"))
        nickEdit = field("Твой ник — обязательно (до 20 символов)", prefs.nick)
        col.addView(nickEdit)
        col.addView(text("Ник можно сменить потом: кнопка ✎ в окне чата или здесь.", 13f, color = 0xFFB0B0C4.toInt()))

        // ---- 2. комнаты
        col.addView(section("2. Комнаты"))
        col.addView(
            text(
                "Открытая комната: нужно только название, войдёт любой, кто его знает. " +
                    "Закрытая: нужны название и пароль, без пароля туда не попасть.",
                13f, color = 0xFFB0B0C4.toInt(),
            )
        )
        roomsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(roomsBox)

        col.addView(text("Создать комнату или войти в чужую", 14f, bold = true).apply {
            setPadding(0, dp(16), 0, dp(2))
        })
        roomNameEdit = field("Название комнаты", "")
        col.addView(roomNameEdit)

        privToggle = text("", 15f).apply {
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                newPrivate = !newPrivate
                updatePrivateUi()
            }
        }
        col.addView(privToggle)

        roomPassEdit = field("Пароль комнаты", "", InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        col.addView(roomPassEdit)
        updatePrivateUi()

        col.addView(button("Создать / войти", primary = true) { createRoom() })

        // ---- 3. прозрачность
        col.addView(section("3. Прозрачность окна"))
        opacityLabel = text("", 14f)
        col.addView(opacityLabel)
        opacitySeek = SeekBar(this).apply {
            max = 70
            progress = prefs.opacity - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    opacityLabel.text = "Видимость: ${p + 30}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        opacityLabel.text = "Видимость: ${prefs.opacity}%"
        col.addView(opacitySeek)

        // ---- 4. размеры и форма
        col.addView(section("4. Размеры и форма"))
        col.addView(
            text(
                "Если чат сейчас запущен, окно перезапустится, чтобы применить новое значение.",
                13f, color = 0xFFB0B0C4.toInt(),
            )
        )
        addSlider(col, "Ширина окна чата", 200, 420, prefs.panelWidthDp) { prefs.panelWidthDp = it }
        addSlider(col, "Высота окна чата", 160, 560, prefs.panelHeightDp) { prefs.panelHeightDp = it }
        addSlider(col, "Размер пузыря", 36, 96, prefs.bubbleSizeDp) { prefs.bubbleSizeDp = it }
        addSlider(col, "Скругление пузыря (0 — квадрат, 100 — круг)", 0, 100, prefs.bubbleRoundPct, "%") {
            prefs.bubbleRoundPct = it
        }

        // ---- 5. Telegram
        col.addView(section("5. Telegram (по желанию)"))
        col.addView(
            text(
                "Нужны свои api_id и api_hash: зайди на my.telegram.org, войди по номеру, " +
                    "открой «API development tools», создай приложение и перепиши два значения сюда. " +
                    "Потом в окне чата нажми ✈ и войди по номеру телефона. " +
                    "Если Telegram не подключается (в России его замедляют), вставь ссылку на прокси.",
                13f, color = 0xFFB0B0C4.toInt(),
            )
        )
        tgIdEdit = field("api_id (число)", if (prefs.tgApiId > 0) prefs.tgApiId.toString() else "")
        tgIdEdit.inputType = InputType.TYPE_CLASS_NUMBER
        col.addView(tgIdEdit)
        tgHashEdit = field("api_hash", prefs.tgApiHash)
        col.addView(tgHashEdit)
        tgProxyEdit = field("Прокси: ссылка tg://proxy?… (необязательно)", prefs.tgProxy)
        col.addView(tgProxyEdit)
        col.addView(text("После смены этих полей нажми «Запустить чат» заново.", 13f, color = 0xFFB0B0C4.toInt()))

        // ---- 6. запуск
        col.addView(section("6. Запуск"))
        statusText = text("", 13f, color = 0xFFB0B0C4.toInt())
        col.addView(statusText)
        col.addView(button("Разрешить показ поверх приложений") { openOverlaySettings() })
        col.addView(button("Запустить чат", primary = true) { startChat() })
        col.addView(button("Остановить чат") { stopChat() })

        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF14141C.toInt())
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)

        renderRooms()
    }

    override fun onResume() {
        super.onResume()
        renderRooms()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    // ------------------------------------------------------------ комнаты

    private fun updatePrivateUi() {
        privToggle.text =
            if (newPrivate) "☑ Закрытая комната (нужен пароль)" else "☐ Закрытая комната (нужен пароль)"
        roomPassEdit.visibility = if (newPrivate) View.VISIBLE else View.GONE
    }

    /** Список сохранённых комнат. Нажатие на название — выбрать комнату. */
    private fun renderRooms() {
        roomsBox.removeAllViews()
        val current = prefs.currentRoom()
        val saved = prefs.rooms()

        if (saved.isEmpty()) {
            roomsBox.addView(text("Комнат пока нет — создай первую ниже.", 13f, color = 0xFFB0B0C4.toInt()))
            return
        }
        for (r in saved) {
            val isCur = current != null && current.sameAs(r)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameTv = TextView(this).apply {
                text = (if (isCur) "✓ " else "") + r.label()
                textSize = 16f
                setTextColor(if (isCur) 0xFF4C8DFF.toInt() else Color.WHITE)
                typeface = if (isCur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setBackgroundColor(0xFF22222E.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { selectRoom(r) }
            }
            row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val del = TextView(this).apply {
                text = "🗑"
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { deleteRoom(r) }
            }
            row.addView(del, LinearLayout.LayoutParams(dp(48), dp(44)))
            roomsBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
    }

    private fun selectRoom(r: Room) {
        prefs.setCurrent(r)
        renderRooms()
        if (OverlayService.isRunning) {
            launchService()
            Toast.makeText(this, "Переключено: ${r.label()}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Выбрано: ${r.label()}. Нажми «Запустить чат»", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRoom(r: Room) {
        val cur = prefs.currentRoom()
        val wasCurrent = cur != null && cur.sameAs(r)
        prefs.removeRoom(r)
        renderRooms()
        if (wasCurrent && OverlayService.isRunning) launchService() // окно чата предложит выбрать другую
    }

    private fun createRoom() {
        val name = roomNameEdit.text.toString().trim().take(30)
        val pass = roomPassEdit.text.toString().trim().take(40)
        if (name.isEmpty()) {
            Toast.makeText(this, "Введи название комнаты", Toast.LENGTH_LONG).show()
            roomNameEdit.requestFocus()
            return
        }
        if (newPrivate && pass.isEmpty()) {
            Toast.makeText(this, "Для закрытой комнаты нужен пароль", Toast.LENGTH_LONG).show()
            roomPassEdit.requestFocus()
            return
        }
        val room = Room(name, newPrivate, if (newPrivate) pass else "")
        roomNameEdit.setText("")
        roomPassEdit.setText("")
        selectRoom(room)
    }

    // ------------------------------------------------------------ действия

    private fun saveFields() {
        prefs.nick = nickEdit.text.toString().trim()
        prefs.opacity = opacitySeek.progress + 30
        prefs.tgApiId = tgIdEdit.text.toString().trim().toIntOrNull() ?: 0
        prefs.tgApiHash = tgHashEdit.text.toString()
        prefs.tgProxy = tgProxyEdit.text.toString()
    }

    private fun refreshStatus() {
        val perm = if (Settings.canDrawOverlays(this)) "разрешение есть ✅" else "разрешения нет ❌"
        val run = if (OverlayService.isRunning) "работает" else "выключен"
        val room = prefs.currentRoom()?.label() ?: "не выбрана"
        statusText.text = "Поверх приложений: $perm\nЧат: $run\nКомната: $room"
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    /** Перезапускает окно чата, чтобы оно подхватило новый ник, комнату и прозрачность. */
    private fun launchService() {
        saveFields()
        stopService(Intent(this, OverlayService::class.java))
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun startChat() {
        saveFields()
        if (prefs.cleanNick().isEmpty()) {
            Toast.makeText(this, "Впиши свой ник — без него в чат нельзя", Toast.LENGTH_LONG).show()
            nickEdit.requestFocus()
            return
        }
        if (prefs.currentRoom() == null) {
            Toast.makeText(this, "Выбери комнату или создай новую", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Нужно разрешить показ поверх приложений", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        launchService()
        Toast.makeText(this, "Чат запущен. Открывай игру — кружок 💬 будет поверх", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun stopChat() {
        stopService(Intent(this, OverlayService::class.java))
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    /** Ползунок со значением от [lo] до [hi]. Значение сохраняется, когда палец отпущен. */
    private fun addSlider(
        col: LinearLayout,
        title: String,
        lo: Int,
        hi: Int,
        value: Int,
        unit: String = "",
        onSave: (Int) -> Unit,
    ) {
        val label = text("$title: $value$unit", 14f).apply { setPadding(0, dp(12), 0, dp(2)) }
        col.addView(label)
        val seek = SeekBar(this).apply {
            max = hi - lo
            progress = (value - lo).coerceIn(0, hi - lo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title: ${p + lo}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onSave((s?.progress ?: 0) + lo)
                    if (OverlayService.isRunning) launchService()
                }
            })
        }
        col.addView(seek)
    }

    // ------------------------------------------------------------ мелкие помощники для интерфейса

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun section(s: String): TextView =
        text(s, 17f, bold = true, color = 0xFF4C8DFF.toInt()).apply { setPadding(0, dp(22), 0, dp(6)) }

    private fun field(hintText: String, value: String, extraType: Int = 0): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setHintTextColor(0xFF70708A.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or extraType
            setBackgroundColor(0xFF22222E.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(if (primary) 0xFF4C8DFF.toInt() else 0xFF2E2E40.toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
}
