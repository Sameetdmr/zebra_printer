import 'package:flutter/services.dart';
import '../models/printer_status.dart';

/// Class for communicating with Zebra printers
class PrinterManager {
  /// Singleton instance
  static final PrinterManager _instance = PrinterManager._internal();

  /// Factory constructor
  factory PrinterManager() => _instance;

  /// Private constructor
  PrinterManager._internal();

  /// Method channel
  static const MethodChannel _channel = MethodChannel('com.sameetdmr.zebra_printer/zebra_print');

  /// Sends ZPL code to the printer
  ///
  /// [macAddress] MAC address of the printer
  /// [zplData] ZPL code
  ///
  /// Returns result message if successful, throws an error if failed
  Future<String> sendZplToPrinter(String macAddress, String zplData) async {
    if (macAddress.isEmpty) {
      throw Exception("MAC address cannot be empty.");
    }
    try {
      // If ZPL code already starts with ^XA and ends with ^XZ, send as is
      final String finalZplToSend;
      if (zplData.trim().startsWith("^XA") && zplData.trim().endsWith("^XZ")) {
        finalZplToSend = zplData;
      } else {
        // Otherwise, wrap the ZPL code between ^XA and ^XZ
        const String initCommands = "^XA";
        finalZplToSend = "$initCommands$zplData^XZ";
      }

      final String result = await _channel.invokeMethod('printLabel', {'address': macAddress, 'data': finalZplToSend});
      return result;
    } on PlatformException catch (e) {
      throw Exception("Print Error (${e.code}): ${e.message}");
    }
  }

  /// Prints a test label
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns result message if successful, throws an error if failed
  Future<String> printTestLabel(String macAddress) async {
    String testZpl = """^XA
^PON
^PW400
^MMT
^PR0
^LH0,6
^PMN
^FO50,50
^A0N,50,50
^FDZebra Test Print^FS
^FO50,120
^A0N,30,30
^FDDate: ${DateTime.now()}^FS
^FO50,170
^A0N,30,30
^FDTest Successful!^FS
^PQ1,0,1,Y
^XZ""";
    return sendZplToPrinter(macAddress, testZpl);
  }

  /// Gets information about the printer
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns printer information, throws an error if failed
  Future<String> getPrinterInfo(String macAddress) async {
    try {
      final String result = await _channel.invokeMethod('getPrinterInfo', {'address': macAddress});
      return result;
    } on PlatformException catch (e) {
      throw Exception("Printer Info Error (${e.code}): ${e.message}");
    }
  }

  /// Checks the printer status
  ///
  /// [macAddress] MAC address of the printer
  ///
  /// Returns printer status, returns a status with error if failed
  Future<PrinterStatus> checkPrinterStatus(String macAddress) async {
    try {
      final Map<dynamic, dynamic> result = await _channel.invokeMethod('checkPrinterStatus', {'address': macAddress});
      return PrinterStatus.fromMap(result);
    } on PlatformException catch (e) {
      return PrinterStatus(isConnected: false, errorMessage: "Printer Status Error (${e.code}): ${e.message}");
    }
  }
}