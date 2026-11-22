package com.sameetdmr.zebra_printer;

import java.util.Map;

/**
 * Per-call options sent from Dart ({@code PrintOptions.toMap()}).
 *
 * <p>The defaults implement "shared printer" behaviour: one printer, many
 * devices. Every device connects, sends its job and releases the Bluetooth
 * link as soon as possible so the next device can connect.
 */
final class PrintOptions {

    /** Total time budget for getting a connection while the printer is busy. */
    final long maxWaitMs;
    /** First back-off delay between connection attempts. */
    final long initialRetryDelayMs;
    /** Upper bound for a single back-off delay. */
    final long maxRetryDelayMs;
    /** Randomise back-off delays so devices do not retry in lock-step. */
    final boolean jitter;

    /** Pause after the last write before closing, so the BT stack flushes. */
    final long drainDelayMs;
    /** Keep the link open this long after the job (0 = close immediately). */
    final long keepAliveMs;

    /** Read printer status on the same link before sending the job. */
    final boolean checkStatusBeforePrint;
    /** Poll status after sending until the printer finished the format. */
    final boolean waitForCompletion;
    /** Upper bound for {@link #waitForCompletion}. */
    final long completionTimeoutMs;

    /** Use an insecure (unbonded) RFCOMM link: devices need not pair first. */
    final boolean insecure;

    private PrintOptions(Map<?, ?> m) {
        maxWaitMs = getLong(m, "maxWaitMs", 30_000);
        initialRetryDelayMs = getLong(m, "initialRetryDelayMs", 500);
        maxRetryDelayMs = getLong(m, "maxRetryDelayMs", 4_000);
        jitter = getBool(m, "jitter", true);
        drainDelayMs = getLong(m, "drainDelayMs", 400);
        keepAliveMs = getLong(m, "keepAliveMs", 0);
        checkStatusBeforePrint = getBool(m, "checkStatusBeforePrint", true);
        waitForCompletion = getBool(m, "waitForCompletion", false);
        completionTimeoutMs = getLong(m, "completionTimeoutMs", 5_000);
        insecure = getBool(m, "insecure", false);
    }

    static PrintOptions from(Object raw) {
        return new PrintOptions(raw instanceof Map ? (Map<?, ?>) raw : null);
    }

    private static long getLong(Map<?, ?> m, String key, long def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v instanceof Number) {
            long l = ((Number) v).longValue();
            return l < 0 ? def : l;
        }
        return def;
    }

    private static boolean getBool(Map<?, ?> m, String key, boolean def) {
        if (m == null) return def;
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
