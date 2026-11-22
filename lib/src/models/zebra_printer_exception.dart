import 'package:flutter/services.dart';

/// Stable error codes reported by the native side.
enum ZebraPrinterErrorCode {
  /// Another device holds the printer's Bluetooth link. Waited for
  /// `PrintOptions.maxWait` without getting a turn.
  ///
  /// Bluetooth cannot report "busy" directly: this is inferred from fast
  /// connection refusals, so treat it as a strong hint, not a certainty.
  printerBusy('PRINTER_BUSY'),

  /// Printer is off, out of range or its Bluetooth is not answering.
  printerUnreachable('PRINTER_UNREACHABLE'),

  /// This device is not paired with the printer (secure mode).
  notPaired('NOT_PAIRED'),

  /// Bluetooth permission (BLUETOOTH_CONNECT / BLUETOOTH_SCAN) missing.
  permissionDenied('PERMISSION_DENIED'),

  /// Bluetooth is turned off on this device.
  bluetoothOff('BLUETOOTH_OFF'),

  /// This device has no Bluetooth adapter.
  bluetoothUnavailable('BLUETOOTH_UNAVAILABLE'),

  /// The link dropped while sending or reading.
  connectionLost('CONNECTION_LOST'),

  /// Printer reports paper out.
  paperOut('PAPER_OUT'),

  /// Print head is open.
  headOpen('HEAD_OPEN'),

  /// Ribbon out (thermal transfer printers).
  ribbonOut('RIBBON_OUT'),

  /// Printer is paused.
  printerPaused('PRINTER_PAUSED'),

  /// Printer language could not be detected (e.g. line_print mode).
  languageUnknown('LANGUAGE_UNKNOWN'),

  /// Invalid input (empty data, malformed MAC address, ...).
  invalidArgument('INVALID_ARGUMENT'),

  /// Unsupported data format (font or image).
  unsupportedFormat('UNSUPPORTED_FORMAT'),

  /// The job was removed from the queue before it started.
  cancelled('CANCELLED'),

  /// Any other failure.
  operationFailed('OPERATION_FAILED'),

  /// Code not known to this version of the package.
  unknown('UNKNOWN');

  /// Code string used on the platform channel.
  final String value;

  const ZebraPrinterErrorCode(this.value);

  /// Maps a platform code to the enum (falls back to [unknown]).
  static ZebraPrinterErrorCode fromValue(String? value) {
    for (final code in values) {
      if (code.value == value) return code;
    }
    return unknown;
  }
}

/// Error thrown by printer operations.
///
/// ```dart
/// try {
///   await printer.safePrint(mac, zpl);
/// } on ZebraPrinterException catch (e) {
///   switch (e.code) {
///     case ZebraPrinterErrorCode.printerBusy:
///       showSnack('Printer is used by another device, try again');
///     case ZebraPrinterErrorCode.paperOut:
///       showSnack('Load paper');
///     default:
///       showSnack(e.message);
///   }
/// }
/// ```
class ZebraPrinterException implements Exception {
  /// Typed error code.
  final ZebraPrinterErrorCode code;

  /// Raw code from the platform (useful when [code] is [ZebraPrinterErrorCode.unknown]).
  final String rawCode;

  /// Human readable message (English).
  final String message;

  /// Extra details from the native side, e.g. `attempts`, `waitedMs`.
  final Map<String, dynamic> details;

  ZebraPrinterException(
    this.code,
    this.message, {
    String? rawCode,
    Map<String, dynamic>? details,
  }) : rawCode = rawCode ?? code.value,
       details = details ?? const {};

  /// Converts a [PlatformException] from the method channel.
  factory ZebraPrinterException.fromPlatformException(PlatformException e) {
    final rawDetails = e.details;
    return ZebraPrinterException(
      ZebraPrinterErrorCode.fromValue(e.code),
      e.message ?? e.code,
      rawCode: e.code,
      details:
          rawDetails is Map
              ? rawDetails.map((k, v) => MapEntry(k.toString(), v))
              : const {},
    );
  }

  /// Number of connection attempts made (if reported).
  int? get attempts => details['attempts'] as int?;

  /// Time spent waiting for the printer (if reported).
  Duration? get waited {
    final ms = details['waitedMs'];
    return ms is int ? Duration(milliseconds: ms) : null;
  }

  /// True for conditions that usually clear by themselves, so retrying later
  /// makes sense (busy, unreachable, connection lost).
  bool get isTransient =>
      code == ZebraPrinterErrorCode.printerBusy ||
      code == ZebraPrinterErrorCode.printerUnreachable ||
      code == ZebraPrinterErrorCode.connectionLost;

  /// True when someone has to act on the printer (paper, head, ribbon, pause).
  bool get needsAttention =>
      code == ZebraPrinterErrorCode.paperOut ||
      code == ZebraPrinterErrorCode.headOpen ||
      code == ZebraPrinterErrorCode.ribbonOut ||
      code == ZebraPrinterErrorCode.printerPaused;

  @override
  String toString() => 'ZebraPrinterException(${code.value}): $message';
}
