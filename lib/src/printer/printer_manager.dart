import 'package:flutter/services.dart';
import 'package:zebra_printer/src/config/zebra_config.dart';
import 'package:zebra_printer/src/models/bluetooth_device.dart';
import 'package:zebra_printer/src/models/calibration_type.dart';
import 'package:zebra_printer/src/models/discovery_type.dart';
import 'package:zebra_printer/src/models/printer_info.dart';
import 'package:zebra_printer/src/models/printer_status.dart';

// ============================================================================
// PRINT LOCK EXCEPTION
// ============================================================================

/// Exception thrown when a print operation is blocked because another
/// print operation is already in progress on a different printer.
///
/// This prevents conflicts when using multiple printers with fast scanning.
class PrintLockedException implements Exception {
  /// Error message
  final String message;

  /// Address of the printer that is currently printing
  final String currentPrintingAddress;

  /// Address of the printer that was requested
  final String requestedAddress;

  PrintLockedException(this.message, {required this.currentPrintingAddress, required this.requestedAddress});

  @override
  String toString() =>
      'PrintLockedException: $message '
      '(current: $currentPrintingAddress, requested: $requestedAddress)';

  /// Returns user-friendly error message
  String get userMessage =>
      'Şu an "$currentPrintingAddress" yazıcısında yazdırma devam ediyor. '
      'Lütfen işlem bitene kadar bekleyin.';
}

/// Enum for print state
enum PrintState {
  /// Print job is idle
  idle,

  /// Connecting to printer
  connecting,

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

/// Print progress information with comprehensive printer status
class PrintProgress {
  /// Current print state
  final PrintState state;

  /// Progress percentage (0-100)
  final int percentage;

  /// Optional message describing current state
  final String? message;

  /// Error message if state is error
  final String? errorMessage;

  /// Printer address
  final String? address;

  /// Timestamp of this progress update
  final DateTime timestamp;

  /// Is this a batch print progress?
  final bool isBatch;

  /// Current label number (for batch printing)
  final int? currentLabel;

  /// Total number of labels (for batch printing)
  final int? totalLabels;

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
    this.address,
    this.isBatch = false,
    this.currentLabel,
    this.totalLabels,
    this.isPaperOut,
    this.isHeadOpen,
    this.isHeadCold,
    this.isHeadTooHot,
    this.isRibbonOut,
    this.labelsRemainingInBatch,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now();

  /// Creates PrintProgress from native callback data
  factory PrintProgress.fromNative(Map<dynamic, dynamic> data) {
    return PrintProgress(
      state: PrintState.fromString(data['state'] as String?),
      percentage: (data['percentage'] as int?) ?? 0,
      message: data['message'] as String?,
      errorMessage: data['errorMessage'] as String?,
      address: data['address'] as String?,
      isBatch: (data['isBatch'] as bool?) ?? false,
      currentLabel: data['currentLabel'] as int?,
      totalLabels: data['totalLabels'] as int?,
      // Printer status from SDK polling
      isPaperOut: data['isPaperOut'] as bool?,
      isHeadOpen: data['isHeadOpen'] as bool?,
      isHeadCold: data['isHeadCold'] as bool?,
      isHeadTooHot: data['isHeadTooHot'] as bool?,
      isRibbonOut: data['isRibbonOut'] as bool?,
      labelsRemainingInBatch: data['labelsRemainingInBatch'] as int?,
      timestamp: data['timestamp'] != null ? DateTime.fromMillisecondsSinceEpoch(data['timestamp'] as int) : DateTime.now(),
    );
  }

  /// Creates idle progress
  factory PrintProgress.idle() => PrintProgress(state: PrintState.idle, percentage: 0, message: 'Ready');

  /// Creates connecting progress
  factory PrintProgress.connecting() => PrintProgress(state: PrintState.connecting, percentage: 10, message: 'Connecting to printer...');

  /// Creates sending progress
  factory PrintProgress.sending({int percentage = 30}) => PrintProgress(state: PrintState.sending, percentage: percentage, message: 'Sending data to printer...');

  /// Creates printing progress
  factory PrintProgress.printing({int percentage = 50}) => PrintProgress(state: PrintState.printing, percentage: percentage, message: 'Printing...');

  /// Creates processing progress
  factory PrintProgress.processing({int percentage = 70}) => PrintProgress(state: PrintState.processing, percentage: percentage, message: 'Printer is processing...');

  /// Creates completed progress
  factory PrintProgress.completed() => PrintProgress(state: PrintState.completed, percentage: 100, message: 'Print completed successfully');

  /// Creates error progress
  factory PrintProgress.error(String error) => PrintProgress(state: PrintState.error, percentage: 0, errorMessage: error);

  // ==================== HELPER GETTERS ====================

  /// Is print in progress?
  bool get isInProgress => state == PrintState.connecting || state == PrintState.sending || state == PrintState.printing || state == PrintState.verifying || state == PrintState.processing;

  /// Is print completed?
  bool get isCompleted => state == PrintState.completed;

  /// Is there an error?
  bool get hasError => state == PrintState.error;

  /// Does printer have any warning/error status?
  bool get hasPrinterIssue => (isPaperOut ?? false) || (isHeadOpen ?? false) || (isHeadCold ?? false) || (isHeadTooHot ?? false) || (isRibbonOut ?? false);

  /// Progress for batch printing (e.g., "3/10")
  String get batchProgressText {
    if (!isBatch || currentLabel == null || totalLabels == null) return '';
    return '$currentLabel/$totalLabels';
  }

  /// Returns printer status warning message (if any)
  String? get printerWarning {
    if (isPaperOut == true) return 'Kağıt bitti!';
    if (isHeadOpen == true) return 'Yazıcı kafası açık!';
    if (isRibbonOut == true) return 'Ribon bitti!';
    if (isHeadCold == true) return 'Yazıcı kafası ısınıyor...';
    if (isHeadTooHot == true) return 'Yazıcı kafası soğuyor...';
    return null;
  }

  @override
  String toString() {
    final buffer = StringBuffer('PrintProgress{state: $state, percentage: $percentage%');
    if (isBatch) {
      buffer.write(', label: $batchProgressText');
    }
    if (message != null) {
      buffer.write(', message: $message');
    }
    if (hasPrinterIssue) {
      buffer.write(', warning: $printerWarning');
    }
    buffer.write('}');
    return buffer.toString();
  }
}

/// Discovered Zebra Printer model
class DiscoveredPrinter {
  final String type; // "bluetooth" or "network"
  final String address;
  final String friendlyName;

  DiscoveredPrinter({required this.type, required this.address, required this.friendlyName});

  factory DiscoveredPrinter.fromMap(Map<dynamic, dynamic> map) {
    return DiscoveredPrinter(type: map['type'] as String? ?? '', address: map['address'] as String? ?? '', friendlyName: map['friendlyName'] as String? ?? '');
  }

  @override
  String toString() {
    return 'DiscoveredPrinter{type: $type, address: $address, friendlyName: $friendlyName}';
  }
}

/// Class for communicating with Zebra printers using Link-OS SDK
class PrinterManager {
  /// Singleton instance
  static final PrinterManager _instance = PrinterManager._internal();

  /// Factory constructor
  factory PrinterManager() => _instance;

  /// Private constructor
  PrinterManager._internal() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  /// Config instance for logging
  final ZebraConfig _config = ZebraConfig();

  /// Method channel
  static const MethodChannel _channel = MethodChannel('com.sameetdmr.zebra_printer/zebra_print');

  /// Callback for when a printer is found during discovery
  void Function(DiscoveredPrinter printer)? onPrinterFound;

  /// Callback for when discovery is finished
  void Function(List<DiscoveredPrinter> printers)? onDiscoveryFinished;

  /// Callback for when connection state changes
  void Function(Map<String, dynamic> info)? onConnectionStateChanged;

  /// Callback for print progress updates
  /// [PrintProgress] contains current state, progress percentage, and optional message
  void Function(PrintProgress progress)? onPrintProgress;

  /// Flag to track if manager is disposed
  bool _isDisposed = false;

  /// Throws exception if manager is disposed
  void _checkDisposed() {
    if (_isDisposed) {
      _config.log('⚠️ PrinterManager is disposed! Call reinitialize() first.');
      throw StateError('PrinterManager has been disposed. Call reinitialize() to use again.');
    }
  }

  // ==================== PRINT LOCK MECHANISM ====================
  // Birden fazla yazıcıya aynı anda yazdırma isteği gönderilmesini engeller

  /// 🔒 Per-printer lock: MAC address -> print start time
  /// Her yazıcı için ayrı lock (çoklu yazıcı desteği)
  final Map<String, DateTime> _printerLocks = {};

  /// 🔒 Is a specific printer currently printing?
  bool isPrinterLocked(String address) => _printerLocks.containsKey(address);

  /// 🔒 Get all currently printing printers
  List<String> get currentlyPrintingPrinters => _printerLocks.keys.toList();

  /// 🔒 How long has a specific printer been printing?
  Duration? getPrinterLockDuration(String address) {
    final startTime = _printerLocks[address];
    if (startTime == null) return null;
    return DateTime.now().difference(startTime);
  }

  /// Is any print operation in progress? (lock state)
  bool get isPrintingInProgress => _printerLocks.isNotEmpty;

  /// Address of printer currently printing (if any)
  String? get currentPrintingAddress => _printerLocks.keys.firstOrNull;

  /// 🔒 Current print progress percentage (0-100, null if not printing)
  int? _currentPrintPercentage;
  int? get currentPrintPercentage => _currentPrintPercentage;

  /// ⏱️ Maximum print duration before auto-timeout (15 seconds)
  static const Duration _maxPrintDuration = Duration(seconds: 15);

  /// 🔒 Handle print lock automatically from progress callback
  /// Lock is acquired when printing starts, released when completed/error
  /// Auto-releases after 15 seconds as safety timeout
  void _handlePrintLockFromProgress(PrintProgress progress) {
    final address = progress.address;
    if (address == null) return;

    // ⏱️ Check for timeout - if printing for more than 15 seconds, auto-release
    final lockTime = _printerLocks[address];
    if (lockTime != null && progress.state != PrintState.completed && progress.state != PrintState.error) {
      final duration = DateTime.now().difference(lockTime);
      if (duration > _maxPrintDuration) {
        _config.log('⏱️ Print timeout for $address! Auto-releasing lock after ${duration.inSeconds}s');
        _printerLocks.remove(address);
        // Callback ile timeout bildir
        onPrintProgress?.call(PrintProgress(state: PrintState.error, percentage: 0, errorMessage: 'Yazdırma zaman aşımı (${_maxPrintDuration.inSeconds}s)', address: address));
        return;
      }
    }

    switch (progress.state) {
      case PrintState.connecting:
      case PrintState.sending:
      case PrintState.printing:
      case PrintState.verifying:
      case PrintState.processing:
        // 🔒 Yazdırma başladı/devam ediyor - lock al
        if (!_printerLocks.containsKey(address)) {
          _printerLocks[address] = DateTime.now();
          _config.log('🔒 Auto-lock acquired for printer: $address');
        }

      case PrintState.completed:
        // 🔓 Yazdırma tamamlandı - lock serbest bırak
        _config.log('🔓 Auto-lock released for $address: completed (${progress.percentage}%)');
        _printerLocks.remove(address);

      case PrintState.error:
        // 🔓 Hata oluştu - lock serbest bırak
        _config.log('🔓 Auto-lock released for $address: error - ${progress.errorMessage}');
        _printerLocks.remove(address);

      case PrintState.idle:
        // Idle - lock durumunu değiştirme
        break;
    }
  }

  /// Method call handler for callbacks from native side
  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onPrinterFound':
        if (onPrinterFound != null && call.arguments != null) {
          final printer = DiscoveredPrinter.fromMap(call.arguments as Map<dynamic, dynamic>);
          onPrinterFound!(printer);
        }
      case 'onDiscoveryFinished':
        if (onDiscoveryFinished != null && call.arguments != null) {
          final List<dynamic> printersList = call.arguments as List<dynamic>;
          final List<DiscoveredPrinter> printers = printersList.map((e) => DiscoveredPrinter.fromMap(e as Map<dynamic, dynamic>)).toList();
          onDiscoveryFinished!(printers);
        }
      case 'onConnectionStateChanged':
        if (onConnectionStateChanged != null && call.arguments != null) {
          final info = Map<String, dynamic>.from(call.arguments as Map);
          onConnectionStateChanged!(info);
        }
      case 'onPrintProgress':
        // 📊 Handle native print progress callback
        if (call.arguments != null) {
          final progressData = call.arguments as Map<dynamic, dynamic>;
          final progress = PrintProgress.fromNative(progressData);
          _config.log('Native print progress: ${progress.state} - ${progress.percentage}% - ${progress.message}');

          // 🔒 AUTO LOCK MANAGEMENT based on progress
          _handlePrintLockFromProgress(progress);

          // Call the global callback if set
          onPrintProgress?.call(progress);
        }
      default:
        break;
    }
  }

  // ==================== DISCOVERY METHODS ====================

  /// Starts discovering Zebra printers using Zebra Link-OS SDK
  ///
  /// [type] Discovery type: DiscoveryType.bluetooth, DiscoveryType.network, or DiscoveryType.both (default)
  ///
  /// Returns a list of discovered Zebra printers
  Future<List<DiscoveredPrinter>> startDiscovery({DiscoveryType type = DiscoveryType.both}) async {
    _checkDisposed();
    try {
      _config.log('startDiscovery called with type: ${type.value}');
      final result = await _channel.invokeMethod('startDiscovery', {'type': type.value});

      if (result == null) {
        _config.log('startDiscovery returned null');
        return [];
      }

      final List<dynamic> printersList = result as List<dynamic>;
      _config.log('startDiscovery found ${printersList.length} printers');

      final printers = printersList.map((e) => DiscoveredPrinter.fromMap(e as Map<dynamic, dynamic>)).toList();

      return printers;
    } on PlatformException catch (e) {
      _config.log('startDiscovery error: ${e.code} - ${e.message}');
      throw Exception("Discovery Error (${e.code}): ${e.message}");
    } catch (e) {
      _config.log('startDiscovery unexpected error: $e');
      rethrow;
    }
  }

  /// Stops the printer discovery process
  Future<bool> stopDiscovery() async {
    _checkDisposed();
    try {
      final result = await _channel.invokeMethod('stopDiscovery');
      return result as bool? ?? false;
    } on PlatformException catch (e) {
      throw Exception("Stop Discovery Error (${e.code}): ${e.message}");
    }
  }

  // ==================== CONNECTION METHODS ====================

  /// Connects to a Zebra printer and maintains the connection
  ///
  /// [address] Printer address (MAC address for Bluetooth or IP for Network)
  ///
  /// Returns true if connected successfully, false otherwise
  Future<bool> connect(String address) async {
    _checkDisposed();
    try {
      _config.log('connect called for address: $address');
      final result = await _channel.invokeMethod('connect', {'address': address});
      _config.log('connect result: $result');
      return result as bool? ?? false;
    } on PlatformException catch (e) {
      _config.log('connect error: ${e.code} - ${e.message}');
      throw Exception("Connection Error (${e.code}): ${e.message}");
    }
  }

  /// Disconnects from a Zebra printer
  ///
  /// [address] Optional printer address. If null, disconnects from currently connected printer
  ///
  /// Returns true if disconnected successfully, false otherwise
  Future<bool> disconnect({String? address}) async {
    _checkDisposed();
    try {
      _config.log('disconnect called for address: $address');
      final result = await _channel.invokeMethod('disconnect', {'address': address});
      _config.log('disconnect result: $result');
      return result as bool? ?? false;
    } on PlatformException catch (e) {
      _config.log('disconnect error: ${e.code} - ${e.message}');
      throw Exception("Disconnection Error (${e.code}): ${e.message}");
    }
  }

  /// Checks if printer is currently connected
  ///
  /// [address] Optional printer address to check. If null, checks general connection status
  ///
  /// Returns true if connected, false otherwise
  Future<bool> isConnected({String? address}) async {
    _checkDisposed();
    try {
      _config.log('isConnected called for address: $address');
      final result = await _channel.invokeMethod('isConnected', {'address': address});
      _config.log('isConnected result: $result');
      return result as bool? ?? false;
    } on PlatformException catch (e) {
      _config.log('isConnected error: ${e.code} - ${e.message}');
      throw Exception("IsConnected Error (${e.code}): ${e.message}");
    }
  }

  /// Unpairs a Bluetooth device
  /// Uses Android Bluetooth API to remove bonding
  ///
  /// Returns true if successful, throws exception otherwise
  Future<bool> unpairPrinter(String address) async {
    _checkDisposed();
    try {
      _config.log('unpairPrinter called for: $address');
      final result = await _channel.invokeMethod('unpairPrinter', {'address': address});
      _config.log('unpairPrinter successful');
      return result as bool;
    } on PlatformException catch (e) {
      _config.log('unpairPrinter error: ${e.code} - ${e.message}');
      throw Exception("Unpair Printer Error (${e.code}): ${e.message}");
    }
  }

  /// Gets paired (bonded) Bluetooth devices
  /// Uses Android Bluetooth API to get bonded devices
  ///
  /// Returns a list of paired Bluetooth devices as BluetoothDevice objects
  Future<List<BluetoothDevice>> getPairedPrinters() async {
    _checkDisposed();
    try {
      _config.log('getPairedPrinters called');
      final result = await _channel.invokeMethod('getPairedPrinters');

      if (result == null) {
        _config.log('getPairedPrinters returned null');
        return [];
      }

      final List<dynamic> devicesList = result as List<dynamic>;
      _config.log('getPairedPrinters found ${devicesList.length} printers');

      // Map'i BluetoothDevice'a dönüştür
      final devices =
          devicesList.map((deviceMap) {
            final map = Map<String, dynamic>.from(deviceMap as Map);
            return BluetoothDevice(
              name: map['friendlyName'] as String?,
              address: map['address'] as String,
              type: BluetoothDeviceType.classic, // Paired devices are classic Bluetooth
              bondState: BluetoothBondState.bonded, // All are bonded
            );
          }).toList();

      return devices;
    } on PlatformException catch (e) {
      _config.log('getPairedPrinters error: ${e.code} - ${e.message}');
      throw Exception("Get Paired Printers Error (${e.code}): ${e.message}");
    }
  }

  // ==================== PRINT LOCK HELPERS ====================

  /// 🔄 Force release print lock (emergency use)
  /// Use this if a print operation hangs or gets stuck
  void forceReleasePrintLock([String? address]) {
    if (address != null) {
      _config.log('⚠️ Force releasing print lock for: $address');
      _printerLocks.remove(address);
    } else {
      _config.log('⚠️ Force releasing ALL print locks (${_printerLocks.length} printers)');
      _printerLocks.clear();
    }
  }

  // ==================== PRINTING METHODS (INTERNAL) ====================

  /// Internal: Sends ZPL code to the printer
  /// Use [safePrint] instead for lock-protected printing
  Future<String> _sendZplToPrinter(String macAddress, String zplData) async {
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }
    try {
      // If ZPL code already starts with ^XA and ends with ^XZ, send as is
      final String finalZplToSend;
      if (zplData.trim().startsWith("^XA") && zplData.trim().endsWith("^XZ")) {
        finalZplToSend = zplData;
      } else {
        // Otherwise, wrap the ZPL code between ^XA and ^XZ
        const String initCommands = "^XA";
        finalZplToSend = "$initCommands$zplData^XZ";
      }

      final String result = await _channel.invokeMethod<String>('printLabel', {'address': macAddress, 'data': finalZplToSend}) ?? '';
      return result;
    } on PlatformException catch (e) {
      throw Exception("Print Error (${e.code}): ${e.message}");
    }
  }

  // ==================== SAFE PRINTING (WITH LOCK) ====================

  /// 🔒 SAFE PRINT: Prints with lock mechanism to prevent concurrent prints
  ///
  /// This method ensures only one printer can print at a time.
  /// If another printer is currently printing, throws [PrintLockedException].
  ///
  /// Lock is automatically managed by progress callbacks:
  /// - Acquired when native sends "connecting/sending/printing"
  /// - Released when native sends "completed" or "error"
  /// - Auto-released after 30 seconds (timeout safety)
  ///
  /// [macAddress] MAC address of the printer
  /// [zplData] ZPL code
  ///
  /// Returns result message if successful
  /// Throws [PrintLockedException] if another print is in progress
  /// Throws [Exception] on print errors
  Future<String> safePrint(String macAddress, String zplData) async {
    _checkDisposed();
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }

    // 🔒 Check if THIS printer is currently printing
    if (isPrinterLocked(macAddress)) {
      final duration = getPrinterLockDuration(macAddress);
      throw PrintLockedException('Bu yazıcıda yazdırma devam ediyor: $macAddress (${duration?.inSeconds ?? 0}s)', currentPrintingAddress: macAddress, requestedAddress: macAddress);
    }

    // 🚀 Hemen "preparing" progress gönder - dialog anında açılsın
    // Native taraftan connecting gelmeden önce kullanıcıya feedback ver
    onPrintProgress?.call(PrintProgress(state: PrintState.connecting, percentage: 5, message: 'Yazıcı hazırlanıyor...', address: macAddress));

    // Actual print - lock is managed by progress callback
    return _sendZplToPrinter(macAddress, zplData);
  }

  /// 🔒 SAFE BATCH PRINT: Batch print with lock mechanism
  ///
  /// Same as [safePrint] but for multiple labels.
  /// Throws [PrintLockedException] if another print is in progress.
  Future<String> safeBatchPrint(String macAddress, List<String> zplList) async {
    _checkDisposed();
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }
    if (zplList.isEmpty) {
      throw Exception("ZPL list cannot be empty.");
    }

    // 🔒 Check if THIS printer is currently printing
    if (isPrinterLocked(macAddress)) {
      final duration = getPrinterLockDuration(macAddress);
      throw PrintLockedException('Bu yazıcıda yazdırma devam ediyor: $macAddress (${duration?.inSeconds ?? 0}s)', currentPrintingAddress: macAddress, requestedAddress: macAddress);
    }

    // 🚀 Hemen "preparing" progress gönder - dialog anında açılsın
    onPrintProgress?.call(PrintProgress(state: PrintState.connecting, percentage: 5, message: 'Yazıcı hazırlanıyor...', address: macAddress, isBatch: true, currentLabel: 0, totalLabels: zplList.length));

    // Actual batch print - lock is managed by progress callback
    return _sendMultipleZplToPrinter(macAddress, zplList);
  }

  /// Internal: Sends multiple ZPL codes to the printer
  /// Use [safeBatchPrint] instead for lock-protected batch printing
  Future<String> _sendMultipleZplToPrinter(String macAddress, List<String> zplList) async {
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }
    if (zplList.isEmpty) {
      throw Exception("ZPL list cannot be empty.");
    }

    try {
      // Prepare ZPL list: ensure each ZPL starts with ^XA and ends with ^XZ
      final List<String> finalZplList =
          zplList.map((zplData) {
            final trimmed = zplData.trim();
            if (trimmed.startsWith("^XA") && trimmed.endsWith("^XZ")) {
              return zplData;
            } else {
              return "^XA$zplData^XZ";
            }
          }).toList();

      _config.log('_sendMultipleZplToPrinter called for $macAddress with ${finalZplList.length} labels');
      final String result = await _channel.invokeMethod<String>('printMultipleLabels', {'address': macAddress, 'dataList': finalZplList}) ?? '';
      _config.log('_sendMultipleZplToPrinter result: $result');
      return result;
    } on PlatformException catch (e) {
      _config.log('_sendMultipleZplToPrinter error: ${e.code} - ${e.message}');
      throw Exception("Batch Print Error (${e.code}): ${e.message}");
    }
  }

  /// Calibrates the printer with specified calibration type
  /// Uses [safePrint] for lock protection
  ///
  /// [macAddress] MAC address of the printer
  /// [calibrationType] Calibration type (gap or bar)
  ///
  /// Returns result message if successful
  /// Throws [PrintLockedException] if another print is in progress
  Future<String> calibratePrinter(String macAddress, CalibrationType calibrationType) async {
    return safePrint(macAddress, calibrationType.zplCommand);
  }

  /// Prints a test label
  /// Uses [safePrint] for lock protection
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns result message if successful
  /// Throws [PrintLockedException] if another print is in progress
  Future<String> printTestLabel(String macAddress) async {
    String testZpl = """^XA
^PON
^PW400
^MMT
^PR0
^LH0,6
^PMN
^FO50,50
^A0N,50,50
^FDZebra Test Print^FS
^FO50,120
^A0N,30,30
^FDDate: ${DateTime.now()}^FS
^FO50,170
^A0N,30,30
^FDTest Successful!^FS
^PQ1,0,1,Y
^XZ""";
    return safePrint(macAddress, testZpl);
  }

  /// Gets information about the printer
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns PrinterInfo object with model, serial number, firmware, and language information
  /// Throws an error if failed
  Future<PrinterInfo> getPrinterInfo(String macAddress) async {
    _checkDisposed();
    _config.log('getPrinterInfo called with address: $macAddress');

    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty");
    }

    try {
      _config.log('Invoking native getPrinterInfo method...');
      final String result = await _channel.invokeMethod<String>('getPrinterInfo', {'address': macAddress}) ?? '';
      _config.log('Native method returned: $result');

      final printerInfo = PrinterInfo.fromString(result);
      _config.log('PrinterInfo parsed successfully: ${printerInfo.toCompactString()}');
      return printerInfo;
    } on PlatformException catch (e) {
      _config.log('PlatformException in getPrinterInfo: Code: ${e.code}, Message: ${e.message}, Details: ${e.details}');
      throw Exception("Printer Info Error (${e.code}): ${e.message}");
    } catch (e) {
      _config.log('Unexpected error in getPrinterInfo: $e');
      throw Exception("Printer Info Error: $e");
    }
  }

  // ==================== ZEBRA SDK PRINTER STATUS ====================

  /// 🎯 Gets comprehensive printer status using Zebra SDK PrinterStatus class
  ///
  /// This method provides all status information from Zebra SDK:
  /// - isReadyToPrint: Is printer ready?
  /// - isPrinting: Is printer currently printing?
  /// - isPaperOut: Is paper out?
  /// - isHeadOpen: Is head open?
  /// - isHeadCold/isHeadTooHot: Head temperature issues
  /// - isRibbonOut: Is ribbon out? (thermal transfer)
  /// - isReceiveBufferFull: Is buffer full?
  /// - labelsRemainingInBatch: Labels remaining in batch
  /// - canPrint: Helper - can accept new print jobs?
  /// - isBusy: Helper - is printer busy?
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns [ZebraPrinterStatus] with comprehensive status information
  Future<ZebraPrinterStatus> getZebraPrinterStatus(String macAddress) async {
    _checkDisposed();
    if (macAddress.isEmpty) {
      return ZebraPrinterStatus.error('MAC adresi boş olamaz');
    }

    try {
      _config.log('getZebraPrinterStatus called for: $macAddress');

      final Map<dynamic, dynamic> result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getZebraPrinterStatus', {'address': macAddress}) ?? {};

      final status = ZebraPrinterStatus.fromNative(result);
      _config.log('ZebraPrinterStatus: ${status.toCompactString()}');

      return status;
    } on PlatformException catch (e) {
      _config.log('getZebraPrinterStatus error: ${e.code} - ${e.message}');
      return ZebraPrinterStatus.error('Durum hatası (${e.code}): ${e.message}', address: macAddress);
    } catch (e) {
      _config.log('getZebraPrinterStatus unexpected error: $e');
      return ZebraPrinterStatus.error('Beklenmeyen hata: $e', address: macAddress);
    }
  }

  /// 🔄 Checks if printer can accept new print jobs
  ///
  /// This is a quick check that returns true if:
  /// - Printer is ready to print
  /// - No paper/head/ribbon errors
  /// - Printer is not paused
  /// - Buffer is not full
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns true if printer can print, false otherwise
  Future<bool> canPrint(String macAddress) async {
    _checkDisposed();
    try {
      final status = await getZebraPrinterStatus(macAddress);
      return status.canPrint;
    } catch (e) {
      _config.log('canPrint error: $e');
      return false;
    }
  }

  /// 🔄 Checks if printer is currently busy (printing or has jobs in queue)
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns true if printer is busy, false otherwise
  Future<bool> isPrinterBusy(String macAddress) async {
    _checkDisposed();
    try {
      final status = await getZebraPrinterStatus(macAddress);
      return status.isBusy;
    } catch (e) {
      _config.log('isPrinterBusy error: $e');
      return false;
    }
  }

  /// ⏳ Waits for printer to be ready (not busy)
  ///
  /// Useful when switching between printers or printing multiple jobs
  /// Polls the printer status until it's ready or timeout is reached
  ///
  /// [macAddress] MAC address of the printer
  /// [timeout] Maximum time to wait (default: 10 seconds)
  /// [pollInterval] Time between status checks (default: 500ms)
  ///
  /// Returns [ZebraPrinterStatus] when ready, or status with error if timeout
  Future<ZebraPrinterStatus> waitForPrinterReady(String macAddress, {Duration timeout = const Duration(seconds: 10), Duration pollInterval = const Duration(milliseconds: 500)}) async {
    _checkDisposed();
    _config.log('waitForPrinterReady: Waiting for $macAddress...');

    final stopwatch = Stopwatch()..start();
    ZebraPrinterStatus lastStatus = ZebraPrinterStatus.disconnected(address: macAddress);

    while (stopwatch.elapsed < timeout) {
      lastStatus = await getZebraPrinterStatus(macAddress);

      // Yazıcı hazırsa ve meşgul değilse dön
      if (lastStatus.canPrint && !lastStatus.isBusy) {
        _config.log('waitForPrinterReady: Printer is ready after ${stopwatch.elapsedMilliseconds}ms');
        return lastStatus;
      }

      // Kritik hata varsa (kağıt yok, kafa açık) hemen dön
      if (lastStatus.hasCriticalError) {
        _config.log('waitForPrinterReady: Critical error - ${lastStatus.statusMessage}');
        return lastStatus;
      }

      _config.log('waitForPrinterReady: Still busy... ${lastStatus.statusCode}');
      await Future<void>.delayed(pollInterval);
    }

    // Timeout
    _config.log('waitForPrinterReady: Timeout after ${timeout.inSeconds}s');
    return ZebraPrinterStatus(isReadyToPrint: lastStatus.isReadyToPrint, isPrinting: lastStatus.isPrinting, errorMessage: 'Yazıcı ${timeout.inSeconds} saniye içinde hazır olmadı', address: macAddress);
  }

  // ==================== LOGGING CONTROL ====================

  /// Enable logging for both Dart and native (Java/Swift) sides
  ///
  /// When enabled, all debug logs will be printed
  /// Default: false (for production)
  Future<void> enableLogging() async {
    _config.enableLogging();
    try {
      await _channel.invokeMethod('setLogging', {'enabled': true});
      _config.log('Logging enabled (Dart + Native)');
    } catch (e) {
      _config.log('Failed to enable native logging: $e');
    }
  }

  /// Disable logging for both Dart and native (Java/Swift) sides
  Future<void> disableLogging() async {
    try {
      await _channel.invokeMethod('setLogging', {'enabled': false});
    } catch (e) {
      _config.log('Failed to disable native logging: $e');
    }
    _config.disableLogging();
  }

  /// Check if logging is enabled on native side
  Future<bool> isNativeLoggingEnabled() async {
    try {
      final result = await _channel.invokeMethod<bool>('isLoggingEnabled');
      return result ?? false;
    } catch (e) {
      _config.log('Failed to check native logging status: $e');
      return false;
    }
  }

  /// Check if logging is enabled on Dart side
  bool get isLoggingEnabled => _config.isLoggingEnabled;

  // ==================== FONT MANAGEMENT ====================
  
  /// Uploads a font to the printer from assets
  /// ZPL font file is automatically parsed to extract font name
  ///
  /// [macAddress] - The MAC address of the printer
  /// [assetPath] - Path to the font asset (e.g., "assets/arial/arial.zpl")  
  ///
  /// Returns [true] if upload was successful, [false] otherwise
  ///
  /// Throws [Exception] if the operation fails
  ///
  /// Example:
  /// ```dart
  /// final success = await printerManager.uploadFont(
  ///   "00:07:80:12:34:56",
  ///   "assets/arial/arial.zpl"
  /// );
  /// ```
  Future<bool> uploadFont(String macAddress, String assetPath) async {
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }
    
    if (assetPath.isEmpty) {
      throw Exception("Asset path cannot be empty.");
    }

    try {
      // Read the asset to extract font name
      final ByteData data = await rootBundle.load(assetPath);
      final Uint8List fontData = data.buffer.asUint8List();
      final String fontName = _extractFontNameFromZpl(fontData);
      
      _config.log('📁 Uploading font: $assetPath to $macAddress as $fontName');
      
      final bool result = await _channel.invokeMethod<bool>('uploadFont', {
        'address': macAddress,
        'assetPath': assetPath,
        'fontType': fontName,
      }) ?? false;

      if (result) {
        _config.log('✅ Font upload successful: $fontName');
      } else {
        _config.log('❌ Font upload failed: $fontName');
      }
      
      return result;
      
    } catch (e) {
      _config.log('❌ Font upload error: $e');
      throw Exception('Font upload failed: $e');
    }
  }

  /// Upload font data directly from byte array
  /// ZPL font file is automatically parsed to extract font name
  Future<bool> uploadFontData(String macAddress, Uint8List fontData) async {
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }
    
    if (fontData.isEmpty) {
      throw Exception("Font data cannot be empty.");
    }

    try {
      // Extract font name from ZPL data (e.g., "~DYE:arial,B,T,915212" -> "arial")
      String fontName = _extractFontNameFromZpl(fontData);
      
      _config.log('📁 Uploading font data: ${fontData.length} bytes to $macAddress as $fontName');
      
      final bool result = await _channel.invokeMethod<bool>('uploadFontData', {
        'address': macAddress,
        'fontData': fontData,
        'fontType': fontName,
      }) ?? false;

      if (result) {
        _config.log('✅ Font data upload successful: $fontName');
      } else {
        _config.log('❌ Font data upload failed: $fontName');
      }
      
      return result;
      
    } catch (e) {
      _config.log('❌ Font data upload error: $e');
      throw Exception('Font data upload failed: $e');
    }
  }

  /// Extracts font name from ZPL font data
  /// Parses "~DYE:fontname,..." format
  String _extractFontNameFromZpl(Uint8List fontData) {
    try {
      // Convert first 100 bytes to string to find the font name
      final String header = String.fromCharCodes(fontData.take(100));
      
      // Look for ~DYE:fontname pattern
      final RegExp dyePattern = RegExp(r'~?DYE?:([^,]+)');
      final Match? match = dyePattern.firstMatch(header);
      
      if (match != null && match.group(1) != null) {
        final String fontName = match.group(1)!.trim();
        _config.log('🔍 Extracted font name from ZPL: $fontName');
        return fontName;
      }
      
      // Fallback: use "font" as default name
      _config.log('⚠️ Could not extract font name from ZPL, using default: "font"');
      return "font";
      
    } catch (e) {
      _config.log('⚠️ Error extracting font name: $e, using default: "font"');
      return "font";
    }
  }

  // ==================== LIFECYCLE ====================

  /// Disposes the PrinterManager and cleans up resources
  ///
  /// Call this when the PrinterManager is no longer needed
  /// After calling dispose, the PrinterManager should not be used
  void dispose() {
    if (_isDisposed) return;

    _config.log('PrinterManager disposing...');

    // Clear callbacks
    onPrinterFound = null;
    onDiscoveryFinished = null;
    onConnectionStateChanged = null;
    onPrintProgress = null;

    // Remove method call handler
    _channel.setMethodCallHandler(null);

    _isDisposed = true;
    _config.log('PrinterManager disposed');
  }

  /// Check if manager is disposed
  bool get isDisposed => _isDisposed;

  /// Reinitialize the manager after dispose (if needed)
  void reinitialize() {
    if (!_isDisposed) return;

    _channel.setMethodCallHandler(_handleMethodCall);
    _isDisposed = false;
    _config.log('PrinterManager reinitialized');
  }
}
