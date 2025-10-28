# Zebra Printer

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![Pub Version (including pre-releases)](https://img.shields.io/pub/v/zebra_printer?include_prereleases)](https://pub.dev/packages/zebra_printer)
[![pub publisher](https://img.shields.io/pub/publisher/zebra_printer)](https://pub.dev/packages/zebra_printer)
[![Pub Likes](https://img.shields.io/pub/likes/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![Pub Popularity](https://img.shields.io/pub/popularity/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![Pub Points](https://img.shields.io/pub/points/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Zebra yazıcılar için Flutter paketi. Bluetooth bağlantısı ve ZPL gönderimi için kullanılır. Zebra Link-OS SDK'sını kullanarak Zebra yazıcılarla iletişim kurmanızı sağlar.

## Özellikler

- Bluetooth cihazlarını tarama ve keşfetme
- Bluetooth cihazlarıyla eşleşme ve eşleşmeyi kaldırma
- Zebra yazıcılara bağlanma ve bağlantıyı kesme
- ZPL kodunu yazıcıya gönderme
- Yazıcı durumunu kontrol etme
- Yazıcı hakkında bilgi alma

## Kurulum

`pubspec.yaml` dosyanıza paketi ekleyin:

```yaml
dependencies:
  zebra_printer: ^0.1.1
```

### Android Kurulumu

Android için gerekli izinleri `android/app/src/main/AndroidManifest.xml` dosyasına ekleyin:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Android 12 ve üzeri için ek izinler gerekebilir. Detaylı bilgi için [Flutter Bluetooth Permissions](https://flutter.dev/docs/development/packages-and-plugins/plugin-api-migration#bluetooth-permissions) sayfasına bakabilirsiniz.

## Kullanım

### Temel Kullanım

```dart
import 'package:zebra_printer/zebra_printer.dart';

// Bluetooth yöneticisini başlat
final bluetoothManager = BluetoothManager();

// Yazıcı yöneticisini başlat
final printerManager = PrinterManager();

// Bluetooth'un açık olup olmadığını kontrol et
bool isEnabled = await bluetoothManager.isBluetoothEnabled();

// Eşleştirilmiş cihazları al
List<BluetoothDevice> bondedDevices = await bluetoothManager.getBondedDevices();

// Cihaz keşfini başlat
await bluetoothManager.startDiscovery();

// Cihaz keşfini durdur
await bluetoothManager.stopDiscovery();

// Cihaza bağlan
await bluetoothManager.connect('00:11:22:33:44:55');

// Bağlantıyı kes
await bluetoothManager.disconnect();

// ZPL kodu gönder
try {
  String result = await printerManager.sendZplToPrinter('00:11:22:33:44:55', '^XA^FO50,50^A0N,50,50^FDHello, World!^FS^XZ');
  print('Baskı başarılı: $result');
} catch (e) {
  print('Baskı hatası: $e');
}

// Test etiketi yazdır
try {
  String result = await printerManager.printTestLabel('00:11:22:33:44:55');
  print('Test baskısı başarılı: $result');
} catch (e) {
  print('Test baskısı hatası: $e');
}

// Yazıcı durumunu kontrol et
try {
  PrinterStatus status = await printerManager.checkPrinterStatus('00:11:22:33:44:55');
  print('Yazıcı durumu: $status');
} catch (e) {
  print('Durum kontrolü hatası: $e');
}
```

### Bluetooth Cihaz Keşfi ve Bağlantı Yönetimi

```dart
// Bluetooth Manager örneği oluştur
final bluetoothManager = BluetoothManager();

// Bluetooth durumunu dinle
bluetoothManager.onConnectionStateChanged.listen((state) {
  print('Bağlantı durumu değişti: $state');
  
  if (state == BluetoothConnectionState.connected) {
    print('Cihaza bağlandı: ${bluetoothManager.connectedDevice?.name}');
  }
});

// Tarama durumunu dinle
bluetoothManager.onScanStateChanged.listen((state) {
  print('Tarama durumu değişti: $state');
});

// Cihaz bulunduğunda bildirim al
bluetoothManager.onDeviceFound.listen((device) {
  print('Cihaz bulundu: ${device.name} (${device.address})');
});

// Tarama tamamlandığında bildirim al
bluetoothManager.onDiscoveryFinished.listen((_) {
  print('Tarama tamamlandı');
  print('Bulunan cihazlar: ${bluetoothManager.devices.length}');
});

// Taramayı başlat
await bluetoothManager.startDiscovery();

// Bir süre sonra taramayı durdur
await Future.delayed(Duration(seconds: 10));
await bluetoothManager.stopDiscovery();

// Bulunan cihazları listele
for (var device in bluetoothManager.devices) {
  print('${device.name} (${device.address}) - Tür: ${device.type}');
}

// Eşleştirilmiş cihazları listele
final bondedDevices = await bluetoothManager.getBondedDevices();
for (var device in bondedDevices) {
  print('Eşleştirilmiş: ${device.name} (${device.address})');
}

// Cihaza bağlan
final targetDevice = bluetoothManager.devices.firstWhere(
  (d) => d.name?.contains('Zebra') ?? false,
  orElse: () => null,
);

if (targetDevice != null) {
  await bluetoothManager.connect(targetDevice.address);
}

// Kaynakları temizle
bluetoothManager.dispose();
```

## Enum Kullanımı

Bu paket, daha okunabilir ve tip güvenli kod için integer değerler yerine enum'ları kullanır:

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

## ZPL Örnekleri

### Basit ZPL Örnekleri

```dart
// Basit bir etiket oluşturma
String createSimpleLabel(String text) {
  return """
^XA
^FO50,50
^A0N,50,50
^FD$text^FS
^XZ
""";
}

// Barkod içeren bir etiket oluşturma
String createBarcodeLabel(String barcode) {
  return """
^XA
^FO50,50
^A0N,30,30
^FDBarkod:^FS
^FO50,100
^BY3
^BCN,100,Y,N,N
^FD$barcode^FS
^XZ
""";
}

// QR kod içeren bir etiket oluşturma
String createQRCodeLabel(String data) {
  return """
^XA
^FO50,50
^A0N,30,30
^FDQR Kod:^FS
^FO50,100
^BQN,2,10
^FDMA,$data^FS
^XZ
""";
}
```

## Zebra Link-OS SDK

Bu paket, [Zebra Link-OS SDK](https://techdocs.zebra.com/link-os/2-14/android)'yı kullanır. Daha detaylı bilgi için Zebra'nın resmi dokümantasyonuna bakabilirsiniz.

## Lisans

Bu proje MIT lisansı altında lisanslanmıştır. Detaylar için [LICENSE](LICENSE) dosyasına bakabilirsiniz.