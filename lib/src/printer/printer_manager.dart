import 'package:flutter/services.dart';
import '../models/printer_status.dart';

/// Zebra yazıcılarla iletişim kurmak için sınıf
class PrinterManager {
  /// Singleton instance
  static final PrinterManager _instance = PrinterManager._internal();

  /// Factory constructor
  factory PrinterManager() => _instance;

  /// Private constructor
  PrinterManager._internal();

  /// Method channel
  static const MethodChannel _channel = MethodChannel('com.sameetdmr.zebra_printer/zebra_print');

  /// ZPL kodunu yazıcıya gönderir
  ///
  /// [macAddress] Yazıcının MAC adresi
  /// [zplData] ZPL kodu
  ///
  /// Başarılı olursa sonuç mesajını döndürür, başarısız olursa hata fırlatır
  Future<String> sendZplToPrinter(String macAddress, String zplData) async {
    if (macAddress.isEmpty) {
      throw Exception("MAC adresi boş olamaz.");
    }
    try {
      const String initCommands = "^XA~JA~JSN";
      final String finalZplToSend = "$initCommands^CI28$zplData^XZ";
      final String result = await _channel.invokeMethod('printLabel', {'address': macAddress, 'data': finalZplToSend});
      return result;
    } on PlatformException catch (e) {
      throw Exception("Baskı Hatası (${e.code}): ${e.message}");
    }
  }

  /// Test etiketi yazdırır
  ///
  /// [macAddress] Yazıcının MAC adresi
  ///
  /// Başarılı olursa sonuç mesajını döndürür, başarısız olursa hata fırlatır
  Future<String> printTestLabel(String macAddress) async {
    String testZpl = """
^FO50,50
^A0N,50,50
^FDZebra Test Baskısı^FS
^FO50,120
^A0N,30,30
^FDTarih: ${DateTime.now()}^FS
^FO50,170
^A0N,30,30
^FDTest Başarılı!^FS
""";
    return sendZplToPrinter(macAddress, testZpl);
  }

  /// Yazıcı hakkında bilgi alır
  ///
  /// [macAddress] Yazıcının MAC adresi
  ///
  /// Yazıcı bilgilerini döndürür, başarısız olursa hata fırlatır
  Future<String> getPrinterInfo(String macAddress) async {
    try {
      final String result = await _channel.invokeMethod('getPrinterInfo', {'address': macAddress});
      return result;
    } on PlatformException catch (e) {
      throw Exception("Yazıcı Bilgisi Hatası (${e.code}): ${e.message}");
    }
  }

  /// Yazıcının durumunu kontrol eder
  ///
  /// [macAddress] Yazıcının MAC adresi
  ///
  /// Yazıcı durumunu döndürür, başarısız olursa hata içeren bir durum döndürür
  Future<PrinterStatus> checkPrinterStatus(String macAddress) async {
    try {
      final Map<dynamic, dynamic> result = await _channel.invokeMethod('checkPrinterStatus', {'address': macAddress});
      return PrinterStatus.fromMap(result);
    } on PlatformException catch (e) {
      return PrinterStatus(isConnected: false, errorMessage: "Yazıcı Durum Hatası (${e.code}): ${e.message}");
    }
  }
}
