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

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private MethodChannel methodChannel;
    
    // Discovery state
    private boolean isDiscovering = false;
    
    // ⚡ MULTI-PRINTER CONNECTION POOL
    private final Map<String, Connection> connectionPool = new HashMap<>();
    private final Map<String, Long> lastPrintTime = new HashMap<>();
    
    // Bağlantı önbellekleme - aynı yazıcıya art arda yazdırmalarda hızlandırma
    private final Map<String, Long> lastConnectionTime = new HashMap<>();
    private static final long CONNECTION_CACHE_DURATION = 30000; // 30 saniye (optimized for BT stack)
    
    // ⚡ FAST RETAIL MODE: Keep connection open for rapid successive prints
    private static final long FAST_PRINT_WINDOW = 5000; // 5 saniye içindeki basımlar için bağlantıyı açık tut
    private static final int MAX_POOL_SIZE = 3; // Maksimum eş zamanlı açık connection sayısı
    
    // Pending async close tracker
    private final Map<String, Boolean> pendingCloseOperations = new HashMap<>();
    
    // Legacy single connection support (backward compatibility)
    @Deprecated
    private Connection activeConnection = null;
    @Deprecated
    private String connectedAddress = null;

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
                
            case "checkPrinterStatus":
                final String statusAddress = call.argument("address");
                executorService.execute(() -> {
                    try {
                        Map<String, Object> status = checkPrinterStatus(statusAddress);
                        mainHandler.post(() -> result.success(status));
                    } catch (Exception e) {
                        mainHandler.post(() -> result.error("STATUS_FAIL", e.getMessage(), e.toString()));
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
        Log.d(TAG, "startDiscovery called with type: " + discoveryType);
        
        // Context kontrolü
        if (context == null) {
            Log.e(TAG, "Context is null! Cannot start discovery");
            result.error("NO_CONTEXT", "Context is null", null);
            return;
        }
        
        if (isDiscovering) {
            Log.w(TAG, "Already discovering, returning error");
            result.error("ALREADY_DISCOVERING", "Zaten keşif işlemi devam ediyor", null);
            return;
        }
        
        isDiscovering = true;
        final List<Map<String, Object>> discoveredPrinters = new ArrayList<>();
        Log.d(TAG, "Starting discovery on executor thread");
        Log.d(TAG, "Context: " + context.getClass().getName());
        
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Creating DiscoveryHandler");
                DiscoveryHandler discoveryHandler = new DiscoveryHandler() {
                    @Override
                    public void foundPrinter(DiscoveredPrinter discoveredPrinter) {
                        Log.d(TAG, "========================================");
                        Log.d(TAG, "foundPrinter CALLED!");
                        Log.d(TAG, "Printer class: " + discoveredPrinter.getClass().getName());
                        Log.d(TAG, "Printer toString: " + discoveredPrinter.toString());
                        Log.d(TAG, "========================================");
                        
                        Map<String, Object> printerMap = new HashMap<>();
                        
                        // Bluetooth yazıcı
                        if (discoveredPrinter instanceof DiscoveredPrinterBluetooth) {
                            DiscoveredPrinterBluetooth btPrinter = (DiscoveredPrinterBluetooth) discoveredPrinter;
                            Log.d(TAG, "✓ Bluetooth Printer Found!");
                            Log.d(TAG, "  - Address: " + btPrinter.address);
                            Log.d(TAG, "  - Friendly Name: " + btPrinter.friendlyName);
                            
                            printerMap.put("type", "bluetooth");
                            printerMap.put("address", btPrinter.address);
                            printerMap.put("friendlyName", btPrinter.friendlyName != null && !btPrinter.friendlyName.isEmpty() 
                                ? btPrinter.friendlyName 
                                : btPrinter.address);
                            
                        // Network yazıcı
                        } else if (discoveredPrinter instanceof DiscoveredPrinterNetwork) {
                            DiscoveredPrinterNetwork netPrinter = (DiscoveredPrinterNetwork) discoveredPrinter;
                            Log.d(TAG, "Found Network Printer: " + netPrinter.address);
                            printerMap.put("type", "network");
                            printerMap.put("address", netPrinter.address);
                            
                            // Discovery data'dan friendly name'i almaya çalış
                            Map<String, String> discoveryDataMap = netPrinter.getDiscoveryDataMap();
                            String friendlyName = discoveryDataMap.get("PRODUCT_NAME");
                            if (friendlyName == null || friendlyName.isEmpty()) {
                                friendlyName = netPrinter.address;
                            }
                            printerMap.put("friendlyName", friendlyName);
                            Log.d(TAG, "Network printer friendly name: " + friendlyName);
                        }
                        
                        discoveredPrinters.add(printerMap);
                        Log.d(TAG, "Total printers found: " + discoveredPrinters.size());
                        
                        // Flutter'a yazıcı bulundu bildirimi gönder
                        if (methodChannel != null) {
                            Log.d(TAG, "Sending onPrinterFound to Flutter");
                            mainHandler.post(() -> methodChannel.invokeMethod("onPrinterFound", printerMap));
                        } else {
                            Log.w(TAG, "methodChannel is null, cannot send to Flutter");
                        }
                    }

                    @Override
                    public void discoveryFinished() {
                        Log.d(TAG, "========================================");
                        Log.d(TAG, "discoveryFinished CALLED!");
                        Log.d(TAG, "Total printers found: " + discoveredPrinters.size());
                        
                        if (discoveredPrinters.isEmpty()) {
                            Log.w(TAG, "⚠️ NO PRINTERS FOUND!");
                            Log.w(TAG, "Possible reasons:");
                            Log.w(TAG, "  1. No Zebra printers nearby");
                            Log.w(TAG, "  2. Printers not in discoverable mode");
                            Log.w(TAG, "  3. Bluetooth/Location permissions missing");
                            Log.w(TAG, "  4. Printers already paired (try unpair)");
                        } else {
                            Log.d(TAG, "✓ Found " + discoveredPrinters.size() + " printer(s):");
                            for (Map<String, Object> p : discoveredPrinters) {
                                Log.d(TAG, "  - " + p.get("friendlyName") + " (" + p.get("address") + ")");
                            }
                        }
                        Log.d(TAG, "========================================");
                        
                        isDiscovering = false;
                        
                        // Flutter'a keşif tamamlandı bildirimi gönder
                        if (methodChannel != null) {
                            Log.d(TAG, "Sending onDiscoveryFinished to Flutter");
                            mainHandler.post(() -> methodChannel.invokeMethod("onDiscoveryFinished", discoveredPrinters));
                        }
                        
                        // Ana thread'de result döndür
                        mainHandler.post(() -> {
                            Log.d(TAG, "Sending success result with " + discoveredPrinters.size() + " printers");
                            result.success(discoveredPrinters);
                        });
                    }

                    @Override
                    public void discoveryError(String errorMessage) {
                        Log.e(TAG, "Discovery error callback: " + errorMessage);
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
                    Log.d(TAG, "Starting Network discovery only");
                    NetworkDiscoverer.findPrinters(discoveryHandler);
                } else {
                    // "bluetooth" veya "both" için sadece Bluetooth discovery
                    // Bluetooth discovery hem paired hem unpaired cihazları bulur
                    Log.d(TAG, "Starting Bluetooth discovery (type: " + discoveryType + ")");
                    BluetoothDiscoverer.findPrinters(context, discoveryHandler);
                }
                Log.d(TAG, "Discovery method called successfully");
                
            } catch (Exception e) {
                isDiscovering = false;
                Log.e(TAG, "Discovery exception: " + e.getMessage(), e);
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
        Log.d(TAG, "unpairPrinter called for address: " + address);
        
        if (address == null || address.isEmpty()) {
            Log.e(TAG, "Invalid address provided");
            result.error("INVALID_ADDRESS", "Geçersiz cihaz adresi", null);
            return;
        }
        
        if (context == null) {
            Log.e(TAG, "Context is null! Cannot unpair device");
            result.error("NO_CONTEXT", "Context is null", null);
            return;
        }
        
        executorService.execute(() -> {
            try {
                // Eğer bu cihaza bağlıysak önce bağlantıyı kes
                if (activeConnection != null && address.equals(connectedAddress)) {
                    Log.d(TAG, "Device is connected, disconnecting first");
                    try {
                        activeConnection.close();
                        activeConnection = null;
                        connectedAddress = null;
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing connection during unpair: " + e.getMessage());
                    }
                }
                
                // Android Bluetooth API ile eşleşmeyi kaldır
                android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                
                if (bluetoothAdapter == null) {
                    Log.e(TAG, "Bluetooth adapter is null");
                    mainHandler.post(() -> result.error("NO_BLUETOOTH", "Bluetooth not available", null));
                    return;
                }
                
                android.bluetooth.BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                
                // Reflection kullanarak removeBond metodunu çağır
                java.lang.reflect.Method method = device.getClass().getMethod("removeBond");
                method.invoke(device);
                
                Log.d(TAG, "Unpair successful for: " + address);
                mainHandler.post(() -> result.success(true));
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error: " + e.getMessage());
                mainHandler.post(() -> result.error("PERMISSION_DENIED", "Bluetooth permission denied", e.toString()));
            } catch (Exception e) {
                Log.e(TAG, "Unpair error: " + e.getMessage());
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
        Log.d(TAG, "getPairedPrinters called - using Android Bluetooth API");
        
        if (context == null) {
            Log.e(TAG, "Context is null! Cannot get paired devices");
            result.error("NO_CONTEXT", "Context is null", null);
            return;
        }
        
        executorService.execute(() -> {
            try {
                List<Map<String, Object>> pairedPrinters = new ArrayList<>();
                
                // Android Bluetooth API ile eşleşmiş cihazları al
                android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                
                if (bluetoothAdapter == null) {
                    Log.e(TAG, "Bluetooth adapter is null");
                    mainHandler.post(() -> result.error("NO_BLUETOOTH", "Bluetooth not available", null));
                    return;
                }
                
                if (!bluetoothAdapter.isEnabled()) {
                    Log.e(TAG, "Bluetooth is not enabled");
                    mainHandler.post(() -> result.error("BLUETOOTH_OFF", "Bluetooth is turned off", null));
                    return;
                }
                
                // Eşleşmiş (bonded) Bluetooth cihazlarını al
                java.util.Set<android.bluetooth.BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                Log.d(TAG, "Found " + bondedDevices.size() + " paired Bluetooth devices");
                
                for (android.bluetooth.BluetoothDevice device : bondedDevices) {
                    Map<String, Object> deviceInfo = new HashMap<>();
                    deviceInfo.put("address", device.getAddress());
                    deviceInfo.put("friendlyName", device.getName() != null ? device.getName() : device.getAddress());
                    deviceInfo.put("type", "bluetooth");
                    deviceInfo.put("isPaired", true);
                    
                    pairedPrinters.add(deviceInfo);
                    Log.d(TAG, "Paired device: " + device.getName() + " (" + device.getAddress() + ")");
                }
                
                final List<Map<String, Object>> finalList = pairedPrinters;
                mainHandler.post(() -> {
                    Log.d(TAG, "Returning " + finalList.size() + " paired devices");
                    result.success(finalList);
                });
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error: " + e.getMessage());
                mainHandler.post(() -> result.error("PERMISSION_DENIED", "Bluetooth permission denied", e.toString()));
            } catch (Exception e) {
                Log.e(TAG, "Get paired devices error: " + e.getMessage());
                e.printStackTrace();
                mainHandler.post(() -> result.error("GET_PAIRED_ERROR", e.getMessage(), e.toString()));
            }
        });
    }
    
    // ==================== CONNECTION METHODS ====================
    
    /**
     * Zebra yazıcıya bağlanır ve bağlantıyı açık tutar
     * @param address Yazıcı adresi (MAC veya IP)
     * @param result Sonuç callback'i - Boolean döndürür (true: başarılı, false/error: başarısız)
     */
    private void connectToPrinter(String address, @NonNull MethodChannel.Result result) {
        Log.d(TAG, "connectToPrinter called for address: " + address);
        
        if (address == null || address.isEmpty()) {
            Log.e(TAG, "Invalid address provided");
            result.error("INVALID_ADDRESS", "Geçersiz yazıcı adresi", null);
            return;
        }
        
        // Zaten bağlıysa önce kes
        if (activeConnection != null) {
            try {
                Log.d(TAG, "Closing existing connection");
                activeConnection.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing existing connection: " + e.getMessage());
            }
            activeConnection = null;
            connectedAddress = null;
        }
        
        executorService.execute(() -> {
            // ⏱️ BENCHMARK START
            long benchmarkStart = System.currentTimeMillis();
            long stepStart = benchmarkStart;
            
            Log.d(TAG, "════════════════════════════════════════════════════════");
            Log.d(TAG, "⏱️ BENCHMARK START - connectToPrinter");
            Log.d(TAG, "   Address: " + address);
            Log.d(TAG, "════════════════════════════════════════════════════════");
            
            try {
                Log.d(TAG, "⏱️ Opening Bluetooth connection to: " + address);
                Connection connection = new BluetoothConnection(address);
                connection.open();
                
                long openDuration = System.currentTimeMillis() - stepStart;
                Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ Connection opened");
                
                // Bağlantı testi - yazıcının gerçek bir Zebra yazıcı olduğunu doğrula
                stepStart = System.currentTimeMillis();
                Log.d(TAG, "⏱️ Verifying Zebra printer...");
                ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
                PrinterLanguage language = printer.getPrinterControlLanguage();
                
                long verifyDuration = System.currentTimeMillis() - stepStart;
                Log.d(TAG, "⏱️ [+" + verifyDuration + "ms] ✅ Printer verified. Language: " + language.toString());
                
                // Bağlantıyı sakla
                activeConnection = connection;
                connectedAddress = address;
                
                long totalDuration = System.currentTimeMillis() - benchmarkStart;
                Log.d(TAG, "════════════════════════════════════════════════════════");
                Log.d(TAG, "⏱️ BENCHMARK END - Total Duration: " + totalDuration + "ms");
                Log.d(TAG, "════════════════════════════════════════════════════════");
                
                mainHandler.post(() -> {
                    Log.d(TAG, "Connection successful!");
                    result.success(true); // Boolean: başarılı
                    
                    // Callback gönder
                    if (methodChannel != null) {
                        Map<String, Object> connInfo = new HashMap<>();
                        connInfo.put("address", address);
                        connInfo.put("isConnected", true);
                        methodChannel.invokeMethod("onConnectionStateChanged", connInfo);
                    }
                });
                
            } catch (Exception e) {
                long totalDuration = System.currentTimeMillis() - benchmarkStart;
                Log.e(TAG, "⏱️ [+" + totalDuration + "ms] ❌ Connection error: " + e.getMessage());
                Log.d(TAG, "════════════════════════════════════════════════════════");
                
                activeConnection = null;
                connectedAddress = null;
                
                mainHandler.post(() -> {
                    result.error("CONNECTION_FAILED", "Bağlantı hatası: " + e.getMessage(), e.toString());
                    
                    // Error callback gönder
                    if (methodChannel != null) {
                        Map<String, Object> errorInfo = new HashMap<>();
                        errorInfo.put("address", address);
                        errorInfo.put("isConnected", false);
                        errorInfo.put("error", e.getMessage());
                        methodChannel.invokeMethod("onConnectionStateChanged", errorInfo);
                    }
                });
            }
        });
    }
    
    /**
     * Yazıcı bağlantısını keser
     * @param address Yazıcı adresi (null ise aktif bağlantıyı keser)
     * @param result Sonuç callback'i - Boolean döndürür (true: başarılı, false/error: başarısız)
     */
    private void disconnectFromPrinter(String address, @NonNull MethodChannel.Result result) {
        Log.d(TAG, "disconnectFromPrinter called for address: " + address);
        
        // Eğer address null veya boşsa, aktif bağlantıyı kes
        if (address == null || address.isEmpty()) {
            if (activeConnection == null) {
                Log.d(TAG, "No active connection to disconnect");
                result.error("NOT_CONNECTED", "Bağlı bir yazıcı yok", null);
                return;
            }
            address = connectedAddress;
        } else {
            // Belirtilen adres aktif bağlantı değilse hata ver
            if (activeConnection == null || !address.equals(connectedAddress)) {
                Log.d(TAG, "Not connected to specified address: " + address);
                result.error("NOT_CONNECTED", "Belirtilen adrese bağlı değil: " + address, null);
                return;
            }
        }
        
        final String finalAddress = address;
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Closing connection to: " + finalAddress);
                if (activeConnection != null) {
                    activeConnection.close();
                    activeConnection = null;
                }
                connectedAddress = null;
                
                mainHandler.post(() -> {
                    Log.d(TAG, "Disconnection successful!");
                    result.success(true); // Boolean: başarılı
                    
                    // Callback gönder
                    if (methodChannel != null) {
                        Map<String, Object> disconnectInfo = new HashMap<>();
                        disconnectInfo.put("address", finalAddress);
                        disconnectInfo.put("isConnected", false);
                        methodChannel.invokeMethod("onConnectionStateChanged", disconnectInfo);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Disconnect error: " + e.getMessage());
                activeConnection = null;
                connectedAddress = null;
                
                mainHandler.post(() -> result.error("DISCONNECT_FAILED", "Bağlantı kesme hatası: " + e.getMessage(), e.toString()));
            }
        });
    }
    
    /**
     * Yazıcıya bağlı olup olmadığını kontrol eder
     * @param address Kontrol edilecek yazıcı adresi (null ise genel bağlantı durumu)
     * @return Bağlantı durumu
     */
    private boolean isConnected(String address) {
        Log.d(TAG, "isConnected check for address: " + address);
        Log.d(TAG, "Current connected address: " + connectedAddress);
        Log.d(TAG, "Active connection exists: " + (activeConnection != null));
        
        // Eğer address null veya boşsa, genel bağlantı durumunu kontrol et
        if (address == null || address.isEmpty()) {
            boolean connected = activeConnection != null && connectedAddress != null;
            Log.d(TAG, "General connection status: " + connected);
            return connected;
        }
        
        // Belirli bir adrese bağlı mı kontrol et
        boolean connected = activeConnection != null 
                         && connectedAddress != null 
                         && address.equals(connectedAddress);
        Log.d(TAG, "Connection status for " + address + ": " + connected);
        return connected;
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
        
        Log.d(TAG, "════════════════════════════════════════════════════════");
        Log.d(TAG, "⏱️ BENCHMARK START - sendZplToPrinter");
        Log.d(TAG, "   Address: " + macAddress);
        Log.d(TAG, "   ZPL Size: " + (zplData != null ? zplData.length() : 0) + " bytes");
        Log.d(TAG, "════════════════════════════════════════════════════════");

        if (macAddress == null || zplData == null || macAddress.isEmpty() || zplData.isEmpty()) {
            throw new IllegalArgumentException("MAC adresi veya ZPL verisi boş olamaz.");
        }

        Connection connection = null;
        boolean shouldCloseConnection = false;
        
        try {
            // ⚡ MULTI-PRINTER CONNECTION POOL: Check if connection exists for this printer
            Connection pooledConnection = connectionPool.get(macAddress);
            
            Log.d(TAG, "⏱️ [+0ms] Connection check started");
            Log.d(TAG, "   Pool size: " + connectionPool.size());
            Log.d(TAG, "   Has pooled connection for " + macAddress + ": " + (pooledConnection != null));
            
            // Bağlantının gerçekten açık olup olmadığını kontrol et
            boolean usePooledConnection = false;
            if (pooledConnection != null) {
                try {
                    // Bağlantı gerçekten açık mı test et
                    if (pooledConnection.isConnected()) {
                        long checkDuration = System.currentTimeMillis() - stepStart;
                        Log.d(TAG, "⏱️ [+" + checkDuration + "ms] ✅ Pooled connection validated (multi-printer ready!)");
                        
                        connection = pooledConnection;
                        shouldCloseConnection = false; // Pool'daki bağlantıyı kapatma!
                        usePooledConnection = true;
                        
                    } else {
                        long checkDuration = System.currentTimeMillis() - stepStart;
                        Log.w(TAG, "⏱️ [+" + checkDuration + "ms] ⚠️ Pooled connection is closed, removing from pool");
                        
                        // Pool'dan kaldır
                        try {
                            pooledConnection.close();
                        } catch (Exception e) {
                            // Ignore
                        }
                        connectionPool.remove(macAddress);
                    }
                } catch (Exception e) {
                    long checkDuration = System.currentTimeMillis() - stepStart;
                    Log.w(TAG, "⏱️ [+" + checkDuration + "ms] ❌ Pooled connection check error: " + e.getMessage());
                    
                    // Pool'dan kaldır
                    try {
                        if (pooledConnection != null) pooledConnection.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    connectionPool.remove(macAddress);
                }
            } else {
                long checkDuration = System.currentTimeMillis() - stepStart;
                Log.d(TAG, "⏱️ [+" + checkDuration + "ms] No pooled connection for this printer");
            }
            
            if (!usePooledConnection) {
                // ✅ YENİ BAĞLANTI: Pool'da yok veya farklı bir yazıcı
                stepStart = System.currentTimeMillis();
                
                // 🔍 Check for pending close operations
                Boolean hasPendingClose = pendingCloseOperations.get(macAddress);
                if (hasPendingClose != null && hasPendingClose) {
                    Log.d(TAG, "⏱️ Waiting for pending close operation to complete...");
                    // Wait briefly for close to complete (max 100ms)
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // 🔍 Connection cache check
                Long lastConnTime = lastConnectionTime.get(macAddress);
                long currentTime = System.currentTimeMillis();
                boolean isRecentConnection = (lastConnTime != null && 
                                             (currentTime - lastConnTime) < CONNECTION_CACHE_DURATION);
                
                if (isRecentConnection) {
                    long timeSinceLastConn = currentTime - lastConnTime;
                    Log.d(TAG, "⏱️ Opening connection to: " + macAddress + " (last: " + timeSinceLastConn + "ms ago) 🔥 WARM");
                } else {
                    Log.d(TAG, "⏱️ Opening connection to: " + macAddress + " ❄️ COLD START");
                }
                
                // ✅ ZEBRA SDK BEST PRACTICE:
                // connection.open() is BLOCKING - waits until connection is ready
                // SDK handles all connection warm-up and readiness checks internally
                connection = new BluetoothConnection(macAddress);
                connection.open();
                shouldCloseConnection = true; // Yeni bağlantıyı sonra kapat
                
                long openDuration = System.currentTimeMillis() - stepStart;
                
                if (isRecentConnection) {
                    Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ WARM connection opened (cache hit!)");
                } else {
                    Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ COLD connection opened (first/expired)");
                }
                
                // Bağlantı zamanını güncelle
                lastConnectionTime.put(macAddress, currentTime);
            }

            // Veri Gönderme: ZPL verisini yazar (UTF-8 kodlaması ile)
            stepStart = System.currentTimeMillis();
            Log.d(TAG, "⏱️ Sending ZPL data (" + zplData.length() + " bytes)...");
            
            // ✅ connection.write() blocking call - ZPL tamamen gönderilene kadar bekler
            // Zebra SDK asenkron printing'i halleder, ekstra sleep gereksiz!
            connection.write(zplData.getBytes("UTF-8"));
            
            long writeDuration = System.currentTimeMillis() - stepStart;
            Log.d(TAG, "⏱️ [+" + writeDuration + "ms] ✅ ZPL data sent successfully");
            
            // ⚡ FAST RETAIL MODE: Check if we should keep connection open
            Long lastPrintTime = lastConnectionTime.get(macAddress);
            long currentTime = System.currentTimeMillis();
            boolean isFastPrint = (lastPrintTime != null && 
                                   (currentTime - lastPrintTime) < FAST_PRINT_WINDOW);
            
            if (isFastPrint && shouldCloseConnection) {
                // 🔥 RAPID PRINT MODE: Keep connection open!
                Log.d(TAG, "⚡ FAST PRINT MODE: Keeping connection open for rapid successive prints");
                shouldCloseConnection = false; // Don't close!
                
                // ⚡ MULTI-PRINTER POOL: Add to connection pool
                if (connection != null && !connectionPool.containsKey(macAddress)) {
                    // Pool size check - max 3 concurrent connections
                    if (connectionPool.size() >= MAX_POOL_SIZE) {
                        // Remove oldest connection
                        String oldestAddress = null;
                        long oldestTime = Long.MAX_VALUE;
                        for (Map.Entry<String, Long> entry : this.lastPrintTime.entrySet()) {
                            if (entry.getValue() < oldestTime) {
                                oldestTime = entry.getValue();
                                oldestAddress = entry.getKey();
                            }
                        }
                        if (oldestAddress != null) {
                            Connection oldConn = connectionPool.remove(oldestAddress);
                            if (oldConn != null) {
                                try {
                                    oldConn.close();
                                    Log.d(TAG, "⚡ Removed oldest connection from pool: " + oldestAddress);
                                } catch (Exception e) {
                                    Log.w(TAG, "Error closing oldest connection: " + e.getMessage());
                                }
                            }
                        }
                    }
                    
                    connectionPool.put(macAddress, connection);
                    this.lastPrintTime.put(macAddress, currentTime);
                    Log.d(TAG, "⚡ Connection added to pool (multi-printer mode active, pool size: " + connectionPool.size() + ")");
                }
                
                // Legacy support
                if (activeConnection == null && connection != null) {
                    activeConnection = connection;
                    connectedAddress = macAddress;
                }
            }
            
            // ⚠️ Mark pending close BEFORE finally block (for next request)
            if (shouldCloseConnection && macAddress != null) {
                pendingCloseOperations.put(macAddress, true);
                Log.d(TAG, "⏱️ Marking pending close for: " + macAddress);
            }

        } finally {
            // ⏱️ BENCHMARK END - TOTAL DURATION (close öncesi)
            long totalDuration = System.currentTimeMillis() - benchmarkStart;
            Log.d(TAG, "════════════════════════════════════════════════════════");
            Log.d(TAG, "⏱️ BENCHMARK END - Total Duration: " + totalDuration + "ms");
            Log.d(TAG, "════════════════════════════════════════════════════════");
            
            // ✅ BAĞLANTIYI KAPAT: Asynchronous close (blocking'den kaçın!)
            if (shouldCloseConnection && connection != null) {
                final Connection finalConnection = connection;
                final String finalAddress = macAddress;
                
                // ⚡ NON-BLOCKING: Background thread'de kapat
                executorService.execute(() -> {
                    long closeStart = System.currentTimeMillis();
                    try {
                        finalConnection.close();
                        long closeDuration = System.currentTimeMillis() - closeStart;
                        Log.d(TAG, "⏱️ [async] Connection closed in " + closeDuration + "ms");
                    } catch (Exception closeEx) {
                        long closeDuration = System.currentTimeMillis() - closeStart;
                        Log.e(TAG, "⏱️ [async] Connection close error after " + closeDuration + "ms: " + closeEx.getMessage());
                    } finally {
                        // Clear pending close flag
                        pendingCloseOperations.remove(finalAddress);
                        Log.d(TAG, "⏱️ Pending close cleared for: " + finalAddress);
                    }
                });
            } else if (connection != null) {
                Log.d(TAG, "⏱️ Active connection kept open for future use");
            }
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
        
        Log.d(TAG, "getPrinterInfo called");
        Log.d(TAG, "Getting printer info for: " + macAddress);
        
        Connection connection = null;
        boolean shouldCloseConnection = false;
        StringBuilder info = new StringBuilder();
        
        try {
            Log.d(TAG, "getPrinterInfo method started for: " + macAddress);
            
            // ✅ AKTİF BAĞLANTIYI KULLAN: Eğer zaten bağlıysak yeni bağlantı açma!
            boolean hasActiveConnection = (activeConnection != null && 
                                          connectedAddress != null && 
                                          connectedAddress.equals(macAddress));
            
            boolean useActiveConnection = false;
            if (hasActiveConnection) {
                try {
                    // Bağlantı gerçekten açık mı test et
                if (activeConnection.isConnected()) {
                    Log.d(TAG, "Using existing active connection for getPrinterInfo");
                    connection = activeConnection;
                    shouldCloseConnection = false; // Aktif bağlantıyı kapatma!
                    useActiveConnection = true;
                    
                    // ✅ Zebra SDK kendi buffer'ını yönetir - sleep gereksiz!
                    } else {
                        Log.w(TAG, "Active connection for getPrinterInfo exists but is closed");
                        activeConnection = null;
                        connectedAddress = null;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error checking connection in getPrinterInfo: " + e.getMessage());
                    activeConnection = null;
                    connectedAddress = null;
                }
            }
            
            if (!useActiveConnection) {
                // ✅ YENİ BAĞLANTI: Aktif bağlantı yok veya farklı bir yazıcı
                Log.d(TAG, "Opening new connection for getPrinterInfo");
                connection = new BluetoothConnection(macAddress);
                
                // ✅ ZEBRA SDK: connection.open() blocking - no sleep needed
                connection.open();
                shouldCloseConnection = true; // Yeni bağlantıyı sonra kapat
            }
            
            Log.d(TAG, "Creating ZebraPrinter instance...");
            // Zebra Printer nesnesini oluştur
            ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
            
            Log.d(TAG, "Getting printer information via SGD commands...");
            // SGD komutları ile yazıcı bilgilerini al
            String model = SGD.GET("device.product_name", connection);
            Log.d(TAG, "Model: " + model);
            
            String serialNumber = SGD.GET("device.unique_id", connection);
            Log.d(TAG, "Serial: " + serialNumber);
            
            String firmware = SGD.GET("appl.name", connection);
            Log.d(TAG, "Firmware: " + firmware);
            
            info.append("Model: ").append(model).append("\n");
            info.append("Seri No: ").append(serialNumber).append("\n");
            info.append("Firmware: ").append(firmware).append("\n");
            
            Log.d(TAG, "Getting printer control language...");
            // Yazıcı dili bilgisini al
            PrinterLanguage language = printer.getPrinterControlLanguage();
            Log.d(TAG, "Language: " + language.toString());
            info.append("Dil: ").append(language.toString()).append("\n");
            
            Log.d(TAG, "Printer info collected successfully: " + info.toString().trim());
            return info.toString();
            
        } finally {
            // ✅ BAĞLANTIYI KAPAT: Sadece yeni açtığımız bağlantıları kapat
            if (shouldCloseConnection && connection != null) {
                try {
                    connection.close();
                    Log.d(TAG, "Temporary connection closed");
                } catch (ConnectionException e) {
                    Log.e(TAG, "Connection close error: " + e.getMessage());
                }
            } else if (connection != null) {
                Log.d(TAG, "Active connection kept open");
            }
        }
    }
    
    /**
     * Yazıcının durumunu kontrol eder (kağıt durumu, bağlantı durumu vb.)
     * @param macAddress MAC adresi
     * @return Yazıcı durumu
     * @throws ConnectionException Bağlantı hatası
     */
    private Map<String, Object> checkPrinterStatus(String macAddress) 
            throws ConnectionException {
        
        Log.d(TAG, "checkPrinterStatus called for: " + macAddress);
        
        Connection connection = null;
        boolean shouldCloseConnection = false;
        Map<String, Object> statusMap = new HashMap<>();
        
        try {
            // ✅ AKTİF BAĞLANTIYI KULLAN: Eğer zaten bağlıysak yeni bağlantı açma!
            boolean hasActiveConnection = (activeConnection != null && 
                                          connectedAddress != null && 
                                          connectedAddress.equals(macAddress));
            
            boolean useActiveConnection = false;
            if (hasActiveConnection) {
                try {
                    // Bağlantı gerçekten açık mı test et
                if (activeConnection.isConnected()) {
                    Log.d(TAG, "Using existing active connection for checkPrinterStatus");
                    connection = activeConnection;
                    shouldCloseConnection = false; // Aktif bağlantıyı kapatma!
                    useActiveConnection = true;
                    
                    // ✅ Zebra SDK kendi buffer'ını yönetir - sleep gereksiz!
                    } else {
                        Log.w(TAG, "Active connection for checkPrinterStatus exists but is closed");
                        activeConnection = null;
                        connectedAddress = null;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error checking connection in checkPrinterStatus: " + e.getMessage());
                    activeConnection = null;
                    connectedAddress = null;
                }
            }
            
            if (!useActiveConnection) {
                // ✅ YENİ BAĞLANTI: Aktif bağlantı yok veya farklı bir yazıcı
                Log.d(TAG, "Opening new connection for checkPrinterStatus");
                connection = new BluetoothConnection(macAddress);
                
                // ✅ ZEBRA SDK: connection.open() blocking - no sleep needed
                connection.open();
                shouldCloseConnection = true; // Yeni bağlantıyı sonra kapat
            }
            
            Log.d(TAG, "Getting printer status via SGD commands...");
            // Yazıcı durumunu al
            boolean isPaperOut = "1".equals(SGD.GET("head.paper_out", connection));
            boolean isPaused = "1".equals(SGD.GET("device.pause", connection));
            boolean isHeadOpen = "1".equals(SGD.GET("head.open", connection));
            
            // Yazıcı sıcaklığını al
            String temperature = SGD.GET("head.temperature", connection);
            
            // Durum bilgilerini map'e ekle
            statusMap.put("isPaperOut", isPaperOut);
            statusMap.put("isPaused", isPaused);
            statusMap.put("isHeadOpen", isHeadOpen);
            statusMap.put("temperature", temperature);
            statusMap.put("isConnected", true);
            
            Log.d(TAG, "Printer status retrieved successfully");
            return statusMap;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting printer status: " + e.getMessage());
            statusMap.put("isConnected", false);
            statusMap.put("error", e.getMessage());
            return statusMap;
        } finally {
            // ✅ BAĞLANTIYI KAPAT: Sadece yeni açtığımız bağlantıları kapat
            if (shouldCloseConnection && connection != null) {
                try {
                    connection.close();
                    Log.d(TAG, "Temporary connection closed");
                } catch (ConnectionException e) {
                    Log.e(TAG, "Connection close error: " + e.getMessage());
                }
            } else if (connection != null) {
                Log.d(TAG, "Active connection kept open");
            }
        }
    }

    /**
     * Kaynakları temizler
     */
    public void dispose() {
        // Aktif bağlantıyı kapat
        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing connection on dispose: " + e.getMessage());
            }
            activeConnection = null;
            connectedAddress = null;
        }
        
        // Discovery'yi durdur
        isDiscovering = false;
        
        // ExecutorService'i kapat
        executorService.shutdown();
    }
}
