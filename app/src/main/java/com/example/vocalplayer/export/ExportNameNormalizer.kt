package com.example.vocalplayer.export

import java.util.Locale

/**
 * Normalizer and sanitizer for export file display names and filesystem paths.
 * Full support for Arabic, Hebrew, and other right-to-left scripts, Unicode characters,
 * accented glyphs, along with sanitized fallback for strict filesystem requirements.
 */
object ExportNameNormalizer {

    /**
     * Preserves Arabic letters (\u0600-\u06FF, \u0750-\u077F, \u08A0-\u08FF),
     * Arabic presentation forms (\uFB50-\uFDFF, \uFE70-\uFEFF), Latin alphabets,
     * numbers, dashes, spaces, and underscores.
     * Removes control codes and dangerous filesystem characters (`/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`).
     */
    fun sanitizeDisplayName(rawName: String, defaultName: String = "vocal_isolated"): String {
        if (rawName.isBlank()) return defaultName

        // Filter out illegal characters for display and file headers
        val cleaned = rawName
            .replace(Regex("""[/\\:*?"<>|\u0000-\u001F]"""), "_")
            .trim()

        return if (cleaned.isBlank()) defaultName else cleaned
    }

    /**
     * Produces a sanitized, safe filename for internal cache and storage files.
     * Preserves Arabic, alphanumeric, and safe punctuation, replacing spaces with underscores.
     */
    fun sanitizeFileName(rawName: String, defaultName: String = "vocal_isolated"): String {
        val display = sanitizeDisplayName(rawName, defaultName)
        // Keep Arabic, alphanumeric, underscores, dots, hyphens, and spaces replaced by '_'
        val sanitized = display
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[^\p{L}\p{N}._\-]"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_')

        return if (sanitized.isBlank()) defaultName else sanitized
    }

    /**
     * Determines whether the given text contains Arabic script characters.
     */
    fun isArabic(text: String): Boolean {
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_SUPPLEMENT ||
                block == Character.UnicodeBlock.ARABIC_EXTENDED_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Formats export title according to language/script context.
     * If the title contains Arabic, appends the Arabic localization for "(Instruments Muted)".
     */
    fun formatExportTitle(title: String, isArabicLanguage: Boolean = false): String {
        val sanitized = sanitizeDisplayName(title)
        val hasArabic = isArabic(sanitized) || isArabicLanguage
        return if (hasArabic) {
            "$sanitized (بدون موسيقى)"
        } else {
            "$sanitized (Instruments Muted)"
        }
    }

    /**
     * Formats MediaStore display filename with extension.
     */
    fun formatExportFileName(title: String, isArabicLanguage: Boolean = false): String {
        val sanitized = sanitizeFileName(title)
        val suffix = if (isArabic(title) || isArabicLanguage) "_مفرغ_صوتيا" else "_muted_instruments"
        return "${sanitized}${suffix}.mp4"
    }
}
