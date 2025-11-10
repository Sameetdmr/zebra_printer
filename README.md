# Zebra Printer

[![pub package](https://img.shields.io/pub/v/zebra_printer.svg)](https://pub.dev/packages/zebra_printer)
[![Pub Version (including pre-releases)](https://img.shields.io/pub/v/zebra_printer?include_prereleases)](https://pub.dev/packages/zebra_printer)
[![pub publisher](https://img.shields.io/pub/publisher/zebra_printer)](https://pub.dev/packages/zebra_printer)
[![Pub Likes](https://img.shields.io/pub/likes/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![Pub Popularity](https://img.shields.io/pub/popularity/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![Pub Points](https://img.shields.io/pub/points/zebra_printer)](https://pub.dev/packages/zebra_printer/score)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Flutter package for Zebra printers. Uses Zebra Link-OS SDK for Bluetooth and Network connectivity, ZPL printing, and printer management. Supports Android platform.

## Features

### Zebra-Specific Features (via PrinterManager - Recommended)
- 🔍 **Discover Zebra Printers** - Find printers via Bluetooth & Network using Zebra SDK
- 🔗 **Persistent Connection** - Connect once, print multiple times
- 📄 **Print ZPL Labels** - Send ZPL commands directly
- 📊 **Check Printer Status** - Get real-time printer status
- ℹ️ **Get Printer Info** - Retrieve printer details and firmware info

### Generic Bluetooth Features (via BluetoothManager - Optional)
- 📡 Scan and discover all Bluetooth devices
- 🔌 Pair and unpair with Bluetooth devices
- 🎯 Generic Bluetooth connectivity for non-Zebra devices

## 🏗️ Architecture & SDK Usage

### Android Native Implementation Comparison

| Feature | PrinterManager | BluetoothManager | Native SDK Used |
|---------|----------------|------------------|-----------------|
| **Discovery** | ✅ Zebra-specific discovery | ✅ All BT devices | **PrinterManager**: Zebra SDK (`BluetoothDiscoverer`, `NetworkDiscoverer`)<br>**BluetoothManager**: Android Bluetooth API |
| **Connection** | ✅ Persistent connection | ✅ Generic BT connection | **PrinterManager**: Zebra SDK (`BluetoothConnection`)<br>**BluetoothManager**: Android Bluetooth API (`BluetoothSocket`) |
| **Print (ZPL)** | ✅ Optimized for Zebra | ✅ Raw data send | **Both**: Zebra SDK (`Connection.write()`) |
| **Printer Status** | ✅ Full status info | ❌ Not available | **PrinterManager**: Zebra SDK (`SGD.GET()`) |
| **Printer Info** | ✅ Model, SN, firmware | ❌ Not available | **PrinterManager**: Zebra SDK (`SGD.GET()`, `ZebraPrinterFactory`) |
| **Get Paired Devices** | ✅ All paired BT devices | ✅ All paired BT devices | **Both**: Android Bluetooth API (`BluetoothAdapter.getBondedDevices()`) |
| **Unpair Device** | ✅ Remove pairing | ✅ Remove pairing | **Both**: Android Bluetooth API (Reflection: `device.removeBond()`) |
| **Connection Caching** | ✅ 10s cache for fast prints | ❌ No caching | **PrinterManager**: Custom implementation with Zebra SDK |
| **Language Detection** | ✅ ZPL/CPCL detection | ❌ Not available | **PrinterManager**: Zebra SDK (`printer.getPrinterControlLanguage()`) |

### Why Two Managers?

1. **PrinterManager (Recommended for Zebra)**: 
   - Uses **Zebra Link-OS SDK** for all printer-specific operations
   - Falls back to **Android Bluetooth API** only for operations not supported by Zebra SDK (pairing/unpairing)
   - Optimized for Zebra printers with connection pooling and status monitoring
   - Provides rich printer information and status

2. **BluetoothManager (Generic Bluetooth)**:
   - Uses **Android Bluetooth API** for generic Bluetooth operations
   - Suitable for non-Zebra devices or when you need raw Bluetooth control
   - Can discover and connect to any Bluetooth device
   - Simpler implementation for basic printing needs

## Installation

Add the package to your `pubspec.yaml` file:

```yaml
dependencies:
  zebra_printer: latest_version
```

### Android Setup

Add the required permissions to your `android/app/src/main/AndroidManifest.xml` file:

```xml
<!-- Bluetooth Permissions -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />

<!-- Location Permissions (Required for Bluetooth discovery on Android 12+) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Network Permissions (For network printer discovery) -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

**Important**: For Android 12+, you need to request runtime permissions for Bluetooth and Location.

## Usage

### 🎯 Recommended: Using PrinterManager (Zebra-Specific)

The `PrinterManager` uses Zebra Link-OS SDK and is the recommended approach for working with Zebra printers.

#### Basic Setup

```dart
import 'package:zebra_printer/zebra_printer.dart';

final printerManager = PrinterManager();
```

#### 1. Discover Zebra Printers

```dart
// Setup callbacks for real-time discovery updates
printerManager.onPrinterFound = (printer) {
  print('Found: ${printer.friendlyName} (${printer.address})');
};

printerManager.onDiscoveryFinished = (printers) {
  print('Discovery finished. Found ${printers.length} printers');
};

// Start discovery
try {
  // Options: 'bluetooth', 'network', or 'both'
  final printers = await printerManager.startDiscovery(type: 'both');
  
  for (var printer in printers) {
    print('${printer.friendlyName} - ${printer.type} - ${printer.address}');
  }
} catch (e) {
  print('Discovery error: $e');
}

// Stop discovery when done
await printerManager.stopDiscovery();
```

#### 2. Connect to Printer

```dart
// Connect to a discovered printer
try {
  final connectionInfo = await printerManager.connect('AC:3F:A4:XX:XX:XX');
  print('Connected: ${connectionInfo['friendlyName']}');
} catch (e) {
  print('Connection error: $e');
}

// Check connection status
bool connected = await printerManager.isConnected();
// Or check specific printer
bool connected = await printerManager.isConnected(address: 'AC:3F:A4:XX:XX:XX');
```

#### 3. Print Labels

```dart
// Print custom ZPL
String zpl = """
^XA
^FO50,50
^A0N,50,50
^FDHello from Flutter!^FS
^FO50,120
^A0N,30,30
^FDDate: ${DateTime.now()}^FS
^XZ
""";

try {
  String result = await printerManager.sendZplToPrinter(
    'AC:3F:A4:XX:XX:XX',
    zpl
  );
  print('Print successful: $result');
} catch (e) {
  print('Print error: $e');
}

// Or use the quick test print
await printerManager.printTestLabel('AC:3F:A4:XX:XX:XX');
```

#### 4. Check Printer Status

```dart
try {
  PrinterStatus status = await printerManager.checkPrinterStatus('AC:3F:A4:XX:XX:XX');
  
  print('Connected: ${status.isConnected}');
  print('Paper: ${status.isPaperOut ? "Out" : "OK"}');
  print('Head: ${status.isHeadOpen ? "Open" : "Closed"}');
  print('Paused: ${status.isPaused}');
} catch (e) {
  print('Status check error: $e');
}
```

#### 5. Get Printer Information

```dart
try {
  String info = await printerManager.getPrinterInfo('AC:3F:A4:XX:XX:XX');
  print('Printer Info: $info');
} catch (e) {
  print('Info error: $e');
}
```

#### 6. Disconnect

```dart
// Disconnect from specific printer
await printerManager.disconnect(address: 'AC:3F:A4:XX:XX:XX');

// Or disconnect from currently connected printer
await printerManager.disconnect();
```

#### Complete Example with Connection State Monitoring

```dart
class PrinterScreen extends StatefulWidget {
  @override
  _PrinterScreenState createState() => _PrinterScreenState();
}

class _PrinterScreenState extends State<PrinterScreen> {
  final printerManager = PrinterManager();
  List<DiscoveredPrinter> printers = [];
  DiscoveredPrinter? selectedPrinter;
  bool isConnected = false;
  
  @override
  void initState() {
    super.initState();
    
    // Listen for connection state changes
    printerManager.onConnectionStateChanged = (info) {
      setState(() {
        isConnected = info['isConnected'] ?? false;
      });
      print('Connection state: $isConnected');
    };
    
    // Listen for discovered printers
    printerManager.onPrinterFound = (printer) {
      setState(() {
        if (!printers.any((p) => p.address == printer.address)) {
          printers.add(printer);
        }
      });
    };
  }
  
  Future<void> discover() async {
    setState(() => printers.clear());
    await printerManager.startDiscovery(type: 'both');
  }
  
  Future<void> connect(DiscoveredPrinter printer) async {
    try {
      await printerManager.connect(printer.address);
      setState(() => selectedPrinter = printer);
    } catch (e) {
      print('Connection error: $e');
    }
  }
  
  Future<void> printTest() async {
    if (selectedPrinter != null) {
      await printerManager.printTestLabel(selectedPrinter!.address);
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Zebra Printer')),
      body: Column(
        children: [
          ElevatedButton(
            onPressed: discover,
            child: Text('Discover Printers'),
          ),
          if (isConnected) ...[
            ElevatedButton(
              onPressed: printTest,
              child: Text('Print Test'),
            ),
            ElevatedButton(
              onPressed: () => printerManager.disconnect(),
              child: Text('Disconnect'),
            ),
          ],
          Expanded(
            child: ListView.builder(
              itemCount: printers.length,
              itemBuilder: (context, index) {
                final printer = printers[index];
                return ListTile(
                  title: Text(printer.friendlyName),
                  subtitle: Text('${printer.type} - ${printer.address}'),
                  onTap: () => connect(printer),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
```

### 🔧 Alternative: Using BluetoothManager (Generic Bluetooth)

The `BluetoothManager` provides generic Android Bluetooth functionality for non-Zebra devices or when you need low-level Bluetooth control.

```dart
import 'package:zebra_printer/zebra_printer.dart';

final bluetoothManager = BluetoothManager();

// Check Bluetooth status
bool isEnabled = await bluetoothManager.isBluetoothEnabled();

// Get paired devices
List<BluetoothDevice> bondedDevices = await bluetoothManager.getBondedDevices();

// Listen for device discovery
bluetoothManager.onDeviceFound.listen((device) {
  print('Found: ${device.name} (${device.address})');
});

// Start scanning
await bluetoothManager.startDiscovery();

// Stop scanning
await bluetoothManager.stopDiscovery();

// Connect to device
await bluetoothManager.connect('00:11:22:33:44:55');

// Disconnect
await bluetoothManager.disconnect();

// Clean up
bluetoothManager.dispose();
```

## ZPL Examples

### Simple Label

```dart
String createSimpleLabel(String text) {
  return """
^XA
^FO50,50
^A0N,50,50
^FD$text^FS
^XZ
""";
}
```

### Barcode Label

```dart
String createBarcodeLabel(String barcode, String description) {
  return """
^XA
^FO50,50^A0N,30,30^FD$description^FS
^FO50,100^BY3^BCN,100,Y,N,N^FD$barcode^FS
^XZ
""";
}
```

### QR Code Label

```dart
String createQRCodeLabel(String data) {
  return """
^XA
^FO50,50^A0N,30,30^FDQR Code:^FS
^FO50,100^BQN,2,10^FDMA,$data^FS
^XZ
""";
}
```

### Multi-Line Receipt

```dart
String createReceipt(String storeName, List<String> items, String total) {
  String zpl = "^XA\n";
  zpl += "^FO50,50^A0N,40,40^FD$storeName^FS\n";
  
  int y = 100;
  for (var item in items) {
    zpl += "^FO50,$y^A0N,25,25^FD$item^FS\n";
    y += 35;
  }
  
  zpl += "^FO50,$y^GB350,2,2^FS\n";
  y += 20;
  zpl += "^FO50,$y^A0N,30,30^FDTotal: $total^FS\n";
  zpl += "^XZ";
  
  return zpl;
}
```

## Zebra Link-OS SDK

This package uses the [Zebra Link-OS SDK](https://techdocs.zebra.com/link-os/2-14/android) for Android platform. For more detailed information, refer to Zebra's official documentation.

### Supported Features
- Bluetooth Classic and Network connectivity
- Zebra printer discovery using SDK
- ZPL command execution
- Real-time printer status monitoring
- Persistent connections for multiple print jobs
- Printer information retrieval

## Troubleshooting

### Printer Not Found
1. Ensure Bluetooth is enabled on your device
2. Check Location permission is granted (required for Bluetooth discovery)
3. Make sure printer is powered on and in range
4. Try power cycling the printer
5. Check if printer is already paired with another device

### Connection Issues
1. Verify the MAC address is correct
2. Unpair and re-pair the device if needed
3. Check printer battery level
4. Ensure printer supports Bluetooth connectivity
5. Try restarting both devices

### Print Quality Issues
1. Verify ZPL commands are correct
2. Check printer paper and ribbon levels
3. Adjust print darkness settings via ZPL
4. Clean printer head if needed

## Example App

The package includes a comprehensive example app demonstrating:
- Zebra-specific printer discovery (SDK-based)
- Generic Bluetooth device scanning
- Connection management
- Printing examples
- Status monitoring

Run the example:
```bash
cd example
flutter run
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Support

For issues and feature requests, please visit our [GitHub Issues](https://github.com/yourusername/zebra_printer/issues) page.

## Credits

- Powered by [Zebra Link-OS SDK](https://www.zebra.com/us/en/support-downloads/software/developer-tools/link-os-sdk.html)
- Maintained by the Flutter community
