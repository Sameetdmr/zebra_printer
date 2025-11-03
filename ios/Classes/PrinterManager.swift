import Foundation
import ExternalAccessory
import Flutter

// Import Zebra SDK headers
@_silgen_name("TcpPrinterConnection_new")
func TcpPrinterConnection_new(_ address: UnsafePointer<Int8>, _ port: Int32) -> UnsafeMutableRawPointer?

@_silgen_name("SGD_GET")
func SGD_GET(_ device: UnsafeMutableRawPointer, _ setting: UnsafePointer<Int8>) -> UnsafeMutablePointer<Int8>?

@_silgen_name("SGD_SET")
func SGD_SET(_ device: UnsafeMutableRawPointer, _ setting: UnsafePointer<Int8>, _ value: UnsafePointer<Int8>) -> Bool

@_silgen_name("ZebraPrinterConnection_close")
func ZebraPrinterConnection_close(_ connection: UnsafeMutableRawPointer) -> Void

@_silgen_name("ZebraPrinterConnection_open")
func ZebraPrinterConnection_open(_ connection: UnsafeMutableRawPointer) -> Bool

@_silgen_name("ZebraPrinterConnection_write")
func ZebraPrinterConnection_write(_ connection: UnsafeMutableRawPointer, _ data: UnsafePointer<UInt8>, _ dataLength: Int32) -> Int32

@_silgen_name("ZebraPrinterConnection_isConnected")
func ZebraPrinterConnection_isConnected(_ connection: UnsafeMutableRawPointer) -> Bool

@_silgen_name("ZebraPrinterFactory_getInstance")
func ZebraPrinterFactory_getInstance(_ connection: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer?

@_silgen_name("ZebraPrinter_getCurrentStatus")
func ZebraPrinter_getCurrentStatus(_ printer: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer?

@_silgen_name("PrinterStatus_isHeadOpen")
func PrinterStatus_isHeadOpen(_ status: UnsafeMutableRawPointer) -> Bool

@_silgen_name("PrinterStatus_isPaperOut")
func PrinterStatus_isPaperOut(_ status: UnsafeMutableRawPointer) -> Bool

@_silgen_name("PrinterStatus_isPaused")
func PrinterStatus_isPaused(_ status: UnsafeMutableRawPointer) -> Bool

@_silgen_name("MfiBtPrinterConnection_new")
func MfiBtPrinterConnection_new(_ serialNumber: UnsafePointer<Int8>) -> UnsafeMutableRawPointer?

@objc public class PrinterManager: NSObject {
    
    // MARK: - Singleton instance
    @objc public static let shared = PrinterManager()
    
    // MARK: - Properties
    private var methodChannel: FlutterMethodChannel?
    private var activeConnections: [String: UnsafeMutableRawPointer] = [:]
    
    // MARK: - Initialization
    private override init() {
        super.init()
    }
    
    // MARK: - Public Methods
    @objc public func setMethodCallHandler(methodChannel: FlutterMethodChannel) {
        self.methodChannel = methodChannel
    }
    
    @objc public func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "printLabel":
            if let args = call.arguments as? [String: Any],
               let macAddress = args["address"] as? String,
               let zplData = args["data"] as? String {
                
                printZpl(address: macAddress, zplData: zplData, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Address and data are required", details: nil))
            }
            
        case "getPrinterInfo":
            if let args = call.arguments as? [String: Any],
               let address = args["address"] as? String {
                
                getPrinterInfo(address: address, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Address is required", details: nil))
            }
            
        case "checkPrinterStatus":
            if let args = call.arguments as? [String: Any],
               let address = args["address"] as? String {
                
                checkPrinterStatus(address: address, result: result)
            } else {
                result(FlutterError(code: "INVALID_ARGUMENT", message: "Address is required", details: nil))
            }
            
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    // MARK: - Private Methods
    private func getConnection(for address: String) -> UnsafeMutableRawPointer? {
        // Check if we already have an open connection
        if let existingConnection = activeConnections[address] {
            if ZebraPrinterConnection_isConnected(existingConnection) {
                return existingConnection
            } else {
                // Close the stale connection
                ZebraPrinterConnection_close(existingConnection)
                activeConnections.removeValue(forKey: address)
            }
        }
        
        // Try to create a new connection
        var connection: UnsafeMutableRawPointer?
        
        // First try Bluetooth connection
        if let btConnection = createBluetoothConnection(address: address) {
            connection = btConnection
        }
        // If Bluetooth fails, try TCP connection (assuming address could be an IP)
        else if let tcpConnection = createTcpConnection(address: address) {
            connection = tcpConnection
        }
        
        // If we have a connection, try to open it
        if let conn = connection {
            if ZebraPrinterConnection_open(conn) {
                activeConnections[address] = conn
                return conn
            } else {
                ZebraPrinterConnection_close(conn)
            }
        }
        
        return nil
    }
    
    private func createBluetoothConnection(address: String) -> UnsafeMutableRawPointer? {
        return address.withCString { cString in
            return MfiBtPrinterConnection_new(cString)
        }
    }
    
    private func createTcpConnection(address: String) -> UnsafeMutableRawPointer? {
        return address.withCString { cString in
            return TcpPrinterConnection_new(cString, 9100) // Default Zebra printer port is 9100
        }
    }
    
    private func printZpl(address: String, zplData: String, result: @escaping FlutterResult) {
        DispatchQueue.global(qos: .background).async {
            guard let connection = self.getConnection(for: address) else {
                DispatchQueue.main.async {
                    result(FlutterError(code: "CONNECTION_ERROR", message: "Failed to connect to printer at \(address)", details: nil))
                }
                return
            }
            
            // Ensure ZPL data is properly formatted
            let finalZpl: String
            if zplData.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("^XA") && 
               zplData.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix("^XZ") {
                finalZpl = zplData
            } else {
                finalZpl = "^XA\(zplData)^XZ"
            }
            
            // Convert ZPL to data
            let zplData = finalZpl.data(using: .ascii)!
            let bytes = [UInt8](zplData)
            
            // Send to printer
            let bytesWritten = bytes.withUnsafeBufferPointer { buffer in
                return ZebraPrinterConnection_write(connection, buffer.baseAddress!, Int32(buffer.count))
            }
            
            DispatchQueue.main.async {
                if bytesWritten > 0 {
                    result("Print successful: \(bytesWritten) bytes sent to \(address)")
                } else {
                    result(FlutterError(code: "PRINT_ERROR", message: "Failed to send data to printer", details: nil))
                }
            }
        }
    }
    
    private func getPrinterInfo(address: String, result: @escaping FlutterResult) {
        DispatchQueue.global(qos: .background).async {
            guard let connection = self.getConnection(for: address) else {
                DispatchQueue.main.async {
                    result(FlutterError(code: "CONNECTION_ERROR", message: "Failed to connect to printer at \(address)", details: nil))
                }
                return
            }
            
            guard let printer = ZebraPrinterFactory_getInstance(connection) else {
                DispatchQueue.main.async {
                    result(FlutterError(code: "PRINTER_ERROR", message: "Failed to get printer instance", details: nil))
                }
                return
            }
            
            // Use SGD commands to get printer info
            var info = ""
            
            if let cString = "device.product_name".cString(using: .ascii), let modelPtr = SGD_GET(connection, cString) {
                let model = String(cString: modelPtr)
                info += "Model: \(model)\n"
            }
            
            if let cString = "device.unique_id".cString(using: .ascii), let serialPtr = SGD_GET(connection, cString) {
                let serial = String(cString: serialPtr)
                info += "Serial: \(serial)\n"
            }
            
            if let cString = "device.host_status".cString(using: .ascii), let firmwarePtr = SGD_GET(connection, cString) {
                let firmware = String(cString: firmwarePtr)
                info += "Firmware: \(firmware)\n"
            }
            
            DispatchQueue.main.async {
                if info.isEmpty {
                    info = "Model: Zebra Printer\nSerial: \(address)\nFirmware: Unknown"
                }
                result(info)
            }
        }
    }
    
    private func checkPrinterStatus(address: String, result: @escaping FlutterResult) {
        DispatchQueue.global(qos: .background).async {
            guard let connection = self.getConnection(for: address) else {
                DispatchQueue.main.async {
                    var errorStatus = [String: Any]()
                    errorStatus["isConnected"] = false
                    errorStatus["isPaperOut"] = false
                    errorStatus["isPaused"] = false
                    errorStatus["isHeadOpen"] = false
                    errorStatus["temperature"] = "0"
                    errorStatus["errorMessage"] = "Failed to connect to printer"
                    result(errorStatus)
                }
                return
            }
            
            guard let printer = ZebraPrinterFactory_getInstance(connection) else {
                DispatchQueue.main.async {
                    var errorStatus = [String: Any]()
                    errorStatus["isConnected"] = true
                    errorStatus["isPaperOut"] = false
                    errorStatus["isPaused"] = false
                    errorStatus["isHeadOpen"] = false
                    errorStatus["temperature"] = "0"
                    errorStatus["errorMessage"] = "Failed to get printer instance"
                    result(errorStatus)
                }
                return
            }
            
            var statusMap = [String: Any]()
            statusMap["isConnected"] = true
            
            if let printerStatus = ZebraPrinter_getCurrentStatus(printer) {
                statusMap["isPaperOut"] = PrinterStatus_isPaperOut(printerStatus)
                statusMap["isPaused"] = PrinterStatus_isPaused(printerStatus)
                statusMap["isHeadOpen"] = PrinterStatus_isHeadOpen(printerStatus)
                
                // Get temperature if available
                if let cString = "head.temperature".cString(using: .ascii), let tempPtr = SGD_GET(connection, cString) {
                    statusMap["temperature"] = String(cString: tempPtr)
                } else {
                    statusMap["temperature"] = "0"
                }
            } else {
                statusMap["isPaperOut"] = false
                statusMap["isPaused"] = false
                statusMap["isHeadOpen"] = false
                statusMap["temperature"] = "0"
            }
            
            DispatchQueue.main.async {
                result(statusMap)
            }
        }
    }
    
    // MARK: - Cleanup
    func closeAllConnections() {
        for (_, connection) in activeConnections {
            ZebraPrinterConnection_close(connection)
        }
        activeConnections.removeAll()
    }
    
    deinit {
        closeAllConnections()
    }
}
