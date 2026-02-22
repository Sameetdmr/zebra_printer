# Zebra Printer 🦓

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A robust Flutter package for **Zebra Link-OS™ Printers**. Unlike generic Bluetooth plugins, this package leverages the official **Zebra SDK** to provide professional-grade discovery, status monitoring, and high-performance printing.

## 🚀 Key Features

- 🔍 **Professional Discovery**: Find Zebra printers via **Bluetooth** and **Network** using official Zebra SDK discovery algorithms.
- ⚡ **Batch Printing**: Print multiple labels in a single connection session—optimized for high-volume tasks.
- 📊 **Real-time Status**: Monitor paper out, head open, and pause states before you print.
- ℹ️ **Device Intelligence**: Fetch model names, serial numbers, and firmware versions automatically.
- 🛡️ **SDK-Powered Stability**: Built on Zebra Link-OS™ SDK for maximum compatibility and reliability.
- 🎯 **Type-Safe Models**: Modern Dart API with structured models for all printer data.

---

## 🏗️ Architecture

This package offers two managers to suit your needs:

1.  **PrinterManager (Recommended)**: The core of the package. Uses the **Zebra Link-OS™ SDK**. Perfect for high-reliability production environments and detailed printer management.
2.  **BluetoothManager**: A lightweight utility for generic Bluetooth operations (scanning, pairing) using the standard Android API.

---

## 📦 Installation

Add the package to your `pubspec.yaml`:

```yaml
dependencies:
  zebra_printer: ^0.3.0
```

### Android Configuration

Requires Android 5.0 (API 21) or higher. Add permissions to `AndroidManifest.xml`:

```xml
<!-- Bluetooth & Network Permissions -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 🛠️ Usage

### Quick Start

```dart
import 'package:zebra_printer/zebra_printer.dart';

final printerManager = PrinterManager();

// 1. Discover
await printerManager.startDiscovery(type: DiscoveryType.bluetooth);
printerManager.onPrinterFound = (printer) => print('Found: ${printer.friendlyName}');
```

### Basic Print & Progress Tracking

The package provides a locking mechanism (`safePrint` and `safeBatchPrint`) to prevent concurrent print jobs, along with detailed progress callbacks.

```dart
// Listen to print progress
printerManager.onPrintProgress = (progress) {
  print('Progress: ${progress.percentage}% - ${progress.message}');
  if (progress.isBatch) {
    print('Label ${progress.currentLabel} of ${progress.totalLabels}');
  }
};

// Single label print (Safe)
await printerManager.safePrint('AC:3F:A4:XX:XX:XX', '^XA^FO50,50^A0N,50,50^FDTest Label^FS^XZ');
```

### Batch Printing

Print multiple labels in a single, continuous connection sequence using `safeBatchPrint`. This is much faster and more reliable than printing them one by one.

```dart
List<String> labels = [
  '^XA^FO50,50^A0N,50,50^FDLabel 1^FS^XZ',
  '^XA^FO50,50^A0N,50,50^FDLabel 2^FS^XZ',
  '^XA^FO50,50^A0N,50,50^FDLabel 3^FS^XZ',
];

// Prevents overlaps and shows batch progress
await printerManager.safeBatchPrint('AC:3F:A4:XX:XX:XX', labels);
```

### Detailed Status & Info

```dart
// Check comprehensive printer status
ZebraPrinterStatus status = await printerManager.getZebraPrinterStatus('AC:3F:A4:XX:XX:XX');

if (status.isReadyToPrint) {
  print('Printer is ready!');
} else if (status.hasError) {
  print('Status: ${status.statusMessagePlain}');
  print('Paper Out: ${status.isPaperOut}');
  print('Head Open: ${status.isHeadOpen}');
}

// Check if printer is busy
bool isBusy = await printerManager.isPrinterBusy('AC:3F:A4:XX:XX:XX');

// Wait for printer to be ready
ZebraPrinterStatus finalStatus = await printerManager.waitForPrinterReady('AC:3F:A4:XX:XX:XX', timeout: Duration(seconds: 5));

// Get device details
PrinterInfo info = await printerManager.getPrinterInfo('AC:3F:A4:XX:XX:XX');
print('Printing to ${info.model} (S/N: ${info.serialNumber})');

// Manage logging (Dart & Native)
await printerManager.enableLogging(); // Turn on debug logs
```

---

## 🔧 Troubleshooting

- **Printer Not Found**: Ensure Bluetooth and Location services are ON. Grant permissions in system settings.
- **Connection Failed**: Ensure the printer isn't connected to another device. Zebra Bluetooth printers usually support only one active connection.
- **Print Quality**: Use `calibratePrinter(address, CalibrationType.gap)` if your labels aren't aligned.

---

## 📑 ZPL Resource Tips

- Use the [Zebra Labelary Viewer](http://labelary.com/viewer.html) to test your ZPL code.
- Always wrap your code with `^XA` and `^XZ`.

## 📜 Credits

- Powered by [Zebra Link-OS™ SDK](https://www.zebra.com/us/en/support-downloads/software/developer-tools/link-os-sdk.html)
- Developed and maintained by [Samed Demir](https://github.com/Sameetdmr)

## ⚖️ License

MIT License - Copyright (c) 2025 Samed Demir
