package com.sameetdmr.zebra_printer;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** ZebraPrinterPlugin */
public class ZebraPrinterPlugin implements FlutterPlugin, MethodCallHandler {
  private MethodChannel printerChannel;
  private MethodChannel bluetoothChannel;
  private PrinterManager printerManager;
  private BluetoothManager bluetoothManager;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    // Printer channel - PrinterManager handles its own calls
    printerChannel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "com.sameetdmr.zebra_printer/zebra_print");
    printerManager = new PrinterManager(flutterPluginBinding.getApplicationContext());
    printerManager.setMethodChannel(printerChannel);
    // Set PrinterManager as handler for printer channel
    printerChannel.setMethodCallHandler((call, result) -> printerManager.handleMethodCall(call, result));
    
    // Bluetooth channel - BluetoothManager has its own handler
    bluetoothChannel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "com.sameetdmr.zebra_printer/bluetooth");
    bluetoothManager = new BluetoothManager(flutterPluginBinding.getApplicationContext());
    bluetoothManager.setMethodCallHandler(bluetoothChannel);
    // BluetoothManager already sets its own handler in setMethodCallHandler
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    // This method is no longer used - each channel has its own handler
    // Kept for backwards compatibility
    result.notImplemented();
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    printerChannel.setMethodCallHandler(null);
    bluetoothChannel.setMethodCallHandler(null);
    
    if (printerManager != null) {
      printerManager.dispose();
    }
    
    if (bluetoothManager != null) {
      bluetoothManager.dispose();
    }
  }
}
