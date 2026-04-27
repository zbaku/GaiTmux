package com.aishell.terminal

data class TerminalCell(
    val char: Char = ' ',
    val fgColor: Int = 0xFFE6EDF3.toInt(),
    val bgColor: Int = 0xFF0D1117.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

/**
 * Represents a rectangular region of the terminal that has changed
 * and needs to be re-rendered.
 */
data class DirtyRegion(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int
)

class TerminalBuffer(
    private var cols: Int = 80,
    private var rows: Int = 24
) {
    @PublishedApi
    internal var cells: Array<Array<TerminalCell>> = Array(rows) {
        Array(cols) { TerminalCell() }
    }

    var cursorCol = 0
        private set
    var cursorRow = 0
        private set

    private var savedCursorCol = 0
    private var savedCursorRow = 0

    // --- Dirty region tracking ---
    private var dirtyStartRow: Int = rows
    private var dirtyEndRow: Int = -1
    private var dirtyStartCol: Int = cols
    private var dirtyEndCol: Int = -1

    private fun markDirty(row: Int, col: Int) {
        dirtyStartRow = minOf(dirtyStartRow, row)
        dirtyEndRow = maxOf(dirtyEndRow, row)
        dirtyStartCol = minOf(dirtyStartCol, col)
        dirtyEndCol = maxOf(dirtyEndCol, col)
    }

    private fun markDirtyRow(row: Int) {
        dirtyStartRow = minOf(dirtyStartRow, row)
        dirtyEndRow = maxOf(dirtyEndRow, row)
        dirtyStartCol = 0
        dirtyEndCol = cols - 1
    }

    private fun markAllDirty() {
        dirtyStartRow = 0
        dirtyEndRow = rows - 1
        dirtyStartCol = 0
        dirtyEndCol = cols - 1
    }

    /** Returns the dirty region since last clear, or null if nothing is dirty. */
    fun getDirtyRegion(): DirtyRegion? {
        if (dirtyEndRow < 0) return null
        return DirtyRegion(
            startRow = dirtyStartRow.coerceIn(0, rows - 1),
            endRow = dirtyEndRow.coerceIn(0, rows - 1),
            startCol = dirtyStartCol.coerceIn(0, cols - 1),
            endCol = dirtyEndCol.coerceIn(0, cols - 1)
        )
    }

    /** Clears the dirty region after rendering. */
    fun clearDirty() {
        dirtyStartRow = rows
        dirtyEndRow = -1
        dirtyStartCol = cols
        dirtyEndCol = -1
    }

    fun process(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u001B' && i + 1 < text.length && text[i + 1] == '[' -> {
                    // ANSI escape sequence
                    val end = findAnsiEnd(text, i + 2)
                    if (end > i) {
                        val seq = text.substring(i + 2, end)
                        handleAnsiSequence(seq)
                        i = end + 1
                    } else {
                        i++
                    }
                }
                c == '\n' -> {
                    cursorRow++
                    cursorCol = 0
                    if (cursorRow >= rows) scrollUp()
                    i++
                }
                c == '\r' -> {
                    cursorCol = 0
                    i++
                }
                c == '\t' -> {
                    cursorCol = ((cursorCol / 8) + 1) * 8
                    if (cursorCol >= cols) cursorCol = cols - 1
                    i++
                }
                c.code in 0x20..0x7E || c.code > 0x7F -> {
                    if (cursorCol < cols && cursorRow < rows) {
                        cells[cursorRow][cursorCol] = TerminalCell(char = c)
                        markDirty(cursorRow, cursorCol)
                        cursorCol++
                        if (cursorCol >= cols) {
                            cursorCol = 0
                            cursorRow++
                            if (cursorRow >= rows) scrollUp()
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
    }

    private fun findAnsiEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c in 'A'..'Z' || c in 'a'..'z') return i
            i++
        }
        return -1
    }

    private fun handleAnsiSequence(seq: String) {
        val parts = seq.split(";")
        try {
            when {
                seq.endsWith("H") || seq.endsWith("f") -> {
                    // Cursor position
                    val row = parts.getOrNull(0)?.toIntOrNull()?.minus(1) ?: 0
                    val col = parts.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0
                    cursorRow = row.coerceIn(0, rows - 1)
                    cursorCol = col.coerceIn(0, cols - 1)
                }
                seq == "A" -> cursorRow = (cursorRow - 1).coerceAtLeast(0)
                seq == "B" -> cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
                seq == "C" -> cursorCol = (cursorCol + 1).coerceAtMost(cols - 1)
                seq == "D" -> cursorCol = (cursorCol - 1).coerceAtLeast(0)
                seq.endsWith("J") -> handleEraseDisplay(seq)
                seq.endsWith("K") -> handleEraseLine(seq)
                seq == "s" -> { savedCursorCol = cursorCol; savedCursorRow = cursorRow }
                seq == "u" -> { cursorCol = savedCursorCol; cursorRow = savedCursorRow }
                seq.endsWith("m") -> { /* Color/style handling — simplified */ }
            }
        } catch (_: Exception) { }
    }

    private fun handleEraseDisplay(seq: String) {
        val n = seq.dropLast(1).toIntOrNull() ?: 0
        when (n) {
            0 -> clearFromCursor()
            1 -> clearToCursor()
            2 -> clearAll()
        }
    }

    private fun handleEraseLine(seq: String) {
        val n = seq.dropLast(1).toIntOrNull() ?: 0
        when (n) {
            0 -> for (c in cursorCol until cols) { cells[cursorRow][c] = TerminalCell(); markDirty(cursorRow, c) }
            1 -> for (c in 0..cursorCol) { cells[cursorRow][c] = TerminalCell(); markDirty(cursorRow, c) }
            2 -> for (c in 0 until cols) { cells[cursorRow][c] = TerminalCell(); markDirty(cursorRow, c) }
        }
    }

    private fun clearFromCursor() {
        for (c in cursorCol until cols) { cells[cursorRow][c] = TerminalCell(); markDirty(cursorRow, c) }
        for (r in cursorRow + 1 until rows) {
            for (c in 0 until cols) { cells[r][c] = TerminalCell(); markDirty(r, c) }
        }
    }

    private fun clearToCursor() {
        for (r in 0 until cursorRow) {
            for (c in 0 until cols) { cells[r][c] = TerminalCell(); markDirty(r, c) }
        }
        for (c in 0..cursorCol) { cells[cursorRow][c] = TerminalCell(); markDirty(cursorRow, c) }
    }

    private fun clearAll() {
        for (r in 0 until rows) {
            for (c in 0 until cols) cells[r][c] = TerminalCell()
        }
        cursorRow = 0
        cursorCol = 0
        markAllDirty()
    }

    private fun scrollUp() {
        for (r in 0 until rows - 1) {
            cells[r] = cells[r + 1]
        }
        cells[rows - 1] = Array(cols) { TerminalCell() }
        cursorRow = rows - 1
        markAllDirty()
    }

    fun resize(newCols: Int, newRows: Int) {
        val newCells = Array(newRows) { row ->
            Array(newCols) { col ->
                if (row < rows && col < cols) cells[row][col] else TerminalCell()
            }
        }
        cells = newCells
        cols = newCols
        rows = newRows
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        markAllDirty()
    }

    fun forEachCell(action: (row: Int, col: Int, cell: TerminalCell) -> Unit) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                action(r, c, cells[r][c])
            }
        }
    }

    fun getCell(row: Int, col: Int): TerminalCell? {
        return if (row in 0 until rows && col in 0 until cols) cells[row][col] else null
    }

    fun getCols() = cols
    fun getRows() = rows
}