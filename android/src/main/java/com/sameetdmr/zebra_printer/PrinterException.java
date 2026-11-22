package com.sameetdmr.zebra_printer;

import java.util.HashMap;
import java.util.Map;

/**
 * Exception carrying a stable error code that is forwarded to Dart as
 * {@code PlatformException.code} and mapped to {@code ZebraPrinterErrorCode}.
 */
final class PrinterException extends Exception {

    // Connection
    static final String PRINTER_BUSY = "PRINTER_BUSY";
    static final String PRINTER_UNREACHABLE = "PRINTER_UNREACHABLE";
    static final String NOT_PAIRED = "NOT_PAIRED";
    static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    static final String BLUETOOTH_OFF = "BLUETOOTH_OFF";
    static final String BLUETOOTH_UNAVAILABLE = "BLUETOOTH_UNAVAILABLE";
    static final String CONNECTION_LOST = "CONNECTION_LOST";

    // Printer state
    static final String PAPER_OUT = "PAPER_OUT";
    static final String HEAD_OPEN = "HEAD_OPEN";
    static final String RIBBON_OUT = "RIBBON_OUT";
    static final String PRINTER_PAUSED = "PRINTER_PAUSED";
    static final String LANGUAGE_UNKNOWN = "LANGUAGE_UNKNOWN";

    // Input / misc
    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    static final String OPERATION_FAILED = "OPERATION_FAILED";

    final String code;
    final Map<String, Object> details = new HashMap<>();

    PrinterException(String code, String message) {
        super(message);
        this.code = code;
    }

    PrinterException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    PrinterException with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    /** Wraps any throwable into a PrinterException (keeps existing codes). */
    static PrinterException wrap(Throwable t) {
        if (t instanceof PrinterException) return (PrinterException) t;
        if (t instanceof SecurityException) {
            return new PrinterException(PERMISSION_DENIED,
                    "Bluetooth permission denied (BLUETOOTH_CONNECT is required on Android 12+)", t);
        }
        if (t instanceof IllegalArgumentException) {
            return new PrinterException(INVALID_ARGUMENT, String.valueOf(t.getMessage()), t);
        }
        return new PrinterException(OPERATION_FAILED, String.valueOf(t.getMessage()), t);
    }
}
