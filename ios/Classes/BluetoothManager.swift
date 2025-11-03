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
        // Disconnect first if already connected to another device
        if connectionState == CONNECTION_STATE_CONNECTED && connectedPeripheral != nil {
            // If trying to connect to the same device, just return success
            if connectedPeripheral!.identifier.uuidString == address {
                result(true)
                return
            }
            
            // Disconnect from current device first
            centralManager.cancelPeripheralConnection(connectedPeripheral!)
            connectedPeripheral = nil
        }
        
        // If already connecting, cancel that attempt
        if connectionState == CONNECTION_STATE_CONNECTING {
            // Reset connection state
            updateConnectionState(CONNECTION_STATE_DISCONNECTED)
        }
        
        if let peripheral = findPeripheralByAddress(address) {
            // Update connection state
            updateConnectionState(CONNECTION_STATE_CONNECTING)
            
            // Set as the peripheral we're trying to connect to
            connectedPeripheral = peripheral
            peripheral.delegate = self
            
            // Connect with options that improve reliability
            let options: [String: Any] = [
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
                CBConnectPeripheralOptionNotifyOnConnectionKey: true
            ]
            centralManager.connect(peripheral, options: options)
            
            // Connection result will be handled asynchronously in CBCentralManagerDelegate methods
            result(true)
        } else {
            result(FlutterError(code: "DEVICE_NOT_FOUND", message: "Device not found with address: \(address)", details: nil))
        }
    }
    
    private func disconnect(result: FlutterResult?) {
        // If not connected or no connected peripheral, return success (already disconnected)
        if connectionState != CONNECTION_STATE_CONNECTED || connectedPeripheral == nil {
            if let result = result {
                result(true) // Return success instead of error since we're already disconnected
            }
            return
        }
        
        // Update connection state
        updateConnectionState(CONNECTION_STATE_DISCONNECTING)
        
        // Store the peripheral reference before disconnecting
        let peripheral = connectedPeripheral!
        
        // Disconnect
        centralManager.cancelPeripheralConnection(peripheral)
        
        // Immediately clear the connected peripheral to avoid state issues
        // The actual state will be updated in the delegate methods
        connectedPeripheral = nil
        
        // Return success immediately, actual result will be handled in delegate methods
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
        // Connection successful
        connectedPeripheral = peripheral
        peripheral.delegate = self
        
        // Start service discovery - try with nil to discover all services if the specific UUID doesn't work
        peripheral.discoverServices([SERVICE_UUID])
        
        // Update connection state
        updateConnectionState(CONNECTION_STATE_CONNECTED)
        
        // Log success
        print("Successfully connected to peripheral: \(peripheral.identifier.uuidString)")
    }
    
    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        // Connection failed
        print("Failed to connect to peripheral: \(peripheral.identifier.uuidString), error: \(error?.localizedDescription ?? "unknown error")")
        
        if peripheral.identifier == connectedPeripheral?.identifier {
            connectedPeripheral = nil
            updateConnectionState(CONNECTION_STATE_ERROR)
            
            // Try to reconnect after a short delay (optional)
            /*
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                guard let self = self else { return }
                if self.connectionState != CONNECTION_STATE_CONNECTED && self.connectionState != CONNECTION_STATE_CONNECTING {
                    print("Attempting to reconnect to: \(peripheral.identifier.uuidString)")
                    self.centralManager.connect(peripheral, options: nil)
                }
            }
            */
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        // Disconnected
        print("Disconnected from peripheral: \(peripheral.identifier.uuidString), error: \(error?.localizedDescription ?? "No error")")
        
        if peripheral.identifier == connectedPeripheral?.identifier {
            connectedPeripheral = nil
            
            if error != nil {
                // If there was an error, it might be an unexpected disconnection
                updateConnectionState(CONNECTION_STATE_ERROR)
            } else {
                // Normal disconnection
                updateConnectionState(CONNECTION_STATE_DISCONNECTED)
            }
        }
    }
    
    // MARK: - CBPeripheralDelegate
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            print("Error discovering services: \(error.localizedDescription)")
            
            // If the specific UUID didn't work, try discovering all services
            if peripheral.services == nil || peripheral.services?.isEmpty == true {
                print("Retrying service discovery with nil UUID")
                peripheral.discoverServices(nil)
            }
            return
        }
        
        guard let services = peripheral.services, !services.isEmpty else {
            print("No services found for peripheral: \(peripheral.identifier.uuidString)")
            
            // If no services found with specific UUID, try with nil to discover all
            if peripheral.services?.isEmpty == true {
                print("Retrying service discovery with nil UUID")
                peripheral.discoverServices(nil)
            }
            return
        }
        
        print("Discovered \(services.count) services for peripheral: \(peripheral.identifier.uuidString)")
        
        // Discover characteristics for each service
        for service in services {
            print("Discovering characteristics for service: \(service.uuid)")
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            print("Error discovering characteristics: \(error.localizedDescription)")
            return
        }
        
        guard let characteristics = service.characteristics, !characteristics.isEmpty else {
            print("No characteristics found for service: \(service.uuid)")
            return
        }
        
        print("Discovered \(characteristics.count) characteristics for service: \(service.uuid)")
        
        // Log discovered characteristics
        for characteristic in characteristics {
            print("Characteristic: \(characteristic.uuid), properties: \(characteristic.properties)")
            
            // Subscribe to notifications if the characteristic supports it
            if characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate) {
                print("Subscribing to notifications for characteristic: \(characteristic.uuid)")
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            print("Error updating characteristic value: \(error.localizedDescription)")
            return
        }
        
        guard let value = characteristic.value else {
            print("No value for characteristic: \(characteristic.uuid)")
            return
        }
        
        print("Received data from characteristic \(characteristic.uuid): \(value.count) bytes")
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            print("Error writing characteristic value: \(error.localizedDescription)")
        } else {
            print("Successfully wrote value to characteristic: \(characteristic.uuid)")
        }
    }
    
    // MARK: - Dispose
    @objc public func dispose() {
        // Stop active scanning if any
        if isDiscovering {
            centralManager.stopScan()
            isDiscovering = false
        }
        
        // Disconnect active connection if any
        if let connectedPeripheral = connectedPeripheral {
            centralManager.cancelPeripheralConnection(connectedPeripheral)
            self.connectedPeripheral = nil
        }
        
        // Reset connection state
        updateConnectionState(CONNECTION_STATE_DISCONNECTED)
        
        // Clean up discovered peripherals
        discoveredPeripherals.removeAll()
        
        // Close the method channel
        methodChannel = nil
    }
}