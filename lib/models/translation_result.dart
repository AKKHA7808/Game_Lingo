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

  /// Filters out blank lines, non-Japanese noise lines, and duplicate lines while
  /// preserving reading order.
  static String cleanAndMergeJapaneseLines(List<String> rawLines) {
    final seen = <String>{};
    final result = <String>[];

    for (final raw in rawLines) {
      final normalized = raw
          .replaceAll(RegExp(r'[\r\n]+'), ' ')
          .replaceAll(RegExp(r'\s{2,}'), ' ')
          .trim();
      if (normalized.isNotEmpty &&
          containsJapaneseScript(normalized) &&
          seen.add(normalized)) {
        result.add(normalized);
      }
    }

    return result.join('\n');
  }
}


