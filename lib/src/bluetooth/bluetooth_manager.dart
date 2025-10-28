import 'dart:async';

import 'package:flutter/services.dart';
import '../models/bluetooth_device.dart';

/// Bluetooth bağlantı durumu için enum
enum BluetoothConnectionState {
  /// Bağlantı yok
  disconnected,

  /// Bağlanıyor
  connecting,

  /// Bağlantı kuruldu
  connected,

  /// Bağlantı kesiliyor
  disconnecting,

  /// Bağlantı hatası
  error,
}

/// Bluetooth tarama durumu için enum
enum BluetoothScanState {
  /// Tarama yok
  idle,

  /// Tarama başlatılıyor
  starting,

  /// Tarama devam ediyor
  scanning,

  /// Tarama durduruluyor
  stopping,
}

/// Bluetooth işlemlerini yöneten sınıf
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

  /// Discovery bittiğinde tetiklenen stream controller
  final StreamController<void> _discoveryFinishedController = StreamController.broadcast();

  /// Cihaz bulunduğunda tetiklenen stream controller
  final StreamController<BluetoothDevice> _deviceFoundController = StreamController.broadcast();

  /// Bağlantı durumu değiştiğinde tetiklenen stream controller
  final StreamController<BluetoothConnectionState> _connectionStateController = StreamController.broadcast();

  /// Tarama durumu değiştiğinde tetiklenen stream controller
  final StreamController<BluetoothScanState> _scanStateController = StreamController.broadcast();

  /// Tarama durumu
  BluetoothScanState _scanState = BluetoothScanState.idle;

  /// Bağlantı durumu
  BluetoothConnectionState _connectionState = BluetoothConnectionState.disconnected;

  /// Bağlı cihaz
  BluetoothDevice? _connectedDevice;

  /// Bulunan cihazlar listesi
  final List<BluetoothDevice> _devices = [];

  /// Eşleştirilmiş cihazlar listesi
  final List<BluetoothDevice> _bondedDevices = [];

  /// Tarama durumunu döndürür
  BluetoothScanState get scanState => _scanState;

  /// Bağlantı durumunu döndürür
  BluetoothConnectionState get connectionState => _connectionState;

  /// Bağlı cihazı döndürür
  BluetoothDevice? get connectedDevice => _connectedDevice;

  /// Tarama yapılıyor mu?
  bool get isScanning => _scanState == BluetoothScanState.scanning || _scanState == BluetoothScanState.starting;

  /// Bağlantı kuruldu mu?
  bool get isConnected => _connectionState == BluetoothConnectionState.connected;

  /// Bulunan cihazları döndürür (değiştirilemez liste)
  List<BluetoothDevice> get devices => List.unmodifiable(_devices);

  /// Eşleştirilmiş cihazları döndürür (değiştirilemez liste)
  List<BluetoothDevice> get bondedDevices => List.unmodifiable(_bondedDevices);

  /// Cihaz bulunduğunda tetiklenen stream
  Stream<BluetoothDevice> get onDeviceFound => _deviceFoundController.stream;

  /// Discovery bittiğinde tetiklenen stream
  Stream<void> get onDiscoveryFinished => _discoveryFinishedController.stream;

  /// Bağlantı durumu değiştiğinde tetiklenen stream
  Stream<BluetoothConnectionState> get onConnectionStateChanged => _connectionStateController.stream;

  /// Tarama durumu değiştiğinde tetiklenen stream
  Stream<BluetoothScanState> get onScanStateChanged => _scanStateController.stream;

  /// Method channel handler'ı ayarlar
  void _init() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  /// Method channel'dan gelen çağrıları işler
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

  /// Tarama durumunu günceller ve stream'e bildirir
  void _updateScanState(BluetoothScanState newState) {
    if (_scanState != newState) {
      _scanState = newState;
      _scanStateController.add(newState);
    }
  }

  /// Bağlantı durumunu günceller ve stream'e bildirir
  void _updateConnectionState(BluetoothConnectionState newState) {
    if (_connectionState != newState) {
      _connectionState = newState;
      _connectionStateController.add(newState);
    }
  }

  /// MAC adresine göre cihazı bulur
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

  /// Bluetooth'un açık olup olmadığını kontrol eder
  Future<bool> isBluetoothEnabled() async {
    return await _channel.invokeMethod('isBluetoothEnabled');
  }

  /// Eşleştirilmiş cihazları getirir ve günceller
  Future<List<BluetoothDevice>> getBondedDevices() async {
    final List<dynamic> result = await _channel.invokeMethod('getBondedDevices');
    final devices = result.map((deviceMap) => BluetoothDevice.fromMap(deviceMap)).toList();

    // Eşleştirilmiş cihazlar listesini güncelle
    _bondedDevices.clear();
    _bondedDevices.addAll(devices);

    return devices;
  }

  /// Cihaz keşfini başlatır
  Future<bool> startDiscovery() async {
    if (isScanning) {
      return false;
    }

    _updateScanState(BluetoothScanState.starting);

    try {
      // Önce eşleştirilmiş cihazları al
      final bondedDevices = await getBondedDevices();

      // Bulunan cihazlar listesini temizle ve eşleştirilmiş cihazları ekle
      _devices.clear();
      _devices.addAll(bondedDevices);

      // Keşfi başlat
      final result = await _channel.invokeMethod('startDiscovery');

      if (result == true) {
        _updateScanState(BluetoothScanState.scanning);
        return true;
      } else {
        _updateScanState(BluetoothScanState.idle);
        return false;
      }
    } catch (e) {
      _updateScanState(BluetoothScanState.idle);
      return false;
    }
  }

  /// Cihaz keşfini durdurur
  Future<bool> stopDiscovery() async {
    if (!isScanning) {
      return false;
    }

    _updateScanState(BluetoothScanState.stopping);

    try {
      final result = await _channel.invokeMethod('stopDiscovery');
      _updateScanState(BluetoothScanState.idle);
      return result;
    } catch (e) {
      _updateScanState(BluetoothScanState.idle);
      return false;
    }
  }

  /// Cihazla eşleşir
  Future<bool> pairDevice(String address) async {
    try {
      return await _channel.invokeMethod('pairDevice', {'address': address});
    } catch (e) {
      return false;
    }
  }

  /// Cihazla eşleşmeyi kaldırır
  Future<bool> unpairDevice(String address) async {
    try {
      // Eğer bağlı cihaz ise önce bağlantıyı kes
      if (_connectedDevice?.address == address) {
        await disconnect();
      }

      return await _channel.invokeMethod('unpairDevice', {'address': address});
    } catch (e) {
      return false;
    }
  }

  /// Cihaza bağlanır
  Future<bool> connect(String address) async {
    if (_connectionState == BluetoothConnectionState.connected || _connectionState == BluetoothConnectionState.connecting) {
      return false;
    }

    _updateConnectionState(BluetoothConnectionState.connecting);

    try {
      return await _channel.invokeMethod('connect', {'address': address});
    } catch (e) {
      _updateConnectionState(BluetoothConnectionState.error);
      return false;
    }
  }

  /// Bağlantıyı keser
  Future<bool> disconnect() async {
    if (_connectionState != BluetoothConnectionState.connected) {
      return false;
    }

    _updateConnectionState(BluetoothConnectionState.disconnecting);

    try {
      return await _channel.invokeMethod('disconnect');
    } catch (e) {
      _updateConnectionState(BluetoothConnectionState.error);
      return false;
    }
  }

  /// Stream controller'ları kapatır
  void dispose() {
    // Aktif tarama varsa durdur
    if (isScanning) {
      stopDiscovery();
    }

    // Aktif bağlantı varsa kes
    if (isConnected) {
      disconnect();
    }

    // Stream controller'ları kapat
    _discoveryFinishedController.close();
    _deviceFoundController.close();
    _connectionStateController.close();
    _scanStateController.close();
  }
}
