import 'dart:async';

import 'package:flutter/services.dart';
import 'package:zebra_printer/src/config/zebra_config.dart';
import 'package:zebra_printer/src/models/bluetooth_device.dart';
import 'package:zebra_printer/src/models/calibration_type.dart';
import 'package:zebra_printer/src/models/discovered_printer.dart';
import 'package:zebra_printer/src/models/discovery_type.dart';
import 'package:zebra_printer/src/models/print_options.dart';
import 'package:zebra_printer/src/models/print_progress.dart';
import 'package:zebra_printer/src/models/printer_info.dart';
import 'package:zebra_printer/src/models/printer_status.dart';
import 'package:zebra_printer/src/models/zebra_printer_exception.dart';
import 'package:zebra_printer/src/printer/print_queue.dart';

/// Zebra printer operations over Bluetooth (Zebra Link-OS SDK).
///
/// Designed for a **shared printer**: every call connects, does its work and
/// releases the Bluetooth link right away, so other devices can use the
/// printer. If another device is printing, the call waits (back-off + jitter)
/// up to [PrintOptions.maxWait], reporting [PrintState.waiting] through
/// [onPrintProgress].
///
/// Calls for the same printer made from this app are queued (FIFO) and run
/// one after another.
///
/// Errors are thrown as [ZebraPrinterException] with a typed
/// [ZebraPrinterErrorCode].
class PrinterManager {
  /// Singleton instance
  static final PrinterManager _instance = PrinterManager._internal();

  /// Factory constructor
  factory PrinterManager() => _instance;

  PrinterManager._internal() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  final ZebraConfig _config = ZebraConfig();

  static const MethodChannel _channel = MethodChannel(
    'com.sameetdmr.zebra_printer/zebra_print',
  );

  final PrintQueue _queue = PrintQueue();
  int _jobSeq = 0;

  /// Options used when a call does not pass its own.
  PrintOptions defaultOptions = PrintOptions.shared;

  /// Status and info calls wait less for a busy printer than print jobs.
  static const Duration _queryMaxWait = Duration(seconds: 10);

  /// Callback for when a printer is found during discovery
  void Function(DiscoveredPrinter printer)? onPrinterFound;

  /// Callback for when discovery is finished
  void Function(List<DiscoveredPrinter> printers)? onDiscoveryFinished;

  /// Callback for when connection state changes
  void Function(Map<String, dynamic> info)? onConnectionStateChanged;

  /// Callback for print progress updates (including [PrintState.waiting]
  /// while another device uses the printer).
  void Function(PrintProgress progress)? onPrintProgress;

  bool _isDisposed = false;

  void _checkDisposed() {
    if (_isDisposed) {
      throw StateError(
        'PrinterManager has been disposed. Call reinitialize() to use again.',
      );
    }
  }

  // ==================== QUEUE / LOCK STATE ====================

  int? _currentPrintPercentage;

  /// Progress of the running print job (0-100), null when idle.
  int? get currentPrintPercentage => _currentPrintPercentage;

  /// Is a job running or waiting in this app's queue for [address]?
  bool isPrinterLocked(String address) {
    final key = _key(address);
    return _queue.isActive(key) || _queue.pendingCount(key) > 0;
  }

  /// Number of jobs waiting behind the running one for [address].
  int pendingJobCount(String address) => _queue.pendingCount(_key(address));

  /// Printers that currently run a job from this app.
  List<String> get currentlyPrintingPrinters => _queue.activeKeys;

  /// How long the running job for [address] has been going.
  Duration? getPrinterLockDuration(String address) {
    final since = _queue.activeSince(_key(address));
    return since == null ? null : DateTime.now().difference(since);
  }

  /// Is any job running?
  bool get isPrintingInProgress => _queue.activeKeys.isNotEmpty;

  /// Address of a printer running a job (if any).
  String? get currentPrintingAddress => _queue.activeKeys.firstOrNull;

  /// Removes jobs that have not started yet (for [address], or all printers).
  /// Their futures fail with [ZebraPrinterErrorCode.cancelled].
  int cancelPendingJobs([String? address]) =>
      _queue.cancelPending(address == null ? null : _key(address));

  /// Cancels pending jobs. A running job cannot be interrupted.
  @Deprecated('Jobs are queued since 0.5.0. Use cancelPendingJobs().')
  void forceReleasePrintLock([String? address]) => cancelPendingJobs(address);

  /// Queue key: MAC address normalised to "AA:BB:CC:DD:EE:FF".
  static String _key(String address) {
    final hex = address.trim().replaceAll(RegExp('[:-]'), '').toUpperCase();
    if (!RegExp(r'^[0-9A-F]{12}$').hasMatch(hex)) {
      return address.trim().toUpperCase();
    }
    final parts = <String>[];
    for (var i = 0; i < 12; i += 2) {
      parts.add(hex.substring(i, i + 2));
    }
    return parts.join(':');
  }

  String _nextJobId() =>
      '${DateTime.now().microsecondsSinceEpoch}-${++_jobSeq}';

  // ==================== CHANNEL ====================

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onPrinterFound':
        if (onPrinterFound != null && call.arguments != null) {
          onPrinterFound!(
            DiscoveredPrinter.fromMap(call.arguments as Map<dynamic, dynamic>),
          );
        }
      case 'onDiscoveryFinished':
        if (onDiscoveryFinished != null && call.arguments != null) {
          final list =
              (call.arguments as List<dynamic>)
                  .map(
                    (e) =>
                        DiscoveredPrinter.fromMap(e as Map<dynamic, dynamic>),
                  )
                  .toList();
          onDiscoveryFinished!(list);
        }
      case 'onConnectionStateChanged':
        if (onConnectionStateChanged != null && call.arguments != null) {
          onConnectionStateChanged!(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        }
      case 'onPrintProgress':
        if (call.arguments != null) {
          final progress = PrintProgress.fromNative(
            call.arguments as Map<dynamic, dynamic>,
          );
          _emitProgress(progress);
        }
      default:
        break;
    }
  }

  void _emitProgress(PrintProgress progress) {
    _currentPrintPercentage =
        progress.isInProgress ? progress.percentage : null;
    _config.log(
      'Print progress: ${progress.state.name} ${progress.percentage}% '
      '${progress.message ?? ''}',
    );
    onPrintProgress?.call(progress);
  }

  /// Invokes a native method and converts platform errors.
  Future<T?> _invoke<T>(String method, [Map<String, Object?>? args]) async {
    try {
      return await _channel.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      final error = ZebraPrinterException.fromPlatformException(e);
      _config.log('$method failed: $error');
      throw error;
    }
  }

  static void _requireAddress(String? address) {
    if (address == null || address.trim().isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'Printer address cannot be empty',
      );
    }
  }

  /// Queues a native call for [address].
  Future<T> _enqueue<T>(
    String address,
    String label,
    Future<T> Function() task,
  ) {
    _checkDisposed();
    _requireAddress(address);
    return _queue.add<T>(_key(address), task, label: label);
  }

  /// Queues a print job and reports a local "connecting" event first.
  Future<T> _enqueuePrintJob<T>(
    String address,
    String method,
    Map<String, Object?> args,
    PrintOptions? options, {
    int? totalLabels,
  }) {
    final jobId = _nextJobId();
    return _enqueue<T>(address, method, () async {
      _emitProgress(
        PrintProgress(
          state: PrintState.connecting,
          percentage: 5,
          message: 'Preparing printer...',
          address: address,
          jobId: jobId,
          isBatch: totalLabels != null,
          currentLabel: totalLabels == null ? null : 0,
          totalLabels: totalLabels,
        ),
      );
      final result = await _invoke<T>(method, {
        ...args,
        'address': address,
        'jobId': jobId,
        'options': (options ?? defaultOptions).toMap(),
      });
      return result as T;
    });
  }

  PrintOptions _queryOptions(PrintOptions? options) =>
      options ?? defaultOptions.copyWith(maxWait: _queryMaxWait);

  // ==================== DISCOVERY ====================

  /// Discovers Zebra printers over Bluetooth.
  ///
  /// [DiscoveryType.network] is still accepted for discovery, but this package
  /// prints over Bluetooth only.
  Future<List<DiscoveredPrinter>> startDiscovery({
    DiscoveryType type = DiscoveryType.bluetooth,
  }) async {
    _checkDisposed();
    final result = await _invoke<List<dynamic>>('startDiscovery', {
      'type': type.value,
    });
    return (result ?? [])
        .map((e) => DiscoveredPrinter.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  /// Stops discovery. The pending [startDiscovery] call completes with the
  /// printers found so far.
  Future<bool> stopDiscovery() async {
    _checkDisposed();
    return await _invoke<bool>('stopDiscovery') ?? false;
  }

  /// Bluetooth devices paired with this device.
  Future<List<BluetoothDevice>> getPairedPrinters() async {
    _checkDisposed();
    final result = await _invoke<List<dynamic>>('getPairedPrinters');
    return (result ?? []).map((raw) {
      final map = Map<String, dynamic>.from(raw as Map);
      return BluetoothDevice(
        name: map['friendlyName'] as String?,
        address: map['address'] as String,
        type: BluetoothDeviceType.classic,
        bondState: BluetoothBondState.bonded,
      );
    }).toList();
  }

  /// Removes the pairing with a printer.
  Future<bool> unpairPrinter(String address) async {
    _checkDisposed();
    _requireAddress(address);
    return await _invoke<bool>('unpairPrinter', {'address': address}) ?? false;
  }

  // ==================== CONNECTION ====================

  /// Checks that the printer is reachable: connects, verifies it is a Zebra
  /// printer and **disconnects again** (the printer is not held).
  ///
  /// Returns true on success, throws [ZebraPrinterException] otherwise.
  Future<bool> connect(String address, {PrintOptions? options}) async {
    return _enqueue(address, 'connect', () async {
      return await _invoke<bool>('connect', {
            'address': address,
            'options': _queryOptions(options).toMap(),
          }) ??
          false;
    });
  }

  /// Closes a link kept open with [PrintOptions.keepAlive]. With the default
  /// shared options there is nothing to close and this returns true.
  Future<bool> disconnect({String? address}) async {
    _checkDisposed();
    _requireAddress(address);
    return await _invoke<bool>('disconnect', {'address': address}) ?? false;
  }

  /// True only while a link is kept open by [PrintOptions.keepAlive].
  Future<bool> isConnected({String? address}) async {
    _checkDisposed();
    if (address == null || address.isEmpty) return false;
    return await _invoke<bool>('isConnected', {'address': address}) ?? false;
  }

  // ==================== PRINTING ====================

  static String _wrapZpl(String zpl) {
    final trimmed = zpl.trim();
    if (trimmed.startsWith('^XA') && trimmed.endsWith('^XZ')) return zpl;
    return '^XA$zpl^XZ';
  }

  /// Prints a ZPL label. Data not wrapped in `^XA ... ^XZ` is wrapped.
  ///
  /// Queued behind other jobs for the same printer from this app. Waits for
  /// the printer if another device is using it.
  ///
  /// Throws [ZebraPrinterException].
  Future<String> safePrint(
    String macAddress,
    String zplData, {
    PrintOptions? options,
  }) async {
    if (zplData.isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'ZPL data cannot be empty',
      );
    }
    return _enqueuePrintJob<String>(macAddress, 'printLabel', {
      'data': _wrapZpl(zplData),
    }, options);
  }

  /// Prints several labels in one Bluetooth session.
  Future<String> safeBatchPrint(
    String macAddress,
    List<String> zplList, {
    PrintOptions? options,
  }) async {
    if (zplList.isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'ZPL list cannot be empty',
      );
    }
    return _enqueuePrintJob<String>(
      macAddress,
      'printMultipleLabels',
      {'dataList': zplList.map(_wrapZpl).toList()},
      options,
      totalLabels: zplList.length,
    );
  }

  /// Sends raw printer commands as-is (no `^XA`/`^XZ` wrapping and no status
  /// check), e.g. SGD `! U1 setvar ...` lines or `~JC`.
  Future<bool> sendCommand(
    String macAddress,
    String command, {
    PrintOptions? options,
  }) async {
    if (command.isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'Command cannot be empty',
      );
    }
    final result = await _enqueuePrintJob<bool?>(macAddress, 'sendCommand', {
      'data': command,
    }, options);
    return result ?? false;
  }

  /// Sets the media sensor mode and runs a media calibration.
  ///
  /// Uses [sendCommand], so it also works while the printer reports
  /// "paper out" (which a calibration often fixes).
  Future<String> calibratePrinter(
    String macAddress,
    CalibrationType calibrationType, {
    PrintOptions? options,
  }) async {
    await sendCommand(macAddress, calibrationType.command, options: options);
    return 'Calibration started: ${calibrationType.name}';
  }

  /// Prints a small test label.
  Future<String> printTestLabel(
    String macAddress, {
    PrintOptions? options,
  }) async {
    final testZpl =
        '^XA\n^PW400\n^LH0,6\n'
        '^FO50,50^A0N,50,50^FDZebra Test Print^FS\n'
        '^FO50,120^A0N,30,30^FDDate: ${DateTime.now()}^FS\n'
        '^FO50,170^A0N,30,30^FDTest Successful!^FS\n'
        '^XZ';
    return safePrint(macAddress, testZpl, options: options);
  }

  /// Prints an image (PNG, JPG, BMP or WebP bytes) as a label.
  ///
  /// [x], [y] are the position in dots. [width] / [height] scale the image in
  /// dots; 0 keeps the original size. Large images are down-sampled to at
  /// most 2000 px on the longest side before conversion.
  Future<String> printImage(
    String macAddress,
    Uint8List imageData, {
    int x = 0,
    int y = 0,
    int width = 0,
    int height = 0,
    PrintOptions? options,
  }) async {
    if (imageData.isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'Image data cannot be empty',
      );
    }
    return _enqueuePrintJob<String>(macAddress, 'printImage', {
      'imageData': imageData,
      'x': x,
      'y': y,
      'width': width,
      'height': height,
    }, options);
  }

  // ==================== STATUS & INFO ====================

  /// Reads model, serial number, firmware, language, Link-OS version and
  /// Protected Mode state.
  Future<PrinterInfo> getPrinterInfo(
    String macAddress, {
    PrintOptions? options,
  }) async {
    return _enqueue(macAddress, 'getPrinterInfo', () async {
      final result = await _invoke<Map<dynamic, dynamic>>('getPrinterInfo', {
        'address': macAddress,
        'options': _queryOptions(options).toMap(),
      });
      return PrinterInfo.fromMap(result ?? const {});
    });
  }

  /// Reads the printer status in a short session.
  ///
  /// Never throws for printer/connection problems: they are returned in
  /// [ZebraPrinterStatus.errorMessage] / [ZebraPrinterStatus.errorCode].
  ///
  /// Every call opens a Bluetooth link, so on a shared printer avoid polling
  /// it in a loop: print jobs already check the status on their own link
  /// (see [PrintOptions.checkStatusBeforePrint]).
  Future<ZebraPrinterStatus> getZebraPrinterStatus(
    String macAddress, {
    PrintOptions? options,
  }) async {
    _checkDisposed();
    if (macAddress.isEmpty) {
      return ZebraPrinterStatus.error('MAC address cannot be empty');
    }
    try {
      return await _enqueue(macAddress, 'getZebraPrinterStatus', () async {
        final result = await _invoke<Map<dynamic, dynamic>>(
          'getZebraPrinterStatus',
          {'address': macAddress, 'options': _queryOptions(options).toMap()},
        );
        return ZebraPrinterStatus.fromNative(result ?? const {});
      });
    } on ZebraPrinterException catch (e) {
      return ZebraPrinterStatus.error(
        e.message,
        address: macAddress,
        errorCode: e.code,
      );
    }
  }

  /// True if the printer can accept a job right now (one status session).
  Future<bool> canPrint(String macAddress, {PrintOptions? options}) async {
    final status = await getZebraPrinterStatus(macAddress, options: options);
    return status.canPrint;
  }

  /// True if the printer is processing or has queued formats.
  Future<bool> isPrinterBusy(String macAddress, {PrintOptions? options}) async {
    final status = await getZebraPrinterStatus(macAddress, options: options);
    return status.isBusy;
  }

  /// Polls the status until the printer is ready, has a critical error or
  /// [timeout] passes.
  ///
  /// Each poll opens a short Bluetooth session. On a shared printer prefer
  /// just calling [safePrint]: it waits for the printer by itself.
  Future<ZebraPrinterStatus> waitForPrinterReady(
    String macAddress, {
    Duration timeout = const Duration(seconds: 10),
    Duration pollInterval = const Duration(seconds: 2),
  }) async {
    _checkDisposed();
    final stopwatch = Stopwatch()..start();
    var last = ZebraPrinterStatus.disconnected(address: macAddress);
    while (stopwatch.elapsed < timeout) {
      final remaining = timeout - stopwatch.elapsed;
      last = await getZebraPrinterStatus(
        macAddress,
        options: defaultOptions.copyWith(
          maxWait: remaining < _queryMaxWait ? remaining : _queryMaxWait,
        ),
      );
      if (last.canPrint && !last.isBusy) return last;
      if (last.hasCriticalError) return last;
      await Future<void>.delayed(pollInterval);
    }
    return ZebraPrinterStatus(
      isReadyToPrint: last.isReadyToPrint,
      isPrinting: last.isPrinting,
      errorMessage: 'Printer was not ready within ${timeout.inSeconds} seconds',
      errorCode: last.errorCode,
      address: macAddress,
    );
  }

  // ==================== FONTS ====================

  /// Uploads a font from the app's Flutter assets.
  ///
  /// Accepts a ZPL `~DY` font file, a raw TTF or other ZPL text. The font name
  /// is read from the `~DYE:name` header (fallback "font") and the font is
  /// stored as `E:NAME`.
  Future<bool> uploadFont(
    String macAddress,
    String assetPath, {
    PrintOptions? options,
  }) async {
    _checkDisposed();
    if (assetPath.isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'Asset path cannot be empty',
      );
    }
    final ByteData data = await rootBundle.load(assetPath);
    return uploadFontData(
      macAddress,
      data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
      options: options,
    );
  }

  /// Uploads font bytes (see [uploadFont]).
  Future<bool> uploadFontData(
    String macAddress,
    Uint8List fontData, {
    PrintOptions? options,
  }) async {
    if (fontData.isEmpty) {
      throw ZebraPrinterException(
        ZebraPrinterErrorCode.invalidArgument,
        'Font data cannot be empty',
      );
    }
    final fontName = extractFontName(fontData);
    return _enqueue(macAddress, 'uploadFontData', () async {
      return await _invoke<bool>('uploadFontData', {
            'address': macAddress,
            'fontData': fontData,
            'fontType': fontName,
            'options': (options ?? defaultOptions).toMap(),
          }) ??
          false;
    });
  }

  /// Reads the font name from a `~DYE:name,...` header; "font" if absent.
  static String extractFontName(Uint8List fontData) {
    final header = String.fromCharCodes(fontData.take(100));
    final match = RegExp(r'~?DYE?:([^,]+)').firstMatch(header);
    final name = match?.group(1)?.trim();
    return (name == null || name.isEmpty) ? 'font' : name;
  }

  // ==================== LOGGING ====================

  /// Enable logging for Dart and native sides.
  Future<void> enableLogging() async {
    _config.enableLogging();
    try {
      await _channel.invokeMethod('setLogging', {'enabled': true});
    } catch (e) {
      _config.log('Failed to enable native logging: $e');
    }
  }

  /// Disable logging for Dart and native sides.
  Future<void> disableLogging() async {
    try {
      await _channel.invokeMethod('setLogging', {'enabled': false});
    } catch (e) {
      _config.log('Failed to disable native logging: $e');
    }
    _config.disableLogging();
  }

  /// Is logging enabled on the native side?
  Future<bool> isNativeLoggingEnabled() async {
    try {
      return await _channel.invokeMethod<bool>('isLoggingEnabled') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Is logging enabled on the Dart side?
  bool get isLoggingEnabled => _config.isLoggingEnabled;

  // ==================== LIFECYCLE ====================

  /// Clears callbacks, cancels queued jobs and detaches the channel handler.
  ///
  /// [PrinterManager] is a singleton: only call this when the whole app is
  /// done with printing. Call [reinitialize] to use it again.
  void dispose() {
    if (_isDisposed) return;
    cancelPendingJobs();
    onPrinterFound = null;
    onDiscoveryFinished = null;
    onConnectionStateChanged = null;
    onPrintProgress = null;
    _channel.setMethodCallHandler(null);
    _isDisposed = true;
  }

  /// Check if manager is disposed
  bool get isDisposed => _isDisposed;

  /// Reinitialize the manager after dispose.
  void reinitialize() {
    if (!_isDisposed) return;
    _channel.setMethodCallHandler(_handleMethodCall);
    _isDisposed = false;
  }
}
