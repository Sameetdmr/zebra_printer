/// Discovery type for Zebra printer discovery
enum DiscoveryType {
  /// Bluetooth Classic discovery (default)
  bluetooth('bluetooth'),

  /// Network discovery (multicast). Discovery only: this package prints over
  /// Bluetooth.
  network('network'),

  /// Kept for backwards compatibility: runs Bluetooth discovery.
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
        return DiscoveryType.bluetooth;
    }
  }

  @override
  String toString() => value;
}
