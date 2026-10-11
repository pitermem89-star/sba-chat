package com.minichat.app

/**
 * ВСЁ ВНЕШНЕЕ ОФОРМЛЕНИЕ ЧАТА ЗДЕСЬ.
 * Меняй цвета и размеры в этом файле — больше нигде копаться не нужно.
 *
 * Цвета записаны как 0xAARRGGBB:
 *   AA — прозрачность (FF = непрозрачный, 00 = невидимый)
 *   RR GG BB — красный, зелёный, синий
 */
object Style {
    // Цвета
    val panelBg = 0xE6121218.toInt()     // фон окна чата
    val headerBg = 0xFF1E1E2A.toInt()    // шапка
    val inputBg = 0xFF262636.toInt()     // поле ввода
    val textColor = 0xFFFFFFFF.toInt()   // текст сообщений
    val hintColor = 0xFF8A8AA0.toInt()   // подсказка в поле ввода
    val accent = 0xFF4C8DFF.toInt()      // кружок-иконка и кнопка отправки
    val badgeColor = 0xFFFF4D4D.toInt()  // красный счётчик новых сообщений
    val errorColor = 0xFFFF6B6B.toInt()  // текст ошибки связи

    // Цвета ников (как в Роблоксе — у каждого игрока свой цвет)
    val nickColors = intArrayOf(
        0xFFFD2943.toInt(), 0xFF01A2FF.toInt(), 0xFF02B857.toInt(), 0xFFA34EEF.toInt(),
        0xFFFF8A3D.toInt(), 0xFFFFD84D.toInt(), 0xFFFF6FB5.toInt(), 0xFFE8BA36.toInt(),
    )

    // Размеры (в dp — условных пикселях)
    const val PANEL_W_DP = 300       // ширина окна чата
    const val PANEL_H_DP = 280       // высота окна чата
    const val BUBBLE_DP = 56         // размер кружка-иконки
    const val CORNER_DP = 14         // скругление углов окна
    const val TEXT_SP = 14f          // размер текста сообщений

    // Надписи
    const val INPUT_HINT = "Сообщение…"
    const val BUBBLE_ICON = "💬"

    fun nickColor(nick: String): Int =
        nickColors[(nick.hashCode() and 0x7fffffff) % nickColors.size]
}
