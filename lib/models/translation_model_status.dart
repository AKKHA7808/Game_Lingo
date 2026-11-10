/// Download state of an on-device ML Kit translation model.
enum ModelDownloadState {
  notDownloaded,
  downloading,
  downloaded,
  failed;

  String get displayLabel {
    switch (this) {
      case ModelDownloadState.notDownloaded:
        return 'Not Downloaded';
      case ModelDownloadState.downloading:
        return 'Downloading';
      case ModelDownloadState.downloaded:
        return 'Downloaded';
      case ModelDownloadState.failed:
        return 'Failed';
    }
  }

  static ModelDownloadState fromString(String? raw) {
    switch (raw?.toLowerCase()) {
      case 'downloading':
        return ModelDownloadState.downloading;
      case 'downloaded':
        return ModelDownloadState.downloaded;
      case 'failed':
        return ModelDownloadState.failed;
      case 'not_downloaded':
      case 'notdownloaded':
      default:
        return ModelDownloadState.notDownloaded;
    }
  }
}

/// Supported target languages for Japanese (ja) translation.
enum TargetLanguage {
  thai(
    code: 'th',
    displayName: 'Thai (ภาษาไทย)',
    pairLabel: 'Japanese → Thai',
    modelTitle: 'Japanese → Thai Model',
  ),
  english(
    code: 'en',
    displayName: 'English (ภาษาอังกฤษ)',
    pairLabel: 'Japanese → English',
    modelTitle: 'Japanese → English Model',
  );

  final String code;
  final String displayName;
  final String pairLabel;
  final String modelTitle;

  const TargetLanguage({
    required this.code,
    required this.displayName,
    required this.pairLabel,
    required this.modelTitle,
  });

  static TargetLanguage fromCode(String? code) {
    switch (code?.toLowerCase()) {
      case 'en':
        return TargetLanguage.english;
      case 'th':
      default:
        return TargetLanguage.thai;
    }
  }
}

/// Represents the status of Japanese -> Thai and Japanese -> English models.
class TranslationModelStatus {
  final ModelDownloadState jaToThState;
  final ModelDownloadState jaToEnState;
  final String? errorMessage;

  const TranslationModelStatus({
    this.jaToThState = ModelDownloadState.notDownloaded,
    this.jaToEnState = ModelDownloadState.notDownloaded,
    this.errorMessage,
  });

  ModelDownloadState stateForLanguage(TargetLanguage language) {
    switch (language) {
      case TargetLanguage.thai:
        return jaToThState;
      case TargetLanguage.english:
        return jaToEnState;
    }
  }

  bool isLanguageModelReady(TargetLanguage language) =>
      stateForLanguage(language) == ModelDownloadState.downloaded;

  factory TranslationModelStatus.fromMap(Map<dynamic, dynamic> map) {
    return TranslationModelStatus(
      jaToThState: ModelDownloadState.fromString(map['th'] as String?),
      jaToEnState: ModelDownloadState.fromString(map['en'] as String?),
      errorMessage: map['errorMessage'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'th': jaToThState.name,
      'en': jaToEnState.name,
      'errorMessage': errorMessage,
    };
  }

  TranslationModelStatus copyWith({
    ModelDownloadState? jaToThState,
    ModelDownloadState? jaToEnState,
    String? errorMessage,
    bool clearError = false,
  }) {
    return TranslationModelStatus(
      jaToThState: jaToThState ?? this.jaToThState,
      jaToEnState: jaToEnState ?? this.jaToEnState,
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
    );
  }
}

