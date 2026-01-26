/// Configuration for Zebra Printer package
class ZebraConfig {
  /// Singleton instance
  static final ZebraConfig _instance = ZebraConfig._internal();

  /// Factory constructor
  factory ZebraConfig() => _instance;

  /// Private constructor
  ZebraConfig._internal();

  /// Enable/disable logging (default: false)
  bool _loggingEnabled = false;

  /// Get logging status
  bool get isLoggingEnabled => _loggingEnabled;

  /// Enable logging
  void enableLogging() {
    _loggingEnabled = true;
  }

  /// Disable logging
  void disableLogging() {
    _loggingEnabled = false;
  }

  /// Set logging status
  void setLogging(bool enabled) {
    _loggingEnabled = enabled;
  }

  /// Log message if logging is enabled
  void log(String message) {
    if (_loggingEnabled) {
      // ignore: avoid_print
      print('[ZebraPrinter] $message');
    }
  }
}
