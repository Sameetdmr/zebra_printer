package com.sameetdmr.zebra_printer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.graphics.ZebraImageFactory;
import com.zebra.sdk.graphics.ZebraImageI;
import com.zebra.sdk.printer.PrinterLanguage;
import com.zebra.sdk.printer.PrinterStatus;
import com.zebra.sdk.printer.SGD;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.ZebraPrinterLinkOs;
import com.zebra.sdk.printer.discovery.BluetoothDiscoverer;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterBluetooth;
import com.zebra.sdk.printer.discovery.DiscoveredPrinterNetwork;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import com.zebra.sdk.printer.discovery.NetworkDiscoverer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Zebra Link-OS printer operations over Bluetooth Classic.
 *
 * <p><b>Shared printer model.</b> Every operation is a short session:
 * <pre>
 *   open (wait for a busy printer with back-off + jitter)
 *     → optional status check on the same link
 *     → send data
 *     → short drain delay → close
 * </pre>
 * The link is not kept open afterwards (unless {@code keepAliveMs > 0}), so
 * other devices can use the printer right away.
 *
 * <p>All blocking work runs on a single worker thread; Flutter results and
 * events are posted back to the main thread.
 */
public class PrinterManager {
    private static final String TAG = "ZebraPrinterManager";

    private static volatile boolean LOGGING_ENABLED = false;

    // Print progress states (must match Dart PrintState)
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_WAITING = "waiting";
    private static final String STATE_SENDING = "sending";
    private static final String STATE_PRINTING = "printing";
    private static final String STATE_VERIFYING = "verifying";
    private static final String STATE_COMPLETED = "completed";
    private static final String STATE_ERROR = "error";

    /** Images larger than this (longest side, px) are down-sampled while decoding to avoid OOM. */
    private static final int MAX_IMAGE_SIDE_PX = 2000;

    /** Resolves a Flutter asset key (e.g. "assets/fonts/arial.zpl") to an Android asset path. */
    public interface AssetResolver {
        String resolve(String flutterAssetKey);
    }

    private final Context context;
    private final AssetResolver assetResolver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedConnectionManager connections;
    private volatile MethodChannel methodChannel;

    /** Links kept open on request ({@code keepAliveMs > 0}); empty in the default shared mode. */
    private final Map<String, KeptConnection> keptConnections = new ConcurrentHashMap<>();

    /** Printer language per address: avoids one SGD round-trip per session. */
    private final Map<String, PrinterLanguage> languageCache = new ConcurrentHashMap<>();

    // Discovery state
    private final AtomicInteger discoveryGeneration = new AtomicInteger();
    private volatile boolean isDiscovering = false;
    private volatile DiscoverySession currentDiscovery;

    public PrinterManager(Context context, AssetResolver assetResolver) {
        this.context = context;
        this.assetResolver = assetResolver;
        this.connections = new SharedConnectionManager(context, new SharedConnectionManager.Logger() {
            @Override public void d(String msg) { logD(msg); }
            @Override public void w(String msg) { logW(msg); }
        });
    }

    public void setMethodChannel(MethodChannel methodChannel) {
        this.methodChannel = methodChannel;
    }

    // =====================================================================
    // Method channel
    // =====================================================================

    public void handleMethodCall(MethodCall call, @NonNull MethodChannel.Result result) {
        final String rawAddress = call.argument("address");
        final PrintOptions options = PrintOptions.from(call.argument("options"));
        final String jobId = call.argument("jobId");

        switch (call.method) {
            // ---------- Discovery ----------
            case "startDiscovery":
                startDiscovery(call.argument("type"), result);
                return;
            case "stopDiscovery":
                stopDiscovery(result);
                return;
            case "getPairedPrinters":
                runAsync(result, this::getPairedPrinters);
                return;
            case "unpairPrinter":
                runAsync(result, () -> unpairPrinter(normalize(rawAddress)));
                return;

            // ---------- Connection ----------
            case "connect":
                runAsync(result, () -> testConnection(normalize(rawAddress), options));
                return;
            case "disconnect":
                runAsync(result, () -> releaseKeptConnection(normalize(rawAddress)));
                return;
            case "isConnected": {
                String address = safeNormalize(rawAddress);
                KeptConnection kept = address == null ? null : keptConnections.get(address);
                result.success(kept != null && kept.connection.isConnected());
                return;
            }

            // ---------- Printing ----------
            case "printLabel": {
                final String data = call.argument("data");
                runAsync(result, () -> {
                    String address = normalize(rawAddress);
                    printJob(address, jobId, options, Collections.singletonList(data), true);
                    return "Print successful: " + address;
                });
                return;
            }
            case "printMultipleLabels": {
                final List<String> dataList = call.argument("dataList");
                runAsync(result, () -> {
                    String address = normalize(rawAddress);
                    printJob(address, jobId, options, dataList, true);
                    return "Batch print successful: " + address + " (" + (dataList == null ? 0 : dataList.size()) + " labels)";
                });
                return;
            }
            case "sendCommand": {
                // Raw commands (SGD "! U1 ...", "~JC", ...) sent as-is, no status pre-check
                final String data = call.argument("data");
                runAsync(result, () -> {
                    String address = normalize(rawAddress);
                    printJob(address, jobId, options, Collections.singletonList(data), false);
                    return true;
                });
                return;
            }
            case "printImage": {
                final byte[] imageData = call.argument("imageData");
                final int x = intArg(call, "x", 0);
                final int y = intArg(call, "y", 0);
                final int width = intArg(call, "width", 0);
                final int height = intArg(call, "height", 0);
                runAsync(result, () -> {
                    String address = normalize(rawAddress);
                    printImage(address, jobId, options, imageData, x, y, width, height);
                    return "Image print successful: " + address;
                });
                return;
            }

            // ---------- Fonts ----------
            case "uploadFont": {
                final String assetPath = call.argument("assetPath");
                final String fontName = call.argument("fontType");
                runAsync(result, () -> uploadFont(normalize(rawAddress), options, readFlutterAsset(assetPath), fontName));
                return;
            }
            case "uploadFontData": {
                final byte[] fontData = call.argument("fontData");
                final String fontName = call.argument("fontType");
                runAsync(result, () -> uploadFont(normalize(rawAddress), options, fontData, fontName));
                return;
            }

            // ---------- Status & info ----------
            case "getZebraPrinterStatus":
                // Never throws: connection problems are reported inside the map
                executor.execute(() -> {
                    Map<String, Object> status = getPrinterStatusMap(rawAddress, options);
                    mainHandler.post(() -> result.success(status));
                });
                return;
            case "getPrinterInfo":
                runAsync(result, () -> getPrinterInfo(normalize(rawAddress), options));
                return;

            // ---------- Logging ----------
            case "setLogging": {
                Boolean enabled = call.argument("enabled");
                if (enabled == null) {
                    result.error(PrinterException.INVALID_ARGUMENT, "enabled parameter is required", null);
                    return;
                }
                LOGGING_ENABLED = enabled;
                result.success(enabled);
                return;
            }
            case "isLoggingEnabled":
                result.success(LOGGING_ENABLED);
                return;

            default:
                result.notImplemented();
        }
    }

    private interface Work {
        Object run() throws Exception;
    }

    /** Runs blocking work on the worker thread and maps errors to stable codes. */
    private void runAsync(MethodChannel.Result result, Work work) {
        executor.execute(() -> {
            try {
                Object value = work.run();
                mainHandler.post(() -> result.success(value));
            } catch (Throwable t) {
                PrinterException e = PrinterException.wrap(t);
                logE("❌ " + e.code + ": " + e.getMessage());
                Map<String, Object> details = new HashMap<>(e.details);
                if (e.getCause() != null) details.put("cause", String.valueOf(e.getCause()));
                mainHandler.post(() -> result.error(e.code, e.getMessage(), details));
            }
        });
    }

    private static int intArg(MethodCall call, String key, int def) {
        Object v = call.argument(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static String normalize(String address) throws PrinterException {
        return SharedConnectionManager.normalizeAddress(address);
    }

    private static String safeNormalize(String address) {
        try {
            return normalize(address);
        } catch (PrinterException e) {
            return null;
        }
    }

    // =====================================================================
    // Sessions
    // =====================================================================

    private interface SessionWork<T> {
        T run(Connection connection) throws Exception;
    }

    /**
     * Opens (or reuses a kept) link, runs the work and releases the link.
     *
     * @param drain whether data was written and the BT stack needs a drain delay before close
     */
    private <T> T withSession(String address, String jobId, PrintOptions options, boolean drain,
                              SessionWork<T> work) throws Exception {
        Connection connection = takeKeptConnection(address);
        if (connection == null) {
            // Only print jobs (with a jobId) report waiting progress; status/info calls stay silent
            SharedConnectionManager.WaitListener listener = jobId == null ? null
                    : (attempt, waitedMs, nextDelayMs, reason) ->
                    emitProgress(jobId, address, STATE_WAITING, 5,
                            "busy".equals(reason) ? "Printer busy, waiting..." : "Printer not reachable, retrying...",
                            mapOf("attempt", attempt, "waitedMs", waitedMs, "nextRetryMs", nextDelayMs, "reason", reason));
            connection = connections.open(address, options, listener);
        }

        boolean ok = false;
        try {
            T value = work.run(connection);
            ok = true;
            return value;
        } catch (ConnectionException e) {
            throw new PrinterException(PrinterException.CONNECTION_LOST,
                    "Connection lost: " + e.getMessage(), e);
        } finally {
            if (ok && options.keepAliveMs > 0 && connection.isConnected()) {
                keepConnection(address, connection, options.keepAliveMs);
            } else if (drain && ok) {
                connections.drainAndClose(connection, options);
            } else {
                SharedConnectionManager.closeQuietly(connection);
            }
        }
    }

    /** Returns a ZebraPrinter without re-querying the language every session. */
    private ZebraPrinter printerFor(String address, Connection connection)
            throws ConnectionException, PrinterException {
        PrinterLanguage language = languageCache.get(address);
        if (language != null) {
            return ZebraPrinterFactory.getInstance(language, connection);
        }
        try {
            ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
            languageCache.put(address, printer.getPrinterControlLanguage());
            return printer;
        } catch (ZebraPrinterLanguageUnknownException e) {
            throw new PrinterException(PrinterException.LANGUAGE_UNKNOWN,
                    "Printer language could not be detected (printer may be in line_print mode)", e);
        }
    }

    /** Throws for states in which the printer will not print. */
    private static void throwIfNotPrintable(PrinterStatus status) throws PrinterException {
        if (status.isPaperOut) throw new PrinterException(PrinterException.PAPER_OUT, "Paper out");
        if (status.isHeadOpen) throw new PrinterException(PrinterException.HEAD_OPEN, "Print head open");
        if (status.isRibbonOut) throw new PrinterException(PrinterException.RIBBON_OUT, "Ribbon out");
        if (status.isPaused) throw new PrinterException(PrinterException.PRINTER_PAUSED, "Printer is paused");
    }

    // =====================================================================
    // Printing
    // =====================================================================

    /**
     * Sends one or more labels/commands in a single short session.
     *
     * @param checkStatus false for raw commands (e.g. calibration must work with paper out)
     */
    private void printJob(String address, String jobId, PrintOptions options, List<String> dataList,
                          boolean checkStatus) throws Exception {
        if (dataList == null || dataList.isEmpty()) {
            throw new PrinterException(PrinterException.INVALID_ARGUMENT, "Nothing to print");
        }
        final int total = dataList.size();
        final boolean batch = total > 1;
        final long start = SystemClock.elapsedRealtime();

        emitProgress(jobId, address, STATE_CONNECTING, 10, "", batchExtras(batch, 0, total));
        try {
            withSession(address, jobId, options, true, connection -> {
                long connectedAt = SystemClock.elapsedRealtime();

                if (checkStatus && options.checkStatusBeforePrint) {
                    PrinterStatus status = printerFor(address, connection).getCurrentStatus();
                    throwIfNotPrintable(status);
                }

                for (int i = 0; i < total; i++) {
                    String data = dataList.get(i);
                    if (data == null || data.isEmpty()) {
                        logW("Skipping empty label at index " + i);
                        continue;
                    }
                    int percent = 20 + (70 * (i + 1) / total);
                    emitProgress(jobId, address, STATE_SENDING, percent, batch ? (i + 1) + "/" + total : "",
                            batchExtras(batch, i + 1, total));
                    connection.write(data.getBytes(StandardCharsets.UTF_8));
                }

                if (options.waitForCompletion) {
                    emitProgress(jobId, address, STATE_PRINTING, 92, "", batchExtras(batch, total, total));
                    waitForCompletion(address, connection, options, jobId, batch, total);
                }

                long now = SystemClock.elapsedRealtime();
                logD("⏱️ " + address + " connect=" + (connectedAt - start) + "ms send+verify=" + (now - connectedAt) + "ms");
                return null;
            });
        } catch (Exception e) {
            emitError(jobId, address, e);
            throw e;
        }

        Map<String, Object> extras = batchExtras(batch, total, total);
        extras.put("holdMs", SystemClock.elapsedRealtime() - start);
        emitProgress(jobId, address, STATE_COMPLETED, 100, "", extras);
    }

    /** Polls until the printer finished the current format (opt-in). */
    private void waitForCompletion(String address, Connection connection, PrintOptions options,
                                   String jobId, boolean batch, int total) throws Exception {
        ZebraPrinter printer = printerFor(address, connection);
        long deadline = SystemClock.elapsedRealtime() + options.completionTimeoutMs;
        PrinterStatus status = printer.getCurrentStatus();
        while ((status.isPartialFormatInProgress || status.numberOfFormatsInReceiveBuffer > 0)
                && SystemClock.elapsedRealtime() < deadline) {
            Map<String, Object> extras = batchExtras(batch, total, total);
            extras.putAll(statusExtras(status));
            emitProgress(jobId, address, STATE_VERIFYING, 95, "", extras);
            Thread.sleep(200);
            status = printer.getCurrentStatus();
        }
        throwIfNotPrintable(status);
    }

    private void printImage(String address, String jobId, PrintOptions options, byte[] imageData,
                            int x, int y, int width, int height) throws Exception {
        if (imageData == null || imageData.length == 0) {
            throw new PrinterException(PrinterException.INVALID_ARGUMENT, "Image data cannot be empty");
        }
        Bitmap bitmap = decodeBitmap(imageData);
        long start = SystemClock.elapsedRealtime();
        emitProgress(jobId, address, STATE_CONNECTING, 10, "", null);
        try {
            withSession(address, jobId, options, true, connection -> {
                ZebraPrinter printer = printerFor(address, connection);
                if (options.checkStatusBeforePrint) {
                    throwIfNotPrintable(printer.getCurrentStatus());
                }
                emitProgress(jobId, address, STATE_SENDING, 40, "", null);
                ZebraImageI image = ZebraImageFactory.getImage(bitmap);
                printer.printImage(image, x, y, width, height, false);
                return null;
            });
        } catch (Exception e) {
            emitError(jobId, address, e);
            throw e;
        } finally {
            bitmap.recycle();
        }
        emitProgress(jobId, address, STATE_COMPLETED, 100, "",
                mapOf("holdMs", SystemClock.elapsedRealtime() - start));
    }

    private static Bitmap decodeBitmap(byte[] data) throws PrinterException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new PrinterException(PrinterException.UNSUPPORTED_FORMAT,
                    "Image could not be decoded (supported: PNG, JPG, BMP, WebP)");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE_PX) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bitmap == null) {
            throw new PrinterException(PrinterException.UNSUPPORTED_FORMAT, "Image could not be decoded");
        }
        return bitmap;
    }

    // =====================================================================
    // Connection test / kept connections
    // =====================================================================

    /** Opens, verifies the printer and releases the link. Does not keep it open. */
    private Boolean testConnection(String address, PrintOptions options) throws Exception {
        try {
            withSession(address, null, options, false, connection -> {
                printerFor(address, connection);
                return null;
            });
        } catch (Exception e) {
            PrinterException pe = PrinterException.wrap(e);
            emitConnectionState(address, false, pe.code, pe.getMessage());
            throw pe;
        }
        emitConnectionState(address, true, null, null);
        return true;
    }

    private static final class KeptConnection {
        final Connection connection;
        final Runnable closeTask;

        KeptConnection(Connection connection, Runnable closeTask) {
            this.connection = connection;
            this.closeTask = closeTask;
        }
    }

    /** Worker thread only. */
    private Connection takeKeptConnection(String address) {
        KeptConnection kept = keptConnections.remove(address);
        if (kept == null) return null;
        mainHandler.removeCallbacks(kept.closeTask);
        if (kept.connection.isConnected()) {
            logD("♻️ Reusing kept connection for " + address);
            return kept.connection;
        }
        SharedConnectionManager.closeQuietly(kept.connection);
        return null;
    }

    /** Worker thread only. */
    private void keepConnection(String address, Connection connection, long keepAliveMs) {
        Runnable closeTask = new Runnable() {
            @Override
            public void run() {
                final Runnable self = this;
                executor.execute(() -> {
                    KeptConnection current = keptConnections.get(address);
                    if (current != null && current.closeTask == self) {
                        keptConnections.remove(address);
                        SharedConnectionManager.closeQuietly(current.connection);
                        logD("🔒 Kept connection closed after " + keepAliveMs + "ms idle: " + address);
                    }
                });
            }
        };
        keptConnections.put(address, new KeptConnection(connection, closeTask));
        mainHandler.postDelayed(closeTask, keepAliveMs);
    }

    /** Worker thread only. */
    private Boolean releaseKeptConnection(String address) {
        KeptConnection kept = keptConnections.remove(address);
        if (kept != null) {
            mainHandler.removeCallbacks(kept.closeTask);
            SharedConnectionManager.closeQuietly(kept.connection);
        }
        emitConnectionState(address, false, null, null);
        return true;
    }

    // =====================================================================
    // Status & info
    // =====================================================================

    private Map<String, Object> getPrinterStatusMap(String rawAddress, PrintOptions options) {
        Map<String, Object> map = new HashMap<>();
        map.put("address", rawAddress);
        map.put("timestamp", System.currentTimeMillis());
        try {
            String address = normalize(rawAddress);
            map.put("address", address);
            PrinterStatus status = withSession(address, null, options, false,
                    connection -> printerFor(address, connection).getCurrentStatus());

            map.put("isConnected", true);
            map.put("isReadyToPrint", status.isReadyToPrint);
            map.put("isPrinting", status.isPartialFormatInProgress);
            map.put("isPartialFormatInProgress", status.isPartialFormatInProgress);
            map.put("isPaused", status.isPaused);
            map.put("isPaperOut", status.isPaperOut);
            map.put("isHeadOpen", status.isHeadOpen);
            map.put("isHeadCold", status.isHeadCold);
            map.put("isHeadTooHot", status.isHeadTooHot);
            map.put("isRibbonOut", status.isRibbonOut);
            map.put("isReceiveBufferFull", status.isReceiveBufferFull);
            map.put("labelsRemainingInBatch", status.labelsRemainingInBatch);
            map.put("numberOfFormatsInBuffer", status.numberOfFormatsInReceiveBuffer);
            map.put("labelLengthInDots", status.labelLengthInDots);
            if (status.printMode != null) map.put("printMode", status.printMode.toString());

            boolean hasError = status.isPaperOut || status.isHeadOpen || status.isHeadCold
                    || status.isHeadTooHot || status.isRibbonOut || status.isReceiveBufferFull;
            boolean canPrint = status.isReadyToPrint && !status.isPaused && !hasError;
            boolean isBusy = status.isPartialFormatInProgress
                    || status.numberOfFormatsInReceiveBuffer > 0 || status.labelsRemainingInBatch > 0;
            map.put("hasError", hasError);
            map.put("canPrint", canPrint);
            map.put("isBusy", isBusy);
            map.put("statusCode", statusCode(status));
            map.put("statusMessage", statusMessage(status));
        } catch (Throwable t) {
            PrinterException e = PrinterException.wrap(t);
            map.put("isConnected", false);
            map.put("canPrint", false);
            map.put("errorCode", e.code);
            map.put("error", e.getMessage());
            map.put("statusCode", e.code);
        }
        return map;
    }

    private Map<String, Object> getPrinterInfo(String address, PrintOptions options) throws Exception {
        return withSession(address, null, options, false, connection -> {
            ZebraPrinter printer = printerFor(address, connection);
            Map<String, Object> info = new HashMap<>();
            info.put("model", sgd("device.product_name", connection));
            info.put("serialNumber", sgd("device.unique_id", connection));
            info.put("firmware", sgd("appl.name", connection));
            info.put("linkOsVersion", sgd("appl.link_os_version", connection));
            info.put("language", printer.getPrinterControlLanguage().toString());
            Boolean protectedMode = null;
            try {
                ZebraPrinterLinkOs linkOs = ZebraPrinterFactory.createLinkOsPrinter(printer);
                if (linkOs != null) protectedMode = linkOs.isPrinterProtectedMode();
            } catch (Exception e) {
                logW("Protected mode check failed: " + e.getMessage());
            }
            info.put("isProtectedMode", protectedMode);
            return info;
        });
    }

    private String sgd(String setting, Connection connection) {
        try {
            String v = SGD.GET(setting, connection);
            if (v == null) return null;
            v = v.trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return v.isEmpty() || "?".equals(v) ? null : v;
        } catch (Exception e) {
            logW("SGD " + setting + " failed: " + e.getMessage());
            return null;
        }
    }

    private static String statusCode(PrinterStatus s) {
        if (s.isPaperOut) return "PAPER_OUT";
        if (s.isHeadOpen) return "HEAD_OPEN";
        if (s.isRibbonOut) return "RIBBON_OUT";
        if (s.isHeadCold) return "HEAD_COLD";
        if (s.isHeadTooHot) return "HEAD_HOT";
        if (s.isReceiveBufferFull) return "BUFFER_FULL";
        if (s.isPartialFormatInProgress) return "PRINTING";
        if (s.isPaused) return "PAUSED";
        if (s.isReadyToPrint) return "READY";
        return "UNKNOWN";
    }

    private static String statusMessage(PrinterStatus s) {
        if (!s.isPaperOut && !s.isHeadOpen && !s.isRibbonOut && !s.isHeadCold && !s.isHeadTooHot
                && !s.isReceiveBufferFull && !s.isPartialFormatInProgress && !s.isPaused
                && s.labelsRemainingInBatch > 0) {
            return "LABELS_REMAINING:" + s.labelsRemainingInBatch;
        }
        return statusCode(s);
    }

    private static Map<String, Object> statusExtras(PrinterStatus s) {
        Map<String, Object> m = new HashMap<>();
        m.put("isPaperOut", s.isPaperOut);
        m.put("isHeadOpen", s.isHeadOpen);
        m.put("isHeadCold", s.isHeadCold);
        m.put("isHeadTooHot", s.isHeadTooHot);
        m.put("isRibbonOut", s.isRibbonOut);
        m.put("isReceiveBufferFull", s.isReceiveBufferFull);
        m.put("labelsRemainingInBatch", s.labelsRemainingInBatch);
        m.put("isReadyToPrint", s.isReadyToPrint);
        m.put("isPrinting", s.isPartialFormatInProgress);
        return m;
    }

    // =====================================================================
    // Fonts
    // =====================================================================

    private enum FontFormat { TTF_IN_ZPL, PURE_TTF, ZPL_COMMANDS, UNKNOWN }

    private byte[] readFlutterAsset(String assetKey) throws PrinterException {
        if (assetKey == null || assetKey.isEmpty()) {
            throw new PrinterException(PrinterException.INVALID_ARGUMENT, "Asset path cannot be empty");
        }
        List<String> candidates = new ArrayList<>();
        if (assetResolver != null) {
            String resolved = assetResolver.resolve(assetKey);
            if (resolved != null) candidates.add(resolved);
        }
        // Legacy behaviour: native Android asset without the "assets/" prefix
        candidates.add(assetKey.startsWith("assets/") ? assetKey.substring(7) : assetKey);

        IOException last = null;
        for (String path : candidates) {
            try (InputStream in = context.getAssets().open(path)) {
                return readAll(in);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new PrinterException(PrinterException.INVALID_ARGUMENT,
                "Font asset not found: " + assetKey, last);
    }

    /**
     * Uploads a font. Supported inputs: a ZPL ~DY/~DU font command file, a raw
     * TTF file or other ZPL text. The printer path stays "E:" + NAME (no
     * extension) for compatibility with existing label templates.
     */
    private Boolean uploadFont(String address, PrintOptions options, byte[] fontData, String fontName)
            throws Exception {
        if (fontData == null || fontData.length == 0) {
            throw new PrinterException(PrinterException.INVALID_ARGUMENT, "Font data cannot be empty");
        }
        final FontFormat format = detectFontFormat(fontData);
        if (format == FontFormat.UNKNOWN) {
            throw new PrinterException(PrinterException.UNSUPPORTED_FORMAT,
                    "Unsupported font data: expected TTF or a ZPL ~DY/~DU command file");
        }
        final String printerPath = "E:" + (fontName == null || fontName.trim().isEmpty()
                ? "FONT" : fontName.trim().toUpperCase());
        logD("📁 Uploading font (" + format + ", " + fontData.length + " bytes) to " + printerPath);

        return withSession(address, null, options, true, connection -> {
            switch (format) {
                case ZPL_COMMANDS:
                    connection.write(fontData);
                    return true;
                case TTF_IN_ZPL: {
                    ZebraPrinterLinkOs linkOs = linkOsOrNull(address, connection);
                    if (linkOs == null) {
                        // Not a Link-OS printer (e.g. iMZ): ZPL firmware understands the ~DY command itself
                        connection.write(fontData);
                        return true;
                    }
                    linkOs.downloadTtfFont(new ByteArrayInputStream(extractTtfFromZpl(fontData)), printerPath);
                    return true;
                }
                case PURE_TTF:
                default: {
                    ZebraPrinterLinkOs linkOs = linkOsOrNull(address, connection);
                    if (linkOs == null) {
                        throw new PrinterException(PrinterException.UNSUPPORTED_FORMAT,
                                "Raw TTF upload needs a Link-OS printer; send a ZPL ~DY font file instead");
                    }
                    linkOs.downloadTtfFont(new ByteArrayInputStream(fontData), printerPath);
                    return true;
                }
            }
        });
    }

    private ZebraPrinterLinkOs linkOsOrNull(String address, Connection connection) throws Exception {
        try {
            return ZebraPrinterFactory.createLinkOsPrinter(printerFor(address, connection));
        } catch (ConnectionException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private static FontFormat detectFontFormat(byte[] data) {
        if (data.length < 4) return FontFormat.UNKNOWN;
        String head = new String(data, 0, Math.min(64, data.length), StandardCharsets.ISO_8859_1);
        if (head.contains("~DY") || head.contains("~DU")) return FontFormat.TTF_IN_ZPL;
        boolean ttfMagic = (data[0] == 0x00 && data[1] == 0x01 && data[2] == 0x00 && data[3] == 0x00)
                || (data[0] == 't' && data[1] == 'r' && data[2] == 'u' && data[3] == 'e');
        if (ttfMagic) return FontFormat.PURE_TTF;
        for (byte b : data) {
            if (b == ' ' || b == '\r' || b == '\n' || b == '\t') continue;
            return (b == '^' || b == '~' || b == '!') ? FontFormat.ZPL_COMMANDS : FontFormat.UNKNOWN;
        }
        return FontFormat.UNKNOWN;
    }

    /** "~DYE:NAME,B,T,size,,<TTF>" → TTF bytes. */
    private static byte[] extractTtfFromZpl(byte[] zpl) throws PrinterException {
        for (int i = 0; i < zpl.length - 1; i++) {
            if (zpl[i] == ',' && zpl[i + 1] == ',') {
                int start = i + 2;
                byte[] ttf = new byte[zpl.length - start];
                System.arraycopy(zpl, start, ttf, 0, ttf.length);
                if (ttf.length >= 4 && ttf[0] == 0x00 && ttf[1] == 0x01 && ttf[2] == 0x00 && ttf[3] == 0x00) {
                    return ttf;
                }
                break;
            }
        }
        throw new PrinterException(PrinterException.UNSUPPORTED_FORMAT,
                "~DY command does not contain binary TTF data");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // =====================================================================
    // Discovery & pairing
    // =====================================================================

    private final class DiscoverySession {
        final int generation;
        final MethodChannel.Result result;
        final Map<String, Map<String, Object>> found = Collections.synchronizedMap(new LinkedHashMap<>());
        boolean replied;

        DiscoverySession(int generation, MethodChannel.Result result) {
            this.generation = generation;
            this.result = result;
        }

        List<Map<String, Object>> snapshot() {
            synchronized (found) {
                return new ArrayList<>(found.values());
            }
        }

        /** Main thread only. */
        void finish(String error) {
            if (replied) return;
            replied = true;
            if (currentDiscovery == this) {
                currentDiscovery = null;
                isDiscovering = false;
            }
            List<Map<String, Object>> printers = snapshot();
            if (error != null && printers.isEmpty()) {
                result.error("DISCOVERY_ERROR", error, null);
                return;
            }
            MethodChannel channel = methodChannel;
            if (channel != null) channel.invokeMethod("onDiscoveryFinished", printers);
            result.success(printers);
        }
    }

    /**
     * @param type "bluetooth" (default). "network" is kept for backwards
     *             compatibility, but printing is Bluetooth only.
     */
    private void startDiscovery(String type, MethodChannel.Result result) {
        if (isDiscovering) {
            result.error("ALREADY_DISCOVERING", "Discovery already in progress", null);
            return;
        }
        isDiscovering = true;
        final DiscoverySession session = new DiscoverySession(discoveryGeneration.incrementAndGet(), result);
        currentDiscovery = session;
        final boolean network = "network".equalsIgnoreCase(type);

        DiscoveryHandler handler = new DiscoveryHandler() {
            @Override
            public void foundPrinter(DiscoveredPrinter printer) {
                if (session.generation != discoveryGeneration.get()) return;
                Map<String, Object> map = toPrinterMap(printer);
                String key = String.valueOf(map.get("address"));
                if (session.found.containsKey(key)) return;
                session.found.put(key, map);
                mainHandler.post(() -> {
                    MethodChannel channel = methodChannel;
                    if (channel != null) channel.invokeMethod("onPrinterFound", map);
                });
            }

            @Override
            public void discoveryFinished() {
                mainHandler.post(() -> session.finish(null));
            }

            @Override
            public void discoveryError(String message) {
                logE("Discovery error: " + message);
                mainHandler.post(() -> session.finish(message));
            }
        };

        // Discovery is asynchronous in the SDK; start it off the main thread
        new Thread(() -> {
            try {
                if (network) {
                    NetworkDiscoverer.findPrinters(handler);
                } else {
                    BluetoothDiscoverer.findPrinters(context, handler);
                }
            } catch (Throwable t) {
                handler.discoveryError(String.valueOf(PrinterException.wrap(t).getMessage()));
            }
        }, "zebra-discovery").start();
    }

    private Map<String, Object> toPrinterMap(DiscoveredPrinter printer) {
        Map<String, Object> map = new HashMap<>();
        String name = null;
        if (printer instanceof DiscoveredPrinterBluetooth) {
            map.put("type", "bluetooth");
            name = ((DiscoveredPrinterBluetooth) printer).friendlyName;
        } else if (printer instanceof DiscoveredPrinterNetwork) {
            map.put("type", "network");
            Map<String, String> data = printer.getDiscoveryDataMap();
            if (data != null) {
                name = data.get("SYSTEM_NAME");
                if (name == null || name.isEmpty()) name = data.get("PRODUCT_NAME");
            }
        } else {
            map.put("type", "unknown");
        }
        map.put("address", printer.address);
        map.put("friendlyName", name != null && !name.isEmpty() ? name : printer.address);
        return map;
    }

    /**
     * Cancels Bluetooth discovery and completes the pending startDiscovery call
     * with what was found so far.
     */
    @SuppressWarnings("MissingPermission")
    private void stopDiscovery(MethodChannel.Result result) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException e) {
            logW("cancelDiscovery not permitted: " + e.getMessage());
        }
        DiscoverySession session = currentDiscovery;
        if (session != null) {
            discoveryGeneration.incrementAndGet(); // ignore late callbacks
            session.finish(null);
        }
        isDiscovering = false;
        result.success(true);
    }

    @SuppressWarnings("MissingPermission")
    private List<Map<String, Object>> getPairedPrinters() throws PrinterException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new PrinterException(PrinterException.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth adapter");
        }
        try {
            if (!adapter.isEnabled()) {
                throw new PrinterException(PrinterException.BLUETOOTH_OFF, "Bluetooth is turned off");
            }
            List<Map<String, Object>> list = new ArrayList<>();
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    Map<String, Object> map = new HashMap<>();
                    String name = device.getName();
                    map.put("address", device.getAddress());
                    map.put("friendlyName", name != null ? name : device.getAddress());
                    map.put("type", "bluetooth");
                    map.put("isPaired", true);
                    list.add(map);
                }
            }
            return list;
        } catch (SecurityException e) {
            throw PrinterException.wrap(e);
        }
    }

    @SuppressWarnings("MissingPermission")
    private Boolean unpairPrinter(String address) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new PrinterException(PrinterException.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth adapter");
        }
        releaseKeptConnection(address);
        BluetoothDevice device = adapter.getRemoteDevice(address);
        // removeBond is a hidden API: reflection is the only way to call it
        device.getClass().getMethod("removeBond").invoke(device);
        return true;
    }

    // =====================================================================
    // Events
    // =====================================================================

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> batchExtras(boolean batch, int current, int total) {
        Map<String, Object> m = new HashMap<>();
        if (batch) {
            m.put("isBatch", true);
            m.put("currentLabel", current);
            m.put("totalLabels", total);
        }
        return m;
    }

    private void emitProgress(String jobId, String address, String state, int percent, String message,
                              Map<String, Object> extras) {
        if (methodChannel == null) return;
        Map<String, Object> data = new HashMap<>();
        if (extras != null) data.putAll(extras);
        data.put("jobId", jobId);
        data.put("address", address);
        data.put("state", state);
        data.put("percentage", percent);
        data.put("message", message);
        data.put("timestamp", System.currentTimeMillis());
        mainHandler.post(() -> {
            MethodChannel channel = methodChannel;
            if (channel != null) channel.invokeMethod("onPrintProgress", data);
        });
    }

    private void emitError(String jobId, String address, Throwable t) {
        PrinterException e = PrinterException.wrap(t);
        Map<String, Object> extras = new HashMap<>(e.details);
        extras.put("errorCode", e.code);
        extras.put("errorMessage", e.getMessage());
        emitProgress(jobId, address, STATE_ERROR, 0, e.getMessage(), extras);
    }

    private void emitConnectionState(String address, boolean connected, String code, String error) {
        mainHandler.post(() -> {
            MethodChannel channel = methodChannel;
            if (channel == null) return;
            Map<String, Object> info = new HashMap<>();
            info.put("address", address);
            info.put("isConnected", connected);
            if (code != null) info.put("errorCode", code);
            if (error != null) info.put("error", error);
            channel.invokeMethod("onConnectionStateChanged", info);
        });
    }

    // =====================================================================
    // Lifecycle & logging
    // =====================================================================

    public void dispose() {
        discoveryGeneration.incrementAndGet();
        isDiscovering = false;
        executor.execute(() -> {
            for (String address : new ArrayList<>(keptConnections.keySet())) {
                KeptConnection kept = keptConnections.remove(address);
                if (kept != null) {
                    mainHandler.removeCallbacks(kept.closeTask);
                    SharedConnectionManager.closeQuietly(kept.connection);
                }
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        methodChannel = null;
    }

    private void logD(String msg) {
        if (LOGGING_ENABLED) Log.d(TAG, msg);
    }

    private void logW(String msg) {
        if (LOGGING_ENABLED) Log.w(TAG, msg);
    }

    private void logE(String msg) {
        Log.e(TAG, msg);
    }
}
