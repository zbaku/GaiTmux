package com.aishell.terminal

import kotlinx.serialization.Serializable

/**
 * Represents a token from the shell lexer
 */
@Serializable
data class LexToken(
    val tokenType: String,
    val value: String,
    val start: Int,
    val end: Int
)

/**
 * Represents a highlighted line with tokens
 */
@Serializable
data class HighlightedLine(
    val tokens: List<LexToken>
)

/**
 * JNI bridge to Rust syntax highlighter
 */
class HighlightNative {
    
    companion object {
        init {
            System.loadLibrary("aishell_terminal")
        }
    }

    /**
     * Tokenize and highlight a shell command line
     * @param input The shell command to highlight
     * @return JSON string of HighlightedLine
     */
    external fun tokenize(input: String): String

    /**
     * Highlight multiple lines
     * @param input Array of lines to highlight
     * @return JSON array of HighlightedLine
     */
    external fun tokenizeLines(input: Array<String>): String

    /**
     * Get token type color for drawing
     * @param tokenType The type of token
     * @return ARGB color value
     */
    fun getTokenColor(tokenType: String): Int {
        return when (tokenType) {
            "Command" -> 0xFF00E676.toInt()     // Green
            "Option" -> 0xFF64B5F6.toInt()       // Blue
            "Path" -> 0xFFFFD54F.toInt()        // Yellow
            "String" -> 0xFFFF8A65.toInt()      // Orange
            "Pipe" -> 0xFFE0E0E0.toInt()        // White
            "Variable" -> 0xFFCE93D8.toInt()    // Purple
            "Comment" -> 0xFF757575.toInt()      // Gray
            else -> 0xFFE0E0E0.toInt()          // Default white
        }
    }
}

/**
 * Kotlin-side completion result
 */
@Serializable
data class Completion(
    val text: String,
    val display: String,
    val description: String,
    val completionType: String
)

/**
 * JNI bridge to Rust completion engine
 */
class CompleteNative {
    
    companion object {
        init {
            System.loadLibrary("aishell_terminal")
        }
    }

    /**
     * Get completions for input at cursor position
     * @param input The current input
     * @param cursor Cursor position
     * @return JSON array of Completion
     */
    external fun complete(input: String, cursor: Int): String

    /**
     * Get command history for completion
     * @param input Partial command
     * @return JSON array of matching history entries
     */
    external fun completeHistory(input: String): String
}
