# Migrating from 0.4.x to 0.5 / 0.6

Version 0.5 reworked the package for printers shared by many devices. Most apps only need the first two points.

## 1. Errors are typed

Printer calls now throw `ZebraPrinterException` (it still `implements Exception`, so existing `catch (e)` blocks keep working).

```dart
// Before
try {
  await printer.safePrint(mac, zpl);
} catch (e) {
  showError(e.toString()); // "Exception: Print Error (PRINT_FAIL): ..."
}

// After
try {
  await printer.safePrint(mac, zpl);
} on ZebraPrinterException catch (e) {
  showError(e.code.name); // printerBusy, paperOut, ...
}
```

## 2. No more `PrintLockedException`

A second `safePrint` / `safeBatchPrint` for the same printer is **queued** instead of throwing `PrintLockedException`.

- `forceReleasePrintLock()` is deprecated: use `cancelPendingJobs()`.
- `isPrinterLocked(address)` is true while a job runs or waits in the queue.

## 3. The connection is released right after each job

0.4.x kept the Bluetooth connection open for 2 seconds after a print. Now it is closed after a 400 ms drain delay so other devices can print. If the printer is used by this device only and you print in quick bursts, restore the old behaviour:

```dart
printer.defaultOptions = PrintOptions.dedicated; // keepAlive: 2 s
```

## 4. Other changes

| 0.4.x | 0.5 / 0.6 |
|---|---|
| `connect()` "maintains the connection" | Checks the printer is reachable and releases the connection |
| `BluetoothManager.connect()` / `disconnect()` | Unchanged. Don't hold this connection to a printer that `PrinterManager` prints to |
| `calibratePrinter()` also set `wlan.country_code` to "turkey" | Only sets the media type and sensor mode, calibrates and saves |
| `CalibrationType.zplCommand` | Deprecated, use `command` |
| `startDiscovery()` default `DiscoveryType.both` | Default `DiscoveryType.bluetooth` (`both` runs Bluetooth discovery as before) |
| `getPrinterInfo()` | Same `PrinterInfo`, plus `linkOsVersion` and `isProtectedMode` |
| Status errors as text only | `ZebraPrinterStatus.errorCode` |
| Progress states | New `PrintState.waiting` while another device uses the printer |

Font upload keeps the same printer path (`E:<NAME>`), so existing label templates do not change.
