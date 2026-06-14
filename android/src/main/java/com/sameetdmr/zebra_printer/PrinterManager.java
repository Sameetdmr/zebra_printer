package com.sameetdmr.zebra_printer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.printer.PrinterLanguage;
import com.zebra.sdk.printer.PrinterStatus;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLinkOs;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.SGD;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterBluetooth;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterNetwork;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import com.zebra.sdk.printer.discovery.BluetoothDiscoverer;
import com.zebra.sdk.printer.discovery.NetworkDiscoverer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Zebra yazıcılarla iletişim kurmak için sınıf
 * Zebra Link-OS SDK kullanarak printer discovery, connection, printing işlemlerini yönetir
 */
public class PrinterManager {
    private static final String TAG = "PrinterManager";

    // 🔧 LOGGING CONTROL: Centralized logging flag (default: false for production)
    private static boolean LOGGING_ENABLED = false;

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private MethodChannel methodChannel;
    
    // Discovery state
    private boolean isDiscovering = false;
    
    // ✅ ZEBRA SDK BEST PRACTICE: Connection reuse for rapid successive prints
    // "Keep connection open, call write() multiple times" - avoids Bluetooth stack
    // open-close-open race on QLn620/ZQ620 and similar printers
    private final Map<String, CachedConnection> connectionCache = new ConcurrentHashMap<>();
    private static final long CONNECTION_IDLE_TIMEOUT_MS = 2000; // 2 sn - reuse penceresi

    /**
     * Constructor
     * @param context Application context
     */
    public PrinterManager(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * MethodChannel'ı ayarlar
     * @param methodChannel Flutter tarafından gelen MethodChannel
     */
    public void setMethodChannel(MethodChannel methodChannel) {
        this.methodChannel = methodChannel;
    }

    private static class CachedConnection {
        final Connection connection;
        final String address;
        Runnable closeRunnable;

        CachedConnection(Connection conn, String addr) {
            connection = conn;
            address = addr;
        }
    }

    private Connection getCachedConnection(String macAddress) {
        CachedConnection cached = connectionCache.get(macAddress);
        if (cached == null) return null;
        if (!cached.connection.isConnected()) {
            try { cached.connection.close(); } catch (Exception e) { logW("Error closing stale connection: " + e.getMessage()); }
            connectionCache.remove(macAddress);
            return null;
        }
        if (cached.closeRunnable != null) {
            mainHandler.removeCallbacks(cached.closeRunnable);
            cached.closeRunnable = null;
        }
        logD("♻️ Reusing cached connection for " + macAddress);
        return cached.connection;
    }

    private void scheduleConnectionClose(String macAddress, Connection connection) {
        CachedConnection existing = connectionCache.get(macAddress);
        if (existing != null && existing.closeRunnable != null) {
            mainHandler.removeCallbacks(existing.closeRunnable);
        }
        CachedConnection cached = new CachedConnection(connection, macAddress);
        cached.closeRunnable = () -> executorService.execute(() -> {
            CachedConnection toClose = connectionCache.get(macAddress);
            if (toClose != null && toClose.connection == connection) {
                try {
                    connection.close();
                    logD("🔒 Connection closed after idle timeout (freed for other users)");
                } catch (Exception e) {
                    logW("Error closing connection: " + e.getMessage());
                }
                connectionCache.remove(macAddress);
            }
        });
        connectionCache.put(macAddress, cached);
        mainHandler.postDelayed(cached.closeRunnable, CONNECTION_IDLE_TIMEOUT_MS);
        logD("⏱️ Connection scheduled to close in " + CONNECTION_IDLE_TIMEOUT_MS + "ms (reuse window)");
    }

    private void closeCachedConnection(String macAddress, Connection connection) {
        CachedConnection cached = connectionCache.get(macAddress);
        if (cached != null && cached.closeRunnable != null) {
            mainHandler.removeCallbacks(cached.closeRunnable);
        }
        connectionCache.remove(macAddress);
        if (connection != null) {
            final Connection connToClose = connection;
            executorService.submit(() -> {
                try {
                    connToClose.close();
                    logD("🔒 Connection closed in background");
                } catch (Exception e) {
                    logW("Error closing connection: " + e.getMessage());
                }
            });
        }
    }

    // ==================== PRINT PROGRESS CALLBACK ====================
    
    /**
     * Print progress state constants
     */
    private static final String PRINT_STATE_CONNECTING = "connecting";
    private static final String PRINT_STATE_SENDING = "sending";
    private static final String PRINT_STATE_PRINTING = "printing";
    private static final String PRINT_STATE_VERIFYING = "verifying";
    private static final String PRINT_STATE_COMPLETED = "completed";
    private static final String PRINT_STATE_ERROR = "error";
    
    /**
     * Sends print progress callback to Flutter
     * @param state Current print state
     * @param percentage Progress percentage (0-100)
     * @param message Optional message
     * @param address Printer address
     */
    private void sendPrintProgress(String state, int percentage, String message, String address) {
        if (methodChannel == null) return;
        
        Map<String, Object> progressData = new HashMap<>();
        progressData.put("state", state);
        progressData.put("percentage", percentage);
        progressData.put("message", message);
        progressData.put("address", address);
        progressData.put("timestamp", System.currentTimeMillis());
        
        mainHandler.post(() -> {
            methodChannel.invokeMethod("onPrintProgress", progressData);
        });
        
        logD("📊 Print Progress: " + state + " - " + percentage + "% - " + message);
    }

    /**
     * 🎯 Sends print progress with full PrinterStatus from Zebra SDK
     * @param state Current print state
     * @param percentage Progress percentage (0-100)
     * @param message Optional message
     * @param address Printer address
     * @param status PrinterStatus from Zebra SDK (can be null)
     */
    private void sendPrintProgressWithStatus(String state, int percentage, String message, String address, PrinterStatus status) {
        if (methodChannel == null) return;
        
        Map<String, Object> progressData = new HashMap<>();
        progressData.put("state", state);
        progressData.put("percentage", percentage);
        progressData.put("message", message);
        progressData.put("address", address);
        progressData.put("timestamp", System.currentTimeMillis());
        
        // 🎯 Add PrinterStatus from Zebra SDK
        if (status != null) {
            progressData.put("isPaperOut", status.isPaperOut);
            progressData.put("isHeadOpen", status.isHeadOpen);
            progressData.put("isHeadCold", status.isHeadCold);
            progressData.put("isHeadTooHot", status.isHeadTooHot);
            progressData.put("isRibbonOut", status.isRibbonOut);
            progressData.put("isReceiveBufferFull", status.isReceiveBufferFull);
            progressData.put("labelsRemainingInBatch", status.labelsRemainingInBatch);
            progressData.put("isReadyToPrint", status.isReadyToPrint);
            progressData.put("isPrinting", status.isPartialFormatInProgress);
        }
        
        mainHandler.post(() -> {
            methodChannel.invokeMethod("onPrintProgress", progressData);
        });
        
        logD("📊 Print Progress (with status): " + state + " - " + percentage + "% - " + message);
    }
    
    /**
     * Sends print progress with error
     * @param errorMessage Error message
     * @param address Printer address
     */
    private void sendPrintError(String errorMessage, String address) {
        if (methodChannel == null) return;
        
        Map<String, Object> progressData = new HashMap<>();
        progressData.put("state", PRINT_STATE_ERROR);
        progressData.put("percentage", 0);
        progressData.put("message", errorMessage);
        progressData.put("errorMessage", errorMessage);
        progressData.put("address", address);
        progressData.put("timestamp", System.currentTimeMillis());
        
        mainHandler.post(() -> {
            methodChannel.invokeMethod("onPrintProgress", progressData);
        });
        
        logE("📊 Print Error: " + errorMessage);
    }
    
    /**
     * Sends batch print progress callback to Flutter
     * @param state Current print state
     * @param percentage Progress percentage (0-100)
     * @param currentLabel Current label index (1-based)
     * @param totalLabels Total number of labels
     * @param address Printer address
     */
    private void sendBatchPrintProgress(String state, int percentage, int currentLabel, int totalLabels, String address) {
        if (methodChannel == null) return;
        
        Map<String, Object> progressData = new HashMap<>();
        progressData.put("state", state);
        progressData.put("percentage", percentage);
        progressData.put("message", currentLabel + "/" + totalLabels);
        progressData.put("currentLabel", currentLabel);
        progressData.put("totalLabels", totalLabels);
        progressData.put("address", address);
        progressData.put("timestamp", System.currentTimeMillis());
        progressData.put("isBatch", true);
        
        mainHandler.post(() -> {
            methodChannel.invokeMethod("onPrintProgress", progressData);
        });
        
        logD("📊 Batch Print Progress: " + state + " - " + percentage + "% - Label " + currentLabel + "/" + totalLabels);
    }

    /**
     * 🎯 Sends batch print progress with full PrinterStatus from Zebra SDK
     */
    private void sendBatchPrintProgressWithStatus(String state, int percentage, int currentLabel, int totalLabels, String address, PrinterStatus status) {
        if (methodChannel == null) return;
        
        Map<String, Object> progressData = new HashMap<>();
        progressData.put("state", state);
        progressData.put("percentage", percentage);
        progressData.put("message", currentLabel + "/" + totalLabels);
        progressData.put("currentLabel", currentLabel);
        progressData.put("totalLabels", totalLabels);
        progressData.put("address", address);
        progressData.put("timestamp", System.currentTimeMillis());
        progressData.put("isBatch", true);
        
        if (status != null) {
            progressData.put("isPaperOut", status.isPaperOut);
            progressData.put("isHeadOpen", status.isHeadOpen);
            progressData.put("isHeadCold", status.isHeadCold);
            progressData.put("isHeadTooHot", status.isHeadTooHot);
            progressData.put("isRibbonOut", status.isRibbonOut);
            progressData.put("labelsRemainingInBatch", status.labelsRemainingInBatch);
        }
        
        mainHandler.post(() -> {
            methodChannel.invokeMethod("onPrintProgress", progressData);
        });
        
        logD("📊 Batch Print Progress (with status): " + state + " - " + percentage + "% - Label " + currentLabel + "/" + totalLabels);
    }

    /**
     * Flutter tarafından gelen method çağrılarını işler
     * @param call Method çağrısı
     * @param result Sonuç callback'i
     */
    public void handleMethodCall(MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            // Discovery Methods
            case "startDiscovery":
                final String discoveryType = call.argument("type");
                startDiscovery(discoveryType, result);
                break;
                
            case "stopDiscovery":
                stopDiscovery(result);
                break;
                
            case "getPairedPrinters":
                getPairedPrinters(result);
                break;
                
            case "unpairPrinter":
                final String unpairAddress = call.argument("address");
                unpairPrinter(unpairAddress, result);
                break;
            
            // Connection Methods    
            case "connect":
                final String connectAddress = call.argument("address");
                connectToPrinter(connectAddress, result);
                break;
                
            case "disconnect":
                final String disconnectAddress = call.argument("address");
                disconnectFromPrinter(disconnectAddress, result);
                break;
                
            case "isConnected":
                final String checkAddress = call.argument("address");
                result.success(isConnected(checkAddress));
                break;
            
            // Printing Methods
            case "printLabel":
                final String macAddress = call.argument("address");
                final String zplData = call.argument("data");
                executorService.execute(() -> {
                    try {
                        sendZplToPrinter(macAddress, zplData);
                        mainHandler.post(() -> result.success("Print successful: " + macAddress));
                    } catch (Exception e) {
                        final String errorMessage = "Printer or connection error: " + e.getMessage();
                        mainHandler.post(() -> result.error("PRINT_FAIL", errorMessage, e.toString()));
                    }
                });
                break;
                
            case "printMultipleLabels":
                final String batchMacAddress = call.argument("address");
                final List<String> zplList = call.argument("dataList");
                executorService.execute(() -> {
                    try {
                        sendMultipleZplToPrinter(batchMacAddress, zplList);
                        mainHandler.post(() -> result.success("Batch print successful: " + batchMacAddress + " (" + zplList.size() + " labels)"));
                    } catch (Exception e) {
                        final String errorMessage = "Batch printer or connection error: " + e.getMessage();
                        mainHandler.post(() -> result.error("BATCH_PRINT_FAIL", errorMessage, e.toString()));
                    }
                });
                break;
                
            // Font Upload Method
            case "uploadFont":
                final String fontMacAddress = call.argument("address");
                final String assetPath = call.argument("assetPath");
                final String fontType = call.argument("fontType");
                executorService.execute(() -> {
                    try {
                        boolean success = uploadFontToprinter(fontMacAddress, assetPath, fontType);
                        mainHandler.post(() -> result.success(success));
                    } catch (Exception e) {
                        final String errorMessage = "Font upload error: " + e.getMessage();
                        mainHandler.post(() -> result.error("FONT_UPLOAD_FAIL", errorMessage, e.toString()));
                    }
                });
                break;
                
            // Font Upload with Data Method
            case "uploadFontData":
                final String fontDataMacAddress = call.argument("address");
                final byte[] fontDataBytes = call.argument("fontData");
                final String fontDataType = call.argument("fontType");
                executorService.execute(() -> {
                    try {
                        boolean success = uploadFontDataToPrinter(fontDataMacAddress, fontDataBytes, fontDataType);
                        mainHandler.post(() -> result.success(success));
                    } catch (Exception e) {
                        final String errorMessage = "Font data upload error: " + e.getMessage();
                        mainHandler.post(() -> result.error("FONT_UPLOAD_FAIL", errorMessage, e.toString()));
                    }
                });
                break;
                
            case "getPrinterInfo":
                final String address = call.argument("address");
                executorService.execute(() -> {
                    try {
                        String info = getPrinterInfo(address);
                        mainHandler.post(() -> result.success(info));
                    } catch (Exception e) {
                        mainHandler.post(() -> result.error("INFO_FAIL", e.getMessage(), e.toString()));
                    }
                });
                break;
                
            // 🔧 LOGGING CONTROL
            case "setLogging":
                final Boolean enabled = call.argument("enabled");
                if (enabled != null) {
                    LOGGING_ENABLED = enabled;
                    logD("Logging " + (enabled ? "enabled" : "disabled"));
                    result.success(enabled);
                } else {
                    result.error("INVALID_ARGUMENT", "enabled parameter is required", null);
                }
                break;
                
            case "isLoggingEnabled":
                result.success(LOGGING_ENABLED);
                break;
            
            // 🎯 KAPSAMLI YAZICI DURUMU - Zebra SDK PrinterStatus sınıfını kullanır
            case "getZebraPrinterStatus":
                final String zebraStatusAddress = call.argument("address");
                executorService.execute(() -> {
                    try {
                        Map<String, Object> zebraStatus = getZebraPrinterStatus(zebraStatusAddress);
                        mainHandler.post(() -> result.success(zebraStatus));
                    } catch (Exception e) {
                        mainHandler.post(() -> result.error("ZEBRA_STATUS_FAIL", e.getMessage(), e.toString()));
                    }
                });
                break;
                
            default:
                result.notImplemented();
                break;
        }
    }
    
    // ==================== DISCOVERY METHODS ====================
    
    /**
     * Zebra yazıcılarını keşfeder (Bluetooth ve/veya Network)
     * Zebra Link-OS SDK'nın DiscoveryHandler kullanır
     * @param discoveryType "bluetooth", "network" veya "both"
     * @param result Sonuç callback'i
     */
    private void startDiscovery(String discoveryType, @NonNull MethodChannel.Result result) {
        logD("startDiscovery called with type: " + discoveryType);
        
        // Context kontrolü
        if (context == null) {
            logE("Context is null! Cannot start discovery");
            result.error("NO_CONTEXT", "Context is null", null);
            return;
        }
        
        if (isDiscovering) {
            logW("Already discovering, returning error");
            result.error("ALREADY_DISCOVERING", "Discovery already in progress", null);
            return;
        }
        
        isDiscovering = true;
        final List<Map<String, Object>> discoveredPrinters = new ArrayList<>();
        logD("Starting discovery on executor thread");
        logD("Context: " + context.getClass().getName());
        
        executorService.execute(() -> {
            try {
                logD("Creating DiscoveryHandler");
                DiscoveryHandler discoveryHandler = new DiscoveryHandler() {
                    @Override
                    public void foundPrinter(DiscoveredPrinter discoveredPrinter) {
                        logD("========================================");
                        logD("foundPrinter CALLED!");
                        logD("Printer class: " + discoveredPrinter.getClass().getName());
                        logD("Printer toString: " + discoveredPrinter.toString());
                        logD("========================================");
                        
                        Map<String, Object> printerMap = new HashMap<>();
                        
                        // Bluetooth yazıcı
                        if (discoveredPrinter instanceof DiscoveredPrinterBluetooth) {
                            DiscoveredPrinterBluetooth btPrinter = (DiscoveredPrinterBluetooth) discoveredPrinter;
                            logD("✓ Bluetooth Printer Found!");
                            logD("  - Address: " + btPrinter.address);
                            logD("  - Friendly Name: " + btPrinter.friendlyName);
                            
                            printerMap.put("type", "bluetooth");
                            printerMap.put("address", btPrinter.address);
                            printerMap.put("friendlyName", btPrinter.friendlyName != null && !btPrinter.friendlyName.isEmpty() 
                                ? btPrinter.friendlyName 
                                : btPrinter.address);
                            
                        // Network yazıcı
                        } else if (discoveredPrinter instanceof DiscoveredPrinterNetwork) {
                            DiscoveredPrinterNetwork netPrinter = (DiscoveredPrinterNetwork) discoveredPrinter;
                            logD("Found Network Printer: " + netPrinter.address);
                            printerMap.put("type", "network");
                            printerMap.put("address", netPrinter.address);
                            
                            // Discovery data'dan friendly name'i almaya çalış
                            Map<String, String> discoveryDataMap = netPrinter.getDiscoveryDataMap();
                            String friendlyName = discoveryDataMap.get("PRODUCT_NAME");
                            if (friendlyName == null || friendlyName.isEmpty()) {
                                friendlyName = netPrinter.address;
                            }
                            printerMap.put("friendlyName", friendlyName);
                            logD("Network printer friendly name: " + friendlyName);
                        }
                        
                        discoveredPrinters.add(printerMap);
                        logD("Total printers found: " + discoveredPrinters.size());
                        
                        // Flutter'a yazıcı bulundu bildirimi gönder
                        if (methodChannel != null) {
                            logD("Sending onPrinterFound to Flutter");
                            mainHandler.post(() -> methodChannel.invokeMethod("onPrinterFound", printerMap));
                        } else {
                            logW("methodChannel is null, cannot send to Flutter");
                        }
                    }

                    @Override
                    public void discoveryFinished() {
                        logD("========================================");
                        logD("discoveryFinished CALLED!");
                        logD("Total printers found: " + discoveredPrinters.size());
                        
                        if (discoveredPrinters.isEmpty()) {
                            logW("⚠️ NO PRINTERS FOUND!");
                            logW("Possible reasons:");
                            logW("  1. No Zebra printers nearby");
                            logW("  2. Printers not in discoverable mode");
                            logW("  3. Bluetooth/Location permissions missing");
                            logW("  4. Printers already paired (try unpair)");
                        } else {
                            logD("✓ Found " + discoveredPrinters.size() + " printer(s):");
                            for (Map<String, Object> p : discoveredPrinters) {
                                logD("  - " + p.get("friendlyName") + " (" + p.get("address") + ")");
                            }
                        }
                        logD("========================================");
                        
                        isDiscovering = false;
                        
                        // Flutter'a keşif tamamlandı bildirimi gönder
                        if (methodChannel != null) {
                            logD("Sending onDiscoveryFinished to Flutter");
                            mainHandler.post(() -> methodChannel.invokeMethod("onDiscoveryFinished", discoveredPrinters));
                        }
                        
                        // Ana thread'de result döndür
                        mainHandler.post(() -> {
                            logD("Sending success result with " + discoveredPrinters.size() + " printers");
                            result.success(discoveredPrinters);
                        });
                    }

                    @Override
                    public void discoveryError(String errorMessage) {
                        logE("Discovery error callback: " + errorMessage);
                        isDiscovering = false;
                        
                        // Ana thread'de hata döndür
                        mainHandler.post(() -> result.error("DISCOVERY_ERROR", errorMessage, null));
                    }
                };
                
                // Keşif tipine göre işlem yap
                // NOT: "both" seçeneğinde sadece Bluetooth kullanıyoruz
                // çünkü her iki discovery'nin kendi discoveryFinished callback'i var
                // ve ilk biten (genelde network) hemen finished gönderiyor
                
                if ("network".equalsIgnoreCase(discoveryType)) {
                    logD("Starting Network discovery only");
                    NetworkDiscoverer.findPrinters(discoveryHandler);
                } else {
                    // "bluetooth" veya "both" için sadece Bluetooth discovery
                    // Bluetooth discovery hem paired hem unpaired cihazları bulur
                    logD("Starting Bluetooth discovery (type: " + discoveryType + ")");
                    BluetoothDiscoverer.findPrinters(context, discoveryHandler);
                }
                logD("Discovery method called successfully");
                
            } catch (Exception e) {
                isDiscovering = false;
                logE("Discovery exception: " + e.getMessage());
                e.printStackTrace();
                mainHandler.post(() -> result.error("DISCOVERY_EXCEPTION", e.getMessage(), e.toString()));
            }
        });
    }
    
    /**
     * Yazıcı keşfini durdurur
     * @param result Sonuç callback'i
     */
    private void stopDiscovery(@NonNull MethodChannel.Result result) {
        isDiscovering = false;
        result.success(true);
    }
    
    /**
     * Bluetooth cihazıyla eşleşmeyi kaldırır
     * Android Bluetooth API kullanarak bonding'i kaldırır
     * @param address Cihaz MAC adresi
     * @param result Sonuç callback'i
     */
    private void unpairPrinter(String address, @NonNull MethodChannel.Result result) {
        logD("unpairPrinter called for address: " + address);
        
        if (address == null || address.isEmpty()) {
            logE("Invalid address provided");
            result.error("INVALID_ADDRESS", "Invalid device address", null);
            return;
        }
        
        if (context == null) {
            logE("Context is null! Cannot unpair device");
            result.error("NO_CONTEXT", "Context is null", null);
            return;
        }
        
        executorService.execute(() -> {
            try {
                // ✅ ZEBRA SDK BEST PRACTICE: Connections are closed after each print
                // No need to check cache (no cache anymore)
                
                // Android Bluetooth API ile eşleşmeyi kaldır
                android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                
                if (bluetoothAdapter == null) {
                    logE("Bluetooth adapter is null");
                    mainHandler.post(() -> result.error("NO_BLUETOOTH", "Bluetooth not available", null));
                    return;
                }
                
                android.bluetooth.BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                
                // Reflection kullanarak removeBond metodunu çağır
                java.lang.reflect.Method method = device.getClass().getMethod("removeBond");
                method.invoke(device);
                
                logD("Unpair successful for: " + address);
                mainHandler.post(() -> result.success(true));
                
            } catch (SecurityException e) {
                logE("Permission error: " + e.getMessage());
                mainHandler.post(() -> result.error("PERMISSION_DENIED", "Bluetooth permission denied", e.toString()));
            } catch (Exception e) {
                logE("Unpair error: " + e.getMessage());
                e.printStackTrace();
                mainHandler.post(() -> result.error("UNPAIR_FAILED", "Unpair error: " + e.getMessage(), e.toString()));
            }
        });
    }
    
    /**
     * Eşleşmiş (paired) Bluetooth cihazlarını döndürür
     * Android Bluetooth API kullanarak bonded devices listesini alır
     * @param result Sonuç callback'i
     */
    private void getPairedPrinters(@NonNull MethodChannel.Result result) {
        logD("getPairedPrinters called - using Android Bluetooth API");
        
        if (context == null) {
            logE("Context is null! Cannot get paired devices");
            result.error("NO_CONTEXT", "Context is null", null);
            return;
        }
        
        executorService.execute(() -> {
            try {
                List<Map<String, Object>> pairedPrinters = new ArrayList<>();
                
                // Android Bluetooth API ile eşleşmiş cihazları al
                android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                
                if (bluetoothAdapter == null) {
                    logE("Bluetooth adapter is null");
                    mainHandler.post(() -> result.error("NO_BLUETOOTH", "Bluetooth not available", null));
                    return;
                }
                
                if (!bluetoothAdapter.isEnabled()) {
                    logE("Bluetooth is not enabled");
                    mainHandler.post(() -> result.error("BLUETOOTH_OFF", "Bluetooth is turned off", null));
                    return;
                }
                
                // Eşleşmiş (bonded) Bluetooth cihazlarını al
                java.util.Set<android.bluetooth.BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                logD("Found " + bondedDevices.size() + " paired Bluetooth devices");
                
                for (android.bluetooth.BluetoothDevice device : bondedDevices) {
                    Map<String, Object> deviceInfo = new HashMap<>();
                    deviceInfo.put("address", device.getAddress());
                    deviceInfo.put("friendlyName", device.getName() != null ? device.getName() : device.getAddress());
                    deviceInfo.put("type", "bluetooth");
                    deviceInfo.put("isPaired", true);
                    
                    pairedPrinters.add(deviceInfo);
                    logD("Paired device: " + device.getName() + " (" + device.getAddress() + ")");
                }
                
                final List<Map<String, Object>> finalList = pairedPrinters;
                mainHandler.post(() -> {
                    logD("Returning " + finalList.size() + " paired devices");
                    result.success(finalList);
                });
                
            } catch (SecurityException e) {
                logE("Permission error: " + e.getMessage());
                mainHandler.post(() -> result.error("PERMISSION_DENIED", "Bluetooth permission denied", e.toString()));
            } catch (Exception e) {
                logE("Get paired devices error: " + e.getMessage());
                e.printStackTrace();
                mainHandler.post(() -> result.error("GET_PAIRED_ERROR", e.getMessage(), e.toString()));
            }
        });
    }
    
    // ==================== CONNECTION METHODS ====================
    
    /**
     * Zebra yazıcıya bağlantı testi - SDK uyumlu: open → verify → close
     * "Connect when needed, close when done" - kalıcı bağlantı tutulmaz
     * @param address Yazıcı adresi (MAC veya IP)
     * @param result Sonuç callback'i - Boolean (true: erişilebilir, false/error: değil)
     */
    private void connectToPrinter(String address, @NonNull MethodChannel.Result result) {
        logD("connectToPrinter called for address: " + address);
        
        if (address == null || address.isEmpty()) {
            logE("Invalid address provided");
            result.error("INVALID_ADDRESS", "Invalid printer address", null);
            return;
        }
        
        executorService.execute(() -> {
            Connection connection = null;
            long benchmarkStart = System.currentTimeMillis();
            long stepStart = benchmarkStart;
            
            try {
                logD("════════════════════════════════════════════════════════");
                logD("connectToPrinter - Connection test (open → verify → close)");
                logD("   Address: " + address);
                logD("════════════════════════════════════════════════════════");
                
                logD("Opening Bluetooth connection to: " + address);
                connection = new BluetoothConnection(address);
                connection.open();
                
                long openDuration = System.currentTimeMillis() - stepStart;
                logD("[+" + openDuration + "ms] ✅ Connection opened");
                
                stepStart = System.currentTimeMillis();
                logD("Verifying Zebra printer...");
                ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
                PrinterLanguage language = printer.getPrinterControlLanguage();
                
                long verifyDuration = System.currentTimeMillis() - stepStart;
                logD("[+" + verifyDuration + "ms] ✅ Printer verified. Language: " + language.toString());
                
                long totalDuration = System.currentTimeMillis() - benchmarkStart;
                logD("════════════════════════════════════════════════════════");
                logD("connectToPrinter - Total Duration: " + totalDuration + "ms");
                logD("════════════════════════════════════════════════════════");
                
                mainHandler.post(() -> {
                    logD("Connection successful!");
                    result.success(true);
                    if (methodChannel != null) {
                        Map<String, Object> connInfo = new HashMap<>();
                        connInfo.put("address", address);
                        connInfo.put("isConnected", true);
                        methodChannel.invokeMethod("onConnectionStateChanged", connInfo);
                    }
                });
            } catch (Exception e) {
                long totalDuration = System.currentTimeMillis() - benchmarkStart;
                logE("[+" + totalDuration + "ms] ❌ Connection error: " + e.getMessage());
                logD("════════════════════════════════════════════════════════");
                mainHandler.post(() -> {
                    result.error("CONNECTION_FAILED", "Connection error: " + e.getMessage(), e.toString());
                    if (methodChannel != null) {
                        Map<String, Object> errorInfo = new HashMap<>();
                        errorInfo.put("address", address);
                        errorInfo.put("isConnected", false);
                        errorInfo.put("error", e.getMessage());
                        methodChannel.invokeMethod("onConnectionStateChanged", errorInfo);
                    }
                });
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                        logD("🔒 Connection closed after verify");
                    } catch (Exception e) {
                        logW("Error closing connection: " + e.getMessage());
                    }
                }
            }
        });
    }
    
    /**
     * Yazıcı bağlantısını keser
     * @param address Yazıcı adresi (null ise aktif bağlantıyı keser)
     * @param result Sonuç callback'i - Boolean döndürür (true: başarılı, false/error: başarısız)
     */
    private void disconnectFromPrinter(String address, @NonNull MethodChannel.Result result) {
        logD("disconnectFromPrinter called for address: " + address);
        
        // Eğer address null veya boşsa hata ver
        if (address == null || address.isEmpty()) {
            logD("Address is null or empty");
            result.error("INVALID_ADDRESS", "Printer address required", null);
            return;
        }
        
        // Connection cache temizle (SDK: explicit disconnect = release)
        CachedConnection cached = connectionCache.get(address);
        if (cached != null) {
            if (cached.closeRunnable != null) {
                mainHandler.removeCallbacks(cached.closeRunnable);
            }
            connectionCache.remove(address);
            try {
                cached.connection.close();
            } catch (Exception e) {
                logW("Error closing connection on disconnect: " + e.getMessage());
            }
        }
        
        mainHandler.post(() -> {
            logD("Disconnection acknowledged (no-op)");
            result.success(true);
            
            // Callback gönder
            if (methodChannel != null) {
                Map<String, Object> disconnectInfo = new HashMap<>();
                disconnectInfo.put("address", address);
                disconnectInfo.put("isConnected", false);
                methodChannel.invokeMethod("onConnectionStateChanged", disconnectInfo);
            }
        });
    }
    
    /**
     * Yazıcı için cache'te açık connection var mı kontrol eder
     * SDK: Connection reuse penceresinde (idle timeout) true dönebilir
     * @param address Yazıcı adresi
     * @return Cache'te geçerli bağlantı varsa true
     */
    private boolean isConnected(String address) {
        if (address == null || address.isEmpty()) return false;
        CachedConnection cached = connectionCache.get(address);
        return cached != null && cached.connection.isConnected();
    }
    
    // ==================== PRINTING METHODS ====================

    /**
     * Link-OS SDK'yı kullanarak bağlantıyı kurar, ZPL gönderir ve kapatır (AÇ-BAS-KAPAT döngüsü)
     * Zebra SDK best practices: connection warm-up ve printer status check
     * @param macAddress MAC adresi
     * @param zplData ZPL verisi
     * @throws ConnectionException Bağlantı hatası
     * @throws IllegalArgumentException Geçersiz argüman
     * @throws UnsupportedEncodingException Desteklenmeyen kodlama
     */
    private void sendZplToPrinter(String macAddress, String zplData)
            throws ConnectionException, IllegalArgumentException, UnsupportedEncodingException {

        // ⏱️ BENCHMARK START
        long benchmarkStart = System.currentTimeMillis();
        long stepStart = benchmarkStart;
        
        logD("════════════════════════════════════════════════════════");
        logD("⏱️ BENCHMARK START - sendZplToPrinter");
        logD("   Address: " + macAddress);
        logD("   ZPL Size: " + (zplData != null ? zplData.length() : 0) + " bytes");
        logD("════════════════════════════════════════════════════════");

        if (macAddress == null || zplData == null || macAddress.isEmpty() || zplData.isEmpty()) {
            throw new IllegalArgumentException("MAC address or ZPL data cannot be empty");
        }

        sendPrintProgress(PRINT_STATE_CONNECTING, 10, "", macAddress);

        Connection connection = null;
        ZebraPrinter printer = null;
        boolean success = false;
        boolean errorAlreadySent = false;
        
        try {
            // ✅ SDK Connection Reuse: Try cached connection first (rapid successive prints)
            connection = getCachedConnection(macAddress);
            
            if (connection == null) {
                stepStart = System.currentTimeMillis();
                logD("⏱️ Opening connection to: " + macAddress);
                
                int maxRetries = 3;
                ConnectionException lastException = null;
                
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        connection = new BluetoothConnection(macAddress);
                        connection.open();
                        
                        long openDuration = System.currentTimeMillis() - stepStart;
                        logD("⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully" + (attempt > 0 ? " (retry #" + attempt + ")" : ""));
                        lastException = null;
                        break;
                    } catch (ConnectionException e) {
                        // Zebra SDK best practice: open() başarısız olsa bile dahili
                        // BluetoothSocket kaynakları tutabilir, close() ile serbest bırak
                        try { connection.close(); } catch (Exception ignored) {}
                        connection = null;
                        lastException = e;
                        if (attempt < maxRetries) {
                            logD("🔄 Connection attempt " + (attempt + 1) + " failed, retrying in 2s... (" + e.getMessage() + ")");
                            sendPrintProgress(PRINT_STATE_CONNECTING, 10, "Yazıcı meşgul, tekrar deneniyor... (" + (attempt + 1) + "/" + maxRetries + ")", macAddress);
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw e;
                            }
                        }
                    }
                }
                
                if (lastException != null) {
                    long attemptDuration = System.currentTimeMillis() - stepStart;
                    logE("⏱️ [+" + attemptDuration + "ms] ❌ Connection failed after " + (maxRetries + 1) + " attempts: " + lastException.getMessage());
                    
                    errorAlreadySent = true;
                    if (lastException.getMessage().contains("timeout") || lastException.getMessage().contains("timed out")) {
                        sendPrintError("Could not connect to printer: timeout", macAddress);
                    } else {
                        sendPrintError("Could not connect to printer", macAddress);
                    }
                    throw lastException;
                }
            }
            
            sendPrintProgress(PRINT_STATE_CONNECTING, 20, "", macAddress);

            stepStart = System.currentTimeMillis();
            logD("⏱️ Sending ZPL data (" + zplData.length() + " bytes)...");
            
            if (connection == null || !connection.isConnected()) {
                errorAlreadySent = true;
                sendPrintError("Could not connect to printer", macAddress);
                throw new ConnectionException("Connection is not open or not connected");
            }
            
            sendPrintProgress(PRINT_STATE_SENDING, 30, "", macAddress);
            
            // ✅ connection.write() blocking call - ZPL tamamen gönderilene kadar bekler
            // Zebra SDK asenkron printing'i halleder, ekstra sleep gereksiz!
            try {
                connection.write(zplData.getBytes("UTF-8"));
            } catch (ConnectionException e) {
                logE("❌ Write failed: " + e.getMessage());
                errorAlreadySent = true;
                sendPrintError("Could not connect to printer", macAddress);
                throw new ConnectionException("Failed to send ZPL data: " + e.getMessage(), e);
            }
            
            long writeDuration = System.currentTimeMillis() - stepStart;
            logD("⏱️ [+" + writeDuration + "ms] ✅ ZPL data sent successfully");
            
            sendPrintProgress(PRINT_STATE_PRINTING, 50, "", macAddress);
            
            try {
                printer = ZebraPrinterFactory.getInstance(connection);
                PrinterStatus status = printer.getCurrentStatus();
                
                int pollCount = 0;
                int maxPolls = 15;
                
                while ((status.isPartialFormatInProgress || !status.isReadyToPrint) && pollCount < maxPolls) {
                    pollCount++;
                    int progress = 50 + (pollCount * 40 / maxPolls);
                    
                    sendPrintProgressWithStatus(PRINT_STATE_PRINTING, progress, "", macAddress, status);
                    
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    status = printer.getCurrentStatus();
                }
                
                if (status.isPaperOut) {
                    errorAlreadySent = true;
                    sendPrintError("Paper out", macAddress);
                    throw new ConnectionException("Paper out");
                }
                if (status.isHeadOpen) {
                    errorAlreadySent = true;
                    sendPrintError("Head open", macAddress);
                    throw new ConnectionException("Head open");
                }
                if (status.isRibbonOut) {
                    errorAlreadySent = true;
                    sendPrintError("Ribbon out", macAddress);
                    throw new ConnectionException("Ribbon out");
                }
                
            } catch (ZebraPrinterLanguageUnknownException e) {
                logW("Could not get printer status (language unknown): " + e.getMessage());
                // Status alamadık ama print başarılı olmuş olabilir, devam et
            } catch (Exception e) {
                // ✅ Tüm status polling hatalarını yakala - etiket muhtemelen basıldı
                logW("Status polling failed: " + e.getMessage());
                // Devam et - bağlantı kopmuş olabilir ama yazdırma başarılı olabilir
            }
            
            sendPrintProgress(PRINT_STATE_VERIFYING, 90, "", macAddress);
            sendPrintProgress(PRINT_STATE_COMPLETED, 100, "", macAddress);
            success = true;

        } catch (Exception e) {
            if (!errorAlreadySent) {
                sendPrintError("Could not connect to printer", macAddress);
            }
            
            if (connection != null) {
                closeCachedConnection(macAddress, connection);
                connection = null;
            }
            throw e;
        } finally {
            if (success && connection != null) {
                scheduleConnectionClose(macAddress, connection);
            }
            
            // ⏱️ BENCHMARK END - TOTAL DURATION
            long totalDuration = System.currentTimeMillis() - benchmarkStart;
            logD("════════════════════════════════════════════════════════");
            logD("⏱️ BENCHMARK END - Total Duration: " + totalDuration + "ms");
            logD("════════════════════════════════════════════════════════");
        }
    }
    
    /**
     * ⚡ BATCH PRINTING: Birden fazla ZPL'i tek connection'da gönderir
     * Performans optimizasyonu için: 10 etiket için 10x connection açma yerine 1x connection açma
     * @param macAddress MAC adresi
     * @param zplList ZPL verileri listesi
     * @throws ConnectionException Bağlantı hatası
     * @throws IllegalArgumentException Geçersiz argüman
     * @throws UnsupportedEncodingException Desteklenmeyen kodlama
     */
    private void sendMultipleZplToPrinter(String macAddress, List<String> zplList)
            throws ConnectionException, IllegalArgumentException, UnsupportedEncodingException {
        
        // ⏱️ BENCHMARK START
        long benchmarkStart = System.currentTimeMillis();
        long stepStart = benchmarkStart;
        
        int totalLabels = zplList != null ? zplList.size() : 0;
        
        logD("════════════════════════════════════════════════════════");
        logD("⚡ BATCH PRINT START - sendMultipleZplToPrinter");
        logD("   Address: " + macAddress);
        logD("   Label Count: " + totalLabels);
        logD("════════════════════════════════════════════════════════");
        
        if (macAddress == null || macAddress.isEmpty()) {
            throw new IllegalArgumentException("MAC address cannot be empty");
        }
        
        if (zplList == null || zplList.isEmpty()) {
            throw new IllegalArgumentException("ZPL list cannot be empty");
        }
        
        // 📊 Batch Progress: CONNECTING (5%)
        sendBatchPrintProgress(PRINT_STATE_CONNECTING, 5, 0, totalLabels, macAddress);
        
        Connection connection = null;
        boolean batchSuccess = false;
        boolean errorAlreadySent = false;
        
        try {
            connection = getCachedConnection(macAddress);
            
            if (connection == null) {
                stepStart = System.currentTimeMillis();
                logD("⏱️ Opening connection to: " + macAddress + " (batch mode)");
                
                int maxRetries = 3;
                ConnectionException lastException = null;
                
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        connection = new BluetoothConnection(macAddress);
                        connection.open();
                        
                        long openDuration = System.currentTimeMillis() - stepStart;
                        logD("⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully (batch mode)" + (attempt > 0 ? " (retry #" + attempt + ")" : ""));
                        lastException = null;
                        break;
                    } catch (ConnectionException e) {
                        // Zebra SDK best practice: open() başarısız olsa bile dahili
                        // BluetoothSocket kaynakları tutabilir, close() ile serbest bırak
                        try { connection.close(); } catch (Exception ignored) {}
                        connection = null;
                        lastException = e;
                        if (attempt < maxRetries) {
                            logD("🔄 Connection attempt " + (attempt + 1) + " failed, retrying in 2s... (" + e.getMessage() + ")");
                            sendBatchPrintProgress(PRINT_STATE_CONNECTING, 5, 0, totalLabels, macAddress);
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw e;
                            }
                        }
                    }
                }
                
                if (lastException != null) {
                    long attemptDuration = System.currentTimeMillis() - stepStart;
                    logE("⏱️ [+" + attemptDuration + "ms] ❌ Connection failed after " + (maxRetries + 1) + " attempts (batch mode): " + lastException.getMessage());
                    
                    errorAlreadySent = true;
                    if (lastException.getMessage().contains("timeout") || lastException.getMessage().contains("timed out")) {
                        sendPrintError("Could not connect to printer: timeout", macAddress);
                    } else {
                        sendPrintError("Could not connect to printer", macAddress);
                    }
                    throw lastException;
                }
            }
            
            sendBatchPrintProgress(PRINT_STATE_CONNECTING, 10, 0, totalLabels, macAddress);
            
            // ⚡ BATCH PRINTING: Tüm ZPL'leri tek connection'da gönder
            stepStart = System.currentTimeMillis();
            int totalBytes = 0;
            int successfulLabels = 0;
            
            for (int i = 0; i < zplList.size(); i++) {
                String zplData = zplList.get(i);
                if (zplData == null || zplData.isEmpty()) {
                    logW("⚠️ Skipping empty ZPL at index " + i);
                    continue;
                }
                
                int currentLabel = i + 1;
                // Progress: 10% (bağlantı) + 85% (etiketler için) * (currentLabel / totalLabels)
                int progress = 10 + (85 * currentLabel / totalLabels);
                
                // 📊 Batch Progress: PRINTING (10-95%)
                sendBatchPrintProgress(PRINT_STATE_PRINTING, progress, currentLabel, totalLabels, macAddress);
                
                long labelStart = System.currentTimeMillis();
                logD("⏱️ Sending label #" + currentLabel + "/" + totalLabels + " (" + zplData.length() + " bytes)...");
                
                // Connection state kontrolü
                if (connection == null || !connection.isConnected()) {
                    errorAlreadySent = true;
                    sendPrintError("Could not connect to printer", macAddress);
                    throw new ConnectionException("Connection lost during batch printing at label #" + currentLabel);
                }
                
                try {
                    connection.write(zplData.getBytes("UTF-8"));
                    totalBytes += zplData.length();
                    successfulLabels++;
                    
                    long labelDuration = System.currentTimeMillis() - labelStart;
                    logD("⏱️ [+" + labelDuration + "ms] ✅ Label #" + currentLabel + " sent successfully");
                    
                    // 🎯 Her etiket sonrası PrinterStatus ile durum kontrolü
                    try {
                        ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
                        PrinterStatus status = printer.getCurrentStatus();
                        
                        // SDK'dan gerçek kalan etiket sayısını al
                        int sdkRemaining = status.labelsRemainingInBatch;
                        logD("📊 SDK labelsRemainingInBatch: " + sdkRemaining);
                        
                        // Progress callback with full status
                        sendBatchPrintProgressWithStatus(PRINT_STATE_PRINTING, progress, currentLabel, totalLabels, macAddress, status);
                        
                        // 🚨 Kritik hata kontrolü
                        if (status.isPaperOut) {
                            errorAlreadySent = true;
                            sendPrintError("Paper out", macAddress);
                            throw new ConnectionException("Paper out at label #" + currentLabel);
                        }
                        if (status.isHeadOpen) {
                            errorAlreadySent = true;
                            sendPrintError("Head open", macAddress);
                            throw new ConnectionException("Head open at label #" + currentLabel);
                        }
                        if (status.isRibbonOut) {
                            errorAlreadySent = true;
                            sendPrintError("Ribbon out", macAddress);
                            throw new ConnectionException("Ribbon out at label #" + currentLabel);
                        }
                        
                        // ⏳ Yazıcı yazdırıyorsa bekle (bir sonraki etiket için)
                        int waitCount = 0;
                        int maxWait = 10; // Max 2 saniye bekle (10 x 200ms)
                        while (status.isPartialFormatInProgress && waitCount < maxWait) {
                            // ✅ ZEBRA SDK BEST PRACTICE: Status polling interval 200ms
                            Thread.sleep(200);
                            status = printer.getCurrentStatus();
                            waitCount++;
                        }
                        
                    } catch (ZebraPrinterLanguageUnknownException e) {
                        logW("Could not get printer status after label #" + currentLabel);
                        // Devam et - status alınamasa bile yazdırma başarılı olmuş olabilir
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    
                } catch (ConnectionException e) {
                    logE("❌ Write failed for label #" + currentLabel + ": " + e.getMessage());
                    throw new ConnectionException("Failed to send label #" + currentLabel + ": " + e.getMessage(), e);
                }
            }
            
            long batchWriteDuration = System.currentTimeMillis() - stepStart;
            logD("⏱️ [+" + batchWriteDuration + "ms] ✅ All " + totalLabels + " labels sent successfully (total: " + totalBytes + " bytes)");
            
            // 🎯 Son durum kontrolü - yazıcı fiziksel yazdırmayı bitirene kadar bekle
            try {
                ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
                PrinterStatus status = printer.getCurrentStatus();
                
                int waitCount = 0;
                int maxWait = 25; // Max 5 saniye bekle (25 x 200ms)
                while (status.isPartialFormatInProgress && waitCount < maxWait) {
                    int progress = 95 + (5 * Math.min(waitCount, maxWait) / maxWait);
                    
                    sendBatchPrintProgressWithStatus(PRINT_STATE_VERIFYING, progress, totalLabels, totalLabels, macAddress, status);
                    logD("📊 Waiting for printer to finish physical printing, poll #" + waitCount);
                    
                    Thread.sleep(200);
                    status = printer.getCurrentStatus();
                    waitCount++;
                }
                
                logD("✅ All labels printed, final status check completed");
                
            } catch (Exception e) {
                logW("Could not verify final print status: " + e.getMessage());
            }
            
            // 📊 Batch Progress: COMPLETED (100%)
            sendBatchPrintProgress(PRINT_STATE_COMPLETED, 100, totalLabels, totalLabels, macAddress);
            batchSuccess = true;
            
        } catch (Exception e) {
            if (!errorAlreadySent) {
                sendPrintError("Could not connect to printer", macAddress);
            }
            
            if (connection != null) {
                closeCachedConnection(macAddress, connection);
                connection = null;
            }
            throw e;
        } finally {
            if (batchSuccess && connection != null) {
                scheduleConnectionClose(macAddress, connection);
            }
            
            // ⏱️ BENCHMARK END - TOTAL DURATION
            long totalDuration = System.currentTimeMillis() - benchmarkStart;
            logD("════════════════════════════════════════════════════════");
            logD("⚡ BATCH PRINT END - Total Duration: " + totalDuration + "ms");
            logD("   Average per label: " + (zplList.size() > 0 ? (totalDuration / zplList.size()) : 0) + "ms");
            logD("════════════════════════════════════════════════════════");
        }
    }
    
    /**
     * Yazıcı hakkında detaylı bilgi alır (model, seri numarası, firmware vb.)
     * @param macAddress MAC adresi
     * @return Yazıcı bilgisi
     * @throws ConnectionException Bağlantı hatası
     * @throws ZebraPrinterLanguageUnknownException Yazıcı dili bilinmiyor
     */
    private String getPrinterInfo(String macAddress) 
            throws ConnectionException, ZebraPrinterLanguageUnknownException {
        
        logD("getPrinterInfo called");
        logD("Getting printer info for: " + macAddress);
        
        Connection connection = null;
        StringBuilder info = new StringBuilder();
        
        try {
            logD("getPrinterInfo method started for: " + macAddress);
            
            // ✅ ZEBRA SDK BEST PRACTICE: Open connection before use
            logD("Opening connection for getPrinterInfo");
            connection = new BluetoothConnection(macAddress);
            connection.open();
            
            logD("Creating ZebraPrinter instance...");
            // Zebra Printer nesnesini oluştur
            ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
            
            logD("Getting printer information via SGD commands...");
            // SGD komutları ile yazıcı bilgilerini al
            String model = SGD.GET("device.product_name", connection);
            logD("Model: " + model);
            
            String serialNumber = SGD.GET("device.unique_id", connection);
            logD("Serial: " + serialNumber);
            
            String firmware = SGD.GET("appl.name", connection);
            logD("Firmware: " + firmware);
            
            info.append("Model: ").append(model).append("\n");
            info.append("Serial: ").append(serialNumber).append("\n");
            info.append("Firmware: ").append(firmware).append("\n");
            
            logD("Getting printer control language...");
            // Yazıcı dili bilgisini al
            PrinterLanguage language = printer.getPrinterControlLanguage();
            logD("Language: " + language.toString());
            info.append("Language: ").append(language.toString()).append("\n");
            
            logD("Printer info collected successfully: " + info.toString().trim());
            return info.toString();
            
        } finally {
            // ✅ ZEBRA SDK BEST PRACTICE: Close connection in finally block
            if (connection != null) {
                try {
                    connection.close();
                    logD("✅ Connection closed successfully");
                } catch (ConnectionException e) {
                    logW("⚠️ Error closing connection: " + e.getMessage());
                }
            }
        }
    }
    
    // ==================== ZEBRA SDK PRINTER STATUS ====================

    /**
     * 🎯 KAPSAMLI YAZICI DURUMU - Zebra SDK PrinterStatus sınıfını kullanır
     * Yazıcının tüm durumlarını alır: hazır mı, yazdırıyor mu, hata var mı vs.
     * 
     * Bu metod, kullanıcının yazıcı durumunu anlık olarak görmesini sağlar:
     * - Yazıcı hazır mı? (isReadyToPrint)
     * - Şu an yazdırıyor mu? (isPartialFormatInProgress)
     * - Kağıt var mı? (isPaperOut)
     * - Kafa açık mı? (isHeadOpen)
     * - Kafa soğuk/sıcak mı? (isHeadCold, isHeadTooHot)
     * - Ribon var mı? (isRibbonOut)
     * - Buffer dolu mu? (isReceiveBufferFull)
     * - Batch'te kaç etiket kaldı? (labelsRemainingInBatch)
     * 
     * @param macAddress Yazıcı MAC adresi
     * @return Kapsamlı yazıcı durumu Map'i
     */
    private Map<String, Object> getZebraPrinterStatus(String macAddress) {
        logD("📊 getZebraPrinterStatus called for: " + macAddress);
        
        Connection connection = null;
        Map<String, Object> statusMap = new HashMap<>();
        
        // Default değerler
        statusMap.put("address", macAddress);
        statusMap.put("timestamp", System.currentTimeMillis());
        
        try {
            // ✅ ZEBRA SDK BEST PRACTICE: Open connection
            logD("Opening connection for getZebraPrinterStatus...");
            connection = new BluetoothConnection(macAddress);
            connection.open();
            
            // 🎯 ZEBRA SDK: PrinterStatus sınıfını kullan
            ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
            PrinterStatus status = printer.getCurrentStatus();
            
            // ==================== TEMEL DURUMLAR ====================
            statusMap.put("isReadyToPrint", status.isReadyToPrint);
            statusMap.put("isPrinting", status.isPartialFormatInProgress);
            statusMap.put("isPartialFormatInProgress", status.isPartialFormatInProgress);
            statusMap.put("isPaused", status.isPaused);
            
            // ==================== HATA DURUMLARI ====================
            statusMap.put("isPaperOut", status.isPaperOut);
            statusMap.put("isHeadOpen", status.isHeadOpen);
            statusMap.put("isHeadCold", status.isHeadCold);
            statusMap.put("isHeadTooHot", status.isHeadTooHot);
            statusMap.put("isRibbonOut", status.isRibbonOut);
            statusMap.put("isReceiveBufferFull", status.isReceiveBufferFull);
            
            // ==================== BATCH BİLGİLERİ ====================
            statusMap.put("labelsRemainingInBatch", status.labelsRemainingInBatch);
            statusMap.put("numberOfFormatsInBuffer", status.numberOfFormatsInReceiveBuffer);
            
            // ==================== EK BİLGİLER ====================
            statusMap.put("labelLengthInDots", status.labelLengthInDots);
            
            // PrintMode'u string olarak ekle
            if (status.printMode != null) {
                statusMap.put("printMode", status.printMode.toString());
            }
            
            // Bağlantı başarılı
            statusMap.put("isConnected", true);
            
            // ==================== HELPER DEĞERLERİ ====================
            // hasError: Herhangi bir hata var mı?
            boolean hasError = status.isPaperOut || status.isHeadOpen || 
                             status.isHeadCold || status.isHeadTooHot || 
                             status.isRibbonOut || status.isReceiveBufferFull;
            statusMap.put("hasError", hasError);
            
            // canPrint: Yazdırabilir mi?
            boolean canPrint = status.isReadyToPrint && !status.isPaused && 
                             !status.isPaperOut && !status.isHeadOpen && 
                             !status.isHeadCold && !status.isHeadTooHot && 
                             !status.isRibbonOut && !status.isReceiveBufferFull;
            statusMap.put("canPrint", canPrint);
            
            // isBusy: Meşgul mü?
            boolean isBusy = status.isPartialFormatInProgress || 
                           status.numberOfFormatsInReceiveBuffer > 0 || 
                           status.labelsRemainingInBatch > 0;
            statusMap.put("isBusy", isBusy);
            
            // statusCode: Durum kodu
            String statusCode = getStatusCode(status);
            statusMap.put("statusCode", statusCode);
            
            // statusMessage: Kullanıcı dostu mesaj
            String statusMessage = getStatusMessage(status);
            statusMap.put("statusMessage", statusMessage);
            
            logD("✅ Zebra PrinterStatus retrieved: " + statusCode + 
                      " | Ready: " + status.isReadyToPrint + 
                      " | Printing: " + status.isPartialFormatInProgress +
                      " | CanPrint: " + canPrint);
            
            return statusMap;
            
        } catch (ZebraPrinterLanguageUnknownException e) {
            logE("❌ Printer language unknown: " + e.getMessage());
            statusMap.put("isConnected", true); // Bağlandı ama dil algılanamadı
            statusMap.put("error", "Printer language unknown: " + e.getMessage());
            statusMap.put("canPrint", false);
            return statusMap;
            
        } catch (ConnectionException e) {
            logE("❌ Connection error: " + e.getMessage());
            statusMap.put("isConnected", false);
            statusMap.put("error", "Connection error: " + e.getMessage());
            statusMap.put("canPrint", false);
            return statusMap;
            
        } catch (Exception e) {
            logE("❌ Unexpected error: " + e.getMessage());
            statusMap.put("isConnected", false);
            statusMap.put("error", "Unexpected error: " + e.getMessage());
            statusMap.put("canPrint", false);
            return statusMap;
            
        } finally {
            // ✅ ZEBRA SDK BEST PRACTICE: Close connection in finally block
            if (connection != null) {
                try {
                    connection.close();
                    logD("✅ Connection closed after status check");
                } catch (ConnectionException e) {
                    logW("⚠️ Error closing connection: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Tüm cache'teki connection'ları kapatır
     * dispose() öncesi veya gerektiğinde çağrılır
     */
    public void closeActiveConnection() {
        logD("🔌 closeActiveConnection - clearing connection cache");
        for (Map.Entry<String, CachedConnection> entry : new HashMap<>(connectionCache).entrySet()) {
            CachedConnection cached = entry.getValue();
            if (cached.closeRunnable != null) {
                mainHandler.removeCallbacks(cached.closeRunnable);
            }
            try {
                cached.connection.close();
            } catch (Exception e) {
                logW("Error closing connection: " + e.getMessage());
            }
        }
        connectionCache.clear();
    }
    
    // ==================== LOGGING HELPERS ====================
    
    /**
     * 🔧 Log Debug - Only logs if LOGGING_ENABLED is true
     */
    private void logD(String message) {
        if (LOGGING_ENABLED) {
            Log.d(TAG, message);
        }
    }
    
    /**
     * 🔧 Log Warning - Only logs if LOGGING_ENABLED is true
     */
    private void logW(String message) {
        if (LOGGING_ENABLED) {
            Log.w(TAG, message);
        }
    }
    
    /**
     * 🔧 Log Error - Always logs (errors are important)
     */
    private void logE(String message) {
        Log.e(TAG, message);
    }
    
    /**
     * 🔧 Log Info - Only logs if LOGGING_ENABLED is true
     */
    private void logI(String message) {
        if (LOGGING_ENABLED) {
            Log.i(TAG, message);
        }
    }
    
    // ==================== STATUS HELPERS ====================
    
    /**
     * PrinterStatus'tan durum kodu döndürür (logging için)
     */
    private String getStatusCode(PrinterStatus status) {
        if (status.isPaperOut) return "PAPER_OUT";
        if (status.isHeadOpen) return "HEAD_OPEN";
        if (status.isRibbonOut) return "RIBBON_OUT";
        if (status.isHeadCold) return "HEAD_COLD";
        if (status.isHeadTooHot) return "HEAD_HOT";
        if (status.isReceiveBufferFull) return "BUFFER_FULL";
        if (status.isPartialFormatInProgress) return "PRINTING";
        if (status.isPaused) return "PAUSED";
        if (status.isReadyToPrint) return "READY";
        return "UNKNOWN";
    }

    /**
     * PrinterStatus'tan kullanıcı dostu mesaj döndürür
     */
    private String getStatusMessage(PrinterStatus status) {
        if (status.isPaperOut) return "PAPER_OUT";
        if (status.isHeadOpen) return "HEAD_OPEN";
        if (status.isRibbonOut) return "RIBBON_OUT";
        if (status.isHeadCold) return "HEAD_COLD";
        if (status.isHeadTooHot) return "HEAD_HOT";
        if (status.isReceiveBufferFull) return "BUFFER_FULL";
        if (status.isPartialFormatInProgress) return "PRINTING";
        if (status.isPaused) return "PAUSED";
        if (status.labelsRemainingInBatch > 0) return "LABELS_REMAINING:" + status.labelsRemainingInBatch;
        if (status.isReadyToPrint) return "READY";
        return "UNKNOWN";
    }

    /**
     * Kaynakları temizler
     * ✅ Best practice: awaitTermination ile graceful shutdown
     */
    public void dispose() {
        logD("🧹 Disposing PrinterManager, closing all connections...");
        closeActiveConnection();
        isDiscovering = false;
        
        // ✅ ExecutorService graceful shutdown
        executorService.shutdown();
        try {
            // Aktif görevlerin tamamlanması için 5 saniye bekle
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                logW("⚠️ ExecutorService did not terminate in time, forcing shutdown...");
                executorService.shutdownNow();
                
                // Force shutdown sonrası 2 saniye daha bekle
                if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                    logE("❌ ExecutorService could not be terminated");
                }
            }
        } catch (InterruptedException e) {
            logW("⚠️ Shutdown interrupted, forcing shutdown...");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logD("✅ PrinterManager disposed successfully");
    }
    
    // ==================== FONT UPLOAD ====================
    
    /**
     * Uploads a font to the zebra printer using Link-OS SDK FontUtil
     * 
     * @param macAddress MAC address of the printer
     * @param assetPath Path to the asset font file  
     * @param fontType Type of font (arial, helvetica, etc.)
     * @return true if upload successful, false otherwise
     * @throws Exception if upload fails
     */
    private boolean uploadFontToprinter(String macAddress, String assetPath, String fontType) throws Exception {
        logD("📁 Starting font upload: " + assetPath + " to " + macAddress + " as " + fontType);
        
        Connection connection = null;
        InputStream fontStream = null;
        byte[] fontData = null;
        
        try {
            // Get bluetooth connection using existing method
            connection = getCachedConnection(macAddress);
            if (connection == null) {
                // Create new connection if not cached
                connection = new BluetoothConnection(macAddress);
                connection.open();
                logD("📶 Created new connection for font upload: " + macAddress);
            } else {
                logD("♻️ Using cached connection for font upload: " + macAddress);
            }
            
            // Read font data from assets - remove "assets/" prefix for Android
            String androidAssetPath = assetPath;
            if (androidAssetPath.startsWith("assets/")) {
                androidAssetPath = androidAssetPath.substring(7); // Remove "assets/" prefix
            }
            logD("📂 Reading asset: " + androidAssetPath);
            
            try {
                fontStream = context.getAssets().open(androidAssetPath);
                fontData = readAllBytes(fontStream);
                logD("📄 Font data size: " + fontData.length + " bytes");
                
                if (fontData.length == 0) {
                    throw new Exception("Font file is empty: " + androidAssetPath);
                }
            } catch (IOException e) {
                logE("❌ Failed to read asset: " + androidAssetPath + " - " + e.getMessage());
                
                // List available assets for debugging
                try {
                    String[] assetList = context.getAssets().list("arial");
                    logD("Available assets in arial/: " + java.util.Arrays.toString(assetList));
                } catch (IOException listEx) {
                    logE("Failed to list assets: " + listEx.getMessage());
                }
                
                throw new Exception("Could not read font file: " + androidAssetPath + " - " + e.getMessage());
            }
            
            // Analyze font format
            FontDataType dataType = analyzeFontData(fontData);
            logD("🔍 Detected font format: " + dataType);
            
            // Generate printer path: E:FONTNAME (flash storage)
            String printerPath = "E:" + fontType.toUpperCase();
            logD("📍 Target printer path: " + printerPath);
            
            // Process based on format
            boolean success = processFontByType(connection, fontData, dataType, printerPath);
            
            if (success) {
                logD("✅ Font upload completed successfully");
            } else {
                logE("❌ Font upload failed");
            }
            
            return success;
            
        } catch (Exception e) {
            logE("❌ Font upload error: " + e.getMessage());
            throw new Exception("Font upload failed: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            if (fontStream != null) {
                try { fontStream.close(); } catch (IOException ignored) {}
            }
            if (connection != null) {
                try { connection.close(); } catch (ConnectionException ignored) {}
            }
        }
    }
    
    /**
     * Font data type enumeration
     */
    private enum FontDataType {
        TTF_IN_ZPL,     // ~DY command with embedded TTF
        PURE_TTF,       // Direct TTF binary data
        ZPL_COMMANDS    // Raw ZPL font commands
    }
    
    /**
     * Analyzes font data to determine its type
     * 
     * @param data Font file bytes
     * @return FontDataType indicating the format
     */
    private FontDataType analyzeFontData(byte[] data) {
        if (data.length < 10) {
            return FontDataType.ZPL_COMMANDS;
        }
        
        // Check for ZPL command header (~DY or ~DU)
        String header = new String(data, 0, Math.min(50, data.length));
        if (header.contains("~DY") || header.contains("~DU")) {
            logD("🏷️ Found ZPL font command (~DY/~DU)");
            return FontDataType.TTF_IN_ZPL;
        }
        
        // Check for TTF magic number (0x00010000)
        if (data.length >= 4 && 
            data[0] == 0x00 && data[1] == 0x01 && 
            data[2] == 0x00 && data[3] == 0x00) {
            logD("🎯 Found TTF magic number");
            return FontDataType.PURE_TTF;
        }
        
        // Default to ZPL commands
        return FontDataType.ZPL_COMMANDS;
    }
    
    /**
     * Processes font upload based on detected format
     * 
     * @param connection Printer connection
     * @param fontData Raw font bytes
     * @param dataType Detected font format
     * @param printerPath Target path on printer
     * @return true if successful
     */
    private boolean processFontByType(Connection connection, byte[] fontData, FontDataType dataType, String printerPath) throws Exception {
        
        switch (dataType) {
            case TTF_IN_ZPL:
                // Extract TTF from ZPL command and use FontUtil
                logD("🔧 Processing TTF-in-ZPL format");
                byte[] ttfData = extractTtfFromZplCommand(fontData);
                return uploadTtfWithFontUtil(connection, ttfData, printerPath);
                
            case PURE_TTF:
                // Direct TTF - use FontUtil
                logD("🔧 Processing pure TTF format");
                return uploadTtfWithFontUtil(connection, fontData, printerPath);
                
            case ZPL_COMMANDS:
                // Raw ZPL - use connection.write
                logD("🔧 Processing raw ZPL commands");
                connection.write(fontData);
                return true;
                
            default:
                throw new Exception("Unsupported font format: " + dataType);
        }
    }
    
    /**
     * Uploads TTF font using Zebra Link-OS FontUtil API
     * 
     * @param connection Printer connection
     * @param ttfData TTF binary data
     * @param printerPath Target path on printer (E:ARIAL)
     * @return true if successful
     */
    private boolean uploadTtfWithFontUtil(Connection connection, byte[] ttfData, String printerPath) throws Exception {
        try {
            // Create Link-OS printer instance
            ZebraPrinter genericPrinter = ZebraPrinterFactory.getInstance(connection);
            ZebraPrinterLinkOs linkOsPrinter = ZebraPrinterFactory.createLinkOsPrinter(genericPrinter);
            
            if (linkOsPrinter == null) {
                throw new Exception("Failed to create Link-OS printer instance");
            }
            
            // Upload TTF using FontUtil API
            logD("🚀 Uploading TTF via Link-OS FontUtil to: " + printerPath);
            InputStream ttfStream = new ByteArrayInputStream(ttfData);
            linkOsPrinter.downloadTtfFont(ttfStream, printerPath);
            
            logD("✅ FontUtil upload completed");
            return true;
            
        } catch (Exception e) {
            logE("❌ FontUtil upload failed: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Extracts TTF binary data from ZPL ~DY command
     * Format: ~DYE:fontname,b,T,size,,<TTF_BINARY_DATA>
     * 
     * @param zplData Complete ZPL command with embedded TTF
     * @return TTF binary data
     */
    private byte[] extractTtfFromZplCommand(byte[] zplData) throws Exception {
        // Find the position after the second comma (after size parameter)
        // Format: ~DYE:arial,b,T,915212,,<TTF_DATA>
        
        String header = new String(zplData, 0, Math.min(100, zplData.length));
        logD("🔍 ZPL header: " + header.substring(0, Math.min(50, header.length())));
        
        // Find the start of TTF data (after ",,")
        int ttfStart = -1;
        for (int i = 0; i < zplData.length - 1; i++) {
            if (zplData[i] == ',' && zplData[i + 1] == ',') {
                ttfStart = i + 2;
                break;
            }
        }
        
        if (ttfStart == -1) {
            throw new Exception("Could not find TTF data start in ZPL command");
        }
        
        logD("📍 TTF data starts at byte: " + ttfStart);
        
        // Extract TTF portion
        byte[] ttfData = new byte[zplData.length - ttfStart];
        System.arraycopy(zplData, ttfStart, ttfData, 0, ttfData.length);
        
        logD("📦 Extracted TTF size: " + ttfData.length + " bytes");
        
        // Validate TTF magic number
        if (ttfData.length >= 4 && 
            ttfData[0] == 0x00 && ttfData[1] == 0x01 && 
            ttfData[2] == 0x00 && ttfData[3] == 0x00) {
            logD("✅ TTF magic number validated");
            return ttfData;
        } else {
            throw new Exception("Invalid TTF data - magic number not found");
        }
    }
    
    /**
     * Reads all bytes from InputStream
     * 
     * @param inputStream Input stream to read from
     * @return All bytes as array
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        
        return outputStream.toByteArray();
    }
    
    /**
     * Uploads font data directly from byte array (from main app)
     * 
     * @param macAddress MAC address of the printer
     * @param fontData Font file bytes from main app
     * @param fontType Type of font (arial, helvetica, etc.)
     * @return true if upload successful, false otherwise
     * @throws Exception if upload fails
     */
    private boolean uploadFontDataToPrinter(String macAddress, byte[] fontData, String fontType) throws Exception {
        logD("📁 Starting font data upload: " + fontData.length + " bytes to " + macAddress + " as " + fontType);
        
        Connection connection = null;
        
        try {
            // Get bluetooth connection using existing method
            connection = getCachedConnection(macAddress);
            if (connection == null) {
                // Create new connection if not cached
                connection = new BluetoothConnection(macAddress);
                connection.open();
                logD("📶 Created new connection for font data upload: " + macAddress);
            } else {
                logD("♻️ Using cached connection for font data upload: " + macAddress);
            }
            
            logD("📄 Font data size: " + fontData.length + " bytes");
            
            if (fontData.length == 0) {
                throw new Exception("Font data is empty");
            }
            
            // Analyze font format
            FontDataType dataType = analyzeFontData(fontData);
            logD("🔍 Detected font format: " + dataType);
            
            // Generate printer path: E:FONTNAME (flash storage)
            String printerPath = "E:" + fontType.toUpperCase();
            logD("📍 Target printer path: " + printerPath);
            
            // Process based on format
            boolean success = processFontByType(connection, fontData, dataType, printerPath);
            
            if (success) {
                logD("✅ Font data upload completed successfully");
            } else {
                logE("❌ Font data upload failed");
            }
            
            return success;
            
        } catch (Exception e) {
            logE("❌ Font data upload error: " + e.getMessage());
            throw new Exception("Font data upload failed: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            if (connection != null) {
                try { connection.close(); } catch (ConnectionException ignored) {}
            }
        }
    }
}
