package com.sameetdmr.zebra_printer;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodChannel;

/** ZebraPrinterPlugin: registers the printer and bluetooth method channels. */
public class ZebraPrinterPlugin implements FlutterPlugin {
  private MethodChannel printerChannel;
  private MethodChannel bluetoothChannel;
  private PrinterManager printerManager;
  private BluetoothManager bluetoothManager;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    final FlutterPlugin.FlutterAssets assets = binding.getFlutterAssets();

    printerChannel = new MethodChannel(binding.getBinaryMessenger(), "com.sameetdmr.zebra_printer/zebra_print");
    printerManager = new PrinterManager(binding.getApplicationContext(),
        key -> assets != null ? assets.getAssetFilePathByName(key) : null);
    printerManager.setMethodChannel(printerChannel);
    printerChannel.setMethodCallHandler(printerManager::handleMethodCall);

    bluetoothChannel = new MethodChannel(binding.getBinaryMessenger(), "com.sameetdmr.zebra_printer/bluetooth");
    bluetoothManager = new BluetoothManager(binding.getApplicationContext());
    bluetoothManager.setMethodCallHandler(bluetoothChannel);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    if (printerChannel != null) printerChannel.setMethodCallHandler(null);
    if (bluetoothChannel != null) bluetoothChannel.setMethodCallHandler(null);
    if (printerManager != null) printerManager.dispose();
    if (bluetoothManager != null) bluetoothManager.dispose();
  }
}
