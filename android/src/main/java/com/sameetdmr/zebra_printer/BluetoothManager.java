package com.sameetdmr.zebra_printer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Generic Bluetooth helpers: adapter state, bonded devices, discovery and pairing.
 *
 * <p>Note: {@code connect}/{@code disconnect} open a raw RFCOMM socket and keep it
 * open. Zebra printers accept a single Bluetooth link, so a socket held here
 * blocks every other device (and this app's own print jobs). They are kept for
 * backwards compatibility and are deprecated on the Dart side; printing goes
 * through {@link PrinterManager}, which connects only for the duration of a job.
 */
public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    
    // SPP UUID (Serial Port Profile)
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Bağlantı durumları
    private static final int CONNECTION_STATE_DISCONNECTED = 0;
    private static final int CONNECTION_STATE_CONNECTING = 1;
    private static final int CONNECTION_STATE_CONNECTED = 2;
    private static final int CONNECTION_STATE_DISCONNECTING = 3;
    private static final int CONNECTION_STATE_ERROR = 4;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    private final ExecutorService executorService;
    private BroadcastReceiver discoveryReceiver;
    private BroadcastReceiver connectionReceiver;
    private volatile boolean isDiscovering = false;
    private MethodChannel methodChannel;
    
    // Touched from the main thread, the executor and broadcast receivers
    private volatile BluetoothSocket bluetoothSocket;
    private volatile BluetoothDevice connectedDevice;
    private volatile int connectionState = CONNECTION_STATE_DISCONNECTED;

    /**
     * Constructor
     * @param context Uygulama context'i
     */
    public BluetoothManager(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newSingleThreadExecutor();
        
        // Bağlantı durumu değişikliklerini dinleyen receiver'ı kaydet
        registerConnectionReceiver();
    }

    /**
     * MethodChannel işleyicisini ayarlar
     * @param methodChannel Flutter tarafından gelen MethodChannel
     */
    public void setMethodCallHandler(MethodChannel methodChannel) {
        this.methodChannel = methodChannel;
        methodChannel.setMethodCallHandler(this::handleMethodCall);
    }

    /**
     * Flutter tarafından gelen method çağrılarını işler
     * @param call Method çağrısı
     * @param result Sonuç callback'i
     */
    private void handleMethodCall(MethodCall call, MethodChannel.Result result) {
        switch (call.method) {
            case "isBluetoothEnabled":
                result.success(isBluetoothEnabled());
                break;
            case "getBondedDevices":
                result.success(getBondedDevices());
                break;
            case "startDiscovery":
                startDiscovery(result);
                break;
            case "stopDiscovery":
                stopDiscovery(result);
                break;
            case "pairDevice":
                String address = call.argument("address");
                pairDevice(address, result);
                break;
            case "unpairDevice":
                String unpairAddress = call.argument("address");
                unpairDevice(unpairAddress, result);
                break;
            case "connect":
                String connectAddress = call.argument("address");
                connect(connectAddress, result);
                break;
            case "disconnect":
                disconnect(result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * Builds the device map sent to Dart.
     * BluetoothDevice#getName() throws SecurityException on Android 12+ when
     * BLUETOOTH_CONNECT is missing. Inside a BroadcastReceiver that would crash
     * the host app, so every accessor is guarded here.
     */
    @SuppressWarnings("MissingPermission")
    private Map<String, Object> deviceToMap(BluetoothDevice device) {
        Map<String, Object> deviceMap = new HashMap<>();
        String name = null;
        int type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            name = device.getName();
            type = device.getType();
            bondState = device.getBondState();
        } catch (SecurityException e) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing, device details unavailable");
        }
        deviceMap.put("name", name);
        deviceMap.put("address", device.getAddress());
        deviceMap.put("type", type);
        deviceMap.put("bondState", bondState);

        BluetoothDevice current = connectedDevice;
        deviceMap.put("isConnected", current != null
                && device.getAddress().equals(current.getAddress())
                && connectionState == CONNECTION_STATE_CONNECTED);
        return deviceMap;
    }

    private boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Bluetooth'un açık olup olmadığını kontrol eder
     * @return Bluetooth açıksa true, değilse false
     */
    private boolean isBluetoothEnabled() {
        try {
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Eşleştirilmiş cihazları döndürür
     * @return Eşleştirilmiş cihazların listesi
     */
    private List<Map<String, Object>> getBondedDevices() {
        List<Map<String, Object>> devicesList = new ArrayList<>();

        if (bluetoothAdapter == null) {
            return devicesList;
        }

        Set<BluetoothDevice> pairedDevices;
        try {
            pairedDevices = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException e) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing, cannot list bonded devices");
            return devicesList;
        }
        if (pairedDevices != null) {
            for (BluetoothDevice device : pairedDevices) {
                devicesList.add(deviceToMap(device));
            }
        }

        return devicesList;
    }

    /**
     * Cihaz keşfini başlatır
     * @param result Sonuç callback'i
     */
    private void startDiscovery(final MethodChannel.Result result) {
        if (bluetoothAdapter == null) {
            result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth adapter not found", null);
            return;
        }

        // ✅ Android 12+ (API 31+) için BLUETOOTH_SCAN permission kontrolü
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                Log.e(TAG, "BLUETOOTH_SCAN permission not granted");
                result.error("PERMISSION_DENIED", 
                    "BLUETOOTH_SCAN permission is required.", 
                    null);
                return;
            }
        }

        // Cancel a previous discovery
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "cancelDiscovery not permitted: " + e.getMessage());
        }

        // ✅ Önceki receiver'ı temizle
        if (discoveryReceiver != null) {
            try {
                context.unregisterReceiver(discoveryReceiver);
                Log.d(TAG, "Unregistered previous receiver");
            } catch (Exception e) {
                // Receiver zaten unregister edilmiş, sorun değil
                Log.d(TAG, "Receiver already unregistered: " + e.getMessage());
            }
            discoveryReceiver = null;
        }

        // Keşif tamamlandığında ve yeni cihaz bulunduğunda tetiklenecek BroadcastReceiver
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        Map<String, Object> deviceMap = deviceToMap(device);

                        // Flutter'a cihaz bulundu bildirimi gönder
                        mainHandler.post(() -> {
                            if (methodChannel != null) {
                                methodChannel.invokeMethod("onDeviceFound", deviceMap);
                            }
                        });
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    isDiscovering = false;
                    
                    // Flutter'a keşfin tamamlandığını bildir
                    mainHandler.post(() -> {
                        if (methodChannel != null) {
                            methodChannel.invokeMethod("onDiscoveryFinished", null);
                        }
                    });
                    
                    // BroadcastReceiver'ı kaldır
                    try {
                        context.unregisterReceiver(this);
                    } catch (Exception e) {
                        Log.e(TAG, "Receiver unregister error: " + e.getMessage());
                    }
                    if (discoveryReceiver == this) {
                        discoveryReceiver = null;
                    }
                }
            }
        };

        // Intent filtreleri
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        
        // BroadcastReceiver'ı kaydet
        try {
            context.registerReceiver(discoveryReceiver, filter);
            Log.d(TAG, "Receiver registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver: " + e.getMessage());
            result.error("RECEIVER_REGISTRATION_FAILED", "Could not register BroadcastReceiver: " + e.getMessage(), null);
            return;
        }

        // Keşfi başlat
        boolean started;
        try {
            started = bluetoothAdapter.startDiscovery();
        } catch (SecurityException e) {
            Log.e(TAG, "startDiscovery not permitted: " + e.getMessage());
            started = false;
        }
        if (started) {
            isDiscovering = true;
            Log.d(TAG, "Discovery started successfully");
            result.success(true);
        } else {
            // ✅ Başarısız olursa receiver'ı temizle
            Log.e(TAG, "startDiscovery() returned false");
            try {
                context.unregisterReceiver(discoveryReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister receiver after failed start: " + e.getMessage());
            }
            discoveryReceiver = null;
            result.error("DISCOVERY_FAILED", "Could not start discovery. Check Bluetooth permissions.", null);
        }
    }

    /**
     * Cihaz keşfini durdurur
     * @param result Sonuç callback'i (null olabilir)
     */
    private void stopDiscovery(final MethodChannel.Result result) {
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "cancelDiscovery not permitted: " + e.getMessage());
        }

        isDiscovering = false;

        // BroadcastReceiver'ı kaldır
        if (discoveryReceiver != null) {
            try {
                context.unregisterReceiver(discoveryReceiver);
                discoveryReceiver = null;
            } catch (Exception e) {
                Log.e(TAG, "Receiver unregister error: " + e.getMessage());
            }
        }

        if (result != null) {
            result.success(true);
        }
    }

    /**
     * Cihazla eşleşme işlemini başlatır
     * Not: Android 4.4+ sistemlerde programatik eşleşme kısıtlı olduğundan
     * bu metod sadece eşleşme isteği gönderir, kullanıcı onayı gerekebilir
     * @param address Cihaz MAC adresi
     * @param result Sonuç callback'i
     */
    private void pairDevice(String address, final MethodChannel.Result result) {
        if (bluetoothAdapter == null) {
            result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth adapter not found", null);
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                result.success(true);
                return;
            }

            // Eşleşme isteği gönder (Android 4.4+ sistemlerde kullanıcı onayı gerekebilir)
            if (!device.createBond()) {
                result.error("PAIR_FAILED", "Pairing request could not be started", null);
                return;
            }
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Pair error: " + e.getMessage());
            result.error("PAIR_FAILED", "Pairing failed: " + e.getMessage(), null);
        }
    }

    /**
     * Cihazla eşleşmeyi kaldırır
     * @param address Cihaz MAC adresi
     * @param result Sonuç callback'i
     */
    private void unpairDevice(String address, final MethodChannel.Result result) {
        if (bluetoothAdapter == null) {
            result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth adapter not found", null);
            return;
        }

        try {
            // Eğer bağlı cihaz ise önce bağlantıyı kes
            if (connectedDevice != null && connectedDevice.getAddress().equals(address)) {
                disconnect(null);
            }
            
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            device.getClass().getMethod("removeBond").invoke(device);
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Unpair error: " + e.getMessage());
            result.error("UNPAIR_FAILED", "Unpairing failed: " + e.getMessage(), null);
        }
    }
    
    /**
     * Bluetooth cihazına bağlanır
     * @param address Bağlanılacak cihazın MAC adresi
     * @param result Sonuç callback'i
     */
    private void connect(String address, final MethodChannel.Result result) {
        if (bluetoothAdapter == null) {
            result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth adapter not found", null);
            return;
        }
        
        // Zaten bağlıysa veya bağlanıyorsa hata döndür
        if (connectionState == CONNECTION_STATE_CONNECTED || connectionState == CONNECTION_STATE_CONNECTING) {
            result.error("ALREADY_CONNECTING", "Already connected or connecting", null);
            return;
        }
        
        // Önce keşif işlemini durdur (bağlantıyı engelleyebilir)
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "cancelDiscovery not permitted: " + e.getMessage());
        }
        
        // Bağlantı durumunu güncelle
        updateConnectionState(CONNECTION_STATE_CONNECTING);
        
        // Bağlantıyı ayrı bir thread'de gerçekleştir
        executorService.execute(() -> {
            try {
                // Cihazı al
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                
                // Soket oluştur ve bağlan
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                
                // Bağlantı başarılı
                connectedDevice = device;
                updateConnectionState(CONNECTION_STATE_CONNECTED);
                
                // Sonucu ana thread'de döndür
                mainHandler.post(() -> result.success(true));
                
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
                
                // Bağlantı hatası
                closeSocket();
                connectedDevice = null;
                updateConnectionState(CONNECTION_STATE_ERROR);
                
                // Sonucu ana thread'de döndür
                mainHandler.post(() -> result.error("CONNECTION_FAILED", "Connection error: " + e.getMessage(), null));
            }
        });
    }
    
    /**
     * Bluetooth bağlantısını keser
     * @param result Sonuç callback'i (null olabilir)
     */
    private void disconnect(final MethodChannel.Result result) {
        // Bağlı değilse hata döndür
        if (connectionState != CONNECTION_STATE_CONNECTED) {
            if (result != null) {
                result.error("NOT_CONNECTED", "No device connected", null);
            }
            return;
        }
        
        // Bağlantı durumunu güncelle
        updateConnectionState(CONNECTION_STATE_DISCONNECTING);
        
        // Bağlantıyı ayrı bir thread'de kes
        executorService.execute(() -> {
            closeSocket();
            connectedDevice = null;
            updateConnectionState(CONNECTION_STATE_DISCONNECTED);
            
            // Sonuç null değilse ana thread'de döndür
            if (result != null) {
                mainHandler.post(() -> result.success(true));
            }
        });
    }
    
    /**
     * Bluetooth soketini kapatır
     */
    private void closeSocket() {
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Close socket error: " + e.getMessage());
            } finally {
                bluetoothSocket = null;
            }
        }
    }
    
    /**
     * Bağlantı durumunu günceller ve Flutter'a bildirir
     * @param state Yeni bağlantı durumu
     */
    private void updateConnectionState(int state) {
        if (connectionState != state) {
            connectionState = state;
            // Capture now: the device may be cleared before the posted runnable runs
            final BluetoothDevice device = connectedDevice;
            
            // Flutter'a bağlantı durumu değişikliğini bildir
            mainHandler.post(() -> {
                if (methodChannel != null) {
                    Map<String, Object> stateMap = new HashMap<>();
                    stateMap.put("state", state);
                    
                    if (device != null) {
                        stateMap.put("address", device.getAddress());
                    }
                    
                    methodChannel.invokeMethod("onConnectionStateChanged", stateMap);
                }
            });
        }
    }
    
    /**
     * Bağlantı durumu değişikliklerini dinleyen BroadcastReceiver'ı kaydeder
     */
    private void registerConnectionReceiver() {
        connectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                
                if (device == null) {
                    return;
                }
                
                // Cihaz bağlantı durumu değişiklikleri
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    if (connectedDevice != null && device.getAddress().equals(connectedDevice.getAddress())) {
                        updateConnectionState(CONNECTION_STATE_CONNECTED);
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    if (connectedDevice != null && device.getAddress().equals(connectedDevice.getAddress())) {
                        closeSocket();
                        updateConnectionState(CONNECTION_STATE_DISCONNECTED);
                        connectedDevice = null;
                    }
                }
            }
        };
        
        // Intent filtreleri
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        
        // BroadcastReceiver'ı kaydet
        context.registerReceiver(connectionReceiver, filter);
    }

    /**
     * Kaynakları temizler
     * ✅ Best practice: Tüm receiver'ları temizle ve graceful shutdown
     */
    public void dispose() {
        Log.d(TAG, "🧹 Disposing BluetoothManager...");
        
        // Aktif tarama varsa durdur
        stopDiscovery(null);
        
        // Aktif bağlantı varsa kes
        if (connectionState == CONNECTION_STATE_CONNECTED) {
            disconnect(null);
        }
        
        // ✅ Discovery receiver'ı temizle
        if (discoveryReceiver != null) {
            try {
                context.unregisterReceiver(discoveryReceiver);
                discoveryReceiver = null;
                Log.d(TAG, "✅ Discovery receiver unregistered");
            } catch (Exception e) {
                Log.w(TAG, "Discovery receiver unregister error (may already be unregistered): " + e.getMessage());
            }
        }
        
        // ✅ Connection receiver'ı temizle
        if (connectionReceiver != null) {
            try {
                context.unregisterReceiver(connectionReceiver);
                connectionReceiver = null;
                Log.d(TAG, "✅ Connection receiver unregistered");
            } catch (Exception e) {
                Log.w(TAG, "Connection receiver unregister error: " + e.getMessage());
            }
        }
        
        // ✅ ExecutorService graceful shutdown
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "⚠️ ExecutorService did not terminate in time, forcing shutdown...");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "⚠️ Shutdown interrupted, forcing shutdown...");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        Log.d(TAG, "✅ BluetoothManager disposed successfully");
    }
}
