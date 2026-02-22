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
  zebra_printer: latest_version
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

For a complete, production-ready implementation with UI, progress tracking and batch printing, please check the **[example folder](https://github.com/Sameetdmr/zebra_printer/tree/main/example)**.

### Quick Overview

```dart
import 'package:zebra_printer/zebra_printer.dart';

final manager = PrinterManager();

// 1. Discover Printers
await manager.startDiscovery(type: DiscoveryType.bluetooth);

// 2. Safe Print (built-in lock & progress)
await manager.safePrint('AC:3F:A4:XX:XX:XX', '^XA^FO50,50^FDTest Label^FS^XZ');

// 3. Batch Print
await manager.safeBatchPrint('AC:3F:A4:XX:XX:XX', ['^XA^FDLabel 1^XZ', '^XA^FDLabel 2^XZ']);

// 4. Status Check
final status = await manager.getZebraPrinterStatus('AC:3F:A4:XX:XX:XX');
if (status.isReadyToPrint) print('Printer is ready!');
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
