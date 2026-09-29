# zebra_printer 🦓

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![pub points](https://img.shields.io/pub/points/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-android-green.svg)](https://pub.dev/packages/zebra_printer)

A Flutter plugin for **Zebra Label Printers** — built on the official **Zebra Link-OS™ SDK**.

Unlike generic Bluetooth plugins, this package communicates with Zebra printers the right way: through the SDK that Zebra itself provides. That means reliable discovery, real printer status (paper out, head open, ribbon out), and optimized printing for high-volume label workflows.

> **Android only** — iOS is not supported by the Zebra Link-OS SDK for Bluetooth printing.

---

## What can you do with this package?

| Task | How |
|---|---|
| Find nearby Zebra printers | `startDiscovery()` |
| Print a ZPL label | `safePrint(address, zpl)` |
| Print 100 labels at once | `safeBatchPrint(address, zplList)` |
| Check if printer is ready | `getZebraPrinterStatus(address)` |
| Get model, serial, firmware | `getPrinterInfo(address)` |
| Upload a custom font | `uploadFont(address, assetPath)` |
| Pair / unpair devices | `pairDevice()` / `unpairPrinter()` |
| Track print progress (0–100%) | `onPrintProgress` callback |

---

## Installation

```yaml
dependencies:
  zebra_printer: ^0.4.3
```

### Android Permissions

Add these to your `android/app/src/main/AndroidManifest.xml`, **before** the `<application>` tag:

```xml
<!-- Required for Bluetooth on Android ≤ 11 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- Required for Bluetooth on Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />

<!-- Required for Bluetooth discovery -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- Required for Network discovery -->
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Quick Start

### 1 — Create the manager

```dart
import 'package:zebra_printer/zebra_printer.dart';

final printer = PrinterManager(); // singleton
```

### 2 — Discover printers

```dart
// Set callbacks before starting discovery
printer.onPrinterFound = (p) {
  print('Found: ${p.friendlyName} (${p.address})');
};

printer.onDiscoveryFinished = (printers) {
  print('Discovery done. ${printers.length} printer(s) found.');
};

// Start Bluetooth discovery
await printer.startDiscovery(type: DiscoveryType.bluetooth);
```

### 3 — Print a label

```dart
// safePrint handles locking, retries, and progress tracking automatically
await printer.safePrint(
  'AC:3F:A4:XX:XX:XX',
  '^XA^FO50,50^A0N,50,50^FDHello Zebra!^FS^XZ',
);
```

### 4 — Track progress

```dart
printer.onPrintProgress = (progress) {
  print('${progress.percentage}% — ${progress.state}');

  if (progress.hasError) {
    print('Error: ${progress.errorMessage}');
  }

  if (progress.hasPrinterIssue) {
    print('Warning: ${progress.printerWarning}');
  }
};
```

### 5 — Batch print (multiple labels, one connection)

```dart
final labels = List.generate(
  10,
  (i) => '^XA^FO50,50^A0N,50,50^FDLabel ${i + 1}^FS^XZ',
);

await printer.safeBatchPrint('AC:3F:A4:XX:XX:XX', labels);
```

---

## Printer Status

Before printing, you can check if the printer is ready:

```dart
final status = await printer.getZebraPrinterStatus('AC:3F:A4:XX:XX:XX');

if (status.isReadyToPrint) {
  print('Ready to print!');
} else {
  print(status.statusMessage); // e.g. "🔴 Paper out!"
}

// Individual status flags
status.isPaperOut          // paper empty
status.isHeadOpen          // lid is open
status.isRibbonOut         // ribbon empty (thermal transfer)
status.isHeadCold          // head warming up
status.isHeadTooHot        // head cooling down
status.isReceiveBufferFull // printer is busy
status.labelsRemainingInBatch // labels left in current batch

// Helper getters
status.canPrint            // all-clear: ready & no errors
status.isBusy              // currently printing or has queued jobs
status.hasCriticalError    // paper out / head open / ribbon out
```

You can also poll until the printer is ready:

```dart
final status = await printer.waitForPrinterReady(
  'AC:3F:A4:XX:XX:XX',
  timeout: Duration(seconds: 15),
);
```

---

## Printer Info

```dart
final info = await printer.getPrinterInfo('AC:3F:A4:XX:XX:XX');

print(info.model);        // e.g. "ZD421"
print(info.serialNumber); // e.g. "D3J185400123"
print(info.firmware);     // e.g. "V84.20.11Z"
print(info.language);     // PrinterLanguage.zpl
```

---

## Font Upload

Upload a custom TTF font to the printer's flash memory:

```dart
// From assets (add font file to pubspec.yaml assets first)
await printer.uploadFont(
  'AC:3F:A4:XX:XX:XX',
  'assets/fonts/arial.zpl', // ZPL-wrapped TTF or raw TTF
);

// From raw bytes
await printer.uploadFontData('AC:3F:A4:XX:XX:XX', fontBytes);
```

Then use the font in ZPL:

```
^XA^A@N,50,50,E:ARIAL.TTF^FO50,50^FDCustom Font^FS^XZ
```

---

## Pairing Management

```dart
// List currently paired Zebra printers
final paired = await printer.getPairedPrinters();

// Remove a pairing
await printer.unpairPrinter('AC:3F:A4:XX:XX:XX');
```

---

## Architecture

This package provides two managers:

```
zebra_printer
├── PrinterManager      ← Use this for everything Zebra-related
│   ├── Discovery       (Zebra SDK: BluetoothDiscoverer)
│   ├── Printing        (Zebra SDK: connection.write + status polling)
│   ├── Status          (Zebra SDK: printer.getCurrentStatus())
│   ├── Info            (Zebra SDK: SGD.GET commands)
│   └── Font Upload     (Zebra SDK: ZebraPrinterLinkOs.downloadTtfFont)
│
└── BluetoothManager    ← Generic Bluetooth operations only
    ├── Device scanning (Android Bluetooth API)
    ├── Pairing         (Android Bluetooth API)
    └── Raw connection  (Android BluetoothSocket)
```

`PrinterManager` uses the official Zebra Link-OS™ SDK for all printer operations.
`BluetoothManager` uses the standard Android Bluetooth API for generic device management.

---

## Lifecycle

Both managers are **singletons**. Call `dispose()` when you no longer need them (e.g. in your widget's `dispose()`), and `reinitialize()` if you need to reuse them later:

```dart
@override
void dispose() {
  printer.dispose();
  super.dispose();
}

// If you need to reuse:
printer.reinitialize();
```

---

## Calibration & Test Print

```dart
// Fix misaligned labels
await printer.calibratePrinter(address, CalibrationType.gap);  // gap labels
await printer.calibratePrinter(address, CalibrationType.bar);  // black-mark labels

// Print a built-in test label
await printer.printTestLabel(address);
```

---

## Logging

```dart
// Enable logs (useful during development)
await printer.enableLogging();

// Disable logs (recommended for production)
await printer.disableLogging();
```

---

## Troubleshooting

| Problem | Solution |
|---|---|
| **Printer not found** | Make sure Bluetooth is ON, Location is enabled, and permissions are granted |
| **Discovery finds nothing** | The printer might already be paired — tap "Paired" to list bonded devices |
| **Connection fails** | Zebra Bluetooth printers support only one active connection. Disconnect other devices first |
| **Labels are misaligned** | Run `calibratePrinter(address, CalibrationType.gap)` |
| **ZPL not printing correctly** | Preview your ZPL at [Labelary Viewer](http://labelary.com/viewer.html) |
| **`safePrint` throws `PrintLockedException`** | A previous print is still in progress. Wait or call `forceReleasePrintLock()` |

---

## ZPL Resources

- [Labelary Online Viewer](http://labelary.com/viewer.html) — test ZPL in the browser
- [Zebra ZPL Reference](https://www.zebra.com/content/dam/zebra/manuals/printers/common/programming/zpl-zbi2-pm-en.pdf) — complete ZPL command reference
- Always wrap ZPL with `^XA` … `^XZ`

---

## Example App

A full working example with UI, discovery, printing, batch printing, status checking, and printer info is available in the [`example/`](https://github.com/Sameetdmr/zebra_printer/tree/main/example) folder.

---

## License

MIT License — Copyright © 2025 [Samed Demir](https://github.com/Sameetdmr)

Powered by [Zebra Link-OS™ SDK](https://www.zebra.com/us/en/support-downloads/software/developer-tools/link-os-sdk.html)
