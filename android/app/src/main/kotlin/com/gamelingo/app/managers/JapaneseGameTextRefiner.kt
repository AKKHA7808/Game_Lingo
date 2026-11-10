package com.gamelingo.app.managers

/**
 * Specialized NLP pre-processor and post-processor for Japanese video game text.
 *
 * Addresses the main causes of unnatural translations in on-device game translators:
 * 1. **Intra-word OCR spacing**: Removes accidental spaces inserted by OCR between adjacent
 *    Japanese characters (`"魔 王 を 倒 せ"` -> `"魔王を倒せ"`).
 * 2. **HUD / Chrome button noise**: Filters standalone Visual Novel / RPG control buttons
 *    (`AUTO`, `SKIP`, `LOG`, `オート`, `スキップ`, `バックログ`) when real dialogue is present.
 * 3. **Wrapped dialogue line joining**: Reconstructs sentences that wrap across 2–4 lines in a
 *    game dialogue box so SOV Japanese grammar is translated as a complete sentence rather than
 *    disconnected fragments.
 * 4. **Colloquial & Role-Language (Yakuwarigo) normalization**: Expands spoken contractions
 *    (`〜ちゃう` -> `〜てしまう`, `〜なきゃ` -> `〜なければならない`, `〜でござる` -> `〜です`)
 *    for the NMT engine while keeping the original Japanese text intact for display.
 * 5. **Thai / English Gamer Glossary & Pivot-Translation Polish**: Replaces stiff literal
 *    translations (e.g., "คุกใต้ดิน" -> "ดันเจี้ยน", "ความเสียหายวิกฤต" -> "คริติคอล") with
 *    natural Thai/English gaming terminology.
 */
object JapaneseGameTextRefiner {

    /**
     * Regex matching spaces accidentally inserted between two Japanese characters
     * (Hiragana, Katakana, Kanji, or Japanese punctuation).
     */
    private val INTRA_JAPANESE_SPACE_REGEX = Regex(
        "(?<=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\u3400-\\u4DBF\\u3000-\\u303F！？」』】])" +
            "\\s+" +
            "(?=[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\u3400-\\u4DBF\\u3000-\\u303F「『【！？。、])"
    )

    /**
     * Common standalone UI chrome / playback control labels in Japanese RPGs and Visual Novels.
     */
    private val HUD_CHROME_LABELS = setOf(
        "オート", "スキップ", "バックログ", "ログ", "メニュー", "ヒストリー",
        "セーブ", "ロード", "コンフィグ", "システム", "既読スキップ",
        "AUTO", "SKIP", "LOG", "BACKLOG", "MENU", "HISTORY", "SAVE", "LOAD", "CONFIG", "PAUSE"
    )

    /**
     * Terminal characters that mark the end of a complete Japanese sentence or distinct UI item.
     */
    private val SENTENCE_TERMINATORS = charArrayOf(
        '。', '！', '!', '？', '?', '」', '』', '】', '…', '‥', '♪', '〜', '~'
    )

    /**
     * Spoken contractions and role-language (Yakuwarigo) patterns mapped to standard Japanese
     * so ML Kit's compact NMT model translates the intended meaning accurately.
     */
    private val COLLOQUIAL_NORMALIZATIONS: List<Pair<Regex, String>> = listOf(
        // Archaic / Samurai / Elder role language (Yakuwarigo)
        Regex("でござる(?=[。！？!?」』\\s]|$)") to "です",
        Regex("ござる(?=[。！？!?」』\\s]|$)") to "ある",
        Regex("でおじゃる(?=[。！？!?」』\\s]|$)") to "です",
        Regex("じゃった(?=[。！？!?」』\\s]|$)") to "だった",
        Regex("なのじゃ(?=[。！？!?」』\\s]|$)") to "なのです",
        Regex("じゃぞ(?=[。！？!?」』\\s]|$)") to "だぞ",
        Regex("じゃな(?=[。！？!?」』\\s]|$)") to "だな",

        // Common colloquial verb contractions in game dialogue
        Regex("ちゃった") to "てしまった",
        Regex("じゃった") to "でしまった",
        Regex("ちゃう") to "てしまう",
        Regex("ちまう") to "てしまう",
        Regex("ちまった") to "てしまった",
        Regex("なきゃ(?=[。！？!?」』\\s]|$)") to "なければならない",
        Regex("なくちゃ(?=[。！？!?」』\\s]|$)") to "なければならない",
        Regex("とく(?=[。！？!?」』よねぞな\\s]|$)") to "ておく",
        Regex("といた") to "ておいた",
        Regex("てる(?=[。！？!?」』のよねぞな\\s]|$)") to "ている",
        Regex("てた(?=[。！？!?」』のよねぞな\\s]|$)") to "ていた",
        Regex("っす(?=[。！？!?」』ねよな\\s]|$)") to "です",
        Regex("っしょ(?=[。！？!?」』\\s]|$)") to "でしょう",
        Regex("じゃん(?=[。！？!?」』\\s]|$)") to "ではないか",
        Regex("だぜ(?=[。！？!?」』\\s]|$)") to "だよ",
        Regex("だぞ(?=[。！？!?」』\\s]|$)") to "だよ",
        Regex("わよ(?=[。！？!?」』\\s]|$)") to "よ",
        Regex("かしら(?=[。！？!?」』\\s]|$)") to "かな"
    )

    /**
     * Pre-translation Japanese gaming glossary replacements to prevent literal misinterpretations
     * during the `ja -> en -> th` pivot translation.
     */
    private val PRE_TRANSLATION_GAME_TERMS: List<Pair<Regex, String>> = listOf(
        Regex("勇者") to "ผู้กล้า (Hero)",
        Regex("魔王") to "จอมมาร (Demon Lord)"
    )

    /**
     * Post-translation natural Thai gaming vocabulary & phrasing refinements.
     */
    private val THAI_NATURAL_REFINEMENTS: List<Pair<Regex, String>> = listOf(
        // Fix literal translations of common RPG terms
        Regex("คุกใต้ดิน") to "ดันเจี้ยน",
        Regex("เขาวงกตใต้ดิน") to "ดันเจี้ยน",
        Regex("ความเสียหายวิกฤต") to "ดาเมจคริติคอล",
        Regex("การโจมตีที่สำคัญ") to "การโจมตีคริติคอล",
        Regex("คะแนนประสบการณ์") to "ค่าประสบการณ์ (EXP)",
        Regex("ค่าประสบการณ์") to "EXP",
        Regex("จุดสุขภาพ|คะแนนสุขภาพ|พลังชีวิต") to "HP",
        Regex("คะแนนเวทมนตร์|พลังเวทมนตร์") to "MP",
        Regex("ผู้กล้าหาญ") to "ผู้กล้า",
        Regex("ราชาปีศาจ|จอมมารร้าย|ลอร์ดปีศาจ") to "จอมมาร",
        Regex("พรรคพวก|สมาชิกในพรรค") to "สมาชิกปาร์ตี้",
        Regex("เข้าร่วมพรรค") to "เข้าร่วมปาร์ตี้",
        // Clean up artifacts from parenthetical hints if present
        Regex("ผู้กล้า\\s*\\(Hero\\)", RegexOption.IGNORE_CASE) to "ผู้กล้า",
        Regex("จอมมาร\\s*\\(Demon Lord\\)", RegexOption.IGNORE_CASE) to "จอมมาร",
        // Naturalize stiff Thai pronouns/phrasing produced by English pivot
        Regex("คุณต้องการที่จะ") to "ต้องการจะ",
        Regex("มันเป็นไปไม่ได้ที่จะ") to "เป็นไปไม่ได้ที่จะ",
        Regex("โปรดระวังตัวด้วย") to "ระวังตัวด้วยนะ",
        Regex("\\s+([!！?？])") to "$1"
    )

    /**
     * Post-translation English refinements.
     */
    private val ENGLISH_NATURAL_REFINEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("ผู้กล้า\\s*\\(Hero\\)", RegexOption.IGNORE_CASE) to "Hero",
        Regex("จอมมาร\\s*\\(Demon Lord\\)", RegexOption.IGNORE_CASE) to "Demon Lord",
        Regex("\\s+([!?,.:;])") to "$1"
    )

    /**
     * Cleans raw OCR lines, strips intra-word OCR spaces, filters HUD button noise when real
     * dialogue exists, and merges wrapped dialogue lines into natural sentences.
     */
    fun cleanAndReconstructDialogue(rawLines: List<String>): List<String> {
        val cleanedLines = rawLines
            .map { normalizeOcrLine(it) }
            .filter { it.isNotBlank() }

        if (cleanedLines.isEmpty()) return emptyList()

        // Filter out standalone HUD chrome buttons ("AUTO", "SKIP", "ログ") ONLY if there are
        // other substantive lines on screen so we don't wipe out a single-button scan.
        val nonHudLines = cleanedLines.filterNot { isHudChromeLabel(it) }
        val effectiveLines = nonHudLines.ifEmpty { cleanedLines }

        return mergeWrappedJapaneseLines(effectiveLines)
    }

    /**
     * Normalizes a single OCR line by removing accidental spaces between Japanese characters
     * and standardizing full-width symbols.
     */
    fun normalizeOcrLine(raw: String): String {
        var text = raw
            .replace(Regex("[\\r\\n]+"), " ")
            .replace('　', ' ')
            .trim()

        // Remove accidental OCR spaces between Japanese characters repeatedly
        var previous: String
        do {
            previous = text
            text = INTRA_JAPANESE_SPACE_REGEX.replace(text, "")
        } while (text != previous)

        return text.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun isHudChromeLabel(line: String): Boolean {
        val compact = line.replace(Regex("[\\s\\[\\]【】<>()（）]"), "").uppercase()
        return compact in HUD_CHROME_LABELS
    }

    /**
     * Joins multi-line wrapped Japanese sentences inside a dialogue box into complete sentences
     * while keeping speaker headers and distinct menu items separate.
     */
    fun mergeWrappedJapaneseLines(lines: List<String>): List<String> {
        if (lines.size <= 1) return lines

        val result = ArrayList<String>()
        val currentBuilder = StringBuilder()
        var insideOpenQuote = false

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            // Check if this line is a standalone speaker name header (e.g., "【アリス】" or short name before "「")
            val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
            val isSpeakerHeader = isLikelySpeakerHeader(line, nextLine)

            if (isSpeakerHeader) {
                if (currentBuilder.isNotEmpty()) {
                    result.add(currentBuilder.toString().trim())
                    currentBuilder.setLength(0)
                }
                result.add(line)
                insideOpenQuote = false
                continue
            }

            if (currentBuilder.isEmpty()) {
                currentBuilder.append(line)
                insideOpenQuote = hasUnclosedQuote(currentBuilder)
            } else {
                // Join directly without space because Japanese sentences do not use spaces across line wraps.
                currentBuilder.append(line)
                insideOpenQuote = hasUnclosedQuote(currentBuilder)
            }

            val endsWithTerminator = endsWithSentenceTerminator(line)
            val nextStartsNewSpeakerOrQuote =
                nextLine.startsWith("「") ||
                    nextLine.startsWith("『") ||
                    nextLine.startsWith("【") ||
                    nextLine.startsWith("・") ||
                    nextLine.startsWith("◆") ||
                    nextLine.startsWith("▶")

            val isShortMenuItem = line.length <= 8 && !insideOpenQuote && !endsWithContinuationParticle(line)

            if ((endsWithTerminator && !insideOpenQuote) ||
                nextStartsNewSpeakerOrQuote ||
                isShortMenuItem
            ) {
                result.add(currentBuilder.toString().trim())
                currentBuilder.setLength(0)
                insideOpenQuote = false
            }
        }

        if (currentBuilder.isNotEmpty()) {
            result.add(currentBuilder.toString().trim())
        }

        return result
    }

    private fun isLikelySpeakerHeader(line: String, nextLine: String): Boolean {
        if (line.startsWith("【") && line.endsWith("】") && line.length <= 14) {
            return true
        }
        // Short character name (<= 8 chars, no punctuation) immediately followed by a quoted line "「..."
        val hasPunctuation = line.any { it in charArrayOf('。', '、', '！', '？', '!', '?', '「', '」') }
        return !hasPunctuation && line.length in 1..8 && (nextLine.startsWith("「") || nextLine.startsWith("『"))
    }

    private fun hasUnclosedQuote(text: CharSequence): Boolean {
        val openSingle = text.count { it == '「' }
        val closeSingle = text.count { it == '」' }
        val openDouble = text.count { it == '『' }
        val closeDouble = text.count { it == '』' }
        return openSingle > closeSingle || openDouble > closeDouble
    }

    private fun endsWithSentenceTerminator(line: String): Boolean {
        val lastChar = line.lastOrNull() ?: return false
        return lastChar in SENTENCE_TERMINATORS
    }

    private val CONTINUATION_SUFFIXES = listOf(
        "、", "は", "が", "を", "に", "へ", "で", "と", "から", "より", "の", "て", "し", "ば", "たら", "なら"
    )

    /**
     * Checks if a line ends with a Japanese particle or conjunctive form (`は`, `が`, `を`, `に`, `で`, `と`, `の`, `て`, `し`, `、`)
     * which strongly indicates the sentence continues onto the next line.
     */
    private fun endsWithContinuationParticle(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return CONTINUATION_SUFFIXES.any { trimmed.endsWith(it) }
    }

    /**
     * Prepares a Japanese sentence for ML Kit NMT translation by normalizing colloquial contractions
     * and role-language suffixes while preserving the core meaning.
     */
    fun prepareJapaneseForTranslation(sentence: String): String {
        var prepared = normalizeOcrLine(sentence)
        if (prepared.isEmpty()) return prepared

        // Strip decorative brackets around speaker names so ML Kit translates cleanly
        if (prepared.startsWith("【") && prepared.endsWith("】") && prepared.length > 2) {
            val innerName = prepared.substring(1, prepared.length - 1)
            return "[$innerName]"
        }

        for ((pattern, replacement) in COLLOQUIAL_NORMALIZATIONS) {
            prepared = pattern.replace(prepared, replacement)
        }

        for ((pattern, replacement) in PRE_TRANSLATION_GAME_TERMS) {
            prepared = pattern.replace(prepared, replacement)
        }

        return prepared
    }

    /**
     * Polishes the raw translated output from ML Kit to make Thai (`th`) or English (`en`)
     * read naturally in a gaming context.
     */
    fun postProcessTranslation(rawTranslated: String, targetLangCode: String): String {
        var polished = rawTranslated.trim()
        if (polished.isEmpty()) return polished

        val isThai = targetLangCode.lowercase().trim() != "en"
        val rules = if (isThai) THAI_NATURAL_REFINEMENTS else ENGLISH_NATURAL_REFINEMENTS

        for ((pattern, replacement) in rules) {
            polished = pattern.replace(polished, replacement)
        }

        return polished
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
