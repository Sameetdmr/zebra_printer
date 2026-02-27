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
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.SGD;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterBluetooth;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterNetwork;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import com.zebra.sdk.printer.discovery.BluetoothDiscoverer;
import com.zebra.sdk.printer.discovery.NetworkDiscoverer;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final long CONNECTION_IDLE_TIMEOUT_MS = 5000; // 5 sn - reuse penceresi

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
            try {
                connection.close();
                logD("🔒 Connection closed immediately");
            } catch (Exception e) {
                logW("Error closing connection: " + e.getMessage());
            }
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
        progressData.put("message", "Printing label " + currentLabel + " of " + totalLabels);
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
        progressData.put("message", "Etiket " + currentLabel + "/" + totalLabels + " yazdırılıyor");
        progressData.put("currentLabel", currentLabel);
        progressData.put("totalLabels", totalLabels);
        progressData.put("address", address);
        progressData.put("timestamp", System.currentTimeMillis());
        progressData.put("isBatch", true);
        
        // 🎯 Add PrinterStatus from Zebra SDK
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
                        mainHandler.post(() -> result.success("Baskı başarılı: " + macAddress));
                    } catch (Exception e) {
                        final String errorMessage = "Yazıcı veya Bağlantı Hatası: " + e.getMessage();
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
                        mainHandler.post(() -> result.success("Batch baskı başarılı: " + batchMacAddress + " (" + zplList.size() + " etiket)"));
                    } catch (Exception e) {
                        final String errorMessage = "Batch yazıcı veya Bağlantı Hatası: " + e.getMessage();
                        mainHandler.post(() -> result.error("BATCH_PRINT_FAIL", errorMessage, e.toString()));
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
            result.error("ALREADY_DISCOVERING", "Zaten keşif işlemi devam ediyor", null);
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
            result.error("INVALID_ADDRESS", "Geçersiz cihaz adresi", null);
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
                mainHandler.post(() -> result.error("UNPAIR_FAILED", "Eşleşme kaldırma hatası: " + e.getMessage(), e.toString()));
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
            result.error("INVALID_ADDRESS", "Geçersiz yazıcı adresi", null);
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
                    result.error("CONNECTION_FAILED", "Bağlantı hatası: " + e.getMessage(), e.toString());
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
            result.error("INVALID_ADDRESS", "Yazıcı adresi gerekli", null);
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
            sendPrintError("MAC adresi veya ZPL verisi boş olamaz.", macAddress);
            throw new IllegalArgumentException("MAC adresi veya ZPL verisi boş olamaz.");
        }

        // 📊 Progress: IDLE -> CONNECTING (0% -> 10%)
        sendPrintProgress(PRINT_STATE_CONNECTING, 10, "Yazıcıya bağlanılıyor...", macAddress);

        Connection connection = null;
        ZebraPrinter printer = null;
        boolean success = false;
        
        try {
            // ✅ SDK Connection Reuse: Try cached connection first (rapid successive prints)
            connection = getCachedConnection(macAddress);
            
            if (connection == null) {
                stepStart = System.currentTimeMillis();
                logD("⏱️ Opening connection to: " + macAddress);
                
                try {
                    connection = new BluetoothConnection(macAddress);
                    connection.open();
                    
                    long openDuration = System.currentTimeMillis() - stepStart;
                    logD("⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully");
                    
                } catch (ConnectionException e) {
                    long attemptDuration = System.currentTimeMillis() - stepStart;
                    logE("⏱️ [+" + attemptDuration + "ms] ❌ Connection failed: " + e.getMessage());
                    
                    String userMessage;
                    if (e.getMessage().contains("timeout") || e.getMessage().contains("timed out")) {
                        userMessage = "Yazıcıya ulaşılamıyor. Yazıcının açık ve yakında olduğundan emin olun.";
                    } else if (e.getMessage().contains("refused") || e.getMessage().contains("failed")) {
                        userMessage = "Yazıcı bağlantısı reddedildi. Yazıcının açık olduğundan emin olun.";
                    } else {
                        userMessage = "Bağlantı hatası: " + e.getMessage();
                    }
                    
                    sendPrintError(userMessage, macAddress);
                    throw new ConnectionException(userMessage, e);
                }
            }
            
            // 📊 Progress: CONNECTED (20%)
            sendPrintProgress(PRINT_STATE_CONNECTING, 20, "Bağlantı kuruldu", macAddress);

            // Veri Gönderme: ZPL verisini yazar (UTF-8 kodlaması ile)
            stepStart = System.currentTimeMillis();
            logD("⏱️ Sending ZPL data (" + zplData.length() + " bytes)...");
            
            // ✅ Connection state kontrolü (güvenlik için)
            if (connection == null || !connection.isConnected()) {
                sendPrintError("Bağlantı açık değil", macAddress);
                throw new ConnectionException("Connection is not open or not connected");
            }
            
            // 📊 Progress: SENDING (30%)
            sendPrintProgress(PRINT_STATE_SENDING, 30, "Veri gönderiliyor...", macAddress);
            
            // ✅ connection.write() blocking call - ZPL tamamen gönderilene kadar bekler
            // Zebra SDK asenkron printing'i halleder, ekstra sleep gereksiz!
            try {
                connection.write(zplData.getBytes("UTF-8"));
            } catch (ConnectionException e) {
                // Broken pipe veya connection lost durumunda
                logE("❌ Write failed: " + e.getMessage());
                sendPrintError("Veri gönderme hatası: " + e.getMessage(), macAddress);
                throw new ConnectionException("Failed to send ZPL data: " + e.getMessage(), e);
            }
            
            long writeDuration = System.currentTimeMillis() - stepStart;
            logD("⏱️ [+" + writeDuration + "ms] ✅ ZPL data sent successfully");
            
            // 📊 Progress: PRINTING (50%)
            sendPrintProgress(PRINT_STATE_PRINTING, 50, "Yazdırılıyor...", macAddress);
            
            // 🔄 PRINT STATUS POLLING: Zebra SDK ile yazıcı durumunu izle
            try {
                printer = ZebraPrinterFactory.getInstance(connection);
                PrinterStatus status = printer.getCurrentStatus();
                
                int pollCount = 0;
                int maxPolls = 15; // Max 3 saniye bekle (15 x 200ms)
                
                // Yazıcı yazdırırken veya format işlerken bekle
                while ((status.isPartialFormatInProgress || !status.isReadyToPrint) && pollCount < maxPolls) {
                    pollCount++;
                    int progress = 50 + (pollCount * 40 / maxPolls); // 50% -> 90%
                    
                    // 🎯 Status durumuna göre mesaj belirle
                    String statusMessage;
                    if (status.isPartialFormatInProgress) {
                        statusMessage = "Etiket yazdırılıyor...";
                    } else if (status.isPaused) {
                        statusMessage = "Yazıcı duraklatıldı";
                    } else if (status.isPaperOut) {
                        statusMessage = "Kağıt bitti!";
                    } else if (status.isHeadOpen) {
                        statusMessage = "Yazıcı kafası açık!";
                    } else if (status.isHeadCold) {
                        statusMessage = "Yazıcı kafası ısınıyor...";
                    } else if (status.isHeadTooHot) {
                        statusMessage = "Yazıcı kafası soğuyor...";
                    } else if (status.isRibbonOut) {
                        statusMessage = "Ribon bitti!";
                    } else {
                        statusMessage = "İşleniyor...";
                    }
                    
                    // 🎯 Send progress WITH full PrinterStatus
                    sendPrintProgressWithStatus(PRINT_STATE_PRINTING, progress, statusMessage, macAddress, status);
                    
                    // ✅ ZEBRA SDK BEST PRACTICE: Status polling interval 200ms
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    status = printer.getCurrentStatus();
                }
                
                // 🎯 Son durum kontrolü - tüm kritik hatalar
                if (status.isPaperOut) {
                    sendPrintProgressWithStatus(PRINT_STATE_ERROR, 0, "Kağıt bitti!", macAddress, status);
                    throw new ConnectionException("Paper out");
                }
                if (status.isHeadOpen) {
                    sendPrintProgressWithStatus(PRINT_STATE_ERROR, 0, "Yazıcı kafası açık!", macAddress, status);
                    throw new ConnectionException("Head open");
                }
                if (status.isRibbonOut) {
                    sendPrintProgressWithStatus(PRINT_STATE_ERROR, 0, "Ribon bitti!", macAddress, status);
                    throw new ConnectionException("Ribbon out");
                }
                
            } catch (ZebraPrinterLanguageUnknownException e) {
                logW("Could not get printer status: " + e.getMessage());
                // Status alamadık ama print başarılı olmuş olabilir, devam et
            }
            
            // 📊 Progress: VERIFYING (90%)
            sendPrintProgress(PRINT_STATE_VERIFYING, 90, "Doğrulanıyor...", macAddress);
            
            // 📊 Progress: COMPLETED (100%)
            sendPrintProgress(PRINT_STATE_COMPLETED, 100, "Yazdırma tamamlandı", macAddress);
            success = true;

        } catch (Exception e) {
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
            sendPrintError("MAC adresi boş olamaz.", macAddress);
            throw new IllegalArgumentException("MAC adresi boş olamaz.");
        }
        
        if (zplList == null || zplList.isEmpty()) {
            sendPrintError("ZPL listesi boş olamaz.", macAddress);
            throw new IllegalArgumentException("ZPL listesi boş olamaz.");
        }
        
        // 📊 Batch Progress: CONNECTING (5%)
        sendBatchPrintProgress(PRINT_STATE_CONNECTING, 5, 0, totalLabels, macAddress);
        
        Connection connection = null;
        boolean batchSuccess = false;
        
        try {
            connection = getCachedConnection(macAddress);
            
            if (connection == null) {
                stepStart = System.currentTimeMillis();
                logD("⏱️ Opening connection to: " + macAddress + " (batch mode)");
                
                try {
                    connection = new BluetoothConnection(macAddress);
                    connection.open();
                    
                    long openDuration = System.currentTimeMillis() - stepStart;
                    logD("⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully (batch mode)");
                    
                } catch (ConnectionException e) {
                    long attemptDuration = System.currentTimeMillis() - stepStart;
                    logE("⏱️ [+" + attemptDuration + "ms] ❌ Connection failed: " + e.getMessage());
                    
                    String userMessage;
                    if (e.getMessage().contains("timeout") || e.getMessage().contains("timed out")) {
                        userMessage = "Yazıcıya ulaşılamıyor. Yazıcının açık ve yakında olduğundan emin olun.";
                    } else if (e.getMessage().contains("refused") || e.getMessage().contains("failed")) {
                        userMessage = "Yazıcı bağlantısı reddedildi. Yazıcının açık olduğundan emin olun.";
                    } else {
                        userMessage = "Bağlantı hatası: " + e.getMessage();
                    }
                    
                    sendPrintError(userMessage, macAddress);
                    throw new ConnectionException(userMessage, e);
                }
            }
            
            // 📊 Batch Progress: CONNECTED (10%)
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
                    sendPrintError("Bağlantı koptu: Etiket #" + currentLabel, macAddress);
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
                            sendPrintError("Kağıt bitti! (Etiket #" + currentLabel + ")", macAddress);
                            throw new ConnectionException("Paper out at label #" + currentLabel);
                        }
                        if (status.isHeadOpen) {
                            sendPrintError("Yazıcı kafası açık! (Etiket #" + currentLabel + ")", macAddress);
                            throw new ConnectionException("Head open at label #" + currentLabel);
                        }
                        if (status.isRibbonOut) {
                            sendPrintError("Ribon bitti! (Etiket #" + currentLabel + ")", macAddress);
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
                    sendPrintError("Etiket #" + currentLabel + " gönderilemedi: " + e.getMessage(), macAddress);
                    throw new ConnectionException("Failed to send label #" + currentLabel + ": " + e.getMessage(), e);
                }
            }
            
            long batchWriteDuration = System.currentTimeMillis() - stepStart;
            logD("⏱️ [+" + batchWriteDuration + "ms] ✅ All " + totalLabels + " labels sent successfully (total: " + totalBytes + " bytes)");
            
            // 🎯 Son durum kontrolü - tüm etiketler yazdırılana kadar bekle
            try {
                ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
                PrinterStatus status = printer.getCurrentStatus();
                
                int waitCount = 0;
                int maxWait = 25; // Max 5 saniye bekle (25 x 200ms)
                while ((status.isPartialFormatInProgress || status.labelsRemainingInBatch > 0) && waitCount < maxWait) {
                    int remaining = status.labelsRemainingInBatch;
                    int printed = totalLabels - remaining;
                    int progress = 95 + (5 * printed / Math.max(1, totalLabels));
                    
                    sendBatchPrintProgressWithStatus(PRINT_STATE_VERIFYING, progress, printed, totalLabels, macAddress, status);
                    logD("📊 Waiting for print completion: " + remaining + " labels remaining");
                    
                    // ✅ ZEBRA SDK BEST PRACTICE: Status polling interval 200ms
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
            info.append("Seri No: ").append(serialNumber).append("\n");
            info.append("Firmware: ").append(firmware).append("\n");
            
            logD("Getting printer control language...");
            // Yazıcı dili bilgisini al
            PrinterLanguage language = printer.getPrinterControlLanguage();
            logD("Language: " + language.toString());
            info.append("Dil: ").append(language.toString()).append("\n");
            
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
            statusMap.put("error", "Yazıcı dili algılanamadı: " + e.getMessage());
            statusMap.put("canPrint", false);
            return statusMap;
            
        } catch (ConnectionException e) {
            logE("❌ Connection error: " + e.getMessage());
            statusMap.put("isConnected", false);
            statusMap.put("error", "Bağlantı hatası: " + e.getMessage());
            statusMap.put("canPrint", false);
            return statusMap;
            
        } catch (Exception e) {
            logE("❌ Unexpected error: " + e.getMessage());
            statusMap.put("isConnected", false);
            statusMap.put("error", "Beklenmeyen hata: " + e.getMessage());
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
        if (status.isPaperOut) return "🔴 Kağıt bitti!";
        if (status.isHeadOpen) return "🔴 Yazıcı kafası açık!";
        if (status.isRibbonOut) return "🔴 Ribon bitti!";
        if (status.isHeadCold) return "🟡 Yazıcı kafası ısınıyor...";
        if (status.isHeadTooHot) return "🟡 Yazıcı kafası soğuyor...";
        if (status.isReceiveBufferFull) return "🟡 Yazıcı meşgul, sıra bekleniyor...";
        if (status.isPartialFormatInProgress) return "🔵 Yazdırılıyor...";
        if (status.isPaused) return "🟡 Yazıcı duraklatıldı";
        if (status.labelsRemainingInBatch > 0) return "🔵 " + status.labelsRemainingInBatch + " etiket kaldı";
        if (status.isReadyToPrint) return "🟢 Yazıcı hazır";
        return "⚪ Durum bilinmiyor";
    }

    /**
     * Kaynakları temizler
     */
    public void dispose() {
        logD("🧹 Disposing PrinterManager, closing all connections...");
        closeActiveConnection();
        isDiscovering = false;
        
        // ExecutorService'i kapat
        executorService.shutdown();
        
        logD("✅ PrinterManager disposed successfully");
    }
}
