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
  String _status = 'Ready';

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
    // When device is found
    _bluetoothManager.onDeviceFound.listen((device) {
      setState(() {
        if (!_devices.contains(device)) {
          _devices.add(device);
        }
      });
    });

    // When scan state changes
    _bluetoothManager.onScanStateChanged.listen((state) {
      setState(() {
        _isScanning = state == BluetoothScanState.scanning;
      });
    });

    // When connection state changes
    _bluetoothManager.onConnectionStateChanged.listen((state) {
      setState(() {
        if (state == BluetoothConnectionState.connected) {
          _status = 'Connected: ${_bluetoothManager.connectedDevice?.name}';
        } else if (state == BluetoothConnectionState.disconnected) {
          _status = 'Disconnected';
        } else if (state == BluetoothConnectionState.connecting) {
          _status = 'Connecting...';
        } else if (state == BluetoothConnectionState.error) {
          _status = 'Connection error';
        }
      });
    });
  }

  Future<void> _scanDevices() async {
    setState(() {
      _status = 'Scanning devices...';
      _devices = [];
    });

    try {
      bool isEnabled = await _bluetoothManager.isBluetoothEnabled();
      if (!isEnabled) {
        setState(() {
          _status = 'Bluetooth is off';
        });
        return;
      }

      // First get paired devices
      List<BluetoothDevice> bondedDevices = await _bluetoothManager.getBondedDevices();
      setState(() {
        _devices = bondedDevices;
      });

      // Start scanning
      await _bluetoothManager.startDiscovery();
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
      });
    }
  }

  Future<void> _stopScan() async {
    await _bluetoothManager.stopDiscovery();
  }

  Future<void> _connectToDevice(BluetoothDevice device) async {
    setState(() {
      _status = 'Connecting to ${device.name}...';
      _selectedDevice = device;
    });

    try {
      await _bluetoothManager.connect(device.address);
    } catch (e) {
      setState(() {
        _status = 'Connection error: $e';
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
        _status = 'Disconnection error: $e';
      });
    }
  }

  Future<void> _printTestLabel() async {
    if (_selectedDevice == null) {
      setState(() {
        _status = 'No connected device';
      });
      return;
    }

    setState(() {
      _status = 'Printing test label...';
    });

    try {
      String result = await _printerManager.printTestLabel(_selectedDevice!.address);
      setState(() {
        _status = 'Test label printed: $result';
      });
    } catch (e) {
      setState(() {
        _status = 'Print error: $e';
      });
    }
  }

  Future<void> _checkPrinterStatus() async {
    if (_selectedDevice == null) {
      setState(() {
        _status = 'No connected device';
      });
      return;
    }

    setState(() {
      _status = 'Checking printer status...';
    });

    try {
      PrinterStatus status = await _printerManager.checkPrinterStatus(_selectedDevice!.address);
      setState(() {
        _status = 'Printer status: ${status.isConnected ? "Connected" : "Not connected"}';
        if (status.isConnected) {
          _status += ', Paper: ${status.isPaperOut ? "Out" : "Available"}';
          _status += ', Head: ${status.isHeadOpen ? "Open" : "Closed"}';
          _status += ', Paused: ${status.isPaused ? "Yes" : "No"}';
        } else if (status.errorMessage != null) {
          _status += ', Error: ${status.errorMessage}';
        }
      });
    } catch (e) {
      setState(() {
        _status = 'Status check error: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(backgroundColor: Theme.of(context).colorScheme.inversePrimary, title: Text(widget.title)),
      body: Column(
        children: [
          Padding(padding: const EdgeInsets.all(8.0), child: Text('Status: $_status', style: const TextStyle(fontWeight: FontWeight.bold))),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              ElevatedButton(onPressed: _isScanning ? _stopScan : _scanDevices, child: Text(_isScanning ? 'Stop Scan' : 'Scan Devices')),
              if (_bluetoothManager.isConnected) ElevatedButton(onPressed: _disconnect, child: const Text('Disconnect')),
            ],
          ),
          const Divider(),
          if (_bluetoothManager.isConnected) ...[
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [ElevatedButton(onPressed: _printTestLabel, child: const Text('Print Test Label')), ElevatedButton(onPressed: _checkPrinterStatus, child: const Text('Check Printer Status'))],
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
                  title: Text(device.name ?? 'Unnamed Device'),
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
