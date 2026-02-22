// ============================================================================
// ZEBRA PRINTER STATUS - Comprehensive status from Zebra Link-OS SDK
// ============================================================================

/// Comprehensive printer status from Zebra Link-OS SDK
/// Contains all status information available from PrinterStatus class in SDK
class ZebraPrinterStatus {
  // ==================== BASIC STATUS ====================

  /// Is printer ready to print? (SDK: isReadyToPrint)
  final bool isReadyToPrint;

  /// Is printer currently printing/processing a format? (SDK: isPartialFormatInProgress)
  final bool isPrinting;

  /// Is printer paused? (SDK: isPaused)
  final bool isPaused;

  // ==================== ERROR STATUS ====================

  /// Is paper out? (SDK: isPaperOut)
  final bool isPaperOut;

  /// Is print head open? (SDK: isHeadOpen)
  final bool isHeadOpen;

  /// Is print head cold? (SDK: isHeadCold)
  final bool isHeadCold;

  /// Is print head too hot? (SDK: isHeadTooHot)
  final bool isHeadTooHot;

  /// Is ribbon out? (SDK: isRibbonOut) - for thermal transfer printers
  final bool isRibbonOut;

  /// Is receive buffer full? (SDK: isReceiveBufferFull)
  final bool isReceiveBufferFull;

  // ==================== BATCH STATUS ====================

  /// Number of labels remaining in batch (SDK: labelsRemainingInBatch)
  final int labelsRemainingInBatch;

  /// Number of formats in receive buffer (SDK: numberOfFormatsInReceiveBuffer)
  final int numberOfFormatsInBuffer;

  // ==================== ADDITIONAL INFO ====================

  /// Label length in dots (SDK: labelLengthInDots)
  final int labelLengthInDots;

  /// Print mode (SDK: printMode)
  final String? printMode;

  /// Printer address (MAC or IP)
  final String? address;

  /// Timestamp when status was retrieved
  final DateTime timestamp;

  /// Error message (if any)
  final String? errorMessage;

  /// Constructor
  ZebraPrinterStatus({
    this.isReadyToPrint = false,
    this.isPrinting = false,
    this.isPaused = false,
    this.isPaperOut = false,
    this.isHeadOpen = false,
    this.isHeadCold = false,
    this.isHeadTooHot = false,
    this.isRibbonOut = false,
    this.isReceiveBufferFull = false,
    this.labelsRemainingInBatch = 0,
    this.numberOfFormatsInBuffer = 0,
    this.labelLengthInDots = 0,
    this.printMode,
    this.address,
    this.errorMessage,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now();

  /// Creates ZebraPrinterStatus from native callback data
  factory ZebraPrinterStatus.fromNative(Map<dynamic, dynamic> map) {
    return ZebraPrinterStatus(
      isReadyToPrint: map['isReadyToPrint'] as bool? ?? false,
      isPrinting: map['isPrinting'] as bool? ?? map['isPartialFormatInProgress'] as bool? ?? false,
      isPaused: map['isPaused'] as bool? ?? false,
      isPaperOut: map['isPaperOut'] as bool? ?? false,
      isHeadOpen: map['isHeadOpen'] as bool? ?? false,
      isHeadCold: map['isHeadCold'] as bool? ?? false,
      isHeadTooHot: map['isHeadTooHot'] as bool? ?? false,
      isRibbonOut: map['isRibbonOut'] as bool? ?? false,
      isReceiveBufferFull: map['isReceiveBufferFull'] as bool? ?? false,
      labelsRemainingInBatch: map['labelsRemainingInBatch'] as int? ?? 0,
      numberOfFormatsInBuffer: map['numberOfFormatsInBuffer'] as int? ?? 0,
      labelLengthInDots: map['labelLengthInDots'] as int? ?? 0,
      printMode: map['printMode'] as String?,
      address: map['address'] as String?,
      errorMessage: map['error'] as String? ?? map['errorMessage'] as String?,
      timestamp: map['timestamp'] != null ? DateTime.fromMillisecondsSinceEpoch(map['timestamp'] as int) : DateTime.now(),
    );
  }

  /// Creates an error status
  factory ZebraPrinterStatus.error(String message, {String? address}) {
    return ZebraPrinterStatus(errorMessage: message, address: address);
  }

  /// Creates status indicating printer is disconnected
  factory ZebraPrinterStatus.disconnected({String? address}) {
    return ZebraPrinterStatus(errorMessage: 'Yazıcı bağlı değil', address: address);
  }

  // ==================== HELPER GETTERS ====================

  /// Returns true if printer has any error condition
  bool get hasError {
    return isPaperOut || isHeadOpen || isHeadCold || isHeadTooHot || isRibbonOut || isReceiveBufferFull || errorMessage != null;
  }

  /// Returns true if printer can accept new print jobs
  bool get canPrint {
    return isReadyToPrint && !isPaused && !isPaperOut && !isHeadOpen && !isHeadCold && !isHeadTooHot && !isRibbonOut && !isReceiveBufferFull;
  }

  /// Returns true if printer is busy (printing or has jobs in queue)
  bool get isBusy {
    return isPrinting || numberOfFormatsInBuffer > 0 || labelsRemainingInBatch > 0;
  }

  /// Returns true if printer needs attention (warning state)
  bool get needsAttention {
    return isHeadCold || isHeadTooHot || isReceiveBufferFull;
  }

  /// Returns true if printer has critical error (cannot print)
  bool get hasCriticalError {
    return isPaperOut || isHeadOpen || isRibbonOut;
  }

  /// Returns head temperature state as string
  String get headTemperatureState {
    if (isHeadCold) return 'cold';
    if (isHeadTooHot) return 'tooHot';
    return 'normal';
  }

  /// Returns true if head temperature is problematic
  bool get hasHeadTemperatureIssue => isHeadCold || isHeadTooHot;

  /// Returns user-friendly status message with emojis
  String get statusMessage {
    if (errorMessage != null) return errorMessage!;
    if (isPaperOut) return '🔴 Kağıt bitti!';
    if (isHeadOpen) return '🔴 Yazıcı kafası açık!';
    if (isRibbonOut) return '🔴 Ribon bitti!';
    if (isHeadCold) return '🟡 Yazıcı kafası ısınıyor...';
    if (isHeadTooHot) return '🟡 Yazıcı kafası soğuyor...';
    if (isReceiveBufferFull) return '🟡 Yazıcı meşgul, sıra bekleniyor...';
    if (isPrinting) return '🔵 Yazdırılıyor...';
    if (isPaused) return '🟡 Yazıcı duraklatıldı';
    if (labelsRemainingInBatch > 0) return '🔵 $labelsRemainingInBatch etiket kaldı';
    if (isReadyToPrint) return '🟢 Yazıcı hazır';
    return '⚪ Durum bilinmiyor';
  }

  /// Returns user-friendly status message without emojis (for plain text UI)
  String get statusMessagePlain {
    if (errorMessage != null) return errorMessage!;
    if (isPaperOut) return 'Kağıt bitti!';
    if (isHeadOpen) return 'Yazıcı kafası açık!';
    if (isRibbonOut) return 'Ribon bitti!';
    if (isHeadCold) return 'Yazıcı kafası ısınıyor...';
    if (isHeadTooHot) return 'Yazıcı kafası soğuyor...';
    if (isReceiveBufferFull) return 'Yazıcı meşgul, sıra bekleniyor...';
    if (isPrinting) return 'Yazdırılıyor...';
    if (isPaused) return 'Yazıcı duraklatıldı';
    if (labelsRemainingInBatch > 0) return '$labelsRemainingInBatch etiket kaldı';
    if (isReadyToPrint) return 'Yazıcı hazır';
    return 'Durum bilinmiyor';
  }

  /// Returns short status code for logging
  String get statusCode {
    if (isPaperOut) return 'PAPER_OUT';
    if (isHeadOpen) return 'HEAD_OPEN';
    if (isRibbonOut) return 'RIBBON_OUT';
    if (isHeadCold) return 'HEAD_COLD';
    if (isHeadTooHot) return 'HEAD_HOT';
    if (isReceiveBufferFull) return 'BUFFER_FULL';
    if (isPrinting) return 'PRINTING';
    if (isPaused) return 'PAUSED';
    if (isReadyToPrint) return 'READY';
    return 'UNKNOWN';
  }

  /// Returns all current error messages as a list
  List<String> get errorMessages {
    final errors = <String>[];
    if (isPaperOut) errors.add('Kağıt bitti');
    if (isHeadOpen) errors.add('Yazıcı kafası açık');
    if (isRibbonOut) errors.add('Ribon bitti');
    if (isHeadCold) errors.add('Yazıcı kafası soğuk');
    if (isHeadTooHot) errors.add('Yazıcı kafası çok sıcak');
    if (isReceiveBufferFull) errors.add('Buffer dolu');
    if (errorMessage != null) errors.add(errorMessage!);
    return errors;
  }

  /// Converts to Map
  Map<String, dynamic> toMap() {
    return {
      'isReadyToPrint': isReadyToPrint,
      'isPrinting': isPrinting,
      'isPaused': isPaused,
      'isPaperOut': isPaperOut,
      'isHeadOpen': isHeadOpen,
      'isHeadCold': isHeadCold,
      'isHeadTooHot': isHeadTooHot,
      'isRibbonOut': isRibbonOut,
      'isReceiveBufferFull': isReceiveBufferFull,
      'labelsRemainingInBatch': labelsRemainingInBatch,
      'numberOfFormatsInBuffer': numberOfFormatsInBuffer,
      'labelLengthInDots': labelLengthInDots,
      'printMode': printMode,
      'address': address,
      'errorMessage': errorMessage,
      'timestamp': timestamp.millisecondsSinceEpoch,
      // Helper values
      'hasError': hasError,
      'canPrint': canPrint,
      'isBusy': isBusy,
      'statusMessage': statusMessage,
      'statusCode': statusCode,
    };
  }

  @override
  String toString() {
    return 'ZebraPrinterStatus{'
        'ready: $isReadyToPrint, '
        'printing: $isPrinting, '
        'paused: $isPaused, '
        'paperOut: $isPaperOut, '
        'headOpen: $isHeadOpen, '
        'headCold: $isHeadCold, '
        'headHot: $isHeadTooHot, '
        'ribbonOut: $isRibbonOut, '
        'bufferFull: $isReceiveBufferFull, '
        'remaining: $labelsRemainingInBatch, '
        'status: $statusCode'
        '}';
  }

  /// Returns a compact string for logging
  String toCompactString() {
    return '[$statusCode] ${canPrint ? "✓" : "✗"} ${isBusy ? "BUSY" : "IDLE"} ${labelsRemainingInBatch > 0 ? "($labelsRemainingInBatch left)" : ""}';
  }
}
