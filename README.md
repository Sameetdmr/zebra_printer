# Zebra Printer 🦓

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Flutter package for **Zebra Link-OS™ Printers**. Uses the official **Zebra SDK** for discovery, status monitoring, ZPL printing, and printer management.

> **Platform:** Android only (API 21+)

---

## Features

- Bluetooth & Network discovery via Zebra SDK
- Safe single & batch printing with lock & progress tracking
- Full printer status (`ZebraPrinterStatus`) and device info (`PrinterInfo`)
- Font upload to printer flash (`uploadFont`, `uploadFontData`)
- Generic Bluetooth pairing via `BluetoothManager`

---

## Installation

```yaml
dependencies:
  zebra_printer: ^0.4.1
```

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## API Overview

### PrinterManager — Zebra SDK

| Method | Description |
|---|---|
| `startDiscovery(type)` | Find printers (`bluetooth` / `network` / `both`) |
| `stopDiscovery()` | Stop active discovery |
| `connect(address)` | Open persistent connection |
| `disconnect({address})` | Close connection |
| `isConnected({address})` | Check connection state |
| `safePrint(address, zpl)` | Print with lock & progress |
| `safeBatchPrint(address, zplList)` | Batch print in one session |
| `getZebraPrinterStatus(address)` | Full status (`isReadyToPrint`, paper out, etc.) |
| `getPrinterInfo(address)` | Model, serial, firmware |
| `canPrint(address)` | Quick readiness check |
| `isPrinterBusy(address)` | Check if printer is busy |
| `waitForPrinterReady(address)` | Poll until ready or timeout |
| `calibratePrinter(address, type)` | Gap/bar calibration |
| `printTestLabel(address)` | Send built-in test label |
| `uploadFont(address, assetPath)` | Upload font from assets |
| `uploadFontData(address, bytes)` | Upload font from raw bytes |
| `getPairedPrinters()` | List paired Bluetooth devices |
| `unpairPrinter(address)` | Remove device pairing |
| `dispose()` / `reinitialize()` | Lifecycle management |

**Callbacks:** `onPrinterFound`, `onDiscoveryFinished`, `onConnectionStateChanged`, `onPrintProgress`

### BluetoothManager — Android Bluetooth API

| Method | Description |
|---|---|
| `startDiscovery()` / `stopDiscovery()` | Scan for devices |
| `getBondedDevices()` | List paired devices |
| `pairDevice(address)` / `unpairDevice(address)` | Pairing management |
| `connect(address)` / `disconnect()` | Generic BT connection |
| `isBluetoothEnabled()` | Check BT state |
| `dispose()` / `reinitialize()` | Lifecycle management |

**Streams:** `onDeviceFound`, `onDiscoveryFinished`, `onConnectionStateChanged`, `onScanStateChanged`

---

## Quick Start

```dart
import 'package:zebra_printer/zebra_printer.dart';

final printer = PrinterManager();

// Callbacks
printer.onPrinterFound = (p) => print('Found: ${p.name}');
printer.onPrintProgress = (p) => print('${p.percentage}% — ${p.message}');

// Discover & print
await printer.startDiscovery(type: DiscoveryType.bluetooth);
await printer.safePrint('AC:3F:A4:XX:XX:XX', '^XA^FO50,50^FDTest^FS^XZ');

// Status & info
final status = await printer.getZebraPrinterStatus('AC:3F:A4:XX:XX:XX');
final info = await printer.getPrinterInfo('AC:3F:A4:XX:XX:XX');

// Font upload (0.4.0+)
await printer.uploadFont('AC:3F:A4:XX:XX:XX', 'assets/fonts/arial.zpl');

// Cleanup
printer.dispose();
```

Full UI example with progress tracking: **[example/](https://github.com/Sameetdmr/zebra_printer/tree/main/example)**

---

## Troubleshooting

| Issue | Solution |
|---|---|
| Printer not found | Enable Bluetooth & Location, grant permissions |
| Connection failed | Printer may be connected to another device |
| Misaligned labels | `calibratePrinter(address, CalibrationType.gap)` |
| ZPL preview | [Labelary Viewer](http://labelary.com/viewer.html) — wrap with `^XA` / `^XZ` |

---

## License

MIT — Copyright (c) 2025 [Samed Demir](https://github.com/Sameetdmr) · Powered by [Zebra Link-OS™ SDK](https://www.zebra.com/us/en/support-downloads/software/developer-tools/link-os-sdk.html)
