/// Calibration type for Zebra printers
enum CalibrationType {
  /// Gap/web sensor calibration
  gap,
  
  /// Bar/mark sensor calibration
  bar;

  /// Get ZPL command for calibration
  String get zplCommand {
    const String baseCommand = '! U1 setvar "wlan.country_code" "turkey" ! U1 setvar "media.type" "label"';
    
    switch (this) {
      case CalibrationType.gap:
        return '$baseCommand ! U1 setvar "media.sense_mode" "gap" ! U1 AUTOCAL ~JC^XA^JUS^XZ';
      case CalibrationType.bar:
        return '$baseCommand ! U1 setvar "media.sense_mode" "bar" ! U1 AUTOCAL ~JC^XA^JUS^XZ';
    }
  }

  /// Get CalibrationType from string value
  static CalibrationType? fromString(String value) {
    switch (value.toLowerCase()) {
      case 'gap':
        return CalibrationType.gap;
      case 'bar':
        return CalibrationType.bar;
      default:
        return null;
    }
  }

  @override
  String toString() => name;
}
