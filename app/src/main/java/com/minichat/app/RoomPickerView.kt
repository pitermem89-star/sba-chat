package com.minichat.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Выбор комнаты: список моих комнат + форма «войти или создать».
 * Открытая комната — только название. Приватная — название и пароль.
 * Используется и в настройках приложения, и в окне чата поверх игры.
 *
 * [bindKeyboard] — подключает к полю ввода показ клавиатуры (в окне поверх игры это нужно делать особым образом).
 * [onJoin] — вызывается, когда пользователь выбрал или создал комнату.
 */
class RoomPickerView(
    ctx: Context,
    private val prefs: Prefs,
    private val bindKeyboard: (EditText) -> Unit,
    private val onJoin: (Room) -> Unit,
) : LinearLayout(ctx) {

    private var isPrivate = false
    private val listBox = LinearLayout(ctx)
    private val chipOpen = TextView(ctx)
    private val chipPrivate = TextView(ctx)
    private val nameEdit = EditText(ctx)
    private val passEdit = EditText(ctx)
    private val modeHint = TextView(ctx)
    private val errorView = TextView(ctx)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(s: String) = TextView(context).apply {
        text = s
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Style.accent)
        setPadding(0, dp(4), 0, dp(4))
    }

    init {
        orientation = VERTICAL

        addView(label("Мои комнаты"))
        listBox.orientation = VERTICAL
        addView(listBox)

        addView(label("Войти или создать").apply { setPadding(0, dp(14), 0, dp(4)) })

        val chips = LinearLayout(context).apply { orientation = HORIZONTAL }
        chipOpen.setOnClickListener { isPrivate = false; applyMode() }
        chipPrivate.setOnClickListener { isPrivate = true; applyMode() }
        val chipLp = { LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } }
        chips.addView(chipOpen, chipLp())
        chips.addView(chipPrivate, chipLp())
        addView(chips)

        modeHint.textSize = 12f
        modeHint.setTextColor(Style.hintColor)
        modeHint.setPadding(0, dp(6), 0, dp(2))
        addView(modeHint)

        styleField(nameEdit, "Название комнаты", 30)
        nameEdit.imeOptions = EditorInfo.IME_ACTION_DONE
        nameEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (isPrivate) passEdit.requestFocus() else submit()
                true
            } else false
        }
        addView(nameEdit, fieldLp())

        styleField(passEdit, "Пароль", 40)
        passEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        passEdit.imeOptions = EditorInfo.IME_ACTION_DONE
        passEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        addView(passEdit, fieldLp())

        errorView.textSize = 12f
        errorView.setTextColor(Style.errorColor)
        errorView.setPadding(0, dp(4), 0, 0)
        errorView.visibility = GONE
        addView(errorView)

        val join = TextView(context).apply {
            text = "Войти / создать"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = round(Style.accent, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { submit() }
        }
        addView(join, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        bindKeyboard(nameEdit)
        bindKeyboard(passEdit)

        applyMode()
        refresh()
    }

    private fun fieldLp() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(6)
    }

    private fun styleField(et: EditText, hintText: String, maxLen: Int) {
        et.hint = hintText
        et.setHintTextColor(Style.hintColor)
        et.setTextColor(Style.textColor)
        et.textSize = Style.TEXT_SP
        et.background = round(Style.inputBg, 14)
        et.setPadding(dp(12), dp(8), dp(12), dp(8))
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
    }

    private fun styleChip(t: TextView, label: String, selected: Boolean) {
        t.text = label
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.setPadding(dp(8), dp(8), dp(8), dp(8))
        t.background = round(if (selected) Style.accent else Style.inputBg, 16)
    }

    private fun applyMode() {
        styleChip(chipOpen, "🌐 Открытая", !isPrivate)
        styleChip(chipPrivate, "🔒 Приватная", isPrivate)
        passEdit.visibility = if (isPrivate) VISIBLE else GONE
        modeHint.text = if (isPrivate) {
            "Нужны название и пароль. Переписка шифруется: прочитают только те, кто знает оба."
        } else {
            "Войдёт любой, кто знает название. Переписка не шифруется."
        }
        errorView.visibility = GONE
    }

    /** Перерисовать список комнат. */
    fun refresh() {
        listBox.removeAllViews()
        val rooms = prefs.rooms()
        if (rooms.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = "Пока нет комнат — создай первую ниже."
                textSize = 13f
                setTextColor(Style.hintColor)
                setPadding(0, dp(2), 0, dp(6))
            })
            return
        }
        val cur = prefs.currentRoomKey
        for (r in rooms) {
            val isCur = r.key == cur
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
                background = round(if (isCur) 0x554C8DFF else Style.inputBg, 14)
            }
            val nameView = TextView(context).apply {
                text = (if (r.isPrivate) "🔒 " else "🌐 ") + r.name + if (isCur) "   ✓" else ""
                textSize = Style.TEXT_SP
                setTextColor(Style.textColor)
                maxLines = 1
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    prefs.currentRoomKey = r.key
                    refresh()
                    onJoin(r)
                }
            }
            row.addView(nameView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!isCur) { // текущую комнату удалять нельзя
                val del = TextView(context).apply {
                    text = "✕"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Style.hintColor)
                    setOnClickListener {
                        prefs.removeRoom(r)
                        refresh()
                    }
                }
                row.addView(del, LayoutParams(dp(40), dp(36)))
            }
            listBox.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }
    }

    private fun showError(s: String) {
        errorView.text = s
        errorView.visibility = VISIBLE
    }

    private fun submit() {
        val name = nameEdit.text.toString().trim().take(30)
        val pass = passEdit.text.toString().trim()
        if (name.isEmpty()) {
            showError("Введи название комнаты")
            return
        }
        if (isPrivate && pass.isEmpty()) {
            showError("Для приватной комнаты нужен пароль")
            return
        }
        errorView.visibility = GONE
        val room = Room(name, if (isPrivate) pass else "")
        prefs.joinRoom(room)
        nameEdit.setText("")
        passEdit.setText("")
        refresh()
        onJoin(room)
    }
}
