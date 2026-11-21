/// Connection and retry behaviour for a single printer operation.
///
/// The defaults are tuned for a **shared printer**: one Bluetooth printer used
/// by many devices. Each device connects, sends its job and releases the link
/// right away, so the printer is free for the next device. If another device is
/// printing, the call waits with exponential back-off and random jitter for up
/// to [maxWait] before failing with `ZebraPrinterErrorCode.printerBusy`.
///
/// ```dart
/// // Defaults (shared printer)
/// await printer.safePrint(mac, zpl);
///
/// // Wait longer during rush hours
/// await printer.safePrint(mac, zpl,
///     options: const PrintOptions(maxWait: Duration(seconds: 60)));
///
/// // Printer used by this device only: keep the link warm between prints
/// printer.defaultOptions = PrintOptions.dedicated;
/// ```
class PrintOptions {
  /// Total time to keep trying while the printer is busy or unreachable.
  final Duration maxWait;

  /// First delay between connection attempts. Doubles after every attempt.
  final Duration initialRetryDelay;

  /// Upper bound for a single delay between attempts.
  final Duration maxRetryDelay;

  /// Randomises each delay (50–100 %) so devices do not retry in lock-step.
  final bool jitter;

  /// Pause after the last byte is written and before the link is closed.
  ///
  /// Closing a Bluetooth link immediately after writing can cut off the end
  /// of the data on some devices. Tune this with real printers.
  final Duration drainDelay;

  /// Keep the link open this long after a job so the next job from this
  /// device can reuse it. `Duration.zero` (default) closes it immediately,
  /// which is what a shared printer needs.
  final Duration keepAlive;

  /// Read the printer status on the same link before sending a job, and fail
  /// fast with paperOut / headOpen / ribbonOut / printerPaused.
  final bool checkStatusBeforePrint;

  /// After sending, poll until the printer has finished the format. Keeps the
  /// link (and so the printer) longer; off by default.
  final bool waitForCompletion;

  /// Upper bound for [waitForCompletion].
  final Duration completionTimeout;

  /// Use an insecure (unbonded) Bluetooth link so devices do not have to be
  /// paired with the printer first.
  ///
  /// The printer must allow it (Link-OS setting
  /// `bluetooth.minimum_security_mode` = 1). The link is not encrypted.
  final bool insecure;

  const PrintOptions({
    this.maxWait = const Duration(seconds: 30),
    this.initialRetryDelay = const Duration(milliseconds: 500),
    this.maxRetryDelay = const Duration(seconds: 4),
    this.jitter = true,
    this.drainDelay = const Duration(milliseconds: 400),
    this.keepAlive = Duration.zero,
    this.checkStatusBeforePrint = true,
    this.waitForCompletion = false,
    this.completionTimeout = const Duration(seconds: 5),
    this.insecure = false,
  });

  /// Defaults for a printer shared by many devices.
  static const PrintOptions shared = PrintOptions();

  /// For a printer used by this device only: keeps the link for 2 seconds so
  /// rapid successive prints reuse it.
  static const PrintOptions dedicated = PrintOptions(
    keepAlive: Duration(seconds: 2),
  );

  /// Returns a copy with the given fields replaced.
  PrintOptions copyWith({
    Duration? maxWait,
    Duration? initialRetryDelay,
    Duration? maxRetryDelay,
    bool? jitter,
    Duration? drainDelay,
    Duration? keepAlive,
    bool? checkStatusBeforePrint,
    bool? waitForCompletion,
    Duration? completionTimeout,
    bool? insecure,
  }) {
    return PrintOptions(
      maxWait: maxWait ?? this.maxWait,
      initialRetryDelay: initialRetryDelay ?? this.initialRetryDelay,
      maxRetryDelay: maxRetryDelay ?? this.maxRetryDelay,
      jitter: jitter ?? this.jitter,
      drainDelay: drainDelay ?? this.drainDelay,
      keepAlive: keepAlive ?? this.keepAlive,
      checkStatusBeforePrint:
          checkStatusBeforePrint ?? this.checkStatusBeforePrint,
      waitForCompletion: waitForCompletion ?? this.waitForCompletion,
      completionTimeout: completionTimeout ?? this.completionTimeout,
      insecure: insecure ?? this.insecure,
    );
  }

  /// Serialises the options for the native side.
  Map<String, Object> toMap() => {
    'maxWaitMs': maxWait.inMilliseconds,
    'initialRetryDelayMs': initialRetryDelay.inMilliseconds,
    'maxRetryDelayMs': maxRetryDelay.inMilliseconds,
    'jitter': jitter,
    'drainDelayMs': drainDelay.inMilliseconds,
    'keepAliveMs': keepAlive.inMilliseconds,
    'checkStatusBeforePrint': checkStatusBeforePrint,
    'waitForCompletion': waitForCompletion,
    'completionTimeoutMs': completionTimeout.inMilliseconds,
    'insecure': insecure,
  };

  @override
  bool operator ==(Object other) =>
      other is PrintOptions &&
      other.maxWait == maxWait &&
      other.initialRetryDelay == initialRetryDelay &&
      other.maxRetryDelay == maxRetryDelay &&
      other.jitter == jitter &&
      other.drainDelay == drainDelay &&
      other.keepAlive == keepAlive &&
      other.checkStatusBeforePrint == checkStatusBeforePrint &&
      other.waitForCompletion == waitForCompletion &&
      other.completionTimeout == completionTimeout &&
      other.insecure == insecure;

  @override
  int get hashCode => Object.hash(
    maxWait,
    initialRetryDelay,
    maxRetryDelay,
    jitter,
    drainDelay,
    keepAlive,
    checkStatusBeforePrint,
    waitForCompletion,
    completionTimeout,
    insecure,
  );

  @override
  String toString() =>
      'PrintOptions(maxWait: $maxWait, keepAlive: $keepAlive, '
      'drainDelay: $drainDelay, insecure: $insecure)';
}
