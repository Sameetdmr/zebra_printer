/// Yazıcı bağlantı durumu için enum
enum PrinterConnectionState {
  /// Yazıcı bağlı
  connected,

  /// Yazıcı bağlı değil
  disconnected;

  /// Boolean değerden PrinterConnectionState oluşturur
  static PrinterConnectionState fromBool(bool isConnected) {
    return isConnected ? PrinterConnectionState.connected : PrinterConnectionState.disconnected;
  }

  /// Enum değerini boolean'a dönüştürür
  bool toBool() {
    return this == PrinterConnectionState.connected;
  }
}

/// Kağıt durumu için enum
enum PaperState {
  /// Kağıt var
  present,

  /// Kağıt yok
  out;

  /// Boolean değerden PaperState oluşturur
  static PaperState fromBool(bool isPaperOut) {
    return isPaperOut ? PaperState.out : PaperState.present;
  }

  /// Enum değerini boolean'a dönüştürür
  bool isPaperOut() {
    return this == PaperState.out;
  }
}

/// Yazıcı kafası durumu için enum
enum HeadState {
  /// Yazıcı kafası kapalı
  closed,

  /// Yazıcı kafası açık
  open;

  /// Boolean değerden HeadState oluşturur
  static HeadState fromBool(bool isHeadOpen) {
    return isHeadOpen ? HeadState.open : HeadState.closed;
  }

  /// Enum değerini boolean'a dönüştürür
  bool isOpen() {
    return this == HeadState.open;
  }
}

/// Yazıcı duraklatma durumu için enum
enum PauseState {
  /// Yazıcı çalışıyor
  running,

  /// Yazıcı duraklatılmış
  paused;

  /// Boolean değerden PauseState oluşturur
  static PauseState fromBool(bool isPaused) {
    return isPaused ? PauseState.paused : PauseState.running;
  }

  /// Enum değerini boolean'a dönüştürür
  bool isPaused() {
    return this == PauseState.paused;
  }
}

class PrinterStatus {
  /// Yazıcının bağlantı durumu
  final PrinterConnectionState connectionState;

  /// Kağıt durumu
  final PaperState paperState;

  /// Yazıcı duraklatılma durumu
  final PauseState pauseState;

  /// Yazıcı kafası durumu
  final HeadState headState;

  /// Yazıcı kafasının sıcaklığı
  final String temperature;

  /// Hata mesajı (varsa)
  final String? errorMessage;

  /// PrinterStatus constructor
  PrinterStatus({bool isConnected = false, bool isPaperOut = false, bool isPaused = false, bool isHeadOpen = false, this.temperature = '0', this.errorMessage})
    : connectionState = PrinterConnectionState.fromBool(isConnected),
      paperState = PaperState.fromBool(isPaperOut),
      pauseState = PauseState.fromBool(isPaused),
      headState = HeadState.fromBool(isHeadOpen);

  /// Enum değerlerini doğrudan alan constructor
  PrinterStatus.withEnums({required this.connectionState, required this.paperState, required this.pauseState, required this.headState, this.temperature = '0', this.errorMessage});

  /// Map'ten PrinterStatus oluşturur
  factory PrinterStatus.fromMap(Map<dynamic, dynamic> map) {
    return PrinterStatus(
      isConnected: map['isConnected'] as bool? ?? false,
      isPaperOut: map['isPaperOut'] as bool? ?? false,
      isPaused: map['isPaused'] as bool? ?? false,
      isHeadOpen: map['isHeadOpen'] as bool? ?? false,
      temperature: map['temperature'] as String? ?? '0',
      errorMessage: map['error'] as String?,
    );
  }

  /// Map'e dönüştürür
  Map<String, dynamic> toMap() {
    return {'isConnected': connectionState.toBool(), 'isPaperOut': paperState.isPaperOut(), 'isPaused': pauseState.isPaused(), 'isHeadOpen': headState.isOpen(), 'temperature': temperature, 'error': errorMessage};
  }

  /// Geriye uyumluluk için getter'lar
  bool get isConnected => connectionState.toBool();
  bool get isPaperOut => paperState.isPaperOut();
  bool get isPaused => pauseState.isPaused();
  bool get isHeadOpen => headState.isOpen();

  /// String temsilini döndürür
  @override
  String toString() {
    return 'PrinterStatus{bağlantı: $connectionState, kağıt: $paperState, durum: $pauseState, kafa: $headState, sıcaklık: $temperature, hata: $errorMessage}';
  }
}
