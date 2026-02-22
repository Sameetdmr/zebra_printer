import 'package:flutter/material.dart';
import 'package:zebra_printer/zebra_printer.dart';
import 'tabs/bluetooth_tab.dart';
import 'tabs/zebra_sdk_tab.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Zebra Printer Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage>
    with SingleTickerProviderStateMixin {
  final BluetoothManager _bluetoothManager = BluetoothManager();
  final PrinterManager _printerManager = PrinterManager();

  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);

    // Optional: Enable internal logging for debugging
    ZebraConfig().enableLogging();
  }

  @override
  void dispose() {
    _tabController.dispose();
    _bluetoothManager.dispose();
    _printerManager.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Zebra Printer Demo'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(icon: Icon(Icons.print), text: 'Zebra SDK'),
            Tab(icon: Icon(Icons.bluetooth), text: 'Bluetooth'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          ZebraSDKTab(printerManager: _printerManager),
          BluetoothTab(
            bluetoothManager: _bluetoothManager,
            printerManager: _printerManager,
          ),
        ],
      ),
    );
  }
}
