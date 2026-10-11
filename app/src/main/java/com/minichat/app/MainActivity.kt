package com.minichat.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Экран настроек: ссылка на базу, ник, комната, прозрачность и кнопки запуска. */
class MainActivity : Activity() {

    companion object {
        private val FIREBASE_RULES = """{
  "rules": {
    "rooms": {
      "${'$'}room": {
        "messages": {
          ".read": true,
          ".write": true,
          "${'$'}id": {
            ".validate": "newData.hasChildren(['n','t','ts']) && newData.child('n').isString() && newData.child('n').val().length <= 20 && newData.child('t').isString() && newData.child('t').val().length <= 300"
          }
        }
      }
    }
  }
}"""
    }

    private lateinit var prefs: Prefs
    private lateinit var urlEdit: EditText
    private lateinit var nickEdit: EditText
    private lateinit var roomEdit: EditText
    private lateinit var opacityLabel: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var statusText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        col.addView(text("Мини-чат", 26f, bold = true))
        col.addView(text("Чат поверх других приложений и игр. Без фильтров слов.", 14f, color = 0xFFB0B0C4.toInt()))

        col.addView(section("1. База данных (бесплатный Firebase)"))
        col.addView(
            text(
                "Создай проект на console.firebase.google.com → Build → Realtime Database → Create. " +
                    "Скопируй ссылку вида https://…firebaseio.com и вставь сюда. " +
                    "Потом открой вкладку Rules, вставь правила (кнопка ниже копирует их) и нажми Publish.",
                13f, color = 0xFFB0B0C4.toInt(),
            )
        )
        urlEdit = field("https://твой-проект-default-rtdb.firebaseio.com", prefs.dbUrl, InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(urlEdit)
        col.addView(button("Скопировать правила для Firebase") { copyRules() })

        col.addView(section("2. Ник и комната"))
        nickEdit = field("Ник — обязательно (до 20 символов)", prefs.nick)
        col.addView(nickEdit)
        roomEdit = field("Комната (одинаковая у всех, кто общается)", prefs.room)
        col.addView(roomEdit)

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

        col.addView(section("4. Запуск"))
        statusText = text("", 13f, color = 0xFFB0B0C4.toInt())
        col.addView(statusText)
        col.addView(button("Разрешить показ поверх приложений") { openOverlaySettings() })
        col.addView(button("Запустить чат", primary = true) { startChat() })
        col.addView(button("Остановить чат") { stopChat() })

        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF14141C.toInt())
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    // ------------------------------------------------------------ действия

    private fun saveFields() {
        prefs.dbUrl = urlEdit.text.toString().trim()
        prefs.nick = nickEdit.text.toString().trim()
        prefs.room = roomEdit.text.toString().trim().ifEmpty { "general" }
        prefs.opacity = opacitySeek.progress + 30
    }

    private fun refreshStatus() {
        val perm = if (Settings.canDrawOverlays(this)) "разрешение есть ✅" else "разрешения нет ❌"
        val run = if (OverlayService.isRunning) "работает" else "выключен"
        statusText.text = "Поверх приложений: $perm\nЧат: $run"
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun copyRules() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Firebase rules", FIREBASE_RULES))
        Toast.makeText(this, "Правила скопированы — вставь их во вкладке Rules", Toast.LENGTH_LONG).show()
    }

    private fun startChat() {
        saveFields()
        if (prefs.cleanNick().isEmpty()) {
            Toast.makeText(this, "Впиши свой ник — без него в чат нельзя", Toast.LENGTH_LONG).show()
            nickEdit.requestFocus()
            return
        }
        if (prefs.cleanDbUrl().isEmpty()) {
            Toast.makeText(this, "Сначала вставь ссылку на базу данных", Toast.LENGTH_LONG).show()
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
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Чат запущен. Открывай игру — кружок 💬 будет поверх", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun stopChat() {
        stopService(Intent(this, OverlayService::class.java))
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    // ------------------------------------------------------------ мелкие помощники для интерфейса

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
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
