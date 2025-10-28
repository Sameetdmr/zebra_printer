import 'package:flutter/material.dart';
import 'package:zebra_printer/zebra_printer.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(title: 'Zebra Printer Demo', theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue), useMaterial3: true), home: const MyHomePage(title: 'Zebra Printer Demo'));
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  final BluetoothManager _bluetoothManager = BluetoothManager();
  final PrinterManager _printerManager = PrinterManager();

  List<BluetoothDevice> _devices = [];
  BluetoothDevice? _selectedDevice;
  bool _isScanning = false;
  String _status = 'Hazır';

  @override
  void initState() {
    super.initState();
    _setupListeners();
  }

  @override
  void dispose() {
    _bluetoothManager.dispose();
    super.dispose();
  }

  void _setupListeners() {
    // Cihaz bulunduğunda
    _bluetoothManager.onDeviceFound.listen((device) {
      setState(() {
        if (!_devices.contains(device)) {
          _devices.add(device);
        }
      });
    });

    // Tarama durumu değiştiğinde
    _bluetoothManager.onScanStateChanged.listen((state) {
      setState(() {
        _isScanning = state == BluetoothScanState.scanning;
      });
    });

    // Bağlantı durumu değiştiğinde
    _bluetoothManager.onConnectionStateChanged.listen((state) {
      setState(() {
        if (state == BluetoothConnectionState.connected) {
          _status = 'Bağlandı: ${_bluetoothManager.connectedDevice?.name}';
        } else if (state == BluetoothConnectionState.disconnected) {
          _status = 'Bağlantı kesildi';
        } else if (state == BluetoothConnectionState.connecting) {
          _status = 'Bağlanıyor...';
        } else if (state == BluetoothConnectionState.error) {
          _status = 'Bağlantı hatası';
        }
      });
    });
  }

  Future<void> _scanDevices() async {
    setState(() {
      _status = 'Cihazlar taranıyor...';
      _devices = [];
    });

    try {
      bool isEnabled = await _bluetoothManager.isBluetoothEnabled();
      if (!isEnabled) {
        setState(() {
          _status = 'Bluetooth kapalı';
        });
        return;
      }

      // Önce eşleştirilmiş cihazları al
      List<BluetoothDevice> bondedDevices = await _bluetoothManager.getBondedDevices();
      setState(() {
        _devices = bondedDevices;
      });

      // Taramayı başlat
      await _bluetoothManager.startDiscovery();
    } catch (e) {
      setState(() {
        _status = 'Hata: $e';
      });
    }
  }

  Future<void> _stopScan() async {
    await _bluetoothManager.stopDiscovery();
  }

  Future<void> _connectToDevice(BluetoothDevice device) async {
    setState(() {
      _status = '${device.name} cihazına bağlanıyor...';
      _selectedDevice = device;
    });

    try {
      await _bluetoothManager.connect(device.address);
    } catch (e) {
      setState(() {
        _status = 'Bağlantı hatası: $e';
      });
    }
  }

  Future<void> _disconnect() async {
    try {
      await _bluetoothManager.disconnect();
      setState(() {
        _selectedDevice = null;
      });
    } catch (e) {
      setState(() {
        _status = 'Bağlantı kesme hatası: $e';
      });
    }
  }

  Future<void> _printTestLabel() async {
    if (_selectedDevice == null) {
      setState(() {
        _status = 'Bağlı cihaz yok';
      });
      return;
    }

    setState(() {
      _status = 'Test etiketi yazdırılıyor...';
    });

    try {
      String result = await _printerManager.printTestLabel(_selectedDevice!.address);
      setState(() {
        _status = 'Test etiketi yazdırıldı: $result';
      });
    } catch (e) {
      setState(() {
        _status = 'Yazdırma hatası: $e';
      });
    }
  }

  Future<void> _checkPrinterStatus() async {
    if (_selectedDevice == null) {
      setState(() {
        _status = 'Bağlı cihaz yok';
      });
      return;
    }

    setState(() {
      _status = 'Yazıcı durumu kontrol ediliyor...';
    });

    try {
      PrinterStatus status = await _printerManager.checkPrinterStatus(_selectedDevice!.address);
      setState(() {
        _status = 'Yazıcı durumu: ${status.isConnected ? "Bağlı" : "Bağlı değil"}';
        if (status.isConnected) {
          _status += ', Kağıt: ${status.isPaperOut ? "Yok" : "Var"}';
          _status += ', Kafa: ${status.isHeadOpen ? "Açık" : "Kapalı"}';
          _status += ', Duraklatıldı: ${status.isPaused ? "Evet" : "Hayır"}';
        } else if (status.errorMessage != null) {
          _status += ', Hata: ${status.errorMessage}';
        }
      });
    } catch (e) {
      setState(() {
        _status = 'Durum kontrolü hatası: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(backgroundColor: Theme.of(context).colorScheme.inversePrimary, title: Text(widget.title)),
      body: Column(
        children: [
          Padding(padding: const EdgeInsets.all(8.0), child: Text('Durum: $_status', style: const TextStyle(fontWeight: FontWeight.bold))),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              ElevatedButton(onPressed: _isScanning ? _stopScan : _scanDevices, child: Text(_isScanning ? 'Taramayı Durdur' : 'Cihazları Tara')),
              if (_bluetoothManager.isConnected) ElevatedButton(onPressed: _disconnect, child: const Text('Bağlantıyı Kes')),
            ],
          ),
          const Divider(),
          if (_bluetoothManager.isConnected) ...[
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [ElevatedButton(onPressed: _printTestLabel, child: const Text('Test Etiketi Yazdır')), ElevatedButton(onPressed: _checkPrinterStatus, child: const Text('Yazıcı Durumunu Kontrol Et'))],
            ),
            const Divider(),
          ],
          Expanded(
            child: ListView.builder(
              itemCount: _devices.length,
              itemBuilder: (context, index) {
                final device = _devices[index];
                final bool isConnected = device.address == _bluetoothManager.connectedDevice?.address;

                return ListTile(
                  title: Text(device.name ?? 'İsimsiz Cihaz'),
                  subtitle: Text(device.address),
                  trailing: isConnected ? const Icon(Icons.bluetooth_connected, color: Colors.green) : const Icon(Icons.bluetooth),
                  selected: isConnected,
                  onTap: isConnected ? null : () => _connectToDevice(device),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
