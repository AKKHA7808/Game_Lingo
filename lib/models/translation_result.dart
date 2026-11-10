/// Represents the result of a screen capture + Japanese OCR + translation cycle.
class TranslationResult {
  final String originalText;
  final String translatedText;
  final String sourceLanguage;
  final String targetLanguage;
  final DateTime timestamp;
  final bool isSuccess;
  final String? errorMessage;

  const TranslationResult({
    required this.originalText,
    required this.translatedText,
    this.sourceLanguage = 'ja',
    required this.targetLanguage,
    required this.timestamp,
    this.isSuccess = true,
    this.errorMessage,
  });

  factory TranslationResult.fromMap(Map<dynamic, dynamic> map) {
    final rawTimestamp = map['timestamp'];
    DateTime parsedTime;
    if (rawTimestamp is int) {
      parsedTime = DateTime.fromMillisecondsSinceEpoch(rawTimestamp);
    } else if (rawTimestamp is String) {
      parsedTime = DateTime.tryParse(rawTimestamp) ?? DateTime.now();
    } else {
      parsedTime = DateTime.now();
    }

    return TranslationResult(
      originalText: (map['originalText'] as String?) ?? '',
      translatedText: (map['translatedText'] as String?) ?? '',
      sourceLanguage: (map['sourceLanguage'] as String?) ?? 'ja',
      targetLanguage: (map['targetLanguage'] as String?) ?? 'th',
      timestamp: parsedTime,
      isSuccess: (map['isSuccess'] as bool?) ?? true,
      errorMessage: map['errorMessage'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'originalText': originalText,
      'translatedText': translatedText,
      'sourceLanguage': sourceLanguage,
      'targetLanguage': targetLanguage,
      'timestamp': timestamp.millisecondsSinceEpoch,
      'isSuccess': isSuccess,
      'errorMessage': errorMessage,
    };
  }

  TranslationResult copyWith({
    String? originalText,
    String? translatedText,
    String? sourceLanguage,
    String? targetLanguage,
    DateTime? timestamp,
    bool? isSuccess,
    String? errorMessage,
  }) {
    return TranslationResult(
      originalText: originalText ?? this.originalText,
      translatedText: translatedText ?? this.translatedText,
      sourceLanguage: sourceLanguage ?? this.sourceLanguage,
      targetLanguage: targetLanguage ?? this.targetLanguage,
      timestamp: timestamp ?? this.timestamp,
      isSuccess: isSuccess ?? this.isSuccess,
      errorMessage: errorMessage ?? this.errorMessage,
    );
  }
}

/// Utility for validating, filtering, and deduplicating multi-line Japanese OCR text
/// (Hiragana, Katakana, and Kanji).
class JapaneseOcrTextProcessor {
  static final RegExp _japaneseScriptRegex = RegExp(
    r'[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF\uFF66-\uFF9F]',
  );

  /// Returns true if [text] contains at least one Hiragana, Katakana, or Kanji character.
  static bool containsJapaneseScript(String text) {
    return _japaneseScriptRegex.hasMatch(text);
  }

  /// Counts the total number of Japanese script characters in [text].
  static int countJapaneseCharacters(String text) {
    return _japaneseScriptRegex.allMatches(text).length;
  }

  /// Filters out blank lines, non-Japanese noise lines, HUD control labels, and duplicate lines
  /// while merging wrapped dialogue lines into natural sentences.
  static String cleanAndMergeJapaneseLines(List<String> rawLines) {
    final seen = <String>{};
    final cleaned = <String>[];

    const hudLabels = {
      'オート',
      'スキップ',
      'バックログ',
      'ログ',
      'メニュー',
      'ヒストリー',
      'AUTO',
      'SKIP',
      'LOG',
      'MENU',
    };

    for (final raw in rawLines) {
      var normalized = raw
          .replaceAll(RegExp(r'[\r\n]+'), ' ')
          .replaceAll('　', ' ')
          .replaceAll(RegExp(r'\s{2,}'), ' ')
          .trim();
      // Remove accidental intra-Japanese OCR spaces
      normalized = normalized.replaceAllMapped(
        RegExp(
          r'([\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF])\s+([\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF])',
        ),
        (m) => '${m[1]}${m[2]}',
      );
      if (normalized.isNotEmpty &&
          containsJapaneseScript(normalized) &&
          seen.add(normalized)) {
        cleaned.add(normalized);
      }
    }

    final nonHud = cleaned
        .where((line) => !hudLabels.contains(line.toUpperCase()))
        .toList();
    final effective = nonHud.isNotEmpty ? nonHud : cleaned;

    return mergeWrappedJapaneseDialogue(effective).join('\n');
  }

  /// Merges multi-line wrapped Japanese sentences (lines that do not end with terminal punctuation)
  /// so SOV grammar is preserved as a single complete sentence.
  static List<String> mergeWrappedJapaneseDialogue(List<String> lines) {
    if (lines.length <= 1) return lines;
    const terminators = {'。', '！', '!', '？', '?', '」', '』', '】', '…', '♪'};
    final result = <String>[];
    final buffer = StringBuffer();

    for (var i = 0; i < lines.length; i++) {
      final line = lines[i].trim();
      if (line.isEmpty) continue;

      if (line.startsWith('【') && line.endsWith('】')) {
        if (buffer.isNotEmpty) {
          result.add(buffer.toString());
          buffer.clear();
        }
        result.add(line);
        continue;
      }

      buffer.write(line);
      final lastChar = line.substring(line.length - 1);
      final isTerminated = terminators.contains(lastChar);
      if (isTerminated) {
        result.add(buffer.toString());
        buffer.clear();
      }
    }

    if (buffer.isNotEmpty) {
      result.add(buffer.toString());
    }
    return result;
  }

  /// Polishes raw Thai or English output into natural gaming terminology.
  static String postProcessGamerTranslation(
    String rawTranslated, {
    String targetLanguage = 'th',
  }) {
    var text = rawTranslated.trim();
    if (text.isEmpty) return text;
    if (targetLanguage == 'th') {
      text = text
          .replaceAll('คุกใต้ดิน', 'ดันเจี้ยน')
          .replaceAll('ความเสียหายวิกฤต', 'ดาเมจคริติคอล')
          .replaceAll('ผู้กล้าหาญ', 'ผู้กล้า')
          .replaceAll('ราชาปีศาจ', 'จอมมาร')
          .replaceAll('คะแนนประสบการณ์', 'ค่าประสบการณ์ (EXP)');
    }
    return text;
  }
}


