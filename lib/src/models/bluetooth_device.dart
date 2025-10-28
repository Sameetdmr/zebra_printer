/// Bluetooth cihaz türleri için enum
enum BluetoothDeviceType {
  /// Bilinmeyen cihaz türü (0)
  unknown,

  /// Klasik Bluetooth cihazı (1)
  classic,

  /// Bluetooth Low Energy cihazı (2)
  le,

  /// Hem klasik hem de LE destekleyen cihaz (3)
  dual;

  /// Integer değerden BluetoothDeviceType oluşturur
  static BluetoothDeviceType fromInt(int? value) {
    switch (value) {
      case 1:
        return BluetoothDeviceType.classic;
      case 2:
        return BluetoothDeviceType.le;
      case 3:
        return BluetoothDeviceType.dual;
      default:
        return BluetoothDeviceType.unknown;
    }
  }

  /// Enum değerini integer'a dönüştürür
  int toInt() {
    switch (this) {
      case BluetoothDeviceType.classic:
        return 1;
      case BluetoothDeviceType.le:
        return 2;
      case BluetoothDeviceType.dual:
        return 3;
      case BluetoothDeviceType.unknown:
        return 0;
    }
  }
}

/// Bluetooth cihaz eşleşme durumları için enum
enum BluetoothBondState {
  /// Eşleşmemiş (10)
  none,

  /// Eşleşme süreci devam ediyor (11)
  bonding,

  /// Eşleşmiş (12)
  bonded;

  /// Integer değerden BluetoothBondState oluşturur
  static BluetoothBondState fromInt(int? value) {
    switch (value) {
      case 11:
        return BluetoothBondState.bonding;
      case 12:
        return BluetoothBondState.bonded;
      default:
        return BluetoothBondState.none;
    }
  }

  /// Enum değerini integer'a dönüştürür
  int toInt() {
    switch (this) {
      case BluetoothBondState.bonding:
        return 11;
      case BluetoothBondState.bonded:
        return 12;
      case BluetoothBondState.none:
        return 10;
    }
  }
}

class BluetoothDevice {
  /// Cihazın adı (null olabilir)
  final String? name;

  /// Cihazın MAC adresi
  final String address;

  /// Cihazın türü (null olabilir)
  final BluetoothDeviceType type;

  /// Cihazın eşleşme durumu
  final BluetoothBondState bondState;

  /// Cihazın bağlantı durumu
  final bool isConnected;

  /// BluetoothDevice constructor
  BluetoothDevice({required this.address, this.name, BluetoothDeviceType? type, BluetoothBondState? bondState, this.isConnected = false}) : this.type = type ?? BluetoothDeviceType.unknown, this.bondState = bondState ?? BluetoothBondState.none;

  /// Map'ten BluetoothDevice oluşturur
  factory BluetoothDevice.fromMap(Map<dynamic, dynamic> map) {
    return BluetoothDevice(
      name: map['name'] as String?,
      address: map['address'] as String,
      type: BluetoothDeviceType.fromInt(map['type'] as int?),
      bondState: BluetoothBondState.fromInt(map['bondState'] as int?),
      isConnected: map['isConnected'] as bool? ?? false,
    );
  }

  /// Map'e dönüştürür
  Map<String, dynamic> toMap() {
    return {'name': name, 'address': address, 'type': type.toInt(), 'bondState': bondState.toInt(), 'isConnected': isConnected};
  }

  /// Eşitlik kontrolü için override
  @override
  bool operator ==(Object other) => identical(this, other) || other is BluetoothDevice && runtimeType == other.runtimeType && address == other.address;

  /// Hash kodu için override
  @override
  int get hashCode => address.hashCode;

  /// String temsilini döndürür
  @override
  String toString() {
    return 'BluetoothDevice{name: $name, address: $address, type: $type, bondState: $bondState, isConnected: $isConnected}';
  }
}
