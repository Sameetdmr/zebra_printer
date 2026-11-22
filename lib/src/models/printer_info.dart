/// Enum for printer language
enum PrinterLanguage {
  /// ZPL (Zebra Programming Language)
  zpl,

  /// CPCL (Common Printer Command Language)
  cpcl,

  /// Line print mode
  linePrint,

  /// Unknown language
  unknown;

  /// Creates PrinterLanguage from string
  static PrinterLanguage fromString(String? language) {
    if (language == null) return PrinterLanguage.unknown;

    final lower = language.toLowerCase();
    if (lower.contains('zpl')) return PrinterLanguage.zpl;
    if (lower.contains('cpcl')) return PrinterLanguage.cpcl;
    if (lower.contains('line')) return PrinterLanguage.linePrint;
    return PrinterLanguage.unknown;
  }

  @override
  String toString() {
    switch (this) {
      case PrinterLanguage.zpl:
        return 'ZPL';
      case PrinterLanguage.cpcl:
        return 'CPCL';
      case PrinterLanguage.linePrint:
        return 'Line Print';
      case PrinterLanguage.unknown:
        return 'Unknown';
    }
  }
}

/// Printer information model
class PrinterInfo {
  /// Printer model name
  final String model;

  /// Printer serial number
  final String serialNumber;

  /// Printer firmware version
  final String firmware;

  /// Printer language (ZPL/CPCL)
  final PrinterLanguage language;

  /// Link-OS version (null on printers without Link-OS)
  final String? linkOsVersion;

  /// Is the printer in Link-OS Protected Mode? (null if unknown / not Link-OS)
  final bool? isProtectedMode;

  /// Raw info string (for debugging)
  final String? rawInfo;

  PrinterInfo({
    required this.model,
    required this.serialNumber,
    required this.firmware,
    required this.language,
    this.linkOsVersion,
    this.isProtectedMode,
    this.rawInfo,
  });

  /// Creates PrinterInfo from the legacy string format
  /// ("Model: ZD421\nSerial: XXXX\nFirmware: V84.20.11Z\nLanguage: ZPL\n").
  factory PrinterInfo.fromString(String info) {
    final lines = info.split('\n');

    String model = 'Unknown';
    String serialNumber = 'Unknown';
    String firmware = 'Unknown';
    String languageStr = 'Unknown';

    for (var line in lines) {
      if (line.startsWith('Model:')) {
        model = line.substring('Model:'.length).trim();
      } else if (line.startsWith('Serial:')) {
        serialNumber = line.substring('Serial:'.length).trim();
      } else if (line.startsWith('Firmware:')) {
        firmware = line.substring('Firmware:'.length).trim();
      } else if (line.startsWith('Language:')) {
        languageStr = line.substring('Language:'.length).trim();
      }
    }

    return PrinterInfo(
      model: model,
      serialNumber: serialNumber,
      firmware: firmware,
      language: PrinterLanguage.fromString(languageStr),
      rawInfo: info,
    );
  }

  /// Creates PrinterInfo from the map returned by the native side.
  factory PrinterInfo.fromMap(Map<dynamic, dynamic> map) {
    return PrinterInfo(
      model: map['model'] as String? ?? 'Unknown',
      serialNumber: map['serialNumber'] as String? ?? 'Unknown',
      firmware: map['firmware'] as String? ?? 'Unknown',
      language: PrinterLanguage.fromString(map['language'] as String?),
      linkOsVersion: map['linkOsVersion'] as String?,
      isProtectedMode: map['isProtectedMode'] as bool?,
      rawInfo: map['rawInfo'] as String?,
    );
  }

  /// Converts to Map
  Map<String, dynamic> toMap() {
    return {
      'model': model,
      'serialNumber': serialNumber,
      'firmware': firmware,
      'language': language.toString(),
      'linkOsVersion': linkOsVersion,
      'isProtectedMode': isProtectedMode,
      'rawInfo': rawInfo,
    };
  }

  /// Returns string representation
  @override
  String toString() {
    return 'PrinterInfo{\n'
        '  Model: $model\n'
        '  Serial Number: $serialNumber\n'
        '  Firmware: $firmware\n'
        '  Language: ${language.toString()}\n'
        '  Link-OS: ${linkOsVersion ?? '-'}\n'
        '  Protected Mode: ${isProtectedMode ?? '-'}\n'
        '}';
  }

  /// Returns compact string representation
  String toCompactString() {
    return '$model (S/N: $serialNumber) - $firmware - ${language.toString()}';
  }

  /// Returns JSON-like Map for debugging
  Map<String, String> toJson() {
    return {
      'model': model,
      'serialNumber': serialNumber,
      'firmware': firmware,
      'language': language.toString(),
    };
  }
}
