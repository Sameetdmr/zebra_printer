package com.sameetdmr.zebra_printer;

import android.util.Log;

import androidx.annotation.NonNull;

import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.printer.PrinterLanguage;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.SGD;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Zebra yazıcılarla iletişim kurmak için sınıf
 */
public class PrinterManager {
    private static final String TAG = "PrinterManager";

    private final ExecutorService executorService;

    /**
     * Constructor
     */
    public PrinterManager() {
        this.executorService = Executors.newSingleThreadExecutor();
    }

    /**
     * Flutter tarafından gelen method çağrılarını işler
     * @param call Method çağrısı
     * @param result Sonuç callback'i
     */
    public void handleMethodCall(MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "printLabel":
                final String macAddress = call.argument("address");
                final String zplData = call.argument("data");

                executorService.execute(() -> {
                    try {
                        sendZplToPrinter(macAddress, zplData);
                        result.success("Baskı başarılı: " + macAddress);
                    } catch (Exception e) {
                        final String errorMessage = "Yazıcı veya Bağlantı Hatası: " + e.getMessage();
                        result.error("PRINT_FAIL", errorMessage, e.toString());
                    }
                });
                break;
                
            case "getPrinterInfo":
                final String address = call.argument("address");
                executorService.execute(() -> {
                    try {
                        String info = getPrinterInfo(address);
                        result.success(info);
                    } catch (Exception e) {
                        result.error("INFO_FAIL", e.getMessage(), e.toString());
                    }
                });
                break;
                
            case "checkPrinterStatus":
                final String statusAddress = call.argument("address");
                executorService.execute(() -> {
                    try {
                        Map<String, Object> status = checkPrinterStatus(statusAddress);
                        result.success(status);
                    } catch (Exception e) {
                        result.error("STATUS_FAIL", e.getMessage(), e.toString());
                    }
                });
                break;
                
            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * Link-OS SDK'yı kullanarak bağlantıyı kurar, ZPL gönderir ve kapatır (AÇ-BAS-KAPAT döngüsü)
     * @param macAddress MAC adresi
     * @param zplData ZPL verisi
     * @throws ConnectionException Bağlantı hatası
     * @throws IllegalArgumentException Geçersiz argüman
     * @throws UnsupportedEncodingException Desteklenmeyen kodlama
     */
    private void sendZplToPrinter(String macAddress, String zplData)
            throws ConnectionException, IllegalArgumentException, UnsupportedEncodingException {

        if (macAddress == null || zplData == null || macAddress.isEmpty() || zplData.isEmpty()) {
            throw new IllegalArgumentException("MAC adresi veya ZPL verisi boş olamaz.");
        }

        Connection connection = null;
        try {
            // Bağlantı Kurma: MAC adresine bağlanma isteği
            connection = new BluetoothConnection(macAddress);
            connection.open(); // Bağlantıyı aç

            // Veri Gönderme: ZPL verisini yazar (UTF-8 kodlaması ile)
            connection.write(zplData.getBytes("UTF-8"));

            // Yazıcının baskıyı bitirmesi için kısa bir süre beklemek iyi bir uygulamadır
            try {
                Thread.sleep(500); // 0.5 saniye bekle
            } catch (InterruptedException ignored) {}

        } finally {
            // Bağlantıyı Kapatma: Veri iletimi biter bitmez bağlantıyı koparır
            if (connection != null) {
                try {
                    connection.close();
                } catch (ConnectionException closeEx) {
                    // Kapatma hatasını yoksay veya logla
                    Log.e(TAG, "Connection close error: " + closeEx.getMessage());
                }
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
        
        Connection connection = null;
        StringBuilder info = new StringBuilder();
        
        try {
            connection = new BluetoothConnection(macAddress);
            connection.open();
            
            // Zebra Printer nesnesini oluştur
            ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
            
            // SGD komutları ile yazıcı bilgilerini al
            String model = SGD.GET("device.product_name", connection);
            String serialNumber = SGD.GET("device.unique_id", connection);
            String firmware = SGD.GET("appl.name", connection);
            
            info.append("Model: ").append(model).append("\n");
            info.append("Seri No: ").append(serialNumber).append("\n");
            info.append("Firmware: ").append(firmware).append("\n");
            
            // Yazıcı dili bilgisini al
            PrinterLanguage language = printer.getPrinterControlLanguage();
            info.append("Dil: ").append(language.toString()).append("\n");
            
            return info.toString();
            
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (ConnectionException e) {
                    // Kapatma hatası
                    Log.e(TAG, "Connection close error: " + e.getMessage());
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
        
        Connection connection = null;
        Map<String, Object> statusMap = new HashMap<>();
        
        try {
            connection = new BluetoothConnection(macAddress);
            connection.open();
            
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
            
            return statusMap;
            
        } catch (Exception e) {
            statusMap.put("isConnected", false);
            statusMap.put("error", e.getMessage());
            return statusMap;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (ConnectionException e) {
                    // Kapatma hatası
                    Log.e(TAG, "Connection close error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Kaynakları temizler
     */
    public void dispose() {
        executorService.shutdown();
    }
}
