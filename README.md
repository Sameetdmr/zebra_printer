# zebra_printer 🦓

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![pub points](https://img.shields.io/pub/points/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-android-green.svg)](https://pub.dev/packages/zebra_printer)

A Flutter plugin for **Zebra label printers over Bluetooth**, built on the official **Zebra Link-OS™ SDK** (Android build 2.17.5585).

It is designed for a **shared printer**: one Bluetooth printer used by many handheld devices. Every print connects, sends the job and **releases the printer right away**, so the next device can use it. If the printer is busy, the call waits for its turn instead of failing.

> **Android only.** iOS support is planned (the Zebra iOS SDK supports MFi Bluetooth) but not implemented yet.

---

## What can you do with this package?

| Task | How |
|---|---|
| Find nearby Zebra printers | `startDiscovery()` |
| Print a ZPL label | `safePrint(address, zpl)` |
| Print many labels in one session | `safeBatchPrint(address, zplList)` |
| Print an image (PNG/JPG) | `printImage(address, bytes)` |
| Send raw commands (SGD, `~JC`, ...) | `sendCommand(address, command)` |
| Check if the printer is ready | `getZebraPrinterStatus(address)` |
| Model, serial, firmware, Link-OS, Protected Mode | `getPrinterInfo(address)` |
| Upload a custom font | `uploadFont(address, assetPath)` |
| Calibrate media | `calibratePrinter(address, CalibrationType.gap)` |
| Track progress and waiting | `onPrintProgress` |

---

## How a print works (shared printer)

Most Zebra printers accept **one Bluetooth link at a time**. With ten devices and one printer, each device must hold the link as briefly as possible:

```
safePrint()
  └─ queue (jobs from this app run one by one)
       └─ connect ──► printer busy? wait with back-off + jitter (up to 30 s)
            └─ status check on the same link (paper / head / ribbon / pause)
                 └─ send ZPL
                      └─ short drain delay (400 ms) ──► disconnect
```

- **No permanent connection.** Nothing in `PrinterManager` keeps the printer connected after a job (unless you opt in with `keepAlive`).
- **Busy printer = wait, not fail.** Retries use exponential back-off with random jitter so devices do not collide in lock-step. You get `PrintState.waiting` events while waiting.
- **Typed errors.** After the wait budget you get `ZebraPrinterErrorCode.printerBusy` (someone else is printing) or `printerUnreachable` (off / out of range), so you can tell the user what to do.
- **Queue inside the app.** A second `safePrint` while the first is running is queued, not rejected.

---

## Installation

```yaml
dependencies:
  zebra_printer: ^0.6.0
```

### Android permissions

The plugin's manifest already declares these and they are merged into your app. You still have to **request the runtime permissions** (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` on Android 12+, location on Android 11 and lower), e.g. with `permission_handler`.

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```

---

## Quick start

```dart
import 'package:zebra_printer/zebra_printer.dart';

final printer = PrinterManager(); // singleton
const mac = 'AC:3F:A4:12:34:56';  // "AC3FA4123456" also works

printer.onPrintProgress = (p) {
  if (p.isWaiting) {
    showStatus('Printer busy, waiting... (${p.waited?.inSeconds}s)');
  } else if (p.isCompleted) {
    showStatus('Printed');
  }
};

try {
  await printer.safePrint(mac, '^XA^FO50,50^A0N,50,50^FDHello Zebra!^FS^XZ');
} on ZebraPrinterException catch (e) {
  switch (e.code) {
    case ZebraPrinterErrorCode.printerBusy:
      showError('Printer is used by another device. Try again.');
    case ZebraPrinterErrorCode.printerUnreachable:
      showError('Printer is off or out of range.');
    case ZebraPrinterErrorCode.paperOut:
      showError('Load paper.');
    default:
      showError(e.message);
  }
}
```

### Batch print (one session for many labels)

```dart
final labels = List.generate(10, (i) => '^XA^FO50,50^A0N,50,50^FDLabel ${i + 1}^FS^XZ');
await printer.safeBatchPrint(mac, labels);
```

### Print an image

```dart
final bytes = (await rootBundle.load('assets/logo.png')).buffer.asUint8List();
await printer.printImage(mac, bytes, x: 20, y: 20, width: 300); // dots; 0 = original size
```

---

## Options (`PrintOptions`)

Every printer call takes an optional `options:`. Defaults are tuned for a shared printer; set `printer.defaultOptions` to change them app-wide.

| Option | Default | Meaning |
|---|---|---|
| `maxWait` | 30 s | Total time to wait for a busy/unreachable printer |
| `initialRetryDelay` / `maxRetryDelay` | 500 ms / 4 s | Back-off between connection attempts (doubles each time) |
| `jitter` | `true` | Randomise delays (50–100 %) so devices do not retry together |
| `drainDelay` | 400 ms | Pause after the last byte before disconnecting (prevents cut-off data) |
| `keepAlive` | `0` | Keep the link open after a job. **Leave at 0 for a shared printer** |
| `checkStatusBeforePrint` | `true` | Read status on the same link and fail fast on paper out / head open / ribbon out / paused |
| `waitForCompletion` | `false` | Keep the link until the printer finished the format (holds the printer longer) |
| `insecure` | `false` | Unbonded Bluetooth link: devices need not pair with the printer (see below) |

```dart
// Rush hour: wait up to a minute
await printer.safePrint(mac, zpl, options: const PrintOptions(maxWait: Duration(seconds: 60)));

// A printer used by this device only: reuse the link between quick prints
printer.defaultOptions = PrintOptions.dedicated; // keepAlive: 2 s
```

### Many devices without pairing each one (`insecure`)

By default every device must be paired (bonded) with the printer. With many devices that is tedious, and some printers keep a limited number of pairings. `insecure: true` uses an unbonded link instead. The printer must allow it:

```
! U1 setvar "bluetooth.minimum_security_mode" "1"
```

The link is not encrypted. Decide based on your security requirements.

---

## Error codes (`ZebraPrinterErrorCode`)

| Code | Meaning |
|---|---|
| `printerBusy` | Another device held the printer for the whole `maxWait` |
| `printerUnreachable` | Printer off, out of range or not answering |
| `notPaired` | Device is not paired with the printer (secure mode) |
| `permissionDenied` | Missing `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` |
| `bluetoothOff` / `bluetoothUnavailable` | Bluetooth off / no adapter |
| `connectionLost` | Link dropped while sending |
| `paperOut` / `headOpen` / `ribbonOut` / `printerPaused` | Printer needs attention (`e.needsAttention`) |
| `languageUnknown` | Printer language could not be detected (e.g. line_print mode) |
| `invalidArgument` / `unsupportedFormat` | Bad input (empty data, invalid MAC, unknown font/image format) |
| `cancelled` | Job removed from the queue with `cancelPendingJobs()` |

> Bluetooth cannot report "busy" directly. `printerBusy` vs `printerUnreachable` is inferred from how fast the connection is refused, so treat it as a strong hint.

`e.isTransient` is true for busy / unreachable / connection lost (retrying later makes sense).

---

## Printer status

```dart
final status = await printer.getZebraPrinterStatus(mac);

if (status.errorCode != null) {
  print('Could not read status: ${status.errorCode}'); // never throws
} else if (status.canPrint) {
  print('Ready');
} else {
  print(status.statusMessage); // e.g. "🔴 Paper out!"
}
```

Each status call opens a short Bluetooth session. On a shared printer **do not poll it in a loop** (and avoid `waitForPrinterReady` / `canPrint` before every print): `safePrint` already checks the status on its own link and waits for the printer.

---

## Printer info

```dart
final info = await printer.getPrinterInfo(mac);
info.model;           // "ZQ620"
info.serialNumber;    // "XXQLJ183600123"
info.firmware;        // "V85.20.19Z"
info.language;        // PrinterLanguage.zpl
info.linkOsVersion;   // "7.4" (null on non Link-OS printers)
info.isProtectedMode; // Link-OS Protected Mode (null if unknown)
```

---

## Fonts

```dart
// From Flutter assets (declare the file under flutter/assets in pubspec.yaml)
await printer.uploadFont(mac, 'assets/fonts/arial.zpl'); // ~DY file or raw TTF

// From bytes
await printer.uploadFontData(mac, fontBytes);
```

The font is stored as `E:<NAME>`, where NAME comes from the `~DYE:name` header of the file (fallback `FONT`):

```
^XA^A@N,50,50,E:ARIAL^FO50,50^FDCustom Font^FS^XZ
```

Raw TTF upload needs a Link-OS printer. For older printers (e.g. iMZ), upload a ZPL `~DY` font file.

---

## Calibration and raw commands

```dart
await printer.calibratePrinter(mac, CalibrationType.gap); // labels with gaps
await printer.calibratePrinter(mac, CalibrationType.bar); // black-mark media

// Any raw command, sent as-is (no ^XA/^XZ wrapping, no status check)
await printer.sendCommand(mac, '! U1 setvar "print.tone" "15"\r\n');
```

---

## Queue

```dart
printer.isPrinterLocked(mac);  // a job is running or waiting for this printer
printer.pendingJobCount(mac);  // jobs waiting behind the running one
printer.cancelPendingJobs(mac); // drop waiting jobs (running job continues)
```

---

## Discovery and pairing

```dart
printer.onPrinterFound = (p) => print('${p.friendlyName} ${p.address}');
final found = await printer.startDiscovery(); // Bluetooth
await printer.stopDiscovery();                // completes startDiscovery with what was found

final paired = await printer.getPairedPrinters();
await printer.unpairPrinter(mac);
```

Discover once and store the printer's MAC address. Do not run discovery before every print: Bluetooth inquiry is slow and disturbs other devices.

---

## Architecture

```
zebra_printer
├── PrinterManager          ← use this for printers
│   ├── PrintQueue          per-printer FIFO inside the app
│   └── native (Android)
│       ├── SharedConnectionManager  connect with back-off + jitter, drain, release
│       └── Zebra Link-OS SDK        BluetoothConnection(Insecure), PrinterStatus,
│                                    SGD, GraphicsUtil, FontUtil, BluetoothDiscoverer
└── BluetoothManager        ← generic Bluetooth: adapter state, scan, pair
```

`BluetoothManager.connect()` / `disconnect()` are **deprecated**: they open a raw socket and keep it, which blocks the printer for every other device.

---

## Field testing

The example app has a **Stress Test** screen. Run it on several devices at the same time against one printer: it shows how long each job held the printer, retries and error codes, which helps tune `drainDelay` and `maxWait` for your printer model.

---

## Lifecycle

Both managers are singletons. `dispose()` cancels queued jobs and detaches callbacks; only call it when the whole app is done printing. Use `reinitialize()` to use it again.

---

## Troubleshooting

| Problem | Solution |
|---|---|
| `printerBusy` often | Increase `maxWait`; check no app holds a permanent connection (e.g. old `BluetoothManager.connect()`) |
| `printerUnreachable` | Printer off, asleep or out of range; check the printer's Bluetooth is discoverable/connectable |
| `notPaired` | Pair the device in Android settings, or use `insecure: true` (see above) |
| Last part of a label missing | Increase `drainDelay` (e.g. 800 ms) |
| `languageUnknown` | Printer in line_print mode: `! U1 setvar "device.languages" "hybrid_xml_zpl"` |
| Labels misaligned | `calibratePrinter(mac, CalibrationType.gap)` |
| ZPL not printing correctly | Preview at [Labelary Viewer](http://labelary.com/viewer.html) |

---

## Migrating from 0.4.x

- `safePrint` no longer throws `PrintLockedException`; jobs are queued.
- Errors are `ZebraPrinterException` with a `code` (still `implements Exception`).
- The printer link is closed right after each job (0.4.x kept it 2 s). For the old behaviour use `PrintOptions.dedicated`.
- `connect()` only checks reachability and releases the link.
- `getPrinterInfo` returns more fields; the native side returns a map now.
- `calibratePrinter` no longer changes `wlan.country_code`.
- `DiscoveryType` defaults to `bluetooth`.

---

## License

MIT License — Copyright © 2025 [Samed Demir](https://github.com/Sameetdmr)

Powered by [Zebra Link-OS™ SDK](https://www.zebra.com/us/en/support-downloads/software/developer-tools/link-os-sdk.html)
