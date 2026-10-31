package com.linuxdroid.core.runtime.terminal

/**
 * A styled text fragment within a rendered terminal line.
 */
data class StyledSpan(
    val text: String,
    val color: Long = 0xFFE0E0E0,
    val isBold: Boolean = false,
    val isUnderline: Boolean = false,
    val isInverse: Boolean = false,
    val backgroundColor: Long = 0x00000000,
    val isDim: Boolean = false,
    val isItalic: Boolean = false,
    val isStrikethrough: Boolean = false,
)

/**
 * A rendered terminal line containing styled text spans and soft-wrap metadata.
 */
data class TerminalLineData(
    val spans: List<StyledSpan>,
    val isWrapped: Boolean = false,
) {
    val rawText: String get() = spans.joinToString("") { it.text }

    companion object {
        fun simple(text: String, color: Long = 0xFFE0E0E0): TerminalLineData =
            TerminalLineData(listOf(StyledSpan(text, color)))
    }
}

/**
 * Represents a single character cell within the terminal grid matrix.
 */
data class TerminalChar(
    var char: Char = ' ',
    var fgColor: Long = 0xFFE0E0E0L,
    var bgColor: Long = 0x00000000L,
    var isBold: Boolean = false,
    var isUnderline: Boolean = false,
    var isInverse: Boolean = false,
    var isDim: Boolean = false,
    var isItalic: Boolean = false,
    var isStrikethrough: Boolean = false,
) {
    val effectiveFg: Long
        get() = if (isInverse) (if (bgColor != 0L) bgColor else 0xFF1E1E1EL) else fgColor

    val effectiveBg: Long
        get() = if (isInverse) fgColor else bgColor

    fun copy(): TerminalChar = TerminalChar(
        char = char,
        fgColor = fgColor,
        bgColor = bgColor,
        isBold = isBold,
        isUnderline = isUnderline,
        isInverse = isInverse,
        isDim = isDim,
        isItalic = isItalic,
        isStrikethrough = isStrikethrough,
    )
}

/**
 * Terminal cursor shape conforming to VT/DEC standards.
 */
enum class CursorStyle {
    BLOCK,
    UNDERLINE,
    BAR,
}

/**
 * Cursor positioning and display state.
 */
data class CursorState(
    val row: Int = 0,
    val col: Int = 0,
    val isVisible: Boolean = true,
    val style: CursorStyle = CursorStyle.BLOCK,
    val isBlinking: Boolean = true,
)
