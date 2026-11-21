# Changelog

## 0.6.0 - Bluetooth extensions 🧩

- **Insecure (unbonded) Bluetooth links**: `PrintOptions(insecure: true)` uses `BluetoothConnectionInsecure`, so many devices can print without pairing each one (printer needs `bluetooth.minimum_security_mode` = 1).
- **In-app print queue**: jobs for the same printer run FIFO instead of throwing `PrintLockedException`. New `pendingJobCount()`, `cancelPendingJobs()`.
- **`printImage()`**: print PNG/JPG/BMP/WebP bytes via Link-OS `GraphicsUtil`; large images are down-sampled to avoid OOM.
- **`sendCommand()`**: send raw SGD / tilde commands as-is.
- **`getPrinterInfo()`**: native side returns a map; new `linkOsVersion` and `isProtectedMode` (Link-OS Protected Mode, SDK 2.17) fields. SGD values are unquoted.
- **Fonts**:
  - `uploadFont()` reads the Flutter asset in Dart (previously the native side looked in Android assets and usually failed).
  - Unknown binary data is rejected with `unsupportedFormat` instead of being sent to the printer.
  - `~DY` files are written as-is on non Link-OS printers (e.g. iMZ).
  - The printer path stays `E:<NAME>` (unchanged).

## 0.5.0 - Shared printer core 🤝

Built for one Bluetooth printer shared by many devices: connect → print → release.

- **Zebra Link-OS SDK 2.17.5585** (Android 17 RFCOMM `read()` fix, tested on Android 15–17).
- **Short sessions**: the link is closed right after each job after a short drain delay (default 400 ms). The 2 s reuse window and the post-print "wait until ready" loop are now opt-in (`keepAlive`, `waitForCompletion`).
- **Waiting for a busy printer**: exponential back-off with random jitter inside a total budget (default 30 s). New `PrintState.waiting` progress with `attempt`, `waited`, `nextRetryIn`; completed events report `holdTime`.
- **`PrintOptions`**: per call or app-wide via `PrinterManager.defaultOptions`; presets `PrintOptions.shared` and `PrintOptions.dedicated`.
- **Typed errors**: `ZebraPrinterException` with `ZebraPrinterErrorCode` (`printerBusy`, `printerUnreachable`, `notPaired`, `permissionDenied`, `bluetoothOff`, `paperOut`, `headOpen`, `ribbonOut`, `printerPaused`, ...). `ZebraPrinterStatus.errorCode` added.
- **Status check on the same link** before printing (no second connection).
- **Fixes**
  - `safePrint` lock was acquired asynchronously (two quick calls both passed) and auto-released after 15 s in the middle of long batches.
  - `calibratePrinter` changed `wlan.country_code` to "turkey" and wrapped SGD commands in `^XA…^XZ`, so they were ignored. Now sends `media.type`, `media.sense_mode`, `~JC`, `^JUS` as raw commands.
  - Android 12+: `BluetoothDevice.getName()` inside the discovery receiver could crash the app without `BLUETOOTH_CONNECT`.
  - `stopDiscovery()` did not stop anything; it now cancels Bluetooth discovery and completes `startDiscovery()` with the printers found so far.
  - `disconnect()` closed the connection on the UI thread while a print could be using it.
  - A failed status query after a label aborted the whole batch.
  - Status / info / connect opened a second Bluetooth link while one was still open.
  - MAC addresses are validated and normalised (`AC3FA4123456` and `AC-3F-...` accepted).
- **Deprecated**: `BluetoothManager.connect()` / `disconnect()` (they hold the printer's only link), `PrintLockedException`, `forceReleasePrintLock()`, `CalibrationType.zplCommand`.
- **Android**: `compileSdk 36`, AGP 8.7.3, Jackson 2.17.2; location permissions limited to Android 11 and lower.
- **Repo / CI**: unit tests added; CI runs `flutter test` and builds the example APK (compiles the native code); generated / machine-specific files untracked.

## 0.4.3 - Improved README & GitHub CI 📚

- Rewrote README with step-by-step getting started guide, real-world code examples, architecture overview, and a full troubleshooting table.
- Added GitHub Actions CI workflow (`flutter analyze` + format check on push/PR).
- Added GitHub issue templates (Bug Report, Feature Request) and PR template.

## 0.4.2 - Internationalization & Bug Fixes 🌍

- **Bug Fix (Critical)**: `PrinterInfo.fromString` was parsing Turkish keys (`Seri No:`, `Dil:`) but Android was sending English keys (`Serial:`, `Language:`). `serialNumber` and `language` were always returning `"Unknown"`. Fixed to match actual Android output.
- **i18n**: All public-facing strings in `ZebraPrinterStatus`, `PrintProgress`, `PrintLockedException`, `safePrint`, and `safeBatchPrint` translated to English.
  - `statusMessage` / `statusMessagePlain` / `errorMessages` → English
  - `printerWarning` → English
  - `userMessage` in `PrintLockedException` → English
  - Progress messages (`Preparing printer...`, `Print timeout`, etc.) → English
- **Linter**: Fixed `unnecessary_brace_in_string_interps` in `toCompactString()`.

## 0.4.1 - README API Overview 📝

- Rewrote README with concise API tables for `PrinterManager` and `BluetoothManager`.
- Added quick start examples for callbacks, font upload, and lifecycle.
- Added Android-only platform note and troubleshooting table.

## 0.4.0 - Font Upload & Lifecycle Management 🎨

- **New Features**
  - **`uploadFont()` & `uploadFontData()`**: Upload custom fonts to Zebra printers from assets or raw byte data. Supports TTF, ZPL-wrapped TTF (~DY), and raw ZPL font commands via Zebra Link-OS `FontUtil` API.
  - **`reinitialize()`**: Re-enable `PrinterManager` and `BluetoothManager` after `dispose()` without creating a new instance.
  - **Disposed-state guards**: Both managers now throw `StateError` when used after disposal.

- **Stability Improvements**
  - Graceful `ExecutorService` shutdown in native Android layer (3–5s timeout with forced shutdown fallback).
  - Background connection closing to prevent UI blocking during cleanup.
  - Improved Bluetooth `dispose()`: unregisters both discovery and connection receivers.
  - Post-print status polling errors are handled gracefully (print may succeed even if connection drops).
  - Connection idle timeout reduced from 5s to 2s for faster resource release.
  - `open()` failure cleanup follows Zebra SDK best practices (always call `close()` to release Bluetooth socket resources).

- **Developer Experience**
  - Progress and error messages standardized to English.
  - Batch print progress message format simplified to `current/total`.

## 0.3.3 - Descriptive Timeout & Export Optimization 🚀

- Updated timeout error message in `PrinterManager` to be more descriptive and localized.
- Reorganized exports in `zebra_printer.dart` for better internal structure.

## 0.3.2 - Documentation & Formatting 🧹

- Shortened `README.md` usage examples to improve readability and refer to the full `example/` app.
- Internal formatting and minor linter fixes in `PrinterStatus` model.

## 0.3.1 - Documentation Updates 📝

- Updated `README.md` to reflect the latest installation version (`^0.3.0`).
- Updated the license year to 2025.

## 0.3.0 - Comprehensive Print Progress & Safe APIs 🛡️📈

- **New Features**
  - **`safePrint()` and `safeBatchPrint()`**: Replaced old print methods with lock-protected alternatives that prevent overlapping print jobs and provide seamless progress feedback.
  - **`onPrintProgress` Callback**: Real-time progress tracking `(0-100%)` for print jobs, complete with informative string messages and batch label counting.
  - **`ZebraPrinterStatus` & `getZebraPrinterStatus()`**: Replaced legacy `PrinterStatus` with an immensely comprehensive model (`ZebraPrinterStatus`) directly mapped from Zebra's Link-OS SDK, offering deep insights like `isReceiveBufferFull`, `isRibbonOut`, `isBusy`, `hasCriticalError`, and localized alert generation.
  - **Helper Methods**: Added `isPrinterBusy()`, `canPrint()`, and `waitForPrinterReady()` polling utilities to streamline job queues.

- **Breaking Changes**
  - `checkPrinterStatus` replaced with `getZebraPrinterStatus` (Returns `ZebraPrinterStatus` instead of `PrinterStatus`).
  - Deprecated generic `sendZpl` methods in favor of the newly robust `safePrint` and `safeBatchPrint`.

## 0.2.9 - Example App Enhancement & Logging 📱

- **Example App Improvements**
  - Added **Batch Print** demonstration to show high-performance printing of multiple labels.
  - Integrated `ZebraConfig` to show how to enable/disable internal package logging.
  - Improved UI feedback for connection and printing states.

- **Internal Changes**
  - Enabled internal logging by default in the example app for better developer onboarding.

## 0.2.8 - Batch Printing & Stability Focus ⚡

- **New Features**
  - **⚡ Batch Printing**: Added `sendMultipleZplToPrinter()` to print multiple labels in a single connection. This significantly improves performance for multi-label jobs.
  - **Native Batch Support**: Implemented `sendMultipleZplToPrinter` on Android with retry mechanism and robust connection handling.

- **Stability Improvements**
  - **Zebra Best Practices**: Refactored connection management to follow Zebra SDK best practices. Operations now explicitly open and close connections in `finally` blocks to ensure resource cleanup.
  - **Simplified Connection Logic**: Removed complex connection pooling in favor of a more stable "per-operation" connection model, preventing "Broken pipe" and socket issues.
  - **Retry Mechanism**: Added automatic retry for batch printing connections to handle transient Bluetooth issues.

## 0.2.7 - Documentation Cleanup 📝

- **Documentation Improvements**
  - Updated `README.md` credits and support information
  - Fixed broken GitHub issue links

## 0.2.6 - Connection Stability & Error Handling 🛡️

- **Android Stability Fixes**
  - **Strict Connection Pool Cleanup**: Improved reliability by explicitly removing broken or closed connections from the pool
  - **Better Error Recovery**: Automatically detects "Broken pipe" and other socket-related errors to trigger immediate connection cleanup
  - **Improved State Management**: `activeConnection` and `connectionPool` are now synchronized more strictly when connection issues occur
  - **Enhanced checkPrinterStatus**: Added thorough cleanup logic to handle stale connections during status checks

## 0.2.5 - Performance Optimization & New Features 🚀

- **New Features**
  - Added `ZebraConfig` for configurable logging (disabled by default)
  - Added `calibratePrinter()` method with `CalibrationType` (gap/bar) support
  - Added `DiscoveryType` enum for structured discovery mode selection

- **Android Performance & Stability ⚡**
  - **Asynchronous Connection Closing**: `connection.close()` now runs in a background thread to prevent UI blocking
  - **Optimized Timing**: Removed redundant `Thread.sleep()` calls, relying on Zebra SDK's internal management
  - **Enhanced Multi-Printer Support**: Improved connection pooling and tracking for multi-printer environments
  - **Memory Management**: Better cleanup of pending close operations

- **Internal Improvements**
  - Replaced all internal `print()` calls with `ZebraConfig.log()` for cleaner production console
  - Improved error handling and logging details in `getPrinterInfo()`
  - Exported new models and config in main entry point

- **Breaking Changes (Minor)**
  - `startDiscovery()` now takes `DiscoveryType` enum instead of `String` (optional, default: `DiscoveryType.both`)

## 0.2.4 - Connection Validity Check & Broken Pipe Fix 🛡️

- **Critical Bug Fix**
  - Fixed "Broken pipe" error when calling `sendZplToPrinter()` after `getPrinterInfo()`
  - Added connection validity check before reusing `activeConnection`
  - Resolved socket state corruption issue caused by SGD commands
- **Connection Management Improvements**
  - `activeConnection` now verified with `isConnected()` before use
  - Automatic connection recovery when active connection is closed
  - Stale connections automatically detected and recreated
  - All three methods now include connection validity checks:
    - `sendZplToPrinter()`
    - `getPrinterInfo()`
    - `checkPrinterStatus()`
- **Reliability Enhancements**
  - Connection state validation in all reuse scenarios
  - Graceful handling of closed connections
  - 10-second connection cache still active for fast reconnection
  - Better error logging for connection state issues
- **Technical Details**
  - Added `isConnected()` check before using `activeConnection`
  - Automatic cleanup of stale connection references
  - Connection state verified in real-time, not just by reference
  - Fast path (500ms) for recent connections still applies
- **Flow Example**
  ```
  connect() → getPrinterInfo() → sendZplToPrinter()
  ✅ No "Broken pipe" error
  ✅ Automatic recovery if connection breaks
  ✅ Fast execution with connection cache
  ```

**Upgrade Impact:** Non-breaking - Critical reliability fix for production use.

## 0.2.3 - Connection Reuse & Reliability Fix 🔧

- **Bug Fixes**
  - Fixed socket timeout error when calling `getPrinterInfo()` or `checkPrinterStatus()` after `connect()`
  - Resolved "read failed, socket might closed or timeout" error caused by multiple simultaneous connections
- **Performance Improvements**
  - `getPrinterInfo()` now reuses active connection when available (300ms vs 800ms)
  - `checkPrinterStatus()` now reuses active connection when available (300ms vs 800ms)
  - Reduced connection overhead for consecutive info/status queries
- **New Features**
  - Smart connection management: automatically detects and reuses active connections
  - Connection state awareness: methods intelligently switch between active and temporary connections
  - Enhanced logging for connection lifecycle debugging
- **Technical Details**
  - Added `shouldCloseConnection` flag to prevent closing active connections
  - Both `getPrinterInfo()` and `checkPrinterStatus()` now check for `activeConnection`
  - Temporary connections created only when no active connection exists or address differs
  - Connection stabilization: 300ms for active connections, 800ms for new connections
- **Developer Experience**
  - Detailed debug logging added (Dart + Android) for troubleshooting
  - Better error messages with full exception stack traces
  - Null-safe parameter validation

**Upgrade Impact:** Non-breaking - existing code continues to work with improved reliability and performance.

## 0.2.2 - PrinterInfo Model Update 🎯

- **Breaking Changes**
  - `getPrinterInfo()` now returns `PrinterInfo` model instead of raw `String`
- **New Features**
  - Added `PrinterInfo` model class with structured printer information
    - `model`: Printer model name (e.g., "ZD421")
    - `serialNumber`: Printer serial number
    - `firmware`: Firmware version
    - `language`: PrinterLanguage enum (ZPL/CPCL/Unknown)
    - `rawInfo`: Original raw string (for debugging)
  - Added `PrinterLanguage` enum for type-safe language detection
  - Type-safe access to printer information with autocomplete support
- **Improvements**
  - Better type safety for printer information
  - Improved example app with formatted printer info dialog
  - Added helper methods: `toCompactString()`, `toJson()`, `fromMap()`
- **Developer Experience**
  - IDE autocomplete support for printer info fields
  - Null-safe implementation
  - Clean, structured data instead of string parsing
  - Better testability

## 0.2.1 - Documentation Update 📝

- **Documentation Improvements**
  - Moved Architecture & SDK Usage section before Installation for better flow
  - Removed detailed method comparison tables for cleaner documentation
  - Removed API Reference section (redundant with inline documentation)
  - Kept essential comparison tables: Implementation Comparison + Why Two Managers
  - README.md reduced from 18 KB to 14 KB for faster loading

## 0.2.0 - Production Ready Release 🚀

### Major Features & Improvements

#### 🎯 PrinterManager - Full Zebra SDK Integration

- **Discovery System**
  - Renamed `discoverPrinters()` → `startDiscovery()` for clarity
  - Added Zebra SDK-powered discovery (`BluetoothDiscoverer`, `NetworkDiscoverer`)
  - Supports 'bluetooth', 'network', and 'both' discovery modes
  - Real-time callbacks: `onPrinterFound`, `onDiscoveryFinished`
  - Discovery optimization to prevent premature finish callbacks

- **Connection Management**
  - `connect()` now returns `Future<bool>` (previously `Future<Map<String, dynamic>>`)
  - `disconnect()` now returns `Future<bool>` (previously `Future<String>`)
  - Added persistent connection support with `activeConnection` tracking
  - Enhanced `isConnected()` to check specific address or general status
  - Connection state monitoring via `onConnectionStateChanged` callback

- **New Methods**
  - `getPairedPrinters()` - Returns `Future<List<BluetoothDevice>>` (all paired devices)
  - `unpairPrinter(address)` - Removes device pairing via Android Bluetooth API
  - Uses Android API for pairing operations (Zebra SDK limitation)

- **Print Optimization** ⚡
  - **Smart Connection Handling**: Uses active connection when available, creates temporary otherwise
  - **Connection Caching**: 10-second cache for repeated prints to same printer
  - **First-Print Fix**: Robust readiness check with retry mechanism (3 attempts: 2s, 1s, 800ms)
  - **Fast Path**: 300ms delay for active connections, 500ms for cached connections
  - **Lightweight Verification**: Uses `SGD.GET("device.friendly_name")` instead of heavy `getCurrentStatus()`
  - Eliminated "socket closed or timeout" errors on first print
  - Optimized for rapid successive printing (warehouse/retail scenarios)

#### 🔧 BluetoothManager - Generic Bluetooth

- Added `unpairDevice(address)` method for consistency
- Automatic disconnection before unpairing
- Maintains 100% Android Bluetooth API implementation

#### 📱 Example App - Professional UI

- Dual-tab interface: "Zebra SDK" and "Bluetooth" tabs
- Shows discovered printers with type indicators (Bluetooth/Network)
- Paired device management with "Paired" button
- Unpair functionality with delete icon on each device
- Connection status indicators (green checkmark)
- Modern Material Design 3 with color-coded sections
- Comprehensive button set: Discover, Paired, Print, Status, Info, Disconnect

### Android Native Improvements

#### PrinterManager.java

- **Architecture**: 99% Zebra Link-OS SDK, 1% Android API (for pairing only)
- Added `Context`, `Handler`, `MethodChannel` for better lifecycle management
- Implemented `ExecutorService` for background operations
- Connection caching with `lastConnectionTime` map (10s duration)
- Multi-retry connection readiness check in `sendZplToPrinter()`
- All methods use Zebra SDK except `getPairedPrinters()` and `unpairPrinter()`

#### BluetoothManager.java

- **Architecture**: 100% Android Bluetooth API
- Added `unpairDevice()` method using reflection
- Automatic disconnection before unpair operation

#### Dependencies & Permissions

- Added Jackson dependencies: `jackson-core`, `jackson-databind`, `jackson-annotations`
- Added Apache Commons: `commons-lang3`
- Network permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
- Updated Bluetooth permissions for Android 12+ compatibility
- NDK version set to `27.0.12077973`

### Documentation 📚

#### README.md

- **New Section**: "🏗️ Architecture & SDK Usage" with detailed comparison tables
- **Android Native Implementation Comparison** table
- **Detailed Method Comparison** for both managers
- Shows which methods use Zebra SDK vs Android Bluetooth API
- Explains why certain methods must use Android API
- Updated all API method signatures
- Added comprehensive code examples
- ZPL examples for barcodes, QR codes, receipts

#### New Files

- API comparison tables integrated into README
- All method signatures updated to reflect v0.2.0 changes

### Breaking Changes ⚠️

1. **Method Renames**
   - `discoverPrinters()` → `startDiscovery()`

2. **Return Type Changes**
   - `connect()`: `Future<Map<String, dynamic>>` → `Future<bool>`
   - `disconnect()`: `Future<String>` → `Future<bool>`
   - `getPairedPrinters()`: `Future<List<Map<String, dynamic>>>` → `Future<List<BluetoothDevice>>`

3. **Method Behavior Changes**
   - `sendZplToPrinter()`: Now uses active connection when available, creates temporary otherwise
   - `disconnect()`: Now accepts optional address parameter

### Bug Fixes 🐛

- **Fixed**: "socket closed or timeout" error on first print after connect
- **Fixed**: Connection readiness timing issues
- **Fixed**: Discovery callbacks firing prematurely with "both" mode
- **Fixed**: Missing imports (`PrinterStatus`, Jackson classes)
- **Fixed**: Thread.sleep() unreported exceptions
- **Fixed**: NDK version mismatch warnings

### Performance Enhancements 🚀

- **10x faster** successive prints with connection caching (10s window)
- **Active connection reuse** reduces latency from ~2s to ~300ms
- **Smart retry logic** ensures first print reliability
- **Lightweight status checks** using SGD instead of full printer status

### Testing & Quality

- Comprehensive debug logging added throughout
- Example app demonstrates all features
- Tested with rapid successive printing scenarios
- Connection pooling verified for warehouse environments

## 0.1.9

- Removed iOS platform support
- Package now supports Android platform only
- Cleaned up iOS-related code and dependencies
- Translated example app to English
- Updated documentation to reflect Android-only support

## 0.1.8

- Previous iOS-related changes (now removed)

## 0.1.7

- Translated documentation to English
- Updated package documentation

## 0.1.6

- Package update
- Performance improvements

## 0.1.5

- Added missing files
- Updated configuration files

## 0.1.4

- Improved code format in example app

## 0.1.3

- Added example app
- Improved package structure

## 0.1.2

- Updated README file
- Added pub.dev badges
- Updated version information

## 0.1.1

- Updated GitHub repository information
- Fixed package name (com.example -> com.sameetdmr)

## 0.1.0

- Initial release
- Core features:
  - Scan and discover Bluetooth devices
  - Pair and unpair with Bluetooth devices
  - Connect and disconnect from Zebra printers
  - Send ZPL code to printers
  - Check printer status
  - Get printer information
- Enum usage:
  - BluetoothDeviceType
  - BluetoothBondState
  - BluetoothConnectionState
  - BluetoothScanState
  - PrinterConnectionState
  - PaperState
  - HeadState
  - PauseState
- Advanced Bluetooth connection management
- Zebra Link-OS SDK integration for Android
