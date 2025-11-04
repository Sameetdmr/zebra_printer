# Zebra Printer

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![Pub Version (including pre-releases)](https://img.shields.io/pub/v/zebra_printer?include_prereleases)](https://pub.dev/packages/zebra_printer)
[![pub publisher](https://img.shields.io/pub/publisher/zebra_printer)](https://pub.dev/packages/zebra_printer)
[![Pub Likes](https://img.shields.io/pub/likes/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![Pub Popularity](https://img.shields.io/pub/popularity/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![Pub Points](https://img.shields.io/pub/points/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Flutter package for Zebra printers. Uses Zebra Link-OS SDK for Bluetooth connectivity and ZPL sending. Supports Android platform.

## Features

- Scan and discover Bluetooth devices
- Pair and unpair with Bluetooth devices
- Connect and disconnect from Zebra printers
- Send ZPL code to printers
- Check printer status
- Get printer information
- Android platform support

## Installation

Add the package to your `pubspec.yaml` file:

```yaml
dependencies:
  zebra_printer: latest_version
```

### Android Setup

Add the required permissions to your `android/app/src/main/AndroidManifest.xml` file:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

For Android 12 and above, additional permissions may be required. For more information, see [Flutter Bluetooth Permissions](https://flutter.dev/docs/development/packages-and-plugins/plugin-api-migration#bluetooth-permissions).


## Usage

### Basic Usage

```dart
import 'package:zebra_printer/zebra_printer.dart';

// Initialize Bluetooth manager
final bluetoothManager = BluetoothManager();

// Initialize printer manager
final printerManager = PrinterManager();

// Check if Bluetooth is enabled
bool isEnabled = await bluetoothManager.isBluetoothEnabled();

// Get paired devices
List<BluetoothDevice> bondedDevices = await bluetoothManager.getBondedDevices();

// Start device discovery
await bluetoothManager.startDiscovery();

// Stop device discovery
await bluetoothManager.stopDiscovery();

// Connect to a device
await bluetoothManager.connect('00:11:22:33:44:55');

// Disconnect from a device
await bluetoothManager.disconnect();

// Send ZPL code to printer
try {
  String result = await printerManager.sendZplToPrinter('00:11:22:33:44:55', '^XA^FO50,50^A0N,50,50^FDHello, World!^FS^XZ');
  print('Print successful: $result');
} catch (e) {
  print('Print error: $e');
}

// Print a test label
try {
  String result = await printerManager.printTestLabel('00:11:22:33:44:55');
  print('Test print successful: $result');
} catch (e) {
  print('Test print error: $e');
}

// Check printer status
try {
  PrinterStatus status = await printerManager.checkPrinterStatus('00:11:22:33:44:55');
  print('Printer status: $status');
} catch (e) {
  print('Status check error: $e');
}
```

### Bluetooth Device Discovery and Connection Management

```dart
// Create a Bluetooth Manager instance
final bluetoothManager = BluetoothManager();

// Listen for connection state changes
bluetoothManager.onConnectionStateChanged.listen((state) {
  print('Connection state changed: $state');
  
  if (state == BluetoothConnectionState.connected) {
    print('Connected to device: ${bluetoothManager.connectedDevice?.name}');
  }
});

// Listen for scan state changes
bluetoothManager.onScanStateChanged.listen((state) {
  print('Scan state changed: $state');
});

// Listen for device discovery
bluetoothManager.onDeviceFound.listen((device) {
  print('Device found: ${device.name} (${device.address})');
});

// Listen for discovery completion
bluetoothManager.onDiscoveryFinished.listen((_) {
  print('Discovery completed');
  print('Found devices: ${bluetoothManager.devices.length}');
});

// Start discovery
await bluetoothManager.startDiscovery();

// Wait for some time then stop discovery
await Future.delayed(Duration(seconds: 10));
await bluetoothManager.stopDiscovery();

// List found devices
for (var device in bluetoothManager.devices) {
  print('${device.name} (${device.address}) - Type: ${device.type}');
}

// List paired devices
final bondedDevices = await bluetoothManager.getBondedDevices();
for (var device in bondedDevices) {
  print('Paired: ${device.name} (${device.address})');
}

// Connect to a device
final targetDevice = bluetoothManager.devices.firstWhere(
  (d) => d.name?.contains('Zebra') ?? false,
  orElse: () => null,
);

if (targetDevice != null) {
  await bluetoothManager.connect(targetDevice.address);
}

// Clean up resources
bluetoothManager.dispose();
```

## Enum Usage

This package uses enums instead of integer values for better readability and type safety:

### BluetoothDeviceType

```dart
enum BluetoothDeviceType {
  unknown,  // 0
  classic,  // 1
  le,       // 2
  dual      // 3
}
```

### BluetoothBondState

```dart
enum BluetoothBondState {
  none,     // 10
  bonding,  // 11
  bonded    // 12
}
```

### BluetoothConnectionState

```dart
enum BluetoothConnectionState {
  disconnected,
  connecting,
  connected,
  disconnecting,
  error
}
```

### BluetoothScanState

```dart
enum BluetoothScanState {
  idle,
  starting,
  scanning,
  stopping
}
```

### PrinterConnectionState, PaperState, HeadState, PauseState

```dart
enum PrinterConnectionState {
  connected,
  disconnected
}

enum PaperState {
  present,
  out
}

enum HeadState {
  closed,
  open
}

enum PauseState {
  running,
  paused
}
```

## ZPL Examples

### Simple ZPL Examples

```dart
// Create a simple label
String createSimpleLabel(String text) {
  return """
^XA
^FO50,50
^A0N,50,50
^FD$text^FS
^XZ
""";
}

// Create a label with barcode
String createBarcodeLabel(String barcode) {
  return """
^XA
^FO50,50
^A0N,30,30
^FDBarcode:^FS
^FO50,100
^BY3
^BCN,100,Y,N,N
^FD$barcode^FS
^XZ
""";
}

// Create a label with QR code
String createQRCodeLabel(String data) {
  return """
^XA
^FO50,50
^A0N,30,30
^FDQR Code:^FS
^FO50,100
^BQN,2,10
^FDMA,$data^FS
^XZ
""";
}
```

## Zebra Link-OS SDK

This package uses the [Zebra Link-OS SDK](https://techdocs.zebra.com/link-os/2-14/android) for Android platform. For more detailed information, refer to Zebra's official documentation.

### Android Features

- Programmatic pairing and unpairing are supported
- Devices are identified by MAC address
- Detailed status information can be obtained through the Zebra SDK
- Both Bluetooth Classic and Bluetooth Low Energy are supported

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.