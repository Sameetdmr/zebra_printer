/// Discovery type for Zebra printer discovery
enum DiscoveryType {
  /// Bluetooth discovery only
  bluetooth('bluetooth'),

  /// Network discovery only
  network('network'),

  /// Both Bluetooth and Network discovery
  both('both');

  /// String value for native platform
  final String value;

  const DiscoveryType(this.value);

  /// Get DiscoveryType from string value
  static DiscoveryType fromString(String value) {
    switch (value.toLowerCase()) {
      case 'bluetooth':
        return DiscoveryType.bluetooth;
      case 'network':
        return DiscoveryType.network;
      case 'both':
        return DiscoveryType.both;
      default:
        return DiscoveryType.both;
    }
  }

  @override
  String toString() => value;
}
