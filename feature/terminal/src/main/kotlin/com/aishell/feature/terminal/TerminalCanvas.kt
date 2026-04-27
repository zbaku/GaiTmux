package com.aishell.feature.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.aishell.terminal.TerminalSession

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    onCursorMove: ((Int, Int) -> Unit)? = null
) {
    val buffer = session.buffer
    var cursorVisible by remember { mutableStateOf(true) }

    val charWidth = with(LocalDensity.current) { fontSize.sp.toPx() }
    val charHeight = charWidth * 1.2f

    val textStyle = remember(fontSize) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = Color(0xFFE6EDF3)
        )
    }

    val textPaint = remember(textStyle) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0xE6, 0xED, 0xF3)
            textSize = charWidth
            typeface = android.graphics.Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val col = (offset.x / charWidth).toInt()
                    val row = (offset.y / charHeight).toInt()
                    onCursorMove?.invoke(col, row)
                }
            }
    ) {
        // Draw background
        drawRect(color = Color(0xFF0D1117))

        // Draw cells — uses DirtyRegion for incremental rendering when available
        drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas

            val dirty = buffer.getDirtyRegion()
            val (rowRange, colRange) = if (dirty != null) {
                (dirty.startRow..dirty.endRow) to (dirty.startCol..dirty.endCol)
            } else {
                (0 until buffer.getRows()) to (0 until buffer.getCols())
            }

            for (row in rowRange) {
                for (col in colRange) {
                    val cell = buffer.getCell(row, col) ?: continue
                    if (cell.char != ' ' && cell.char != '\u0000') {
                        // Background color
                        if (cell.bgColor != 0xFF0D1117.toInt()) {
                            nativeCanvas.drawRect(
                                col * charWidth,
                                row * charHeight,
                                (col + 1) * charWidth,
                                (row + 1) * charHeight,
                                android.graphics.Paint().apply { color = cell.bgColor }
                            )
                        }

                        // Draw character
                        textPaint.color = cell.fgColor
                        if (cell.bold) {
                            textPaint.typeface = android.graphics.Typeface.create(
                                android.graphics.Typeface.MONOSPACE,
                                android.graphics.Typeface.BOLD
                            )
                        }
                        nativeCanvas.drawText(
                            cell.char.toString(),
                            col * charWidth,
                            (row + 1) * charHeight - charHeight * 0.2f,
                            textPaint
                        )
                        textPaint.typeface = android.graphics.Typeface.MONOSPACE
                    }
                }
            }

            // Draw cursor
            if (cursorVisible) {
                val cursorX = buffer.cursorCol * charWidth
                val cursorY = buffer.cursorRow * charHeight
                val cursorPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(0x00, 0xE6, 0x76)
                    alpha = 128
                }
                nativeCanvas.drawRect(
                    cursorX, cursorY,
                    cursorX + charWidth, cursorY + charHeight,
                    cursorPaint
                )
            }
        }

        // Clear dirty region after rendering
        buffer.clearDirty()
    }
}

data class TerminalColors(
    val command: Color = Color(0xFF00E676),
    val option: Color = Color(0xFF64B5F6),
    val path: Color = Color(0xFF26C6DA),
    val string: Color = Color(0xFFFFD54F),
    val variable: Color = Color(0xFFCE93D8),
    val comment: Color = Color(0xFF6E7681)
)