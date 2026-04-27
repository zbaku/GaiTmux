package com.aishell.terminal.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

object HighlightRenderer {

    fun render(line: String, tokens: List<ShellToken>): AnnotatedString {
        if (tokens.isEmpty()) return buildAnnotatedString { append(line) }

        return buildAnnotatedString {
            var lastEnd = 0
            for (token in tokens) {
                if (token.start > lastEnd) {
                    append(line.substring(lastEnd, token.start))
                }

                withStyle(SpanStyle(color = getColor(token.type))) {
                    append(token.value)
                }

                lastEnd = token.end
            }

            if (lastEnd < line.length) {
                append(line.substring(lastEnd))
            }
        }
    }

    private fun getColor(type: TokenType): Color = when (type) {
        TokenType.COMMAND -> HighlightColors.Command
        TokenType.OPTION -> HighlightColors.Option
        TokenType.PATH -> HighlightColors.Path
        TokenType.STRING -> HighlightColors.String
        TokenType.PIPE -> HighlightColors.Pipe
        TokenType.VARIABLE -> HighlightColors.Variable
        TokenType.COMMENT -> HighlightColors.Comment
    }
}
