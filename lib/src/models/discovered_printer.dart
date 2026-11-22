/// A Zebra printer found by `PrinterManager.startDiscovery`.
class DiscoveredPrinter {
  /// "bluetooth" or "network"
  final String type;

  /// MAC address (Bluetooth) or IP address (network discovery)
  final String address;

  /// Friendly name reported by the printer (falls back to [address])
  final String friendlyName;

  DiscoveredPrinter({
    required this.type,
    required this.address,
    required this.friendlyName,
  });

  /// Is this a Bluetooth printer?
  bool get isBluetooth => type == 'bluetooth';

  factory DiscoveredPrinter.fromMap(Map<dynamic, dynamic> map) {
    return DiscoveredPrinter(
      type: map['type'] as String? ?? '',
      address: map['address'] as String? ?? '',
      friendlyName: map['friendlyName'] as String? ?? '',
    );
  }

  @override
  String toString() {
    return 'DiscoveredPrinter{type: $type, address: $address, friendlyName: $friendlyName}';
  }
}
