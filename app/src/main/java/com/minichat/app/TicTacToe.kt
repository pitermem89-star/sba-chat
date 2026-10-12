package com.minichat.app

/** Игрок: уникальный код установки приложения + ник. */
data class Player(val id: String, val nick: String)

/**
 * Одна партия в крестики-нолики.
 * Состояние меняется только событиями из чата. Все в комнате получают их в одном и том же порядке,
 * поэтому у всех на экране одна и та же картина, а сервер для игры не нужен.
 */
class TttGame(val id: String, val x: Player) {
    var o: Player? = null
        private set

    val board = CharArray(9) { ' ' }

    /** Чей сейчас ход: 'X' или 'O'. */
    var turn = 'X'
        private set

    /** 'X' или 'O' — победитель, 'D' — ничья, 'A' — игра прервана, ' ' — игра ещё идёт. */
    var result = ' '
        private set

    /** Приглашение отправлено, соперник ещё не принял. */
    val isOpen: Boolean get() = o == null && result == ' '

    /** Идёт игра: соперник принял, победителя ещё нет. */
    val isRunning: Boolean get() = o != null && result == ' '

    val isFinished: Boolean get() = result != ' '

    fun accept(p: Player): Boolean {
        if (!isOpen || p.id == x.id) return false
        o = p
        return true
    }

    fun currentPlayer(): Player? = if (turn == 'X') x else o

    /** Ход игрока [pid] в клетку [cell] (0..8). Возвращает true, если ход принят. */
    fun move(pid: String, cell: Int): Boolean {
        if (!isRunning || cell !in 0..8 || board[cell] != ' ') return false
        if (currentPlayer()?.id != pid) return false
        board[cell] = turn
        result = when {
            hasWon(turn) -> turn
            board.none { it == ' ' } -> 'D'
            else -> ' '
        }
        if (result == ' ') turn = if (turn == 'X') 'O' else 'X'
        return true
    }

    /** Прервать игру или отменить приглашение. Может только участник. */
    fun abort(pid: String): Boolean {
        if (isFinished) return false
        if (pid != x.id && pid != o?.id) return false
        result = 'A'
        return true
    }

    private fun hasWon(c: Char): Boolean = LINES.any { line -> line.all { board[it] == c } }

    /** Проигравший — тот, кто теперь ищет. null, если ничья, игра прервана или ещё идёт. */
    fun loser(): Player? = when (result) {
        'X' -> o
        'O' -> x
        else -> null
    }

    fun winner(): Player? = when (result) {
        'X' -> x
        'O' -> o
        else -> null
    }

    companion object {
        private val LINES = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )
    }
}

/** Что изменилось после очередного игрового события. */
enum class TttChange { NONE, INVITED, ACCEPTED, MOVED, FINISHED, ABORTED }

/** Текущая партия в комнате. Одновременно в комнате идёт не больше одной игры. */
class TttSession {
    var game: TttGame? = null
        private set

    fun reset() {
        game = null
    }

    /**
     * Применяет событие.
     *  inv — предложить игру, acc — принять, mv — ход (клетка [cell]), end — прервать/отменить.
     */
    fun apply(type: String, gid: String, p: Player, cell: Int): TttChange {
        val g = game
        when (type) {
            "inv" -> {
                if (g != null && g.id == gid) return TttChange.NONE
                if (g != null && g.isRunning) return TttChange.NONE // уже идёт другая партия
                game = TttGame(gid, p)
                return TttChange.INVITED
            }
            "acc" -> {
                if (g == null || g.id != gid) return TttChange.NONE
                return if (g.accept(p)) TttChange.ACCEPTED else TttChange.NONE
            }
            "mv" -> {
                if (g == null || g.id != gid) return TttChange.NONE
                if (!g.move(p.id, cell)) return TttChange.NONE
                return if (g.isFinished) TttChange.FINISHED else TttChange.MOVED
            }
            "end" -> {
                if (g == null || g.id != gid) return TttChange.NONE
                return if (g.abort(p.id)) TttChange.ABORTED else TttChange.NONE
            }
        }
        return TttChange.NONE
    }
}
