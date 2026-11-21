import 'zebra_printer_exception.dart';

/// State of a print job.
enum PrintState {
  /// Print job is idle
  idle,

  /// Connecting to printer
  connecting,

  /// Printer is busy (used by another device) or not reachable yet;
  /// the job waits and retries. See [PrintProgress.attempt].
  waiting,

  /// Sending data to printer
  sending,

  /// Printer is actively printing
  printing,

  /// Verifying print completion
  verifying,

  /// Printer is processing data
  processing,

  /// Print completed successfully
  completed,

  /// Print failed with error
  error;

  /// Creates PrintState from native string
  static PrintState fromString(String? value) {
    switch (value?.toLowerCase()) {
      case 'idle':
        return PrintState.idle;
      case 'connecting':
        return PrintState.connecting;
      case 'waiting':
        return PrintState.waiting;
      case 'sending':
        return PrintState.sending;
      case 'printing':
        return PrintState.printing;
      case 'verifying':
        return PrintState.verifying;
      case 'processing':
        return PrintState.processing;
      case 'completed':
        return PrintState.completed;
      case 'error':
        return PrintState.error;
      default:
        return PrintState.idle;
    }
  }
}

/// Progress of a print job, reported through `PrinterManager.onPrintProgress`.
class PrintProgress {
  /// Current print state
  final PrintState state;

  /// Progress percentage (0-100)
  final int percentage;

  /// Optional message describing current state
  final String? message;

  /// Error message if state is error
  final String? errorMessage;

  /// Typed error code if state is error
  final ZebraPrinterErrorCode? errorCode;

  /// Printer address
  final String? address;

  /// Identifier of the job this event belongs to (set by `PrinterManager`).
  final String? jobId;

  /// Timestamp of this progress update
  final DateTime timestamp;

  /// Is this a batch print progress?
  final bool isBatch;

  /// Current label number (for batch printing)
  final int? currentLabel;

  /// Total number of labels (for batch printing)
  final int? totalLabels;

  // ==================== WAITING FOR A SHARED PRINTER ====================

  /// Connection attempt number while [state] is [PrintState.waiting].
  final int? attempt;

  /// Time spent waiting for the printer so far.
  final Duration? waited;

  /// Delay before the next connection attempt.
  final Duration? nextRetryIn;

  /// How long this job held the printer's Bluetooth link (on completion).
  final Duration? holdTime;

  // ==================== PRINTER STATUS (from Zebra SDK) ====================

  /// Is paper out? (from SDK polling)
  final bool? isPaperOut;

  /// Is head open? (from SDK polling)
  final bool? isHeadOpen;

  /// Is head cold? (from SDK polling)
  final bool? isHeadCold;

  /// Is head too hot? (from SDK polling)
  final bool? isHeadTooHot;

  /// Is ribbon out? (from SDK polling)
  final bool? isRibbonOut;

  /// Labels remaining in current batch (from SDK)
  final int? labelsRemainingInBatch;

  PrintProgress({
    required this.state,
    required this.percentage,
    this.message,
    this.errorMessage,
    this.errorCode,
    this.address,
    this.jobId,
    this.isBatch = false,
    this.currentLabel,
    this.totalLabels,
    this.attempt,
    this.waited,
    this.nextRetryIn,
    this.holdTime,
    this.isPaperOut,
    this.isHeadOpen,
    this.isHeadCold,
    this.isHeadTooHot,
    this.isRibbonOut,
    this.labelsRemainingInBatch,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now();

  static Duration? _ms(Object? v) =>
      v is int ? Duration(milliseconds: v) : null;

  /// Creates PrintProgress from native callback data
  factory PrintProgress.fromNative(Map<dynamic, dynamic> data) {
    final rawCode = data['errorCode'] as String?;
    return PrintProgress(
      state: PrintState.fromString(data['state'] as String?),
      percentage: (data['percentage'] as int?) ?? 0,
      message: data['message'] as String?,
      errorMessage: data['errorMessage'] as String?,
      errorCode:
          rawCode == null ? null : ZebraPrinterErrorCode.fromValue(rawCode),
      address: data['address'] as String?,
      jobId: data['jobId'] as String?,
      isBatch: (data['isBatch'] as bool?) ?? false,
      currentLabel: data['currentLabel'] as int?,
      totalLabels: data['totalLabels'] as int?,
      attempt: data['attempt'] as int?,
      waited: _ms(data['waitedMs']),
      nextRetryIn: _ms(data['nextRetryMs']),
      holdTime: _ms(data['holdMs']),
      isPaperOut: data['isPaperOut'] as bool?,
      isHeadOpen: data['isHeadOpen'] as bool?,
      isHeadCold: data['isHeadCold'] as bool?,
      isHeadTooHot: data['isHeadTooHot'] as bool?,
      isRibbonOut: data['isRibbonOut'] as bool?,
      labelsRemainingInBatch: data['labelsRemainingInBatch'] as int?,
      timestamp:
          data['timestamp'] != null
              ? DateTime.fromMillisecondsSinceEpoch(data['timestamp'] as int)
              : DateTime.now(),
    );
  }

  /// Creates idle progress
  factory PrintProgress.idle() =>
      PrintProgress(state: PrintState.idle, percentage: 0, message: 'Ready');

  /// Creates connecting progress
  factory PrintProgress.connecting() => PrintProgress(
    state: PrintState.connecting,
    percentage: 10,
    message: 'Connecting to printer...',
  );

  /// Creates sending progress
  factory PrintProgress.sending({int percentage = 30}) => PrintProgress(
    state: PrintState.sending,
    percentage: percentage,
    message: 'Sending data to printer...',
  );

  /// Creates printing progress
  factory PrintProgress.printing({int percentage = 50}) => PrintProgress(
    state: PrintState.printing,
    percentage: percentage,
    message: 'Printing...',
  );

  /// Creates processing progress
  factory PrintProgress.processing({int percentage = 70}) => PrintProgress(
    state: PrintState.processing,
    percentage: percentage,
    message: 'Printer is processing...',
  );

  /// Creates completed progress
  factory PrintProgress.completed() => PrintProgress(
    state: PrintState.completed,
    percentage: 100,
    message: 'Print completed successfully',
  );

  /// Creates error progress
  factory PrintProgress.error(String error) => PrintProgress(
    state: PrintState.error,
    percentage: 0,
    errorMessage: error,
  );

  // ==================== HELPER GETTERS ====================

  /// Is print in progress?
  bool get isInProgress =>
      state == PrintState.connecting ||
      state == PrintState.waiting ||
      state == PrintState.sending ||
      state == PrintState.printing ||
      state == PrintState.verifying ||
      state == PrintState.processing;

  /// Is the job waiting for a busy printer?
  bool get isWaiting => state == PrintState.waiting;

  /// Is print completed?
  bool get isCompleted => state == PrintState.completed;

  /// Is there an error?
  bool get hasError => state == PrintState.error;

  /// Does printer have any warning/error status?
  bool get hasPrinterIssue =>
      (isPaperOut ?? false) ||
      (isHeadOpen ?? false) ||
      (isHeadCold ?? false) ||
      (isHeadTooHot ?? false) ||
      (isRibbonOut ?? false);

  /// Progress for batch printing (e.g., "3/10")
  String get batchProgressText {
    if (!isBatch || currentLabel == null || totalLabels == null) return '';
    return '$currentLabel/$totalLabels';
  }

  /// Returns printer status warning message (if any)
  String? get printerWarning {
    if (isPaperOut == true) return 'Paper out!';
    if (isHeadOpen == true) return 'Print head open!';
    if (isRibbonOut == true) return 'Ribbon out!';
    if (isHeadCold == true) return 'Print head warming up...';
    if (isHeadTooHot == true) return 'Print head cooling down...';
    return null;
  }

  @override
  String toString() {
    final buffer = StringBuffer(
      'PrintProgress{state: $state, percentage: $percentage%',
    );
    if (isBatch) buffer.write(', label: $batchProgressText');
    if (attempt != null) buffer.write(', attempt: $attempt');
    if (message != null && message!.isNotEmpty) {
      buffer.write(', message: $message');
    }
    if (errorCode != null) buffer.write(', errorCode: ${errorCode!.value}');
    if (hasPrinterIssue) buffer.write(', warning: $printerWarning');
    buffer.write('}');
    return buffer.toString();
  }
}

/// Exception that `safePrint` used to throw when a print was already running.
///
/// Since 0.5.0 jobs are queued instead, so this is no longer thrown.
@Deprecated('Jobs are queued since 0.5.0; this exception is no longer thrown.')
class PrintLockedException implements Exception {
  /// Error message
  final String message;

  /// Address of the printer that is currently printing
  final String currentPrintingAddress;

  /// Address of the printer that was requested
  final String requestedAddress;

  PrintLockedException(
    this.message, {
    required this.currentPrintingAddress,
    required this.requestedAddress,
  });

  @override
  String toString() =>
      'PrintLockedException: $message '
      '(current: $currentPrintingAddress, requested: $requestedAddress)';

  /// Returns user-friendly error message
  String get userMessage =>
      'Printer "$currentPrintingAddress" is currently printing. '
      'Please wait for the operation to complete.';
}
