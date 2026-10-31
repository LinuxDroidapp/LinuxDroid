package com.linuxdroid.core.runtime.terminal

/**
 * Color scheme configuration for terminal rendering modeled after macOS Terminal presets.
 */
data class TerminalTheme(
    val name: String,
    val isDark: Boolean,
    val background: Long,
    val foreground: Long,
    val cursorColor: Long,
    val selectionBackground: Long,
    val ansiColors: LongArray,
    val brightAnsiColors: LongArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TerminalTheme
        return name == other.name && isDark == other.isDark && background == other.background
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + isDark.hashCode()
        result = 31 * result + background.hashCode()
        return result
    }

    companion object {
        // Standard ANSI Palette
        private val DEFAULT_ANSI = longArrayOf(
            0xFF1E1E1EL, // 0: Black
            0xFFE57373L, // 1: Red
            0xFF81C784L, // 2: Green
            0xFFFFD54FL, // 3: Yellow
            0xFF64B5F6L, // 4: Blue
            0xFFBA68C8L, // 5: Magenta
            0xFF4DD0E1L, // 6: Cyan
            0xFFE0E0E0L, // 7: White
        )

        private val DEFAULT_BRIGHT_ANSI = longArrayOf(
            0xFF757575L, // 8: Bright Black (Gray)
            0xFFFF8A80L, // 9: Bright Red
            0xFFA5D6A7L, // 10: Bright Green
            0xFFFFE082L, // 11: Bright Yellow
            0xFF90CAF9L, // 12: Bright Blue
            0xFFCE93D8L, // 13: Bright Magenta
            0xFF80DEEAL, // 14: Bright Cyan
            0xFFFFFFFFL, // 15: Bright White
        )

        /**
         * macOS Terminal Pro (Dark Charcoal) preset.
         */
        val MACOS_PRO = TerminalTheme(
            name = "macOS Pro",
            isDark = true,
            background = 0xFF181818L,
            foreground = 0xFFF1F5F9L,
            cursorColor = 0xFF22C55EL,
            selectionBackground = 0x553B82F6L,
            ansiColors = DEFAULT_ANSI,
            brightAnsiColors = DEFAULT_BRIGHT_ANSI,
        )

        /**
         * macOS Terminal Basic (Light) preset.
         */
        val MACOS_BASIC = TerminalTheme(
            name = "macOS Basic",
            isDark = false,
            background = 0xFFFFFFFFL,
            foreground = 0xFF0F172AL,
            cursorColor = 0xFF2563EBL,
            selectionBackground = 0x442563EBL,
            ansiColors = longArrayOf(
                0xFF0F172AL, // Black
                0xFFDC2626L, // Red
                0xFF16A34AL, // Green
                0xFFD97706L, // Yellow
                0xFF2563EBL, // Blue
                0xFF9333EAL, // Magenta
                0xFF0891B2L, // Cyan
                0xFF475569L, // White/Gray
            ),
            brightAnsiColors = longArrayOf(
                0xFF64748BL,
                0xFFEF4444L,
                0xFF22C55EL,
                0xFFF59E0BL,
                0xFF3B82F6L,
                0xFFA855F7L,
                0xFF06B6D4L,
                0xFF020617L,
            ),
        )

        /**
         * macOS Homebrew (Emerald CRT) preset.
         */
        val HOMEBREW = TerminalTheme(
            name = "Homebrew",
            isDark = true,
            background = 0xFF000000L,
            foreground = 0xFF22C55EL,
            cursorColor = 0xFF4ADE80L,
            selectionBackground = 0x6616A34AL,
            ansiColors = DEFAULT_ANSI,
            brightAnsiColors = DEFAULT_BRIGHT_ANSI,
        )

        /**
         * macOS Terminal Solarized Dark preset.
         */
        val SOLARIZED_DARK = TerminalTheme(
            name = "Solarized Dark",
            isDark = true,
            background = 0xFF002B36L,
            foreground = 0xFF839496L,
            cursorColor = 0xFF93A1A1L,
            selectionBackground = 0x66073642L,
            ansiColors = longArrayOf(
                0xFF073642L, // Black
                0xFFDC322FL, // Red
                0xFF859900L, // Green
                0xFFB58900L, // Yellow
                0xFF268BD2L, // Blue
                0xFFD33682L, // Magenta
                0xFF2AA198L, // Cyan
                0xFFEEE8D5L, // White
            ),
            brightAnsiColors = longArrayOf(
                0xFF002B36L, // Bright Black
                0xFFCB4B16L, // Bright Red
                0xFF586E75L, // Bright Green
                0xFF657B83L, // Bright Yellow
                0xFF839496L, // Bright Blue
                0xFF6C71C4L, // Bright Magenta
                0xFF93A1A1L, // Bright Cyan
                0xFFFDF6E3L, // Bright White
            ),
        )

        /**
         * Resolves color index (0..255) into ARGB hex color.
         */
        fun parse256Color(index: Int, theme: TerminalTheme = MACOS_PRO): Long {
            if (index in 0..7) return theme.ansiColors[index]
            if (index in 8..15) return theme.brightAnsiColors[index - 8]
            if (index in 16..231) {
                val n = index - 16
                val r = (n / 36) * 51
                val g = ((n / 6) % 6) * 51
                val b = (n % 6) * 51
                return 0xFF000000L or ((r and 0xFF).toLong() shl 16) or ((g and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
            }
            if (index in 232..255) {
                val gray = 8 + (index - 232) * 10
                return 0xFF000000L or ((gray and 0xFF).toLong() shl 16) or ((gray and 0xFF).toLong() shl 8) or (gray and 0xFF).toLong()
            }
            return theme.foreground
        }

        /**
         * Ensures optimal contrast for ANSI colors against light/dark background.
         */
        fun adaptColorForContrast(rawColor: Long, isDarkTheme: Boolean, textPrimary: Long): Long {
            return if (isDarkTheme) {
                when (rawColor) {
                    0xFF1E1E1EL, 0xFF000000L -> 0xFF94A3B8L
                    0xFFE0E0E0L -> 0xFFF1F5F9L
                    else -> rawColor
                }
            } else {
                when (rawColor) {
                    0xFFE0E0E0L, 0xFFFFFFFFL, 0xFFF1F5F9L -> textPrimary
                    0xFF757575L, 0xFF1E1E1EL, 0xFF000000L -> 0xFF0F172AL
                    0xFFE57373L, 0xFFFF8A80L -> 0xFFDC2626L // Crisp Red
                    0xFF81C784L, 0xFFA5D6A7L -> 0xFF15803DL // Crisp Green
                    0xFFFFD54FL, 0xFFFFE082L -> 0xFFB45309L // Crisp Amber
                    0xFF64B5F6L, 0xFF90CAF9L -> 0xFF1D4ED8L // Crisp Blue
                    0xFFBA68C8L, 0xFFCE93D8L -> 0xFF7E22CEL // Crisp Magenta
                    0xFF4DD0E1L, 0xFF80DEEAL -> 0xFF0E7490L // Crisp Cyan
                    else -> rawColor
                }
            }
        }
    }
}
