package com.sameetdmr.zebra_printer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.BluetoothConnectionInsecure;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;

import java.util.Random;

/**
 * Opens and releases Bluetooth links for a printer that is shared by many devices.
 *
 * <p>Most Zebra printers accept a single Bluetooth (SPP) link at a time. When
 * another device is printing, {@code open()} fails. This class retries with
 * exponential back-off plus random jitter inside a total time budget, so ten
 * devices hitting the same printer spread out instead of colliding in lock-step.
 *
 * <p>All methods are blocking and must be called from a worker thread.
 */
final class SharedConnectionManager {

    /** Reports retry/wait progress to the caller (forwarded to Dart). */
    interface WaitListener {
        void onWaiting(int attempt, long waitedMs, long nextDelayMs, String reason);
    }

    /**
     * A failed {@code open()} that returns faster than this is treated as
     * "printer reachable but busy". Paging a device that is off or out of range
     * takes ~5 s on Android, while a printer that already has a link refuses
     * quickly. This is a heuristic and is reported as such in the docs.
     */
    static final long BUSY_FAILURE_THRESHOLD_MS = 3_000;

    private final Context context;
    private final Random random = new Random();
    private final Logger log;

    interface Logger {
        void d(String msg);
        void w(String msg);
    }

    SharedConnectionManager(Context context, Logger log) {
        this.context = context;
        this.log = log;
    }

    /** Creates (does not open) a Bluetooth Classic connection. */
    Connection create(String address, PrintOptions options) {
        return options.insecure
                ? new BluetoothConnectionInsecure(address)
                : new BluetoothConnection(address);
    }

    /**
     * Opens a link, waiting for a busy printer up to {@code options.maxWaitMs}.
     *
     * @throws PrinterException with PRINTER_BUSY, PRINTER_UNREACHABLE, NOT_PAIRED,
     *                          PERMISSION_DENIED, BLUETOOTH_OFF or BLUETOOTH_UNAVAILABLE
     */
    Connection open(String address, PrintOptions options, WaitListener listener) throws PrinterException {
        validateAddress(address);
        boolean bonded = checkAdapterAndPermissions(address);

        long start = SystemClock.elapsedRealtime();
        long delay = Math.max(50, options.initialRetryDelayMs);
        int attempt = 0;
        int busyLikeFailures = 0;
        ConnectionException last = null;

        while (true) {
            attempt++;
            Connection connection = create(address, options);
            long attemptStart = SystemClock.elapsedRealtime();
            try {
                connection.open();
                log.d("🔗 Connected to " + address + " (attempt " + attempt + ", waited "
                        + (SystemClock.elapsedRealtime() - start) + "ms)");
                return connection;
            } catch (ConnectionException e) {
                // open() can keep socket resources on failure → always close
                closeQuietly(connection);
                last = e;
                long attemptMs = SystemClock.elapsedRealtime() - attemptStart;
                boolean busyLike = attemptMs < BUSY_FAILURE_THRESHOLD_MS;
                if (busyLike) busyLikeFailures++;
                log.d("Connection attempt " + attempt + " failed after " + attemptMs + "ms"
                        + (busyLike ? " (printer busy?)" : " (printer unreachable?)") + ": " + e.getMessage());
            } catch (SecurityException e) {
                closeQuietly(connection);
                throw PrinterException.wrap(e);
            }

            // A secure link to an unbonded printer triggers the system pairing flow.
            // Retrying would only show the pairing dialog again.
            if (!options.insecure && !bonded) {
                throw new PrinterException(PrinterException.NOT_PAIRED,
                        "Printer " + address + " is not paired with this device. Pair it first or use insecure mode.", last)
                        .with("attempts", attempt);
            }

            long waited = SystemClock.elapsedRealtime() - start;
            long nextDelay = options.jitter ? jittered(delay) : delay;
            if (waited + nextDelay > options.maxWaitMs) {
                // Majority of fast refusals → someone else holds the printer
                boolean busy = busyLikeFailures * 2 >= attempt;
                String code = busy ? PrinterException.PRINTER_BUSY : PrinterException.PRINTER_UNREACHABLE;
                String msg = busy
                        ? "Printer is busy (used by another device). Gave up after " + waited + "ms."
                        : "Printer is unreachable (off, out of range or Bluetooth issue). Gave up after " + waited + "ms.";
                throw new PrinterException(code, msg, last)
                        .with("attempts", attempt)
                        .with("waitedMs", waited)
                        .with("lastError", last != null ? String.valueOf(last.getMessage()) : null);
            }

            if (listener != null) {
                listener.onWaiting(attempt, waited, nextDelay,
                        busyLikeFailures > 0 ? "busy" : "unreachable");
            }
            try {
                Thread.sleep(nextDelay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new PrinterException(PrinterException.OPERATION_FAILED, "Interrupted while waiting for printer", ie);
            }
            delay = Math.min(options.maxRetryDelayMs, delay * 2);
        }
    }

    /**
     * Gives the BT stack time to flush the last bytes, then closes the link.
     * Closing right after write() can truncate data on some devices.
     */
    void drainAndClose(Connection connection, PrintOptions options) {
        if (connection == null) return;
        if (options.drainDelayMs > 0) {
            try {
                Thread.sleep(options.drainDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(connection);
    }

    static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** "Full jitter" between 50% and 100% of the delay. */
    private long jittered(long delay) {
        long half = delay / 2;
        return half + (long) (random.nextDouble() * (delay - half + 1));
    }

    /**
     * Normalises a MAC address to "AA:BB:CC:DD:EE:FF".
     * Accepts ':' or '-' separators or 12 plain hex digits (as printed on Zebra labels).
     */
    static String normalizeAddress(String address) throws PrinterException {
        if (address == null || address.trim().isEmpty()) {
            throw new PrinterException(PrinterException.INVALID_ARGUMENT, "Printer address cannot be empty");
        }
        String hex = address.trim().replace(":", "").replace("-", "").toUpperCase();
        if (hex.length() != 12 || !hex.matches("[0-9A-F]{12}")) {
            throw new PrinterException(PrinterException.INVALID_ARGUMENT,
                    "Invalid Bluetooth MAC address: " + address + " (expected format 00:11:22:33:44:55)");
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    private static void validateAddress(String address) throws PrinterException {
        normalizeAddress(address);
    }

    /** @return whether the device is bonded with the printer */
    @SuppressWarnings("MissingPermission")
    private boolean checkAdapterAndPermissions(String address) throws PrinterException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new PrinterException(PrinterException.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth adapter");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new PrinterException(PrinterException.PERMISSION_DENIED,
                    "BLUETOOTH_CONNECT permission is not granted");
        }
        try {
            if (!adapter.isEnabled()) {
                throw new PrinterException(PrinterException.BLUETOOTH_OFF, "Bluetooth is turned off");
            }
            BluetoothDevice device = adapter.getRemoteDevice(address.toUpperCase());
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            throw PrinterException.wrap(e);
        }
    }
}
