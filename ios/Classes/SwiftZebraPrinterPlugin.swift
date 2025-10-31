import Flutter
import UIKit

public class SwiftZebraPrinterPlugin: NSObject, FlutterPlugin {
  private let printerManager = PrinterManager.shared
  private let bluetoothManager = BluetoothManager.shared
  
  public static func register(with registrar: FlutterPluginRegistrar) {
    // Printer channel
    let printerChannel = FlutterMethodChannel(name: "com.sameetdmr.zebra_printer/zebra_print", binaryMessenger: registrar.messenger())
    
    // Bluetooth channel
    let bluetoothChannel = FlutterMethodChannel(name: "com.sameetdmr.zebra_printer/bluetooth", binaryMessenger: registrar.messenger())
    
    // Plugin instance
    let instance = SwiftZebraPrinterPlugin()
    
    // Manager'ları ayarla
    PrinterManager.shared.setMethodCallHandler(methodChannel: printerChannel)
    BluetoothManager.shared.setMethodCallHandler(methodChannel: bluetoothChannel)
    
    // Plugin'i kaydet
    registrar.addMethodCallDelegate(instance, channel: printerChannel)
    registrar.addMethodCallDelegate(instance, channel: bluetoothChannel)
  }
  
  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    // Kanal adına göre ilgili manager'a yönlendir
    if call.method.hasPrefix("printer") || call.method == "printLabel" || call.method == "getPrinterInfo" || call.method == "checkPrinterStatus" {
      printerManager.handleMethodCall(call, result: result)
    } else {
      bluetoothManager.handleMethodCall(call, result: result)
    }
  }
}

