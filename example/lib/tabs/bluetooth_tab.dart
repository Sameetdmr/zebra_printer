import 'package:flutter/material.dart';
import 'package:zebra_printer/zebra_printer.dart';

class BluetoothTab extends StatefulWidget {
  final BluetoothManager bluetoothManager;
  final PrinterManager printerManager;

  const BluetoothTab({
    super.key,
    required this.bluetoothManager,
    required this.printerManager,
  });

  @override
  State<BluetoothTab> createState() => _BluetoothTabState();
}

class _BluetoothTabState extends State<BluetoothTab> {
  List<BluetoothDevice> _devices = [];
  BluetoothDevice? _selectedDevice;
  bool _isScanning = false;
  String _status = 'Ready - Generic Bluetooth';

  @override
  void initState() {
    super.initState();
    _setupBluetoothListeners();
  }

  void _setupBluetoothListeners() {
    widget.bluetoothManager.onDeviceFound.listen((device) {
      if (mounted) {
        setState(() {
          if (!_devices.any((d) => d.address == device.address)) {
            _devices.add(device);
          }
        });
      }
    });

    widget.bluetoothManager.onScanStateChanged.listen((state) {
      if (mounted) {
        setState(() {
          _isScanning = state == BluetoothScanState.scanning;
        });
      }
    });

    widget.bluetoothManager.onConnectionStateChanged.listen((state) {
      if (mounted) {
        setState(() {
          if (state == BluetoothConnectionState.error) {
            _status = 'Bluetooth connection error';
          }
        });
      }
    });
  }

  Future<void> _scanDevices() async {
    setState(() {
      _status = 'Scanning all Bluetooth devices...';
      _devices = [];
    });

    try {
      bool isEnabled = await widget.bluetoothManager.isBluetoothEnabled();
      if (!isEnabled) {
        setState(() {
          _status = 'Bluetooth is off';
        });
        return;
      }

      List<BluetoothDevice> bondedDevices =
          await widget.bluetoothManager.getBondedDevices();
      setState(() {
        _devices = bondedDevices;
      });

      await widget.bluetoothManager.startDiscovery();
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
      });
    }
  }

  Future<void> _stopScan() async {
    await widget.bluetoothManager.stopDiscovery();
  }

  /// Selects the printer. No connection is opened here: print and status
  /// calls connect only for the duration of the job, so the printer stays
  /// free for other devices.
  void _selectDevice(BluetoothDevice device) {
    setState(() {
      _selectedDevice = device;
      _status = 'Selected: ${device.name ?? device.address}';
    });
  }

  void _deselect() {
    setState(() {
      _selectedDevice = null;
      _status = 'Ready';
    });
  }

  Future<void> _printTest() async {
    if (_selectedDevice == null) return;

    setState(() {
      _status = 'Printing test label...';
    });

    try {
      String result = await widget.printerManager.printTestLabel(
        _selectedDevice!.address,
      );
      setState(() {
        _status = 'Test label printed: $result';
      });
    } catch (e) {
      setState(() {
        _status = 'Print error: $e';
      });
    }
  }

  Future<void> _checkStatus() async {
    if (_selectedDevice == null) return;

    try {
      final status = await widget.printerManager.getZebraPrinterStatus(
        _selectedDevice!.address,
      );
      setState(() {
        _status = 'Printer status: ${status.statusMessagePlain}';
        if (status.isReadyToPrint || status.hasError) {
          _status +=
              '\nReady: ${status.isReadyToPrint ? "Yes" : "No"}, '
              'Paper Out: ${status.isPaperOut ? "Yes" : "No"}, '
              'Head Open: ${status.isHeadOpen ? "Yes" : "No"}';
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
    return Column(
      children: [
        // Status Bar
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          color: Colors.amber.shade50,
          child: Column(
            children: [
              Text(
                _status,
                style: const TextStyle(fontWeight: FontWeight.bold),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                'Using Android Bluetooth API',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
              ),
            ],
          ),
        ),

        // Action Buttons
        Padding(
          padding: const EdgeInsets.all(8.0),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            alignment: WrapAlignment.center,
            children: [
              ElevatedButton.icon(
                onPressed: _isScanning ? _stopScan : _scanDevices,
                icon: Icon(_isScanning ? Icons.stop : Icons.search),
                label: Text(_isScanning ? 'Stop Scan' : 'Scan Devices'),
              ),
              if (_selectedDevice != null) ...[
                ElevatedButton.icon(
                  onPressed: _deselect,
                  icon: const Icon(Icons.close),
                  label: const Text('Deselect'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.red.shade400,
                  ),
                ),
                ElevatedButton.icon(
                  onPressed: _printTest,
                  icon: const Icon(Icons.print),
                  label: const Text('Print Test'),
                ),
                ElevatedButton.icon(
                  onPressed: _checkStatus,
                  icon: const Icon(Icons.info_outline),
                  label: const Text('Check Status'),
                ),
              ],
            ],
          ),
        ),

        const Divider(),

        // Device List
        Expanded(
          child:
              _devices.isEmpty
                  ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.bluetooth,
                          size: 64,
                          color: Colors.grey.shade300,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'No Bluetooth devices found',
                          style: TextStyle(color: Colors.grey.shade600),
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          'Tap "Scan Devices" to search',
                          style: TextStyle(fontSize: 12, color: Colors.grey),
                        ),
                      ],
                    ),
                  )
                  : ListView.builder(
                    itemCount: _devices.length,
                    itemBuilder: (context, index) {
                      final device = _devices[index];
                      final isConnected =
                          device.address == _selectedDevice?.address;

                      return Card(
                        margin: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 4,
                        ),
                        elevation: isConnected ? 4 : 1,
                        color: isConnected ? Colors.amber.shade50 : null,
                        child: ListTile(
                          leading: CircleAvatar(
                            backgroundColor: Colors.blue,
                            child: Icon(
                              isConnected
                                  ? Icons.bluetooth_connected
                                  : Icons.bluetooth,
                              color: Colors.white,
                            ),
                          ),
                          title: Text(
                            device.name ?? 'Unnamed Device',
                            style: TextStyle(
                              fontWeight:
                                  isConnected
                                      ? FontWeight.bold
                                      : FontWeight.normal,
                            ),
                          ),
                          subtitle: Text(device.address),
                          trailing:
                              isConnected
                                  ? const Icon(
                                    Icons.check_circle,
                                    color: Colors.green,
                                  )
                                  : null,
                          onTap:
                              isConnected ? null : () => _selectDevice(device),
                        ),
                      );
                    },
                  ),
        ),
      ],
    );
  }
}
