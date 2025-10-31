import Foundation
import CoreBluetooth
import Flutter

@objc public class BluetoothManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    
    // MARK: - Singleton instance
    @objc public static let shared = BluetoothManager()
    
    // MARK: - Constants
    private let SERVICE_UUID = CBUUID(string: "00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
    
    // MARK: - Connection states
    private let CONNECTION_STATE_DISCONNECTED = 0
    private let CONNECTION_STATE_CONNECTING = 1
    private let CONNECTION_STATE_CONNECTED = 2
    private let CONNECTION_STATE_DISCONNECTING = 3
    private let CONNECTION_STATE_ERROR = 4
    
    // MARK: - Properties
    private var centralManager: CBCentralManager!
    private var methodChannel: FlutterMethodChannel?
    private var discoveredPeripherals = [CBPeripheral]()
    private var connectedPeripheral: CBPeripheral?
    private var connectionState = 0
    private var isDiscovering = false
    
    // MARK: - Initialization
    private override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    // MARK: - Public Methods
    @objc public func setMethodCallHandler(methodChannel: FlutterMethodChannel) {
        self.methodChannel = methodChannel
    }
    
    @objc public func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "isBluetoothEnabled":
            result(isBluetoothEnabled())
            
        case "getBondedDevices":
            result(getBondedDevices())
            
        case "startDiscovery":
            startDiscovery(result: result)
            
        case "stopDiscovery":
            stopDiscovery(result: result)
            
        case "pairDevice":
            if let args = call.arguments as? [String: Any],
               let address = args["address"] as? String {
                pairDevice(address: address, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Address is required", details: nil))
            }
            
        case "unpairDevice":
            if let args = call.arguments as? [String: Any],
               let address = args["address"] as? String {
                unpairDevice(address: address, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Address is required", details: nil))
            }
            
        case "connect":
            if let args = call.arguments as? [String: Any],
               let address = args["address"] as? String {
                connect(address: address, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Address is required", details: nil))
            }
            
        case "disconnect":
            disconnect(result: result)
            
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    // MARK: - Private Methods
    private func isBluetoothEnabled() -> Bool {
        return centralManager.state == .poweredOn
    }
    
    private func getBondedDevices() -> [[String: Any]] {
        var devicesList = [[String: Any]]()
        
        // iOS'ta eşleştirilmiş cihazlar listesi doğrudan erişilebilir değil
        // Bağlı olan cihazlar ve daha önce keşfedilmiş cihazlar döndürülür
        for peripheral in discoveredPeripherals {
            var deviceMap = [String: Any]()
            deviceMap["name"] = peripheral.name ?? "Unknown"
            deviceMap["address"] = peripheral.identifier.uuidString
            deviceMap["type"] = 1 // Classic olarak varsayalım
            deviceMap["bondState"] = 12 // Bonded olarak varsayalım
            
            // Bağlı cihaz ise isConnected true olsun
            let isConnected = connectedPeripheral != nil && 
                            peripheral.identifier == connectedPeripheral!.identifier &&
                            connectionState == CONNECTION_STATE_CONNECTED
            deviceMap["isConnected"] = isConnected
            
            devicesList.append(deviceMap)
        }
        
        return devicesList
    }
    
    private func startDiscovery(result: @escaping FlutterResult) {
        if isDiscovering {
            stopDiscovery(result: nil)
        }
        
        if centralManager.state != .poweredOn {
            result(FlutterError(code: "BLUETOOTH_UNAVAILABLE", message: "Bluetooth is not available", details: nil))
            return
        }
        
        // Keşfedilen cihazlar listesini temizle
        discoveredPeripherals.removeAll()
        
        // Keşfi başlat
        centralManager.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        isDiscovering = true
        
        result(true)
        
        // 10 saniye sonra keşfi otomatik olarak durdur
        DispatchQueue.main.asyncAfter(deadline: .now() + 10.0) { [weak self] in
            guard let self = self, self.isDiscovering else { return }
            self.stopDiscovery(result: nil)
            
            // Flutter'a keşfin tamamlandığını bildir
            DispatchQueue.main.async {
                self.methodChannel?.invokeMethod("onDiscoveryFinished", arguments: nil)
            }
        }
    }
    
    private func stopDiscovery(result: FlutterResult?) {
        if isDiscovering {
            centralManager.stopScan()
            isDiscovering = false
        }
        
        if let result = result {
            result(true)
        }
    }
    
    private func pairDevice(address: String, result: @escaping FlutterResult) {
        // iOS'ta programatik eşleşme doğrudan mümkün değil
        // Cihaza bağlanmayı deneyelim
        if let peripheral = findPeripheralByAddress(address) {
            // Zaten bağlı ise başarılı döndür
            if connectedPeripheral != nil && peripheral.identifier == connectedPeripheral!.identifier {
                result(true)
                return
            }
            
            // Bağlanmayı dene
            centralManager.connect(peripheral, options: nil)
            updateConnectionState(CONNECTION_STATE_CONNECTING)
            
            // Bağlantı sonucunu beklemeden başarılı döndür
            result(true)
        } else {
            result(FlutterError(code: "DEVICE_NOT_FOUND", message: "Device not found with address: \(address)", details: nil))
        }
    }
    
    private func unpairDevice(address: String, result: @escaping FlutterResult) {
        // iOS'ta programatik eşleşme kaldırma doğrudan mümkün değil
        // Eğer bağlı cihaz ise bağlantıyı keselim
        if let peripheral = findPeripheralByAddress(address) {
            if connectedPeripheral != nil && peripheral.identifier == connectedPeripheral!.identifier {
                centralManager.cancelPeripheralConnection(peripheral)
            }
            
            result(true)
        } else {
            result(FlutterError(code: "DEVICE_NOT_FOUND", message: "Device not found with address: \(address)", details: nil))
        }
    }
    
    private func connect(address: String, result: @escaping FlutterResult) {
        // Zaten bağlıysa veya bağlanıyorsa hata döndür
        if connectionState == CONNECTION_STATE_CONNECTED || connectionState == CONNECTION_STATE_CONNECTING {
            result(FlutterError(code: "ALREADY_CONNECTING", message: "Already connected or connecting to a device", details: nil))
            return
        }
        
        if let peripheral = findPeripheralByAddress(address) {
            // Bağlantı durumunu güncelle
            updateConnectionState(CONNECTION_STATE_CONNECTING)
            
            // Bağlan
            centralManager.connect(peripheral, options: nil)
            
            // Bağlantı sonucu asenkron olarak CBCentralManagerDelegate metodlarında işlenecek
            // Geçici olarak başarılı döndürüyoruz, gerçek sonuç delegate metodlarında işlenecek
            result(true)
        } else {
            result(FlutterError(code: "DEVICE_NOT_FOUND", message: "Device not found with address: \(address)", details: nil))
        }
    }
    
    private func disconnect(result: FlutterResult?) {
        // Bağlı değilse hata döndür
        if connectionState != CONNECTION_STATE_CONNECTED || connectedPeripheral == nil {
            if let result = result {
                result(FlutterError(code: "NOT_CONNECTED", message: "Not connected to any device", details: nil))
            }
            return
        }
        
        // Bağlantı durumunu güncelle
        updateConnectionState(CONNECTION_STATE_DISCONNECTING)
        
        // Bağlantıyı kes
        centralManager.cancelPeripheralConnection(connectedPeripheral!)
        
        // Bağlantı kesme sonucu asenkron olarak CBCentralManagerDelegate metodlarında işlenecek
        // Geçici olarak başarılı döndürüyoruz, gerçek sonuç delegate metodlarında işlenecek
        if let result = result {
            result(true)
        }
    }
    
    private func findPeripheralByAddress(_ address: String) -> CBPeripheral? {
        return discoveredPeripherals.first { $0.identifier.uuidString == address }
    }
    
    private func updateConnectionState(_ state: Int) {
        if connectionState != state {
            connectionState = state
            
            // Flutter'a bağlantı durumu değişikliğini bildir
            DispatchQueue.main.async {
                var stateMap = [String: Any]()
                stateMap["state"] = state
                
                if let connectedPeripheral = self.connectedPeripheral {
                    stateMap["address"] = connectedPeripheral.identifier.uuidString
                }
                
                self.methodChannel?.invokeMethod("onConnectionStateChanged", arguments: stateMap)
            }
        }
    }
    
    // MARK: - CBCentralManagerDelegate
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn && isDiscovering {
            stopDiscovery(result: nil)
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        // Cihaz zaten keşfedilmiş mi kontrol et
        if !discoveredPeripherals.contains(where: { $0.identifier == peripheral.identifier }) {
            discoveredPeripherals.append(peripheral)
            
            // Flutter'a cihaz bulundu bildirimi gönder
            var deviceMap = [String: Any]()
            deviceMap["name"] = peripheral.name ?? "Unknown"
            deviceMap["address"] = peripheral.identifier.uuidString
            deviceMap["type"] = 1 // Classic olarak varsayalım
            deviceMap["bondState"] = 10 // None olarak varsayalım
            deviceMap["isConnected"] = false
            
            DispatchQueue.main.async {
                self.methodChannel?.invokeMethod("onDeviceFound", arguments: deviceMap)
            }
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        // Bağlantı başarılı
        connectedPeripheral = peripheral
        peripheral.delegate = self
        
        // Servis keşfini başlat
        peripheral.discoverServices([SERVICE_UUID])
        
        // Bağlantı durumunu güncelle
        updateConnectionState(CONNECTION_STATE_CONNECTED)
    }
    
    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        // Bağlantı hatası
        if peripheral.identifier == connectedPeripheral?.identifier {
            connectedPeripheral = nil
            updateConnectionState(CONNECTION_STATE_ERROR)
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        // Bağlantı kesildi
        if peripheral.identifier == connectedPeripheral?.identifier {
            connectedPeripheral = nil
            updateConnectionState(CONNECTION_STATE_DISCONNECTED)
        }
    }
    
    // MARK: - CBPeripheralDelegate
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            print("Error discovering services: \(error!.localizedDescription)")
            return
        }
        
        guard let services = peripheral.services else { return }
        
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            print("Error discovering characteristics: \(error!.localizedDescription)")
            return
        }
    }
    
    // MARK: - Dispose
    @objc public func dispose() {
        // Aktif tarama varsa durdur
        if isDiscovering {
            centralManager.stopScan()
            isDiscovering = false
        }
        
        // Aktif bağlantı varsa kes
        if let connectedPeripheral = connectedPeripheral {
            centralManager.cancelPeripheralConnection(connectedPeripheral)
        }
    }
}