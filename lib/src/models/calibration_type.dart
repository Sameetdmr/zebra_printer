/// Media sensor type used for calibration.
enum CalibrationType {
  /// Gap/web sensor (labels with gaps between them)
  gap,

  /// Black mark sensor (media with black marks on the back)
  bar;

  /// SGD value for `media.sense_mode`.
  String get senseMode => this == CalibrationType.gap ? 'gap' : 'bar';

  /// Raw command sequence sent with `PrinterManager.sendCommand`:
  ///
  /// 1. sets the media type to labels (SGD),
  /// 2. sets the media sensor mode (SGD),
  /// 3. runs a media calibration (`~JC`),
  /// 4. saves the configuration (`^JUS`).
  ///
  /// SGD lines are terminated with CRLF and are not wrapped in `^XA ... ^XZ`
  /// (SGD commands inside a ZPL format are ignored by the printer).
  String get command =>
      '! U1 setvar "media.type" "label"\r\n'
      '! U1 setvar "media.sense_mode" "$senseMode"\r\n'
      '~JC\r\n'
      '^XA^JUS^XZ\r\n';

  /// Previous name of [command].
  ///
  /// Before 0.5.0 this also changed `wlan.country_code` and was sent wrapped in
  /// `^XA ... ^XZ`, which made the printer ignore the SGD lines.
  @Deprecated('Use command. It is sent as raw data by calibratePrinter().')
  String get zplCommand => command;

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
