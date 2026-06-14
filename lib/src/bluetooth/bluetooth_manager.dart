import 'dart:async';

import 'package:flutter/services.dart';
import '../config/zebra_config.dart';
import '../models/bluetooth_device.dart';

/// Enum for Bluetooth connection state
enum BluetoothConnectionState {
  /// No connection
  disconnected,

  /// Connecting
  connecting,

  /// Connection established
  connected,

  /// Disconnecting
  disconnecting,

  /// Connection error
  error,
}

/// Enum for Bluetooth scan state
enum BluetoothScanState {
  /// No scanning
  idle,

  /// Scan starting
  starting,

  /// Scanning in progress
  scanning,

  /// Scan stopping
  stopping,
}

/// Class for managing Bluetooth operations
class BluetoothManager {
  /// Singleton instance
  static final BluetoothManager _instance = BluetoothManager._internal();

  /// Factory constructor
  factory BluetoothManager() => _instance;

  /// Private constructor
  BluetoothManager._internal() {
    _init();
  }

  /// Method channel
  static const MethodChannel _channel = MethodChannel('com.sameetdmr.zebra_printer/bluetooth');

  /// Config instance for logging
  final ZebraConfig _config = ZebraConfig();

  /// Stream controller triggered when discovery finishes
  late StreamController<void> _discoveryFinishedController;

  /// Stream controller triggered when device is found
  late StreamController<BluetoothDevice> _deviceFoundController;

  /// Stream controller triggered when connection state changes
  late StreamController<BluetoothConnectionState> _connectionStateController;

  /// Stream controller triggered when scan state changes
  late StreamController<BluetoothScanState> _scanStateController;

  /// Initializes stream controllers
  void _initControllers() {
    _discoveryFinishedController = StreamController.broadcast();
    _deviceFoundController = StreamController.broadcast();
    _connectionStateController = StreamController.broadcast();
    _scanStateController = StreamController.broadcast();
  }

  /// Scan state
  BluetoothScanState _scanState = BluetoothScanState.idle;

  /// Connection state
  BluetoothConnectionState _connectionState = BluetoothConnectionState.disconnected;

  /// Connected device
  BluetoothDevice? _connectedDevice;

  /// List of found devices
  final List<BluetoothDevice> _devices = [];

  /// Flag to track if manager is disposed
  bool _isDisposed = false;

  /// Throws exception if manager is disposed
  void _checkDisposed() {
    if (_isDisposed) {
      _config.log('⚠️ BluetoothManager is disposed! Call reinitialize() first.');
      throw StateError('BluetoothManager has been disposed. Call reinitialize() to use again.');
    }
  }

  /// List of bonded devices
  final List<BluetoothDevice> _bondedDevices = [];

  /// Returns the scan state
  BluetoothScanState get scanState => _scanState;

  /// Returns the connection state
  BluetoothConnectionState get connectionState => _connectionState;

  /// Returns the connected device
  BluetoothDevice? get connectedDevice => _connectedDevice;

  /// Is scanning in progress?
  bool get isScanning => _scanState == BluetoothScanState.scanning || _scanState == BluetoothScanState.starting;

  /// Is connection established?
  bool get isConnected => _connectionState == BluetoothConnectionState.connected;

  /// Returns the list of found devices (unmodifiable list)
  List<BluetoothDevice> get devices => List.unmodifiable(_devices);

  /// Returns the list of bonded devices (unmodifiable list)
  List<BluetoothDevice> get bondedDevices => List.unmodifiable(_bondedDevices);

  /// Stream triggered when device is found
  Stream<BluetoothDevice> get onDeviceFound => _deviceFoundController.stream;

  /// Stream triggered when discovery finishes
  Stream<void> get onDiscoveryFinished => _discoveryFinishedController.stream;

  /// Stream triggered when connection state changes
  Stream<BluetoothConnectionState> get onConnectionStateChanged => _connectionStateController.stream;

  /// Stream triggered when scan state changes
  Stream<BluetoothScanState> get onScanStateChanged => _scanStateController.stream;

  /// Sets up the method channel handler and initializes controllers
  void _init() {
    _initControllers();
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  /// Handles calls from the method channel
  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onDeviceFound':
        final deviceMap = call.arguments as Map<dynamic, dynamic>;
        final device = BluetoothDevice.fromMap(deviceMap);
        if (!_devices.contains(device)) {
          _devices.add(device);
          _deviceFoundController.add(device);
        }
        break;

      case 'onDiscoveryFinished':
        _updateScanState(BluetoothScanState.idle);
        _discoveryFinishedController.add(null);
        break;

      case 'onConnectionStateChanged':
        final Map<dynamic, dynamic> args = call.arguments as Map<dynamic, dynamic>;
        final int state = args['state'] as int;
        final String? address = args['address'] as String?;

        switch (state) {
          case 0: // Disconnected
            _updateConnectionState(BluetoothConnectionState.disconnected);
            _connectedDevice = null;
            break;
          case 1: // Connecting
            _updateConnectionState(BluetoothConnectionState.connecting);
            break;
          case 2: // Connected
            _updateConnectionState(BluetoothConnectionState.connected);
            if (address != null) {
              _connectedDevice = _findDeviceByAddress(address);
            }
            break;
          case 3: // Disconnecting
            _updateConnectionState(BluetoothConnectionState.disconnecting);
            break;
          case 4: // Error
            _updateConnectionState(BluetoothConnectionState.error);
            _connectedDevice = null;
            break;
        }
        break;

      default:
        throw PlatformException(code: 'Unimplemented', message: 'Method ${call.method} not implemented');
    }
    return null;
  }

  /// Updates the scan state and notifies the stream
  void _updateScanState(BluetoothScanState newState) {
    if (_scanState != newState) {
      _scanState = newState;
      _scanStateController.add(newState);
    }
  }

  /// Updates the connection state and notifies the stream
  void _updateConnectionState(BluetoothConnectionState newState) {
    if (_connectionState != newState) {
      _connectionState = newState;
      _connectionStateController.add(newState);
    }
  }

  /// Finds a device by MAC address
  BluetoothDevice? _findDeviceByAddress(String address) {
    for (final device in _devices) {
      if (device.address == address) {
        return device;
      }
    }

    for (final device in _bondedDevices) {
      if (device.address == address) {
        return device;
      }
    }

    return null;
  }

  /// Checks if Bluetooth is enabled
  Future<bool> isBluetoothEnabled() async {
    _checkDisposed();
    return await _channel.invokeMethod<bool>('isBluetoothEnabled') ?? false;
  }

  /// Gets and updates the bonded devices
  Future<List<BluetoothDevice>> getBondedDevices() async {
    _checkDisposed();
    final List<dynamic> result = await _channel.invokeMethod<List<dynamic>>('getBondedDevices') ?? [];
    final devices = result.map((deviceMap) => BluetoothDevice.fromMap(deviceMap as Map<dynamic, dynamic>)).toList();

    // Update the bonded devices list
    _bondedDevices.clear();
    _bondedDevices.addAll(devices);

    return devices;
  }

  /// Starts device discovery
  Future<bool> startDiscovery() async {
    _checkDisposed();
    _config.log('startDiscovery called');
    if (isScanning) {
      _config.log('Already scanning, returning false');
      return false;
    }

    _updateScanState(BluetoothScanState.starting);

    try {
      // First get bonded devices
      final bondedDevices = await getBondedDevices();
      _config.log('Found ${bondedDevices.length} bonded devices');

      // Clear the found devices list and add bonded devices
      _devices.clear();
      _devices.addAll(bondedDevices);

      // Start discovery
      final result = await _channel.invokeMethod('startDiscovery');

      if (result == true) {
        _updateScanState(BluetoothScanState.scanning);
        _config.log('Discovery started successfully');
        return true;
      } else {
        _updateScanState(BluetoothScanState.idle);
        _config.log('Discovery failed to start');
        return false;
      }
    } catch (e) {
      _updateScanState(BluetoothScanState.idle);
      _config.log('Discovery error: $e');
      return false;
    }
  }

  /// Stops device discovery
  Future<bool> stopDiscovery() async {
    _checkDisposed();
    if (!isScanning) {
      return false;
    }

    _updateScanState(BluetoothScanState.stopping);

    try {
      final result = await _channel.invokeMethod<bool>('stopDiscovery') ?? false;
      _updateScanState(BluetoothScanState.idle);
      return result;
    } catch (e) {
      _updateScanState(BluetoothScanState.idle);
      return false;
    }
  }

  /// Pairs with a device
  Future<bool> pairDevice(String address) async {
    _checkDisposed();
    try {
      return await _channel.invokeMethod<bool>('pairDevice', {'address': address}) ?? false;
    } catch (e) {
      return false;
    }
  }

  /// Unpairs from a device
  Future<bool> unpairDevice(String address) async {
    _checkDisposed();
    try {
      // If this is the connected device, disconnect first
      if (_connectedDevice?.address == address) {
        await disconnect();
      }

      return await _channel.invokeMethod<bool>('unpairDevice', {'address': address}) ?? false;
    } catch (e) {
      return false;
    }
  }

  /// Connects to a device
  Future<bool> connect(String address) async {
    _checkDisposed();
    _config.log('connect called for: $address');
    if (_connectionState == BluetoothConnectionState.connected || _connectionState == BluetoothConnectionState.connecting) {
      _config.log('Already connected or connecting, returning false');
      return false;
    }

    _updateConnectionState(BluetoothConnectionState.connecting);

    try {
      final result = await _channel.invokeMethod<bool>('connect', {'address': address}) ?? false;
      _config.log('connect result: $result');
      return result;
    } catch (e) {
      _config.log('connect error: $e');
      _updateConnectionState(BluetoothConnectionState.error);
      return false;
    }
  }

  /// Disconnects from a device
  Future<bool> disconnect() async {
    _checkDisposed();
    _config.log('disconnect called');
    if (_connectionState != BluetoothConnectionState.connected) {
      _config.log('Not connected, returning false');
      return false;
    }

    _updateConnectionState(BluetoothConnectionState.disconnecting);

    try {
      final result = await _channel.invokeMethod<bool>('disconnect') ?? false;
      _config.log('disconnect result: $result');
      return result;
    } catch (e) {
      _config.log('disconnect error: $e');
      _updateConnectionState(BluetoothConnectionState.error);
      return false;
    }
  }

  /// Closes the stream controllers and cleans up resources
  Future<void> dispose() async {
    if (_isDisposed) return;
    _config.log('BluetoothManager disposing...');
    
    // Stop active scanning if any
    if (isScanning) {
      await stopDiscovery();
    }

    // Disconnect active connection if any
    if (isConnected) {
      await disconnect();
    }

    // Remove method call handler
    _channel.setMethodCallHandler(null);

    // ✅ Close all stream controllers to prevent memory leaks
    _discoveryFinishedController.close();
    _deviceFoundController.close();
    _connectionStateController.close();
    _scanStateController.close();

    // Clear device lists
    _devices.clear();
    _bondedDevices.clear();

    // Mark as disposed
    _isDisposed = true;
    _config.log('BluetoothManager disposed');
  }

  /// Check if manager is disposed
  bool get isDisposed => _isDisposed;

  /// Reinitialize the manager after dispose (if needed)
  void reinitialize() {
    if (!_isDisposed) return;
    _config.log('BluetoothManager reinitializing...');

    // ✅ Recreate stream controllers (they were closed in dispose)
    _initControllers();
    
    _channel.setMethodCallHandler(_handleMethodCall);
    _scanState = BluetoothScanState.idle;
    _connectionState = BluetoothConnectionState.disconnected;
    _connectedDevice = null;
    _devices.clear();
    _bondedDevices.clear();
    _isDisposed = false;
    _config.log('BluetoothManager reinitialized');
  }
}