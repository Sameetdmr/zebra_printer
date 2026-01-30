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
    
    // ✅ ZEBRA SDK BEST PRACTICE: No connection pooling
    // Each print operation opens and closes connection in finally block
    // This ensures proper resource cleanup and multi-device compatibility
    
    // Legacy connection state (for connect/disconnect methods only)
    private Connection activeConnection = null;
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
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing connection during unpair: " + e.getMessage());
                    } finally {
                        activeConnection = null;
                        connectedAddress = null;
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
        
        try {
            // ✅ ZEBRA SDK BEST PRACTICE: Open connection before use with retry mechanism
            stepStart = System.currentTimeMillis();
            Log.d(TAG, "⏱️ Opening connection to: " + macAddress);
            
            // 🔄 RETRY MECHANISM: Connection açma başarısız olursa retry yap
            int maxRetries = 2; // Toplam 3 deneme (1 initial + 2 retry)
            
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    // Önceki connection'ı kapat (eğer varsa)
                    if (connection != null) {
                        try {
                            connection.close();
                        } catch (Exception e) {
                            // Ignore close errors
                        }
                    }
                    
                    connection = new BluetoothConnection(macAddress);
                    connection.open();
                    
                    long openDuration = System.currentTimeMillis() - stepStart;
                    if (attempt > 0) {
                        Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully (retry #" + attempt + ")");
                    } else {
                        Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully");
                    }
                    break; // Başarılı, döngüden çık
                    
                } catch (ConnectionException e) {
                    long attemptDuration = System.currentTimeMillis() - stepStart;
                    
                    if (attempt < maxRetries - 1) {
                        Log.w(TAG, "⏱️ [+" + attemptDuration + "ms] ⚠️ Connection attempt #" + (attempt + 1) + " failed: " + e.getMessage());
                        Log.d(TAG, "🔄 Retrying connection in 500ms...");
                        
                        // Retry öncesi bekleme (dokümantasyon önerisi)
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ConnectionException("Connection retry interrupted", ie);
                        }
                    } else {
                        // Son deneme de başarısız
                        long totalDuration = System.currentTimeMillis() - stepStart;
                        Log.e(TAG, "⏱️ [+" + totalDuration + "ms] ❌ Connection failed after " + maxRetries + " attempts: " + e.getMessage());
                        throw e; // Son exception'ı fırlat
                    }
                }
            }
            
            // Connection state kontrolü (güvenlik için)
            if (connection != null && !connection.isConnected()) {
                Log.w(TAG, "⚠️ Connection opened but not connected, reopening...");
                try {
                    connection.close();
                } catch (Exception e) {
                    // Ignore
                }
                connection = new BluetoothConnection(macAddress);
                connection.open();
            }

            // Veri Gönderme: ZPL verisini yazar (UTF-8 kodlaması ile)
            stepStart = System.currentTimeMillis();
            Log.d(TAG, "⏱️ Sending ZPL data (" + zplData.length() + " bytes)...");
            
            // ✅ Connection state kontrolü (güvenlik için)
            if (connection == null || !connection.isConnected()) {
                throw new ConnectionException("Connection is not open or not connected");
            }
            
            // ✅ connection.write() blocking call - ZPL tamamen gönderilene kadar bekler
            // Zebra SDK asenkron printing'i halleder, ekstra sleep gereksiz!
            try {
                connection.write(zplData.getBytes("UTF-8"));
            } catch (ConnectionException e) {
                // Broken pipe veya connection lost durumunda
                Log.e(TAG, "❌ Write failed: " + e.getMessage());
                throw new ConnectionException("Failed to send ZPL data: " + e.getMessage(), e);
            }
            
            long writeDuration = System.currentTimeMillis() - stepStart;
            Log.d(TAG, "⏱️ [+" + writeDuration + "ms] ✅ ZPL data sent successfully");

        } finally {
            // ⏱️ BENCHMARK END - TOTAL DURATION (close öncesi)
            long totalDuration = System.currentTimeMillis() - benchmarkStart;
            Log.d(TAG, "════════════════════════════════════════════════════════");
            Log.d(TAG, "⏱️ BENCHMARK END - Total Duration: " + totalDuration + "ms");
            Log.d(TAG, "════════════════════════════════════════════════════════");
            
            // ✅ ZEBRA SDK BEST PRACTICE: Close connection in finally block
            // ⚡ ASYNC CLOSE: connection.close() blocking ve ~5sn sürebiliyor
            // Flutter timeout'undan kaçınmak için background thread'de kapatıyoruz
            if (connection != null) {
                final Connection finalConnection = connection;
                executorService.execute(() -> {
                    long closeStart = System.currentTimeMillis();
                    try {
                        finalConnection.close();
                        long closeDuration = System.currentTimeMillis() - closeStart;
                        Log.d(TAG, "✅ Connection closed successfully in " + closeDuration + "ms");
                    } catch (Exception e) {
                        long closeDuration = System.currentTimeMillis() - closeStart;
                        Log.w(TAG, "⚠️ Error closing connection after " + closeDuration + "ms: " + e.getMessage());
                    }
                });
            }
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
        
        Log.d(TAG, "════════════════════════════════════════════════════════");
        Log.d(TAG, "⚡ BATCH PRINT START - sendMultipleZplToPrinter");
        Log.d(TAG, "   Address: " + macAddress);
        Log.d(TAG, "   Label Count: " + (zplList != null ? zplList.size() : 0));
        Log.d(TAG, "════════════════════════════════════════════════════════");
        
        if (macAddress == null || macAddress.isEmpty()) {
            throw new IllegalArgumentException("MAC adresi boş olamaz.");
        }
        
        if (zplList == null || zplList.isEmpty()) {
            throw new IllegalArgumentException("ZPL listesi boş olamaz.");
        }
        
        Connection connection = null;
        
        try {
            // ✅ ZEBRA SDK BEST PRACTICE: Open connection once for all labels
            stepStart = System.currentTimeMillis();
            Log.d(TAG, "⏱️ Opening connection to: " + macAddress + " (batch mode)");
            
            // 🔄 RETRY MECHANISM: Connection açma başarısız olursa retry yap
            int maxRetries = 2;
            
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    if (connection != null) {
                        try {
                            connection.close();
                        } catch (Exception e) {
                            // Ignore
                        }
                    }
                    
                    connection = new BluetoothConnection(macAddress);
                    connection.open();
                    
                    long openDuration = System.currentTimeMillis() - stepStart;
                    if (attempt > 0) {
                        Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully (retry #" + attempt + ")");
                    } else {
                        Log.d(TAG, "⏱️ [+" + openDuration + "ms] ✅ Connection opened successfully (batch mode)");
                    }
                    break;
                    
                } catch (ConnectionException e) {
                    long attemptDuration = System.currentTimeMillis() - stepStart;
                    
                    if (attempt < maxRetries - 1) {
                        Log.w(TAG, "⏱️ [+" + attemptDuration + "ms] ⚠️ Connection attempt #" + (attempt + 1) + " failed: " + e.getMessage());
                        Log.d(TAG, "🔄 Retrying connection in 500ms...");
                        
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ConnectionException("Connection retry interrupted", ie);
                        }
                    } else {
                        long totalDuration = System.currentTimeMillis() - stepStart;
                        Log.e(TAG, "⏱️ [+" + totalDuration + "ms] ❌ Connection failed after " + maxRetries + " attempts: " + e.getMessage());
                        throw e;
                    }
                }
            }
            
            // Connection state kontrolü
            if (connection != null && !connection.isConnected()) {
                Log.w(TAG, "⚠️ Connection opened but not connected, reopening...");
                try {
                    connection.close();
                } catch (Exception e) {
                    // Ignore
                }
                connection = new BluetoothConnection(macAddress);
                connection.open();
            }
            
            // ⚡ BATCH PRINTING: Tüm ZPL'leri tek connection'da gönder
            stepStart = System.currentTimeMillis();
            int totalBytes = 0;
            
            for (int i = 0; i < zplList.size(); i++) {
                String zplData = zplList.get(i);
                if (zplData == null || zplData.isEmpty()) {
                    Log.w(TAG, "⚠️ Skipping empty ZPL at index " + i);
                    continue;
                }
                
                long labelStart = System.currentTimeMillis();
                Log.d(TAG, "⏱️ Sending label #" + (i + 1) + "/" + zplList.size() + " (" + zplData.length() + " bytes)...");
                
                // Connection state kontrolü
                if (connection == null || !connection.isConnected()) {
                    throw new ConnectionException("Connection lost during batch printing at label #" + (i + 1));
                }
                
                try {
                    connection.write(zplData.getBytes("UTF-8"));
                    totalBytes += zplData.length();
                    
                    long labelDuration = System.currentTimeMillis() - labelStart;
                    Log.d(TAG, "⏱️ [+" + labelDuration + "ms] ✅ Label #" + (i + 1) + " sent successfully");
                    
                } catch (ConnectionException e) {
                    Log.e(TAG, "❌ Write failed for label #" + (i + 1) + ": " + e.getMessage());
                    throw new ConnectionException("Failed to send label #" + (i + 1) + ": " + e.getMessage(), e);
                }
            }
            
            long batchWriteDuration = System.currentTimeMillis() - stepStart;
            Log.d(TAG, "⏱️ [+" + batchWriteDuration + "ms] ✅ All " + zplList.size() + " labels sent successfully (total: " + totalBytes + " bytes)");
            
        } finally {
            // ⏱️ BENCHMARK END - TOTAL DURATION (close öncesi)
            long totalDuration = System.currentTimeMillis() - benchmarkStart;
            Log.d(TAG, "════════════════════════════════════════════════════════");
            Log.d(TAG, "⚡ BATCH PRINT END - Total Duration: " + totalDuration + "ms");
            Log.d(TAG, "   Average per label: " + (zplList.size() > 0 ? (totalDuration / zplList.size()) : 0) + "ms");
            Log.d(TAG, "════════════════════════════════════════════════════════");
            
            // ✅ ZEBRA SDK BEST PRACTICE: Close connection in finally block
            if (connection != null) {
                final Connection finalConnection = connection;
                executorService.execute(() -> {
                    long closeStart = System.currentTimeMillis();
                    try {
                        finalConnection.close();
                        long closeDuration = System.currentTimeMillis() - closeStart;
                        Log.d(TAG, "✅ Connection closed successfully in " + closeDuration + "ms (batch mode)");
                    } catch (Exception e) {
                        long closeDuration = System.currentTimeMillis() - closeStart;
                        Log.w(TAG, "⚠️ Error closing connection after " + closeDuration + "ms: " + e.getMessage());
                    }
                });
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
        StringBuilder info = new StringBuilder();
        
        try {
            Log.d(TAG, "getPrinterInfo method started for: " + macAddress);
            
            // ✅ ZEBRA SDK BEST PRACTICE: Open connection before use
            Log.d(TAG, "Opening connection for getPrinterInfo");
            connection = new BluetoothConnection(macAddress);
            connection.open();
            
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
            // ✅ ZEBRA SDK BEST PRACTICE: Close connection in finally block
            if (connection != null) {
                try {
                    connection.close();
                    Log.d(TAG, "✅ Connection closed successfully");
                } catch (ConnectionException e) {
                    Log.w(TAG, "⚠️ Error closing connection: " + e.getMessage());
                }
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
            // ✅ ZEBRA SDK BEST PRACTICE: Open connection before use
            Log.d(TAG, "Opening connection for checkPrinterStatus");
            connection = new BluetoothConnection(macAddress);
            connection.open();
            
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
            // ✅ ZEBRA SDK BEST PRACTICE: Close connection in finally block
            if (connection != null) {
                try {
                    connection.close();
                    Log.d(TAG, "✅ Connection closed successfully");
                } catch (ConnectionException e) {
                    Log.w(TAG, "⚠️ Error closing connection: " + e.getMessage());
                }
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
